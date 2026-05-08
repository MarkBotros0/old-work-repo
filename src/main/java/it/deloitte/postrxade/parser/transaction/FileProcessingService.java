package it.deloitte.postrxade.parser.transaction;

import it.deloitte.postrxade.entity.*;
import it.deloitte.postrxade.enums.ErrorTypeCode;
import it.deloitte.postrxade.enums.IngestionTypeEnum;
import it.deloitte.postrxade.exception.NotFoundRecordException;
import it.deloitte.postrxade.records.ErrorRecordCause;
import it.deloitte.postrxade.records.ProcessedFailedRecordBatch;
import it.deloitte.postrxade.records.ProcessedRecordBatch;
import it.deloitte.postrxade.repository.*;
import it.deloitte.postrxade.service.ErrorTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ma.glasnost.orika.MapperFacade;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@RequiredArgsConstructor
@Service
@Slf4j
public class FileProcessingService {

    private final FileLineParser parser = new FileLineParser();
    private final FileLineValidator validator = new FileLineValidator();

    @Qualifier("mapperFacade")
    private final MapperFacade mapperFacade;
    private final TransactionRepository transactionRepository;
    private final ResolvedTransactionRepository resolvedTransactionRepository;
    private final MerchantRepository merchantRepository;
    private final ErrorTypeService errorTypeService;

    // Batch size optimized based on testing: 1000 provides best balance
    // Test results: 500->666s total, 1000->591s total (best), 10000->774s total
    // Larger batches increase saving time due to larger transactions
    private static final int BATCH_SIZE = 1000;
    // Thread pool size: Original configuration (was causing 97% CPU usage)
    // TODO: Monitor CPU after optimizations. If still high, consider reducing to cores + 1
    private static final int THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();

    private static final int RAW_ROW_MAX_LENGTH = 250;

    private static String truncateRawRow(String line) {
        if (line == null) return null;
        return line.length() > RAW_ROW_MAX_LENGTH ? line.substring(0, RAW_ROW_MAX_LENGTH) : line;
    }

    private static ErrorRecordCause lineTooLongCause(int actualLength) {
        return new ErrorRecordCause(
                String.format("Line length %d exceeds maximum allowed length of %d characters",
                        actualLength, RAW_ROW_MAX_LENGTH),
                ErrorTypeCode.INVALID_FORMAT.getErrorCode());
    }

    public List<ProcessedRecordBatch> process(
            RemoteFile file,
            Ingestion ingestion,
            Submission submission,
            Obbligation obbligation) throws IOException {
        log.info("Starting parallel processing of file: {}, ingestionId: {}, ingestionType: {}, threads: {}",
                file.name(), ingestion.getId(), ingestion.getIngestionType().getName(), THREAD_POOL_SIZE);

        List<String> allLines = readAllLines(file);
        log.info("Read {} lines from file: {}", allLines.size(), file.name());

        if (allLines.isEmpty()) {
            log.info("No records to process in file: {}", file.name());
            return new ArrayList<>();
        }

        validator.validateHeader(allLines.getFirst(), ingestion.getIngestionType().getName());
        validator.validateFooter(allLines.getLast(), allLines.size(), ingestion.getIngestionType().getName());

        // skip header and footer
        allLines.removeFirst();
        allLines.removeLast();

        log.debug("About to process {} records for ingestionId: {}", allLines.size(), ingestion.getId());

        List<ProcessedRecordBatch> processedBatches = processRecordsInParallel(allLines, ingestion, submission, obbligation);

        log.info("Completed parallel processing of file: {}. Returning {} batches for saving",
                file.name(), processedBatches.size());
        log.debug("Exiting process() for file: {}, ingestionId: {}", file.name(), ingestion.getId());

        return processedBatches;
    }

    public List<ProcessedFailedRecordBatch> processFailedTransactions(
            List<String> allLines,
            Ingestion ingestion,
            Submission submission,
            Obbligation obbligation) {

        if (allLines.isEmpty()) {
            return new ArrayList<>();
        }

        log.debug("About to process {} records for ingestionId: {}", allLines.size(), ingestion.getId());

        List<ProcessedFailedRecordBatch> processedBatches = processFailedRecordsInParallel(allLines, ingestion, submission, obbligation);

        return processedBatches;
    }

    private List<String> readAllLines(RemoteFile file) throws IOException {
        log.debug("Entering readAllLines() for file: {}", file != null ? file.name() : null);
        List<String> lines = new ArrayList<>();
        int blankLinesSkipped = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.stream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    blankLinesSkipped++;
                    continue;
                }
                lines.add(line);
            }
        }

        log.debug("Read {} valid lines, skipped {} blank lines", lines.size(), blankLinesSkipped);
        log.debug("Exiting readAllLines() for file: {} with {} lines", file != null ? file.name() : null, lines.size());
        return lines;
    }

    private List<ProcessedRecordBatch> processRecordsInParallel(
            List<String> lines,
            Ingestion ingestion,
            Submission submission,
            Obbligation obbligation) {
        log.debug("Entering processRecordsInParallel() with {} lines for ingestionId: {}", lines != null ? lines.size() : 0,
                ingestion != null ? ingestion.getId() : null);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        AtomicInteger merchantCount = new AtomicInteger(0);
        AtomicInteger transactionCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        try {
            List<List<String>> batches = partitionList(lines);
            log.info("Processing {} records in {} batches using {} threads",
                    lines.size(), batches.size(), THREAD_POOL_SIZE);

            List<CompletableFuture<ProcessedRecordBatch>> futures = new ArrayList<>();
            log.debug("Created {} batches with batch size: {}", batches.size(), BATCH_SIZE);

            Set<String> fileLevelMerchants = ConcurrentHashMap.newKeySet();

            for (int i = 0; i < batches.size(); i++) {
                final int batchIndex = i;
                final List<String> batch = batches.get(i);
                log.debug("Submitting batch {} to executor with {} records", batchIndex, batch.size());

                CompletableFuture<ProcessedRecordBatch> future = CompletableFuture.supplyAsync(() -> {
                    log.debug("Async execution started for batch {}", batchIndex);
                    try {
                        ProcessedRecordBatch result = processBatch(batch, ingestion, submission, obbligation, batchIndex, fileLevelMerchants);
                        log.debug("Async execution completed for batch {}", batchIndex);
                        return result;
                    } catch (NotFoundRecordException e) {
                        log.error("Error processing batch {}: {}", batchIndex, e.getMessage());
                        throw new CompletionException(e);
                    }
                }, executor);

                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<ProcessedRecordBatch> processedBatches = new ArrayList<>();
            for (CompletableFuture<ProcessedRecordBatch> future : futures) {
                ProcessedRecordBatch result = future.join();
                processedBatches.add(result);
                merchantCount.addAndGet(result.merchants().size());
                transactionCount.addAndGet(result.transactions().size());
                errorCount.addAndGet(result.errorRecords().size());
            }

            log.info("Parallel processing complete for ingestionId: {}. Processed: {} merchants, {} transactions, {} errors. Total valid records: {}",
                    ingestion.getId(), merchantCount.get(), transactionCount.get(), errorCount.get(),
                    merchantCount.get() + transactionCount.get());

            // Clear futures list to free memory (results are already in processedBatches)
            futures.clear();
            // Note: fileLevelMerchants is kept for potential future use, but could be cleared if not needed
            
            return processedBatches;

        } finally {
            shutdownExecutor(executor);
        }
    }

    /**
     * Process records with streaming callback to save batches incrementally.
     * This method processes batches and immediately invokes the callback for each batch,
     * allowing the caller to save to DB in the main thread without accumulating all batches in memory.
     * 
     * @param file The file to process
     * @param ingestion The ingestion entity
     * @param submission The submission entity
     * @param obbligation The obligation entity
     * @param savingService The service that will handle batch saving
     * @throws IOException if file reading fails
     */
    public void processWithImmediateSave(
            RemoteFile file,
            Ingestion ingestion,
            Submission submission,
            Obbligation obbligation,
            Object savingService) throws IOException {
        log.info("Starting parallel processing with immediate save for file: {}, ingestionId: {}, ingestionType: {}, threads: {}",
                file.name(), ingestion.getId(), ingestion.getIngestionType().getName(), THREAD_POOL_SIZE);

        List<String> allLines = readAllLines(file);
        log.info("Read {} lines from file: {}", allLines.size(), file.name());

        if (allLines.isEmpty()) {
            log.info("No records to process in file: {}", file.name());
            return;
        }

        validator.validateHeader(allLines.getFirst(), ingestion.getIngestionType().getName());
        validator.validateFooter(allLines.getLast(), allLines.size(), ingestion.getIngestionType().getName());

        // skip header and footer
        allLines.removeFirst();
        allLines.removeLast();

        log.debug("About to process {} records for ingestionId: {}", allLines.size(), ingestion.getId());

        processRecordsInParallelWithCallback(allLines, ingestion, submission, obbligation, savingService);

        log.info("Completed parallel processing with immediate save for file: {}",
                file.name());
    }

    private void processRecordsInParallelWithCallback(
            List<String> lines,
            Ingestion ingestion,
            Submission submission,
            Obbligation obbligation,
            Object savingService) {
        log.debug("Entering processRecordsInParallelWithCallback() with {} lines for ingestionId: {}", lines.size(), ingestion.getId());
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        BlockingQueue<ProcessedRecordBatch> batchQueue = new LinkedBlockingQueue<>();
        AtomicInteger completedBatches = new AtomicInteger(0);
        List<List<String>> batches = partitionList(lines);
        int totalBatches = batches.size();

        try {
            log.info("Processing {} records in {} batches using {} threads",
                    lines.size(), totalBatches, THREAD_POOL_SIZE);

            Set<String> fileLevelMerchants = ConcurrentHashMap.newKeySet();
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = 0; i < batches.size(); i++) {
                final int batchIndex = i;
                final List<String> batch = batches.get(i);
                log.debug("Submitting batch {} to executor with {} records", batchIndex, batch.size());

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        log.debug("Async execution started for batch {}", batchIndex);
                        ProcessedRecordBatch result = processBatch(batch, ingestion, submission, obbligation, batchIndex, fileLevelMerchants);
                        log.debug("Async execution completed for batch {}, queuing for save", batchIndex);
                        batchQueue.put(result); // Put the result in the queue for immediate saving
                    } catch (NotFoundRecordException | InterruptedException e) {
                        log.error("Error processing batch {}: {}", batchIndex, e.getMessage());
                        throw new CompletionException(e);
                    }
                }, executor);

                futures.add(future);
            }

            // Main thread consumes batches from queue and saves them immediately
            Thread savingThread = new Thread(() -> {
                try {
                    // Cast to ObligationServiceImpl to call the saving method
                    it.deloitte.postrxade.service.impl.ObligationServiceImpl obligationService = 
                        (it.deloitte.postrxade.service.impl.ObligationServiceImpl) savingService;
                    
                    while (completedBatches.get() < totalBatches) {
                        ProcessedRecordBatch batch = batchQueue.poll(10, TimeUnit.SECONDS);
                        if (batch != null) {
                            log.debug("Main thread saving batch, completed: {}/{}", completedBatches.get() + 1, totalBatches);
                            obligationService.saveProcessedBatches(batch, ingestion, submission);
                            completedBatches.incrementAndGet();
                            log.debug("Batch saved successfully, memory cleared for next batch");
                        }
                    }
                } catch (InterruptedException e) {
                    log.error("Saving thread interrupted", e);
                    Thread.currentThread().interrupt();
                }
            });

            savingThread.start();

            // Wait for all processing to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Wait for saving thread to finish
            savingThread.join();

            log.info("Parallel processing with immediate save complete for ingestionId: {}. All {} batches processed and saved",
                    ingestion.getId(), totalBatches);

        } catch (InterruptedException e) {
            log.error("Processing interrupted", e);
            Thread.currentThread().interrupt();
        } finally {
            shutdownExecutor(executor);
        }
    }

    private List<ProcessedFailedRecordBatch> processFailedRecordsInParallel(
            List<String> lines,
            Ingestion ingestion,
            Submission submission,
            Obbligation obbligation) {
        log.debug("Entering processRecordsInParallel() with {} lines for ingestionId: {}", lines != null ? lines.size() : 0,
                ingestion != null ? ingestion.getId() : null);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        AtomicInteger transactionCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        try {
            List<List<String>> batches = partitionList(lines);
            log.info("Processing {} records in {} batches using {} threads",
                    lines.size(), batches.size(), THREAD_POOL_SIZE);

            List<CompletableFuture<ProcessedFailedRecordBatch>> futures = new ArrayList<>();
            log.debug("Created {} batches with batch size: {}", batches.size(), BATCH_SIZE);

            for (int i = 0; i < batches.size(); i++) {
                final int batchIndex = i;
                final List<String> batch = batches.get(i);
                log.debug("Submitting batch {} to executor with {} records", batchIndex, batch.size());

                CompletableFuture<ProcessedFailedRecordBatch> future = CompletableFuture.supplyAsync(() -> {
                    log.debug("Async execution started for batch {}", batchIndex);
                    try {
                        ProcessedFailedRecordBatch result = processFailedBatch(batch, ingestion, submission, obbligation, batchIndex);
                        log.debug("Async execution completed for batch {}", batchIndex);
                        return result;
                    } catch (NotFoundRecordException e) {
                        log.error("Error processing batch {}: {}", batchIndex, e.getMessage());
                        throw new CompletionException(e);
                    }
                }, executor);

                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<ProcessedFailedRecordBatch> processedBatches = new ArrayList<>();
            for (CompletableFuture<ProcessedFailedRecordBatch> future : futures) {
                ProcessedFailedRecordBatch result = future.join();
                processedBatches.add(result);
                transactionCount.addAndGet(result.transactions().size());
                errorCount.addAndGet(result.errorRecords().size());
            }

            log.info("Parallel processing complete for ingestionId: {}. Processed: {} transactions, {} errors.",
                    ingestion.getId(), transactionCount.get(), errorCount.get());

            return processedBatches;

        } finally {
            shutdownExecutor(executor);
        }
    }

    private void shutdownExecutor(ExecutorService executor) {
        log.debug("Shutting down executor service in processRecordsInParallel()");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("Executor termination interrupted, forcing shutdownNow()", e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.debug("Executor service shutdown complete for processRecordsInParallel()");
    }

    private ProcessedRecordBatch processBatch(
            List<String> batch,
            Ingestion ingestion,
            Submission submission,
            Obbligation obbligation,
            int batchIndex,
            Set<String> fileLevelMerchants) throws NotFoundRecordException {
        log.debug("Processing batch {} with {} records", batchIndex, batch.size());

        List<Merchant> merchants = new ArrayList<>();
        List<Transaction> transactions = new ArrayList<>();
        List<ErrorRecord> errorRecords = new ArrayList<>();

        String ingestionTypeName = ingestion.getIngestionType().getName();

        processBatchLines(batch, ingestion, submission, obbligation, batchIndex, ingestionTypeName, merchants, transactions, errorRecords, fileLevelMerchants);

        log.debug("Batch {} complete: {} merchants, {} transactions, {} errors",
                batchIndex, merchants.size(), transactions.size(), errorRecords.size());

        log.trace("Exiting processBatch() for batch {} with merchants={}, transactions={}, errors={}",
                batchIndex, merchants.size(), transactions.size(), errorRecords.size());
        return new ProcessedRecordBatch(merchants, transactions, errorRecords);
    }

    private ProcessedFailedRecordBatch processFailedBatch(
            List<String> batch,
            Ingestion ingestion,
            Submission submission,
            Obbligation obbligation,
            int batchIndex) throws NotFoundRecordException {
        log.debug("Processing batch {} with {} records", batchIndex, batch.size());

        List<ResolvedTransaction> transactions = new ArrayList<>();
        List<ErrorRecord> errorRecords = new ArrayList<>();

        processFailedBatchLines(batch, ingestion, submission, obbligation, batchIndex, transactions, errorRecords);

        log.debug("Batch {} complete: {} transactions, {} errors",
                batchIndex, transactions.size(), errorRecords.size());

        log.trace("Exiting processBatch() for batch {} with transactions={}, errors={}",
                batchIndex, transactions.size(), errorRecords.size());
        return new ProcessedFailedRecordBatch(transactions, errorRecords);
    }

    private void processBatchLines(
            List<String> batch,
            Ingestion ingestion,
            Submission submission,
            Obbligation obbligation,
            int batchIndex,
            String ingestionTypeName,
            List<Merchant> merchants,
            List<Transaction> transactions,
            List<ErrorRecord> errorRecords,
            Set<String> fileLevelMerchants) throws NotFoundRecordException {
        for (String line : batch) {
            log.trace("Processing line in batch {}: {}", batchIndex, line);
            try {
                ErrorRecord error = processLine(line, ingestion, submission, obbligation, ingestionTypeName, merchants, transactions, fileLevelMerchants);
                if (error != null) {
                    errorRecords.add(error);
                    log.trace("Added error record for line in batch {}. Current error count: {}", batchIndex, errorRecords.size());
                }
            } catch (Exception e) {
                log.error("Unexpected error processing line: {}", line, e);
                ErrorRecord error = createErrorRecordFromException(e, line, ingestion, submission);
                errorRecords.add(error);
            }
        }
        validateExisting(ingestion, submission, merchants, transactions, errorRecords);
    }

    private void processFailedBatchLines(
            List<String> batch,
            Ingestion ingestion,
            Submission submission,
            Obbligation obbligation,
            int batchIndex,
            List<ResolvedTransaction> transactions,
            List<ErrorRecord> errorRecords) throws NotFoundRecordException {
        for (String line : batch) {
            log.trace("Processing line in batch {}: {}", batchIndex, line);
            try {
                ErrorRecord error = processFailedTransactionLine(line, ingestion, submission, obbligation, transactions);
                if (error != null) {
                    errorRecords.add(error);
                    log.trace("Added error record for line in batch {}. Current error count: {}", batchIndex, errorRecords.size());
                }
            } catch (Exception e) {
                log.error("Unexpected error processing line: {}", line, e);
                ErrorRecord error = createErrorRecordFromException(e, line, ingestion, submission);
                errorRecords.add(error);
            }
        }
        validateExistingResolvedTransactions(ingestion, submission, transactions, errorRecords);
    }

    private void validateExisting(Ingestion ingestion, Submission submission, List<Merchant> merchants, List<Transaction> transactions, List<ErrorRecord> errorRecords) throws NotFoundRecordException {
        if (ingestion.getIngestionType().getName().equals("anagrafe")) {
            if (!merchants.isEmpty()) validateMerchants(merchants, errorRecords, ingestion, submission);
        } else if (ingestion.getIngestionType().getName().equals("transato")) {
            if (!transactions.isEmpty()) validateTransactions(transactions, errorRecords, ingestion, submission);
        }
    }

    private void validateExistingResolvedTransactions(Ingestion ingestion, Submission submission, List<ResolvedTransaction> transactions, List<ErrorRecord> errorRecords) throws NotFoundRecordException {
        if (!transactions.isEmpty()) validateResolvedTransactions(transactions, errorRecords, ingestion, submission);
    }

    private void validateMerchants(
            List<Merchant> merchants,
            List<ErrorRecord> errorRecords,
            Ingestion ingestion,
            Submission submission) throws NotFoundRecordException {
        List<Merchant> updatedMerchants = new ArrayList<>();
        Map<String, Integer> existingMap = merchantRepository.checkExisting(merchants, submission);

        for (Merchant merchant : merchants) {
            String key = merchant.getIdIntermediario() + "_" + merchant.getIdEsercente() + "_" + submission.getId();
            Integer existsFlag = existingMap.getOrDefault(key, 0);
            if (existsFlag == 1) {
                String description = "Merchant is not created as it has a duplicate in db";
                log.warn("Duplicate merchant found for idIntermediario={}, idEsercente={}, submission_fk={}",
                        merchant.getIdIntermediario(), merchant.getIdEsercente(), submission.getId());

                ErrorRecordCause errorWithDescription = new ErrorRecordCause(
                        description,
                        ErrorTypeCode.FOREIGN_KEY_ERROR.getErrorCode()
                );

                ErrorRecord error = createErrorRecord(List.of(errorWithDescription), merchant.getRawRow(), ingestion, submission);
                errorRecords.add(error);
            } else {
                updatedMerchants.add(merchant);
            }
        }

        merchants.clear();
        merchants.addAll(updatedMerchants);
    }

    private void validateTransactions(
            List<Transaction> transactions,
            List<ErrorRecord> errorRecords,
            Ingestion ingestion,
            Submission submission) throws NotFoundRecordException {
        List<Transaction> updatedTransactions = new ArrayList<>();

        Map<String, Integer> existingTransactionMap =
                transactionRepository.checkExisting(transactions);
        Map<String, Integer> existingMerchantMap =
                merchantRepository.checkExistingByTransactions(transactions);

        for (Transaction transaction : transactions) {
            String trasnsactionKey = transaction.getIdEsercente()
                    + "_" + transaction.getChiaveBanca()
                    + "_" + transaction.getIdPos()
                    + "_" + transaction.getTipoOpe()
                    + "_" + transaction.getDtOpe()
                    + "_" + transaction.getDivisaOpe();

            String merchantKey = transaction.getIdEsercente()
                    + "_" + transaction.getIdIntermediario();

            Integer transactionExistsFlag = existingTransactionMap.getOrDefault(trasnsactionKey, 0);
            Integer merchantExistsFlag = existingMerchantMap.getOrDefault(merchantKey, 0);

            if (merchantExistsFlag == null || merchantExistsFlag == 0) {
                String description = "Transaction is not created as no merchant with same intermediario: " + transaction.getIdIntermediario() + " and esercente: " + transaction.getIdEsercente();
                log.warn("No merchant found for transaction - idIntermediario={}, idEsercente={}",
                        transaction.getIdIntermediario(), transaction.getIdEsercente());
                ErrorRecordCause errorWithDescription = new ErrorRecordCause(
                        description,
                        ErrorTypeCode.FOREIGN_KEY_ERROR.getErrorCode()
                );

                ErrorRecord error = createErrorRecord(List.of(errorWithDescription), transaction.getRawRow(), ingestion, submission);
                errorRecords.add(error);
                } else {
                if (transactionExistsFlag == 1) {
                    String description = "Transaction is not created as it has a duplicate in db";
                    log.warn("Duplicate transaction found - esercente={}, chiaveBanca={}, idPos={}, tipoOpe={}, dtOpe={}, divisaOpe={}",
                            transaction.getIdEsercente(), transaction.getChiaveBanca(), transaction.getIdPos(),
                            transaction.getTipoOpe(), transaction.getDtOpe(), transaction.getDivisaOpe());

                    ErrorRecordCause errorWithDescription = new ErrorRecordCause(
                            description,
                            ErrorTypeCode.FOREIGN_KEY_ERROR.getErrorCode()
                    );

                    ErrorRecord error = createErrorRecord(List.of(errorWithDescription), transaction.getRawRow(), ingestion, submission);
                    errorRecords.add(error);
                } else {
                    updatedTransactions.add(transaction);
                }
            }
        }

        transactions.clear();
        transactions.addAll(updatedTransactions);
    }

    private void validateResolvedTransactions(
            List<ResolvedTransaction> transactions,
            List<ErrorRecord> errorRecords,
            Ingestion ingestion,
            Submission submission) throws NotFoundRecordException {
        List<ResolvedTransaction> updatedTransactions = new ArrayList<>();

        Map<String, Integer> existingTransactionMap =
                transactionRepository.checkExistingWithResolved(transactions);
        Map<String, Integer> existingMerchantMap =
                merchantRepository.checkExistingByResolvedTransactions(transactions);


        for (ResolvedTransaction transaction : transactions) {
            String trasnsactionKey = transaction.getIdEsercente()
                    + "_" + transaction.getChiaveBanca()
                    + "_" + transaction.getIdPos()
                    + "_" + transaction.getTipoOpe()
                    + "_" + transaction.getDtOpe()
                    + "_" + transaction.getDivisaOpe();

            String merchantKey = transaction.getIdEsercente()
                    + "_" + transaction.getIdIntermediario();

            Integer transactionExistsFlag = existingTransactionMap.getOrDefault(trasnsactionKey, 0);
            Integer merchantExistsFlag = existingMerchantMap.getOrDefault(merchantKey, 0);

            if (merchantExistsFlag == null || merchantExistsFlag == 0) {
                String description = "Transaction is not created as no merchant with same intermediario and esercente";
                log.warn("No merchant found for resolved transaction - idIntermediario={}, idEsercente={}",
                        transaction.getIdIntermediario(), transaction.getIdEsercente());
                ErrorRecordCause errorWithDescription = new ErrorRecordCause(
                        description,
                        ErrorTypeCode.FOREIGN_KEY_ERROR.getErrorCode()
                );

                ErrorRecord error = createErrorRecord(List.of(errorWithDescription), transaction.getRawRow(), ingestion, submission);
                errorRecords.add(error);
            } else {
                if (transactionExistsFlag == 1) {
                    String description = "Transaction is not created as it has a duplicate in db";
                    log.debug("Duplicate transaction found for esercente={}, chiaveBanca={}, idPos={}, tipoOpe={}, dtOpe={}, divisaOpe={}",
                            transaction.getIdEsercente(), transaction.getChiaveBanca(), transaction.getIdPos(),
                            transaction.getTipoOpe(), transaction.getDtOpe(), transaction.getDivisaOpe());

                    ErrorRecordCause errorWithDescription = new ErrorRecordCause(
                            description,
                            ErrorTypeCode.FOREIGN_KEY_ERROR.getErrorCode()
                    );

                    ErrorRecord error = createErrorRecord(List.of(errorWithDescription), transaction.getRawRow(), ingestion, submission);
                    errorRecords.add(error);
                } else {
                    updatedTransactions.add(transaction);
                }
            }
        }

        transactions.clear();
        transactions.addAll(updatedTransactions);
    }

    private ErrorRecord processLine(
            String line,
            Ingestion ingestion,
            Submission submission,
            Obbligation obbligation,
            String ingestionTypeName,
            List<Merchant> merchants,
            List<Transaction> transactions,
            Set<String> fileLevelMerchants) throws NotFoundRecordException {
        if (ingestionTypeName.equals("anagrafe")) {
            return processMerchantLine(line, ingestion, submission, merchants, fileLevelMerchants);
        } else if (ingestionTypeName.equals("transato")) {
            return processTransactionLine(line, ingestion, submission, obbligation, transactions);
        }
        return null;
    }

    private ErrorRecord processFailedTransactionLine(
            String line,
            Ingestion ingestion,
            Submission submission,
            Obbligation obbligation,
            List<ResolvedTransaction> transactions) throws NotFoundRecordException {
        return processResolvedTransactionLine(line, ingestion, submission, obbligation, transactions);
    }

    private ErrorRecord processMerchantLine(
            String line,
            Ingestion ingestion,
            Submission submission,
            List<Merchant> merchants,
            Set<String> fileLevelMerchants) throws
            NotFoundRecordException {
        if (line.length() > RAW_ROW_MAX_LENGTH) {
            return createErrorRecord(List.of(lineTooLongCause(line.length())), line, ingestion, submission);
        }
        MerchantRecord record = parser.parseMerchant(line);
        log.debug("Processing merchant record {}", record);
        List<ErrorRecordCause> errorCauses = validator.validateMerchantWithError(record, fileLevelMerchants);
        String merchantRecordKey = record.getIntermediario() + "_" + record.getIdEsercente();
        if (fileLevelMerchants.contains(merchantRecordKey)) {
            errorCauses.add(
                    new ErrorRecordCause("Merchant already exists in the same file", ErrorTypeCode.MERCHANT_ALREADY_EXISTS.getErrorCode())
            );
        }
        if (errorCauses.isEmpty()) {
            Merchant merchant = mapperFacade.map(record, Merchant.class);
            merchant.setIngestion(ingestion);
            merchant.setSubmission(submission);
            merchant.setRawRow(line);
            merchants.add(merchant);
            String merchantKey = merchant.getIdIntermediario() + "_" + merchant.getIdEsercente();
            fileLevelMerchants.add(merchantKey);
        } else {
            return createErrorRecord(errorCauses, line, ingestion, submission);
        }
        return null;
    }

    private ErrorRecord processTransactionLine(
            String line,
            Ingestion ingestion,
            Submission submission,
            Obbligation obbligation,
            List<Transaction> transactions) throws
            NotFoundRecordException {
        if (line.length() > RAW_ROW_MAX_LENGTH) {
            return createErrorRecord(List.of(lineTooLongCause(line.length())), line, ingestion, submission);
        }
        TransactionRecord record = parser.parseTransaction(line);
        log.debug("Processing transaction record {}", record);
        List<ErrorRecordCause> errorCauses = validator.validateTransactionWithError(record, obbligation);
        if (!errorCauses.isEmpty()) {
            return createErrorRecord(errorCauses, line, ingestion, submission);
        } else {
            Transaction entity = mapperFacade.map(record, Transaction.class);
            
            // Correzione IMP-OPE: la stringa ha le ultime 2 cifre come decimali
            // Esempio: "00000000011387" -> 11387 -> divido per 100 -> 113.87
            if (record.getImportoTotaleOperazioni() != null && !record.getImportoTotaleOperazioni().trim().isEmpty()) {
                try {
                    java.math.BigDecimal impOpeValue = new java.math.BigDecimal(record.getImportoTotaleOperazioni().trim());
                    entity.setImpOpe(impOpeValue.divide(new java.math.BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP));
                } catch (NumberFormatException e) {
                    log.warn("Invalid IMP-OPE format: {}", record.getImportoTotaleOperazioni());
                    entity.setImpOpe(null);
                }
            }
            
            entity.setIngestion(ingestion);
            entity.setSubmission(submission);
            entity.setRawRow(line);
            transactions.add(entity);
        }
        return null;
    }

    private ErrorRecord processResolvedTransactionLine(
            String line,
            Ingestion ingestion,
            Submission submission,
            Obbligation obbligation,
            List<ResolvedTransaction> resolvedTransactions) throws
            NotFoundRecordException {
        if (line.length() > RAW_ROW_MAX_LENGTH) {
            return createErrorRecord(List.of(lineTooLongCause(line.length())), line, ingestion, submission);
        }

        TransactionRecord record = parser.parseTransaction(line);
        log.debug("Processing transaction record {}", record);
        List<ErrorRecordCause> errorCauses = validator.validateTransactionWithError(record, obbligation);
        if (!errorCauses.isEmpty()) {
            return createErrorRecord(errorCauses, line, ingestion, submission);
        } else {
            ResolvedTransaction entity = mapperFacade.map(record, ResolvedTransaction.class);
            
            // Correzione IMP-OPE: la stringa ha le ultime 2 cifre come decimali
            // Esempio: "00000000011387" -> 11387 -> divido per 100 -> 113.87
            if (record.getImportoTotaleOperazioni() != null && !record.getImportoTotaleOperazioni().trim().isEmpty()) {
                try {
                    java.math.BigDecimal impOpeValue = new java.math.BigDecimal(record.getImportoTotaleOperazioni().trim());
                    entity.setImpOpe(impOpeValue.divide(new java.math.BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP));
                } catch (NumberFormatException e) {
                    log.warn("Invalid IMP-OPE format: {}", record.getImportoTotaleOperazioni());
                    entity.setImpOpe(null);
                }
            }
            
            entity.setIngestion(ingestion);
            entity.setSubmission(submission);
            entity.setRawRow(line);
            resolvedTransactions.add(entity);
        }
        return null;
    }

    private List<List<String>> partitionList(List<String> list) {
        int batchSize = BATCH_SIZE;
        log.trace("Partitioning list of size {} with batchSize {}", list != null ? list.size() : 0, batchSize);
        List<List<String>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            partitions.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        log.trace("Created {} partitions from list", partitions.size());
        return partitions;
    }

    private ErrorRecord createErrorRecord(
            List<ErrorRecordCause> errorCauses,
            String line,
            Ingestion ingestion,
            Submission submission) throws NotFoundRecordException {
        log.trace("Entering createErrorRecord() for ingestionId: {}", ingestion != null ? ingestion.getId() : null);
        ErrorRecord errorRecord = new ErrorRecord();
        errorRecord.setRawRow(truncateRawRow(line));
        errorRecord.setIngestion(ingestion);
        errorRecord.setSubmission(submission);

        List<ErrorCause> casuses = new ArrayList<>();

        for (ErrorRecordCause cause : errorCauses) {
            ErrorType errorType = errorTypeService.getErrorType(cause.errorCode());

            ErrorCause errorCause = new ErrorCause();
            errorCause.setErrorRecord(errorRecord);
            errorCause.setErrorMessage(cause.description());
            errorCause.setErrorType(errorType);
            errorCause.setSubmission(submission);
            casuses.add(errorCause);
        }
        errorRecord.setErrorCauses(casuses);
        log.debug("Record validation failed for line: {} with errors: {}", line, errorCauses);
        return errorRecord;
    }

    private ErrorRecord createErrorRecordFromException(
            Exception exception,
            String line,
            Ingestion ingestion,
            Submission submission) throws NotFoundRecordException {
        log.trace("Entering createErrorRecordFromException() for ingestionId: {}", ingestion != null ? ingestion.getId() : null);
        ErrorRecord errorRecord = new ErrorRecord();
        errorRecord.setRawRow(truncateRawRow(line));
        errorRecord.setIngestion(ingestion);
        errorRecord.setSubmission(submission);

        String errorMessage = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
        log.debug("Record processing failed with exception for line: {} with error: {}", line, errorMessage);
        ErrorType errorType = errorTypeService.getErrorType(ErrorTypeCode.INVALID_FORMAT.getErrorCode());

        ErrorCause errorCause = new ErrorCause();
        errorCause.setErrorType(errorType);
        errorCause.setErrorMessage(errorMessage);

        List<ErrorCause> causes = new ArrayList<>();
        causes.add(errorCause);
        errorRecord.setErrorCauses(causes);

        return errorRecord;
    }
}
