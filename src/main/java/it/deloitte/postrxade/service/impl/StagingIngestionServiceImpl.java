package it.deloitte.postrxade.service.impl;

import it.deloitte.postrxade.entity.*;
import it.deloitte.postrxade.enums.ErrorTypeCode;
import it.deloitte.postrxade.exception.NotFoundRecordException;
import it.deloitte.postrxade.parser.transaction.FileLineParser;
import it.deloitte.postrxade.parser.transaction.FileLineValidator;
import it.deloitte.postrxade.parser.transaction.MerchantRecord;
import it.deloitte.postrxade.parser.transaction.RemoteFile;
import it.deloitte.postrxade.parser.transaction.TransactionRecord;
import it.deloitte.postrxade.records.ErrorRecordCause;
import it.deloitte.postrxade.records.StagingResult;
import it.deloitte.postrxade.repository.ErrorRecordRepository;
import it.deloitte.postrxade.repository.StagingRepository;
import it.deloitte.postrxade.service.ErrorTypeService;
import it.deloitte.postrxade.service.StagingIngestionService;
import lombok.extern.slf4j.Slf4j;
import ma.glasnost.orika.MapperFacade;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Implementation of staging-based ingestion service.
 * 
 * <p>This implementation provides performance improvements over row-by-row processing
 * while maintaining ALL business validation rules:</p>
 * 
 * <ul>
 *   <li>Full data quality validation (format, mandatory fields, values)</li>
 *   <li>Intra-file duplicate detection for merchants</li>
 *   <li>Database duplicate detection via staging tables</li>
 *   <li>Missing merchant detection for transactions</li>
 *   <li>ErrorRecord/ErrorCause creation for all validation failures</li>
 * </ul>
 */
@Service
@Slf4j
public class StagingIngestionServiceImpl implements StagingIngestionService {

    private final StagingRepository stagingRepository;
    private final ErrorRecordRepository errorRecordRepository;
    private final ErrorTypeService errorTypeService;
    private final MapperFacade mapperFacade;
    private final FileLineParser parser = new FileLineParser();
    private final FileLineValidator validator = new FileLineValidator();
    private static final int RAW_ROW_MAX_LENGTH = 250;

    public StagingIngestionServiceImpl(
            StagingRepository stagingRepository,
            ErrorRecordRepository errorRecordRepository,
            ErrorTypeService errorTypeService,
            @Qualifier("mapperFacade") MapperFacade mapperFacade) {
        this.stagingRepository = stagingRepository;
        this.errorRecordRepository = errorRecordRepository;
        this.errorTypeService = errorTypeService;
        this.mapperFacade = mapperFacade;
    }

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

    // ==================== MERCHANT FILE PROCESSING ====================

    // Batch size for streaming - process merchants in chunks to avoid OOM
    private static final int MERCHANT_PARSE_BATCH_SIZE = 50000;

    @Override
    public StagingResult processMerchantFile(RemoteFile file, Ingestion ingestion, Submission submission) throws IOException {
        log.info("Starting STREAMING staging-based merchant ingestion for file: {}, submission: {}", 
                file.name(), submission.getId());
        long startTime = System.currentTimeMillis();

        // Phase 1: Read all lines as strings (low memory footprint)
        List<String> lines = readAllLines(file);
        if (lines.isEmpty()) {
            return new StagingResult(0, 0);
        }

        int totalLines = lines.size();
        log.info("Read {} lines from file in {}ms", totalLines, System.currentTimeMillis() - startTime);

        // Validate header/footer
        validator.validateHeader(lines.getFirst(), ingestion.getIngestionType().getName());
        validator.validateFooter(lines.getLast(), lines.size(), ingestion.getIngestionType().getName());
        lines.removeFirst();
        lines.removeLast();

        // Phase 2: Process in BATCHES to avoid OOM
        int totalValidationErrors = 0;
        int totalParsed = 0;
        int batchNumber = 0;
        
        List<Merchant> batchMerchants = new ArrayList<>(MERCHANT_PARSE_BATCH_SIZE);
        List<ErrorRecord> batchErrors = new ArrayList<>();
        Set<String> fileLevelMerchants = new HashSet<>(); // For intra-file duplicate detection (kept across batches)
        
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            try {
                ErrorRecord error = processMerchantLine(line, ingestion, submission, batchMerchants, fileLevelMerchants);
                if (error != null) {
                    batchErrors.add(error);
                }
            } catch (Exception e) {
                if (totalValidationErrors < 10) {
                    log.warn("Error processing merchant line {}: {}", i + 1, e.getMessage());
                }
                try {
                    ErrorRecord error = createErrorRecordFromException(e, line, ingestion, submission);
                    batchErrors.add(error);
                } catch (NotFoundRecordException ex) {
                    log.error("Failed to create error record for exception", ex);
                }
            }
            
            // When batch is full OR at end of file, load to staging
            if (batchMerchants.size() >= MERCHANT_PARSE_BATCH_SIZE || i == lines.size() - 1) {
                if (!batchMerchants.isEmpty() || !batchErrors.isEmpty()) {
                    batchNumber++;
                    log.info("Processing batch {}: {} merchants, {} errors (total parsed: {}/{})", 
                            batchNumber, batchMerchants.size(), batchErrors.size(), 
                            i + 1, lines.size());
                    
                    // Save errors for this batch
                    if (!batchErrors.isEmpty()) {
                        errorRecordRepository.bulkInsertRecordsWithCauses(batchErrors, ingestion.getId());
                        totalValidationErrors += batchErrors.size();
                    }
                    
                    // Load merchants to staging (NOT final insert yet)
                    if (!batchMerchants.isEmpty()) {
                        stagingRepository.bulkLoadMerchantsToStaging(batchMerchants, ingestion.getId(), submission.getId());
                        totalParsed += batchMerchants.size();
                    }
                    
                    // CRITICAL: Clear batch lists to free memory (but keep fileLevelMerchants for dedup)
                    batchMerchants.clear();
                    batchErrors.clear();
                }
            }
        }
        
        // Clear original lines list to free memory before final processing
        lines.clear();
        
        log.info("Loaded {} merchants to staging in {} batches, {} validation errors, in {}ms", 
                totalParsed, batchNumber, totalValidationErrors, System.currentTimeMillis() - startTime);

        // Phase 3: Set-based processing (duplicate detection, final insert)
        log.info("Starting set-based processing from staging...");
        long processStart = System.currentTimeMillis();
        StagingResult stagingResult = stagingRepository.processMerchantsFromStaging(submission.getId());
        log.info("Set-based processing completed in {}ms", System.currentTimeMillis() - processStart);

        // Phase 4: Create ErrorRecords for DB duplicates
        if (stagingResult.duplicateCount() > 0) {
            createErrorRecordsForDuplicateMerchants(ingestion, submission);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Completed merchant ingestion in {}ms: parsed={}, inserted={}, duplicates={}, validationErrors={}", 
                elapsed, totalParsed, stagingResult.insertedCount(), stagingResult.duplicateCount(), totalValidationErrors);

        return new StagingResult(stagingResult.insertedCount(), stagingResult.duplicateCount(), 0, totalValidationErrors);
    }

    /**
     * Process a single merchant line with full validation (same logic as FileProcessingService).
     */
    private ErrorRecord processMerchantLine(
            String line,
            Ingestion ingestion,
            Submission submission,
            List<Merchant> merchants,
            Set<String> fileLevelMerchants) throws NotFoundRecordException {

        if (line.length() > RAW_ROW_MAX_LENGTH) {
            return createErrorRecord(List.of(lineTooLongCause(line.length())), line, ingestion, submission);
        }
        
        MerchantRecord record = parser.parseMerchant(line);
        
        // Validate with all business rules (format, mandatory fields, values)
        List<ErrorRecordCause> errorCauses = validator.validateMerchantWithError(record, fileLevelMerchants);
        
        // Check for intra-file duplicates
        String merchantRecordKey = record.getIntermediario() + "_" + record.getIdEsercente();
        if (fileLevelMerchants.contains(merchantRecordKey)) {
            errorCauses.add(new ErrorRecordCause(
                    "Merchant already exists in the same file", 
                    ErrorTypeCode.MERCHANT_ALREADY_EXISTS.getErrorCode()
            ));
        }
        
        if (errorCauses.isEmpty()) {
            // Valid record - add to list for staging
            Merchant merchant = mapperFacade.map(record, Merchant.class);
            merchant.setIngestion(ingestion);
            merchant.setSubmission(submission);
            merchant.setRawRow(line);
            merchants.add(merchant);
            fileLevelMerchants.add(merchantRecordKey);
            return null;
        } else {
            // Invalid record - create error
            return createErrorRecord(errorCauses, line, ingestion, submission);
        }
    }

    // ==================== TRANSACTION FILE PROCESSING ====================

    // Batch size for streaming - process transactions in chunks to avoid OOM
    // Reduced from 50000 to 10000 for very large files (11M+ records) to prevent memory issues
    // Each Transaction object is ~500-1000 bytes, so 10000 = ~5-10MB per batch (safe)
    private static final int TRANSACTION_PARSE_BATCH_SIZE = 10000;

    @Override
    public StagingResult processTransactionFile(RemoteFile file, Ingestion ingestion, Submission submission, Obbligation obbligation) throws IOException {
        // Backward-compatible behavior for single-file ingestion: load to STG + finalize immediately.
        // For multi-file submissions, prefer: loadTransactionFileToStaging(...) for each file,
        // then finalizeTransactionsFromStaging(...) once.
        StagingResult loadResult = loadTransactionFileToStaging(file, ingestion, submission, obbligation);
        StagingResult finalizeResult = finalizeTransactionsFromStaging(ingestion, submission);
        // Keep validationErrors from the load phase (finalize has 0 parsing validation errors by definition).
        return new StagingResult(
                finalizeResult.insertedCount(),
                finalizeResult.duplicateCount(),
                finalizeResult.missingMerchantCount(),
                loadResult.errorCount()
        );
    }

    @Override
    public StagingResult loadTransactionFileToStaging(RemoteFile file, Ingestion ingestion, Submission submission, Obbligation obbligation) throws IOException {
        log.info("==========================================================");
        log.info("Starting TRUE STREAMING staging-based transaction ingestion");
        log.info("File: {}, Submission: {}", file.name(), submission.getId());
        log.info("Batch size: {}, Max error batch: 2000", TRANSACTION_PARSE_BATCH_SIZE);
        log.info("==========================================================");
        long startTime = System.currentTimeMillis();

        // Phase 1: Process in TRUE STREAMING mode (single pass, line by line, NOT all in memory)
        String firstLine = null;
        String lastLine = null;
        int lineCount = 0; // Total lines read (header + transactions + footer)
        int totalValidationErrors = 0; // Lines with validation errors (saved to ERROR_RECORD)
        int totalParsed = 0; // Valid transactions loaded to staging
        int totalProcessed = 0; // Total lines processed (valid + errors, excluding header/footer)
        int batchNumber = 0;
        
        List<Transaction> batchTransactions = new ArrayList<>(TRANSACTION_PARSE_BATCH_SIZE);
        // Limit error batch to prevent OOM when all records are errors
        List<ErrorRecord> batchErrors = new ArrayList<>(5000); // Max 5000 errors per batch
        
        // Single pass: read line by line, process in batches
        // Strategy: Keep previous line to detect footer (when current is null, previous was footer)
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.stream(), StandardCharsets.UTF_8))) {
            
            String line;
            String previousLine = null;
            boolean headerProcessed = false;
            
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                
                lineCount++;
                
                // Capture first non-blank line as header
                if (!headerProcessed) {
                    firstLine = line;
                    validator.validateHeader(firstLine, ingestion.getIngestionType().getName());
                    headerProcessed = true;
                    // DON'T set previousLine = line here! The header should NOT be processed as a transaction.
                    // Leave previousLine = null so the next line (first transaction) will be processed correctly.
                    continue; // Skip header, don't process it
                }
                
                // Check if current line is footer (starts with "9" and ends with "Z")
                // We need to check this BEFORE processing previousLine, because if current line is footer,
                // previousLine is the last transaction, not the footer
                boolean isFooter = line.length() >= 250 && line.charAt(0) == '9' && line.charAt(249) == 'Z';
                
                // If we have a previous line, it's a transaction (process it before checking footer)
                if (previousLine != null) {
                    // Process previous line as transaction
                    totalProcessed++; // Count this line as processed (valid or error)
                    try {
                        ErrorRecord error = processTransactionLine(previousLine, ingestion, submission, obbligation, batchTransactions);
                        if (error != null) {
                            batchErrors.add(error);
                        }
                    } catch (Exception e) {
                        // DISABLED: Logging disabled in batch mode to avoid millions of log entries
                        // Only log at DEBUG level for troubleshooting
                        if (log.isDebugEnabled() && batchErrors.size() < 5) {
                            log.debug("Exception processing transaction line {}: {} - Line preview: {}...", 
                                    lineCount - 1, e.getMessage(), 
                                    previousLine.length() > 100 ? previousLine.substring(0, 100) : previousLine);
                        }
                        try {
                            ErrorRecord error = createErrorRecordFromException(e, previousLine, ingestion, submission);
                            batchErrors.add(error);
                        } catch (NotFoundRecordException ex) {
                            log.error("Failed to create error record for exception", ex);
                        }
                    }
                    
                    // When batch is full OR error batch is too large, load to staging
                    // CRITICAL: Limit error batch size to prevent OOM and improve bulk insert performance
                    // Smaller error batches (1000) improve bulkInsertRecordsWithCauses performance
                    boolean shouldFlush = batchTransactions.size() >= TRANSACTION_PARSE_BATCH_SIZE 
                            || batchErrors.size() >= 1000; // Reduced from 2000 to 1000 for better bulk insert performance
                    
                    if (shouldFlush) {
                        batchNumber++;
                        long batchStartTime = System.currentTimeMillis();
                        // Log shows: valid transactions loaded to staging, validation errors, and total lines processed
                        log.info("Processing batch {}: {} valid transactions loaded to staging, {} validation errors (total processed: {} lines)", 
                                batchNumber, batchTransactions.size(), batchErrors.size(), totalProcessed);
                        
                        // Save errors for this batch FIRST (before transactions) to free memory
                        if (!batchErrors.isEmpty()) {
                            long errorInsertStart = System.currentTimeMillis();
                            errorRecordRepository.bulkInsertRecordsWithCauses(batchErrors, ingestion.getId());
                            totalValidationErrors += batchErrors.size();
                            long errorInsertDuration = System.currentTimeMillis() - errorInsertStart;
                            if (errorInsertDuration > 5000) { // Log only if > 5 seconds
                                log.warn("Error batch insert took {}ms for {} errors (consider reducing batch size)", 
                                        errorInsertDuration, batchErrors.size());
                            }
                            // CRITICAL: Clear errors immediately to free memory
                            batchErrors.clear();
                        }
                        
                        // Load transactions to staging (NOT final insert yet)
                        if (!batchTransactions.isEmpty()) {
                            long stagingStart = System.currentTimeMillis();
                            stagingRepository.bulkLoadTransactionsToStaging(batchTransactions, ingestion.getId(), submission.getId());
                            totalParsed += batchTransactions.size();
                            long stagingDuration = System.currentTimeMillis() - stagingStart;
                            if (stagingDuration > 10000) { // Log only if > 10 seconds
                                log.warn("Staging insert took {}ms for {} transactions", stagingDuration, batchTransactions.size());
                            }
                            // CRITICAL: Clear transactions immediately to free memory
                            batchTransactions.clear();
                        }
                        
                        long batchDuration = System.currentTimeMillis() - batchStartTime;
                        if (batchDuration > 30000) { // Log warning if batch takes > 30 seconds
                            log.warn("Batch {} took {}ms ({} transactions, {} errors) - performance issue detected", 
                                    batchNumber, batchDuration, batchTransactions.size() + batchErrors.size(), batchErrors.size());
                        }
                    }
                }
                
                // If current line is footer, validate it and stop processing
                if (isFooter) {
                    lastLine = line;
                    try {
                        // Footer validation: totalProcessed is the number of transactions (excluding header and footer)
                        // The footer contains the count of transaction records only (not header/footer)
                        validator.validateFooter(lastLine, totalProcessed, ingestion.getIngestionType().getName());
                    } catch (Exception e) {
                        log.warn("Footer validation failed: {}", e.getMessage());
                    }
                    break; // Stop reading, we've reached the footer
                }
                
                // Update previous line (only if not footer)
                previousLine = line;
            }
            
            // Process final batch (remaining items)
            if (!batchTransactions.isEmpty() || !batchErrors.isEmpty()) {
                batchNumber++;
                log.info("Processing final batch {}: {} valid transactions loaded to staging, {} validation errors (total processed: {} lines)", 
                        batchNumber, batchTransactions.size(), batchErrors.size(), totalProcessed);
                
                if (!batchErrors.isEmpty()) {
                    errorRecordRepository.bulkInsertRecordsWithCauses(batchErrors, ingestion.getId());
                    totalValidationErrors += batchErrors.size();
                }
                
                if (!batchTransactions.isEmpty()) {
                    stagingRepository.bulkLoadTransactionsToStaging(batchTransactions, ingestion.getId(), submission.getId());
                    totalParsed += batchTransactions.size();
                }
                
                batchTransactions.clear();
                batchErrors.clear();
            }
        }
        
        // Final summary with detailed breakdown
        log.info("==========================================================");
        log.info("FILE PARSING SUMMARY");
        log.info("==========================================================");
        log.info("Total lines read (non-blank): {} (header + transactions + footer)", lineCount);
        log.info("Total transactions processed: {} (valid + errors, excluding header/footer)", totalProcessed);
        log.info("  - Valid transactions loaded to staging: {}", totalParsed);
        log.info("  - Validation errors (saved to ERROR_RECORD): {}", totalValidationErrors);
        log.info("  - Expected: {} (should match totalParsed + totalValidationErrors)", totalParsed + totalValidationErrors);
        log.info("Batches processed: {}", batchNumber);
        log.info("Time elapsed: {}ms", System.currentTimeMillis() - startTime);
        log.info("==========================================================");
        
        // Sanity check: totalProcessed should equal totalParsed + totalValidationErrors
        if (totalProcessed != totalParsed + totalValidationErrors) {
            log.warn("WARNING: Discrepancy detected! totalProcessed ({}) != totalParsed ({}) + totalValidationErrors ({}) = {}", 
                    totalProcessed, totalParsed, totalValidationErrors, totalParsed + totalValidationErrors);
        }
        
        // Sanity check: lineCount should be totalProcessed + 2 (header + footer)
        int expectedLineCount = totalProcessed + 2; // +2 for header and footer
        if (lineCount != expectedLineCount) {
            log.warn("WARNING: Line count discrepancy! lineCount ({}) != totalProcessed ({}) + 2 = {}", 
                    lineCount, totalProcessed, expectedLineCount);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Completed transaction file load-to-staging in {}ms: parsed={}, validationErrors={}",
                elapsed, totalParsed, totalValidationErrors);

        // NOTE: This method DOES NOT perform set-based processing from staging.
        // Use finalizeTransactionsFromStaging(...) once after all transaction files have been loaded.
        return new StagingResult(0, 0, 0, totalValidationErrors);
    }

    @Override
    public StagingResult finalizeTransactionsFromStaging(Ingestion ingestion, Submission submission) {
        // Phase 3: Set-based processing (duplicate detection, missing merchant, final insert)
        log.info("Starting set-based processing from staging for submission {}...", submission.getId());
        long processStart = System.currentTimeMillis();
        StagingResult stagingResult = stagingRepository.processTransactionsFromStaging(submission.getId());
        log.info("Set-based processing completed in {}ms", System.currentTimeMillis() - processStart);

        // Phase 4: Create ErrorRecords for DB duplicates, batch duplicates (within submission batch), and missing merchants
        int missingCount = stagingResult.missingMerchantCount();
        if (missingCount > 0) {
            log.info("Phase 4: Creating error records for {} missing merchant transaction(s) (ERR1)...", missingCount);
            int written = createErrorRecordsForMissingMerchantTransactionsChunk(ingestion, submission);
            log.info("Phase 4: Wrote {} error records for missing merchants (ERR1) to ERROR_RECORD/ERROR_CAUSE", written);
        }
        if (stagingResult.duplicateCount() > 0) {
            int batchDuplicateErrors = createErrorRecordsForBatchDuplicateTransactionsChunk(ingestion, submission);
            int existingDuplicateErrors = createErrorRecordsForDuplicateTransactionsChunk(ingestion, submission);
            log.info("Created {} batch duplicate + {} existing duplicate error records", batchDuplicateErrors, existingDuplicateErrors);
        }

        // finalize stage doesn't add parsing validation errors (those are created during load phase)
        return new StagingResult(
                stagingResult.insertedCount(),
                stagingResult.duplicateCount(),
                stagingResult.missingMerchantCount(),
                0
        );
    }

    /**
     * Process a single transaction line with full validation (same logic as FileProcessingService).
     */
    private ErrorRecord processTransactionLine(
            String line,
            Ingestion ingestion,
            Submission submission,
            Obbligation obbligation,
            List<Transaction> transactions) throws NotFoundRecordException {

        if (line.length() > RAW_ROW_MAX_LENGTH) {
            return createErrorRecord(List.of(lineTooLongCause(line.length())), line, ingestion, submission);
        }
        
        try {
            TransactionRecord record = parser.parseTransaction(line);
            
            // Validate with all business rules (format, date, mandatory fields, values)
            List<ErrorRecordCause> errorCauses = validator.validateTransactionWithError(record, obbligation);
            
            if (!errorCauses.isEmpty()) {
                // DISABLED: Logging disabled in batch mode to avoid millions of log entries
                // Only log at DEBUG level for troubleshooting (not INFO)
                if (log.isDebugEnabled() && transactions.size() < 5) {
                    log.debug("Transaction validation failed (sample {}): {} errors - {} - Line: {}...", 
                            transactions.size(),
                            errorCauses.size(), 
                            errorCauses.stream().map(ErrorRecordCause::description).limit(3).toList(),
                            line.length() > 100 ? line.substring(0, 100) : line);
                }
                // Invalid record - create error
                return createErrorRecord(errorCauses, line, ingestion, submission);
            } else {
                // Valid record - add to list for staging
                Transaction entity = mapperFacade.map(record, Transaction.class);
                
                // Correzione IMP-OPE: la stringa ha le ultime 2 cifre come decimali
                // Esempio: "00000000011387" -> 11387 -> divido per 100 -> 113.87
                if (record.getImportoTotaleOperazioni() != null && !record.getImportoTotaleOperazioni().trim().isEmpty()) {
                    try {
                        BigDecimal impOpeValue = new BigDecimal(record.getImportoTotaleOperazioni().trim());
                        entity.setImpOpe(impOpeValue.divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP));
                    } catch (NumberFormatException e) {
                        log.warn("Invalid IMP-OPE format: {}", record.getImportoTotaleOperazioni());
                        entity.setImpOpe(null);
                    }
                }
                
                entity.setIngestion(ingestion);
                entity.setSubmission(submission);
                entity.setRawRow(line);
                transactions.add(entity);
                return null;
            }
        } catch (Exception e) {
            // Log first 10 parsing exceptions for debugging
            if (transactions.size() < 10) {
                log.warn("Exception parsing transaction line (sample {}): {} - Line preview: {}...", 
                        transactions.size(),
                        e.getMessage(), 
                        line.length() > 100 ? line.substring(0, 100) : line);
            }
            throw e; // Re-throw to be caught by caller
        }
    }

    // ==================== STAGING TABLE PROCESSING ====================

    @Override
    @Transactional
    public StagingResult processMerchants(List<Merchant> merchants, List<ErrorRecord> validationErrors, 
                                          Ingestion ingestion, Submission submission) {
        // Save validation errors first
        if (!validationErrors.isEmpty()) {
            log.info("Saving {} validation error records for merchants", validationErrors.size());
            errorRecordRepository.bulkInsertRecordsWithCauses(validationErrors, ingestion.getId());
        }

        if (merchants == null || merchants.isEmpty()) {
            return new StagingResult(0, 0, 0, validationErrors.size());
        }

        log.info("Processing {} valid merchants via staging tables", merchants.size());
        long startTime = System.currentTimeMillis();

        // Phase 1: Bulk load into staging table
        long loadStart = System.currentTimeMillis();
        stagingRepository.bulkLoadMerchantsToStaging(merchants, ingestion.getId(), submission.getId());
        log.info("Load phase completed in {}ms", System.currentTimeMillis() - loadStart);

        // Phase 2: Set-based processing (DB duplicate detection + insert)
        long processStart = System.currentTimeMillis();
        StagingResult result = stagingRepository.processMerchantsFromStaging(submission.getId());
        log.info("Process phase completed in {}ms", System.currentTimeMillis() - processStart);

        // Phase 3: Create ErrorRecords for DB duplicates
        if (result.duplicateCount() > 0) {
            long errorStart = System.currentTimeMillis();
            int errorCount = createErrorRecordsForDuplicateMerchants(ingestion, submission);
            log.info("Created {} error records for DB duplicate merchants in {}ms", 
                    errorCount, System.currentTimeMillis() - errorStart);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Merchant staging processing completed in {}ms: inserted={}, duplicates={}", 
                elapsed, result.insertedCount(), result.duplicateCount());

        return new StagingResult(result.insertedCount(), result.duplicateCount(), 0, validationErrors.size());
    }

    @Override
    @Transactional
    public StagingResult processTransactions(List<Transaction> transactions, List<ErrorRecord> validationErrors,
                                             Ingestion ingestion, Submission submission) {
        // Save validation errors first
        if (!validationErrors.isEmpty()) {
            log.info("Saving {} validation error records for transactions", validationErrors.size());
            errorRecordRepository.bulkInsertRecordsWithCauses(validationErrors, ingestion.getId());
        }

        if (transactions == null || transactions.isEmpty()) {
            return new StagingResult(0, 0, 0, validationErrors.size());
        }

        log.info("Processing {} valid transactions via staging tables", transactions.size());
        long startTime = System.currentTimeMillis();

        // Phase 1: Bulk load into staging table
        long loadStart = System.currentTimeMillis();
        stagingRepository.bulkLoadTransactionsToStaging(transactions, ingestion.getId(), submission.getId());
        log.info("Load phase completed in {}ms", System.currentTimeMillis() - loadStart);

        // Phase 2: Set-based processing (missing merchant + DB duplicate detection + insert)
        long processStart = System.currentTimeMillis();
        StagingResult result = stagingRepository.processTransactionsFromStaging(submission.getId());
        log.info("Process phase completed in {}ms", System.currentTimeMillis() - processStart);

        // Phase 3: Create ErrorRecords for DB duplicates and missing merchants
        // Use chunk-based error creation for both chunk-based and single-pass processing
        // (no UPDATE on STG tables - only SELECT queries to identify errors)
        long errorStart = System.currentTimeMillis();
        int duplicateErrors = 0;
        int missingMerchantErrors = 0;
        
        // Always use chunk-based error creation (no UPDATE on STG tables)
        // This works for both chunk-based and single-pass processing
        // Note: duplicateCount includes both batch duplicates (same file) and existing duplicates (already in TRANSACTION)
        if (result.missingMerchantCount() > 0) {
            missingMerchantErrors = createErrorRecordsForMissingMerchantTransactionsChunk(ingestion, submission);
        }
        if (result.duplicateCount() > 0) {
            // Create ErrorRecords for both types of duplicates:
            // 1. Duplicates within batch (same file)
            // 2. Duplicates already in TRANSACTION
            int batchDuplicateErrors = createErrorRecordsForBatchDuplicateTransactionsChunk(ingestion, submission);
            int existingDuplicateErrors = createErrorRecordsForDuplicateTransactionsChunk(ingestion, submission);
            duplicateErrors = batchDuplicateErrors + existingDuplicateErrors;
        }
        
        if (duplicateErrors > 0 || missingMerchantErrors > 0) {
            log.info("Created {} duplicate + {} missing merchant error records in {}ms", 
                    duplicateErrors, missingMerchantErrors, System.currentTimeMillis() - errorStart);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Transaction staging processing completed in {}ms: inserted={}, duplicates={}, missingMerchants={}", 
                elapsed, result.insertedCount(), result.duplicateCount(), result.missingMerchantCount());

        return new StagingResult(result.insertedCount(), result.duplicateCount(), 
                result.missingMerchantCount(), validationErrors.size());
    }

    @Override
    @Transactional
    public void cleanupStaging(Long submissionId) {
        log.info("Cleaning up staging tables for submission: {}", submissionId);
        stagingRepository.clearStaging(submissionId);
    }

    @Override
    // NO @Transactional here - clearTransactionStaging uses TransactionTemplate for each batch
    // This prevents long-running transactions and connection timeouts
    public void cleanupTransactionStaging(Long submissionId) {
        log.info("Cleaning up transaction staging table for submission: {}", submissionId);
        stagingRepository.clearTransactionStaging(submissionId);
    }

    // ==================== ERROR RECORD CREATION ====================

    /**
     * Create ErrorRecords for duplicate merchants found in staging (DB duplicates).
     * Public method for resuming ingestion from staging.
     */
    @Override
    public int createErrorRecordsForDuplicateMerchants(Ingestion ingestion, Submission submission) {
        List<Object[]> duplicates = stagingRepository.getDuplicateMerchantDetails(submission.getId());
        if (duplicates.isEmpty()) {
            return 0;
        }

        List<ErrorRecord> errorRecords = new ArrayList<>();
        for (Object[] row : duplicates) {
            String rawRow = row[0] != null ? String.valueOf(row[0]) : "";
            String errorMessage = row[1] != null ? String.valueOf(row[1]) : "Merchant already exists in database";
            
            try {
                ErrorRecord errorRecord = createErrorRecord(
                        List.of(new ErrorRecordCause(errorMessage, ErrorTypeCode.MERCHANT_ALREADY_EXISTS.getErrorCode())),
                        rawRow, ingestion, submission
                );
                errorRecords.add(errorRecord);
            } catch (NotFoundRecordException e) {
                log.warn("Failed to create error record for duplicate merchant: {}", e.getMessage());
            }
        }

        if (!errorRecords.isEmpty()) {
            errorRecordRepository.bulkInsertRecordsWithCauses(errorRecords, ingestion.getId());
        }
        return errorRecords.size();
    }

    @Override
    public int createErrorRecordsForMissingMerchantTransactionsChunk(Ingestion ingestion, Submission submission) {
        log.info("Creating error records for missing merchants in chunks (no UPDATE on STG)...");
        int totalErrors = 0;
        Long lastProcessedMaxPk = null;
        int chunkNumber = 0;
        final int PROCESSING_CHUNK_SIZE = 100_000;
        
        while (true) {
            Long minPk = stagingRepository.getMinPendingPkForChunk(submission.getId(), lastProcessedMaxPk);
            if (minPk == null) {
                log.info("No more records to process for missing merchants after {} chunks", chunkNumber);
                break;
            }
            
            chunkNumber++;
            Long maxPk = minPk + PROCESSING_CHUNK_SIZE;
            
            // Get missing merchant records for this chunk (SELECT only, no UPDATE)
            List<Object[]> missingMerchantRecords = stagingRepository.getMissingMerchantTransactionDetailsChunk(
                    submission.getId(), minPk, maxPk);
            
            // Do NOT stop after a few empty chunks: missing merchants can be concentrated in later
            // pk ranges (e.g. end of file). We must scan until getMinPendingPk returns null.
            if (missingMerchantRecords.isEmpty()) {
                lastProcessedMaxPk = maxPk;
                if (chunkNumber % 20 == 0) {
                    log.debug("Chunk {}: no missing merchants in pk range [{}, {}), continuing...", chunkNumber, minPk, maxPk);
                }
                continue;
            }
            
            // Create ErrorRecords for this chunk
            List<ErrorRecord> errorRecords = new ArrayList<>();
            for (Object[] row : missingMerchantRecords) {
                String rawRow = row[0] != null ? String.valueOf(row[0]) : "";
                String errorMessage = row[1] != null ? String.valueOf(row[1]) : "Merchant not found for transaction";
                
                try {
                    ErrorRecord errorRecord = createErrorRecord(
                            List.of(new ErrorRecordCause(errorMessage, ErrorTypeCode.FOREIGN_KEY_ERROR.getErrorCode())),
                            rawRow, ingestion, submission
                    );
                    errorRecords.add(errorRecord);
                } catch (NotFoundRecordException e) {
                    log.warn("Failed to create error record for missing merchant transaction: {}", e.getMessage());
                }
            }
            
            if (!errorRecords.isEmpty()) {
                log.info("Writing {} missing merchant error records (ERR1) to ERROR_RECORD for chunk {}", errorRecords.size(), chunkNumber);
                errorRecordRepository.bulkInsertRecordsWithCauses(errorRecords, ingestion.getId());
                totalErrors += errorRecords.size();
            }
            
            lastProcessedMaxPk = maxPk;
            
            if (chunkNumber % 10 == 0) {
                log.info("Processed {} chunks for missing merchant errors, created {} so far", chunkNumber, totalErrors);
            }
        }
        
        log.info("Created {} error records for missing merchants in {} chunks", totalErrors, chunkNumber);
        return totalErrors;
    }

    /**
     * Create ErrorRecords for duplicate transactions found in staging (DB duplicates).
     * Public method for resuming ingestion from staging.
     */
    @Override
    public int createErrorRecordsForDuplicateTransactions(Ingestion ingestion, Submission submission) {
        List<Object[]> duplicates = stagingRepository.getDuplicateTransactionDetails(submission.getId());
        if (duplicates.isEmpty()) {
            return 0;
        }

        List<ErrorRecord> errorRecords = new ArrayList<>();
        for (Object[] row : duplicates) {
            String rawRow = row[0] != null ? String.valueOf(row[0]) : "";
            String errorMessage = row[1] != null ? String.valueOf(row[1]) : "Transaction already exists in database";
            
            try {
                ErrorRecord errorRecord = createErrorRecord(
                        List.of(new ErrorRecordCause(errorMessage, ErrorTypeCode.TRANSACTION_ALREADY_EXISTS.getErrorCode())),
                        rawRow, ingestion, submission
                );
                errorRecords.add(errorRecord);
            } catch (NotFoundRecordException e) {
                log.warn("Failed to create error record for duplicate transaction: {}", e.getMessage());
            }
        }

        if (!errorRecords.isEmpty()) {
            errorRecordRepository.bulkInsertRecordsWithCauses(errorRecords, ingestion.getId());
        }
        return errorRecords.size();
    }

    @Override
    public int createErrorRecordsForDuplicateTransactionsChunk(Ingestion ingestion, Submission submission) {
        log.info("Creating error records for duplicate transactions (already in TRANSACTION) in chunks (no UPDATE on STG)...");
        int totalErrors = 0;
        Long lastProcessedMaxPk = null;
        int chunkNumber = 0;
        final int PROCESSING_CHUNK_SIZE = 100_000;
        
        while (true) {
            Long minPk = stagingRepository.getMinPendingPkForChunk(submission.getId(), lastProcessedMaxPk);
            if (minPk == null) {
                log.info("No more records to process for duplicates after {} chunks", chunkNumber);
                break;
            }
            
            chunkNumber++;
            Long maxPk = minPk + PROCESSING_CHUNK_SIZE;
            
            // Get duplicate records for this chunk (SELECT only, no UPDATE)
            List<Object[]> duplicateRecords = stagingRepository.getDuplicateTransactionDetailsChunk(
                    submission.getId(), minPk, maxPk);
            
            if (duplicateRecords.isEmpty()) {
                // Try a few more chunks before stopping
                boolean foundMore = false;
                for (int i = 0; i < 3; i++) {
                    Long nextMinPk = stagingRepository.getMinPendingPkForChunk(submission.getId(), maxPk);
                    if (nextMinPk == null) break;
                    Long nextMaxPk = nextMinPk + PROCESSING_CHUNK_SIZE;
                    List<Object[]> nextChunk = stagingRepository.getDuplicateTransactionDetailsChunk(
                            submission.getId(), nextMinPk, nextMaxPk);
                    if (!nextChunk.isEmpty()) {
                        foundMore = true;
                        break;
                    }
                    maxPk = nextMaxPk;
                }
                if (!foundMore) {
                    log.info("Stopping duplicate error creation after {} chunks with no records", chunkNumber);
                    break;
                }
                lastProcessedMaxPk = maxPk;
                continue;
            }
            
            // Create ErrorRecords for this chunk
            List<ErrorRecord> errorRecords = new ArrayList<>();
            for (Object[] row : duplicateRecords) {
                String rawRow = row[0] != null ? String.valueOf(row[0]) : "";
                String errorMessage = row[1] != null ? String.valueOf(row[1]) : "Transaction already exists in database";
                
                try {
                    ErrorRecord errorRecord = createErrorRecord(
                            List.of(new ErrorRecordCause(errorMessage, ErrorTypeCode.TRANSACTION_ALREADY_EXISTS.getErrorCode())),
                            rawRow, ingestion, submission
                    );
                    errorRecords.add(errorRecord);
                } catch (NotFoundRecordException e) {
                    log.warn("Failed to create error record for duplicate transaction: {}", e.getMessage());
                }
            }
            
            if (!errorRecords.isEmpty()) {
                errorRecordRepository.bulkInsertRecordsWithCauses(errorRecords, ingestion.getId());
                totalErrors += errorRecords.size();
            }
            
            lastProcessedMaxPk = maxPk;
            
            if (chunkNumber % 10 == 0) {
                log.info("Processed {} chunks for duplicate errors, created {} so far", chunkNumber, totalErrors);
            }
        }
        
        log.info("Created {} error records for duplicate transactions in {} chunks", totalErrors, chunkNumber);
        return totalErrors;
    }

    @Override
    public int createErrorRecordsForBatchDuplicateTransactionsChunk(Ingestion ingestion, Submission submission) {
        log.info("Creating error records for batch duplicate transactions (same file) (single query, then batch insert)...");
        // Single query for all batch duplicates to run the heavy GROUP BY only once (no N×chunk scans).
        List<Object[]> duplicateRecords = stagingRepository.getBatchDuplicateTransactionDetailsAll(submission.getId());
        if (duplicateRecords.isEmpty()) {
            log.info("No batch duplicate records to create error records for");
            return 0;
        }
        final int INSERT_BATCH_SIZE = 5000;
        int totalErrors = 0;
        List<ErrorRecord> batch = new ArrayList<>(INSERT_BATCH_SIZE);
        for (Object[] row : duplicateRecords) {
            String rawRow = row[0] != null ? String.valueOf(row[0]) : "";
            String errorMessage = row[1] != null ? String.valueOf(row[1]) : "Duplicate within batch: same transaction appears multiple times in the file";
            try {
                ErrorRecord errorRecord = createErrorRecord(
                        List.of(new ErrorRecordCause(errorMessage, ErrorTypeCode.TRANSACTION_ALREADY_EXISTS.getErrorCode())),
                        rawRow, ingestion, submission
                );
                batch.add(errorRecord);
                if (batch.size() >= INSERT_BATCH_SIZE) {
                    errorRecordRepository.bulkInsertRecordsWithCauses(batch, ingestion.getId());
                    totalErrors += batch.size();
                    batch.clear();
                }
            } catch (NotFoundRecordException e) {
                log.warn("Failed to create error record for batch duplicate transaction: {}", e.getMessage());
            }
        }
        if (!batch.isEmpty()) {
            errorRecordRepository.bulkInsertRecordsWithCauses(batch, ingestion.getId());
            totalErrors += batch.size();
        }
        log.info("Created {} error records for batch duplicate transactions", totalErrors);
        return totalErrors;
    }

    /**
     * Create ErrorRecords for transactions with missing merchants.
     * Public method for resuming ingestion from staging.
     */
    @Override
    public int createErrorRecordsForMissingMerchantTransactions(Ingestion ingestion, Submission submission) {
        List<Object[]> missingMerchants = stagingRepository.getMissingMerchantTransactionDetails(submission.getId());
        if (missingMerchants.isEmpty()) {
            return 0;
        }

        List<ErrorRecord> errorRecords = new ArrayList<>();
        for (Object[] row : missingMerchants) {
            String rawRow = row[0] != null ? String.valueOf(row[0]) : "";
            String errorMessage = row[1] != null ? String.valueOf(row[1]) : "Merchant not found for transaction";
            
            try {
                ErrorRecord errorRecord = createErrorRecord(
                        List.of(new ErrorRecordCause(errorMessage, ErrorTypeCode.FOREIGN_KEY_ERROR.getErrorCode())),
                        rawRow, ingestion, submission
                );
                errorRecords.add(errorRecord);
            } catch (NotFoundRecordException e) {
                log.warn("Failed to create error record for missing merchant: {}", e.getMessage());
            }
        }

        if (!errorRecords.isEmpty()) {
            errorRecordRepository.bulkInsertRecordsWithCauses(errorRecords, ingestion.getId());
        }
        return errorRecords.size();
    }

    /**
     * Create an ErrorRecord with causes.
     */
    private ErrorRecord createErrorRecord(
            List<ErrorRecordCause> errorCauses,
            String line,
            Ingestion ingestion,
            Submission submission) throws NotFoundRecordException {
        
        ErrorRecord errorRecord = new ErrorRecord();
        errorRecord.setRawRow(truncateRawRow(line));
        errorRecord.setIngestion(ingestion);
        errorRecord.setSubmission(submission);

        List<ErrorCause> causes = new ArrayList<>();
        for (ErrorRecordCause cause : errorCauses) {
            ErrorType errorType = errorTypeService.getErrorType(cause.errorCode());
            
            ErrorCause errorCause = new ErrorCause();
            errorCause.setErrorRecord(errorRecord);
            errorCause.setErrorMessage(cause.description());
            errorCause.setErrorType(errorType);
            errorCause.setSubmission(submission);
            causes.add(errorCause);
        }
        errorRecord.setErrorCauses(causes);
        return errorRecord;
    }

    /**
     * Create an ErrorRecord from an exception.
     */
    private ErrorRecord createErrorRecordFromException(
            Exception exception,
            String line,
            Ingestion ingestion,
            Submission submission) throws NotFoundRecordException {
        
        ErrorRecord errorRecord = new ErrorRecord();
        errorRecord.setRawRow(truncateRawRow(line));
        errorRecord.setIngestion(ingestion);
        errorRecord.setSubmission(submission);

        ErrorType errorType = errorTypeService.getErrorType(ErrorTypeCode.INVALID_FORMAT.getErrorCode());
        
        ErrorCause errorCause = new ErrorCause();
        errorCause.setErrorRecord(errorRecord);
        errorCause.setErrorMessage("Parse error: " + exception.getMessage());
        errorCause.setErrorType(errorType);
        errorCause.setSubmission(submission);
        
        errorRecord.setErrorCauses(List.of(errorCause));
        return errorRecord;
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Read all lines from file.
     */
    private List<String> readAllLines(RemoteFile file) throws IOException {
        List<String> lines = new ArrayList<>();
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.stream(), StandardCharsets.UTF_8))) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        
        return lines;
    }
}
