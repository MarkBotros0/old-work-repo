package it.deloitte.postrxade.service.impl;

import it.deloitte.postrxade.entity.Output;
import it.deloitte.postrxade.entity.ResolvedTransaction;
import it.deloitte.postrxade.entity.Submission;
import it.deloitte.postrxade.entity.Transaction;
import it.deloitte.postrxade.enums.SubmissionStatusEnum;
import it.deloitte.postrxade.exception.NotFoundRecordException;
import it.deloitte.postrxade.formatter.OutputFileFormatter;
import it.deloitte.postrxade.repository.OutputTransactionLineMapEntry;
import it.deloitte.postrxade.repository.OutputTransactionLineMapRepository;
import it.deloitte.postrxade.repository.OutputRepository;
import it.deloitte.postrxade.repository.ResolvedTransactionRepository;
import it.deloitte.postrxade.repository.SubmissionRepository;
import it.deloitte.postrxade.repository.SubmissionStatusRepository;
import it.deloitte.postrxade.repository.TransactionRepository;
import it.deloitte.postrxade.service.EcsTaskService;
import it.deloitte.postrxade.service.OutputService;
import it.deloitte.postrxade.service.S3Service;
import it.deloitte.postrxade.tenant.TenantConfiguration;
import it.deloitte.postrxade.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ALTERNATIVE IMPLEMENTATION: Lazy fk_output update approach
 * 
 * This approach avoids updating fk_output BEFORE generating the file.
 * Instead, it:
 * 1. Creates Output entity
 * 2. Generates file by reading transactions with fk_submission = X AND fk_output IS NULL
 * 3. Updates fk_output AFTER writing each batch to the file (or at the end)
 * 
 * This is MUCH faster for very large tables (30M+ records) because:
 * - No need to update millions of records before starting file generation
 * - Updates only the records that were actually written to the file
 * - Smaller, incremental updates instead of one massive update
 */
@Slf4j
@Service("outputServiceLazy")
@Primary  // Make this the primary bean so it's used by default
public class OutputServiceImplLazy implements OutputService {

    @Value("${output.rows-per-file:1000000}")
    private int rowsPerOutputFile;
    
    private static final int UPDATE_BATCH_SIZE = 100; // Small batches for fk_output updates
    private static final int FETCH_BATCH_SIZE = 5000; // Batch size for fetching transactions
    private static final int FK_UPDATE_BATCH_SIZE = 1000; // Batch size for updating fk_output after writing
    private static final String OUTPUT_FILE_EXTENSION = "txt";
    
    @Value("${aws.s3.output-folder}")
    private String s3OutputFolder;

    @Autowired
    private OutputRepository outputRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private SubmissionStatusRepository submissionStatusRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private ResolvedTransactionRepository resolvedTransactionRepository;

    @Autowired
    private S3Service s3Service;

    @Autowired
    private OutputTransactionLineMapRepository outputTransactionLineMapRepository;

    @Autowired(required = false)
    private TenantConfiguration tenantConfiguration;

    @Autowired(required = false)
    private EcsTaskService ecsTaskService;

    @Value("${aws.ecs.enabled:true}")
    private boolean ecsEnabled;

    private TransactionTemplate transactionTemplate;
    private PlatformTransactionManager transactionManager;

    @Autowired
    public void initTransactionTemplate(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setTimeout(600); // 10 minutes
    }

    private TransactionTemplate createBatchTransactionTemplate(int timeoutSeconds) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setTimeout(timeoutSeconds);
        return template;
    }

    private String buildSubmissionPath(Submission submission) {
        if (submission == null || submission.getId() == null) {
            return "unknown";
        }

        var obbligation = submission.getObbligation();
        if (obbligation == null) {
            log.warn("Submission {} has no obligation, using submission ID only for path", submission.getId());
            return String.valueOf(submission.getId());
        }

        var period = obbligation.getPeriod();
        Integer fiscalYear = obbligation.getFiscalYear();

        if (period == null || period.getName() == null || fiscalYear == null) {
            log.warn("Submission {} has incomplete obligation data (period={}, fiscalYear={}), using submission ID only for path",
                    submission.getId(), period != null ? period.getName() : "null", fiscalYear);
            return String.valueOf(submission.getId());
        }

        return String.format("%d_%s%d", submission.getId(), period.getName(), fiscalYear);
    }

    private String normalizePath(String basePath, String subPath) {
        if (basePath == null) basePath = "";
        if (subPath == null) subPath = "";

        basePath = basePath.replaceAll("/+$", "");
        subPath = subPath.replaceAll("^/+", "");

        if (basePath.isEmpty()) {
            return subPath;
        }
        if (subPath.isEmpty()) {
            return basePath;
        }

        return basePath + "/" + subPath;
    }

    /**
     * LAZY APPROACH: Generate output by reading transactions directly and updating fk_output incrementally
     * 
     * In the lazy approach, we create Output entities as needed while processing transactions.
     * We don't pre-create thousands of Output entities - instead, we create one at a time
     * and process transactions in chunks (rowsPerOutputFile records per file).
     */
    @Override
    public List<Output> generateOutput(Submission submission) {
        if (submission == null || submission.getId() == null) {
            return Collections.emptyList();
        }

        log.info("Starting LAZY generateOutput for submissionId={}", submission.getId());
        List<Output> outputs = new ArrayList<>();
        
        // Check if there are transactions to process
        Long transactionCount = transactionRepository.countBySubmissionIdAndNullOutput(submission.getId());
        Long resolvedCount = resolvedTransactionRepository.countByCurrentSubmissionIdAndNullOutput(submission.getId());
        
        log.info("Found {} transactions and {} resolved transactions with null fk_output for submissionId={}", 
                transactionCount != null ? transactionCount : 0, 
                resolvedCount != null ? resolvedCount : 0, 
                submission.getId());

        // In lazy approach, we create Output entities on-demand during file generation
        // For now, return empty list - generateSubmissionOutputTxt will create Output entities as needed
        // This avoids creating thousands of Output entities upfront
        log.info("LAZY generateOutput: Returning empty list. Output entities will be created on-demand during file generation.");
        return outputs;
    }

    /**
     * LAZY APPROACH: Generate file by reading transactions with fk_output IS NULL and updating fk_output incrementally
     * Creates Output entities on-demand, one per file (max 1M records per file)
     */
    @Override
    public void generateSubmissionOutputTxt(Long submissionId) throws NotFoundRecordException, IOException {
        log.info("Generating TXT outputs using LAZY approach for submissionId={}", submissionId);

        Submission submission = submissionRepository.findOneById(submissionId)
                .orElseThrow(() -> new NotFoundRecordException("Submission not found with id: " + submissionId));

        if (submission.getObbligation() != null) {
            submission.getObbligation().getPeriod();
            submission.getObbligation().getFiscalYear();
        }

        // Check if there are transactions to process
        Long transactionCount = transactionRepository.countBySubmissionIdAndNullOutput(submissionId);
        Long resolvedCount = resolvedTransactionRepository.countByCurrentSubmissionIdAndNullOutput(submissionId);
        
        if ((transactionCount == null || transactionCount == 0) && (resolvedCount == null || resolvedCount == 0)) {
            log.info("No transactions to process for submissionId={}. Updating status to DELOITTE_REVIEW (6).", submissionId);
            moveSubmissionToDeloitteReview(submissionId);
            return;
        }

        int filesGenerated = 0;

        // Build submission path inside transaction to ensure lazy-loaded Period is accessible
        final String submissionPath = transactionTemplate.execute(status -> {
            Submission attachedSubmission = submissionRepository.findOneById(submissionId)
                    .orElseThrow(() -> new RuntimeException("Submission not found: " + submissionId));
            // Trigger lazy loading of Period
            if (attachedSubmission.getObbligation() != null) {
                attachedSubmission.getObbligation().getPeriod();
                attachedSubmission.getObbligation().getFiscalYear();
            }
            return buildSubmissionPath(attachedSubmission);
        });

        String codiceFiscale = resolveOutputCodiceFiscale();

        // Process regular transactions
        if (transactionCount != null && transactionCount > 0) {
            filesGenerated += processTransactionsLazy(submission, false, transactionCount, submissionPath, codiceFiscale);
        }

        // Process resolved transactions
        if (resolvedCount != null && resolvedCount > 0) {
            filesGenerated += processTransactionsLazy(submission, true, resolvedCount, submissionPath, codiceFiscale);
        }

        log.info("LAZY output generation completed successfully for submissionId={}, {} file(s) generated and uploaded to S3", 
                submissionId, filesGenerated);

        moveSubmissionToDeloitteReview(submissionId);
    }

    /** Porta la submission da PROCESSING (5) a DELOITTE_REVIEW (6) al termine della generazione output. */
    private void moveSubmissionToDeloitteReview(Long submissionId) {
        submissionStatusRepository.findOneByOrder(SubmissionStatusEnum.DELOITTE_REVIEW.getOrder())
                .ifPresentOrElse(
                        status -> transactionTemplate.executeWithoutResult(s -> {
                            submissionRepository.updateStatus(submissionId, status);
                            log.info("Submission {} status updated from PROCESSING (5) to DELOITTE_REVIEW (6) after output generation", submissionId);
                        }),
                        () -> log.warn("SubmissionStatus with order 6 (DELOITTE_REVIEW) not found; submission {} left in current status", submissionId)
                );
    }

    /** Codice fiscale per header/footer output: da tenant (Nexi 04107060966, Amex 14778691007). */
    private String resolveOutputCodiceFiscale() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null && !tenantId.isBlank() && tenantConfiguration != null) {
            String resolved = TenantConfiguration.resolveTenantAlias(tenantId);
            TenantConfiguration.TenantProperties props = tenantConfiguration.getTenantProperties(resolved);
            if (props != null && props.getOutputCodiceFiscale() != null && !props.getOutputCodiceFiscale().isBlank()) {
                return props.getOutputCodiceFiscale().trim();
            }
        }
        return "04107060966";
    }

    /**
     * Process transactions (regular or resolved) using lazy approach
     * Creates Output entities on-demand, one per file (max rowsPerOutputFile records per file)
     */
    private int processTransactionsLazy(Submission submission, boolean isResolvedTransaction, Long totalCount, String submissionPath, String codiceFiscale) throws IOException {
        Long submissionId = submission.getId();
        int filesGenerated = 0;
        int currentPartNumber = 1;
        Long lastId = null;
        int recordsInCurrentFile = 0;
        Output currentOutput = null;
        List<Long> transactionIdsWritten = new ArrayList<>();
        List<OutputTransactionLineMapEntry> transactionLineEntriesWritten = new ArrayList<>();
        ByteArrayOutputStream currentFileBaos = null;
        BufferedWriter currentFileBw = null;

        log.info("Starting LAZY processing for {} transactions (total: {}, max {} per file)", 
                isResolvedTransaction ? "resolved" : "regular", totalCount, rowsPerOutputFile);

        while (true) {
            // Create new Output entity and file if needed
            if (currentOutput == null) {
                final int partNumber = currentPartNumber;
                currentOutput = transactionTemplate.execute(status -> {
                    Submission attachedSubmission = submissionRepository.findOneById(submissionId)
                            .orElseThrow(() -> new RuntimeException("Submission not found: " + submissionId));
                    
                    Output output = new Output();
                    output.setSubmission(attachedSubmission);
                    output.setGeneratedAt(LocalDateTime.now().toString());
                    output.setExtensionType(OUTPUT_FILE_EXTENSION);

                    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                    String fileName;
                    if (isResolvedTransaction) {
                        fileName = String.format(
                                "TRXPOSADE_OUTPUT_RESOLVED_%S_%d_resolved_part%03d.%s",
                                timestamp,
                                attachedSubmission.getId(),
                                partNumber,
                                OUTPUT_FILE_EXTENSION
                        );
                    } else {
                        fileName = String.format(
                                "TRXPOSADE_OUTPUT_%s_%d_part%03d.%s",
                                timestamp,
                                attachedSubmission.getId(),
                                partNumber,
                                OUTPUT_FILE_EXTENSION
                        );
                    }

                    String fullPath = normalizePath(s3OutputFolder, normalizePath(submissionPath, fileName));
                    output.setFullPath(fullPath);
                    return outputRepository.save(output);
                });

                // Check if file already exists on S3
                String s3Key = currentOutput.getFullPath();
                if (s3Service.fileExists(s3Key)) {
                    log.info("File already exists on S3: {} for outputId={}. Skipping.", 
                            s3Key, currentOutput.getId());
                    filesGenerated++;
                    currentOutput = null;
                    currentPartNumber++;
                    continue;
                }

                // Initialize new file
                currentFileBaos = new ByteArrayOutputStream();
                currentFileBw = new BufferedWriter(new OutputStreamWriter(currentFileBaos, StandardCharsets.UTF_8));
                currentFileBw.write(OutputFileFormatter.createHeader(codiceFiscale));
                recordsInCurrentFile = 0;
                
                log.info("Created new Output entity: outputId={}, partNumber={}", currentOutput.getId(), currentPartNumber);
            }

            // Fetch batch of transactions
            List<?> batch;
            if (isResolvedTransaction) {
                batch = resolvedTransactionRepository.findBySubmissionIdAndNullOutputWithMerchantsBulkFetched(
                        submissionId, lastId, FETCH_BATCH_SIZE);
            } else {
                batch = transactionRepository.findBySubmissionIdAndNullOutputWithMerchantsBulkFetched(
                        submissionId, lastId, FETCH_BATCH_SIZE);
            }

            if (batch.isEmpty()) {
                // No more transactions - finalize current file
                if (currentOutput != null && recordsInCurrentFile > 0) {
                    currentFileBw.write(OutputFileFormatter.createFooter(codiceFiscale));
                    currentFileBw.flush();
                    
                    String s3Key = currentOutput.getFullPath();
                    log.info("Uploading final file to S3: {} for outputId={}. File size: {} bytes, Records: {}", 
                            s3Key, currentOutput.getId(), currentFileBaos.size(), recordsInCurrentFile);
                    try (ByteArrayInputStream fileInputStream = new ByteArrayInputStream(currentFileBaos.toByteArray())) {
                        s3Service.uploadFile(s3Key, fileInputStream, "text/plain");
                        log.info("File successfully uploaded to S3: {} for outputId={}", s3Key, currentOutput.getId());
                        filesGenerated++;
                    }
                    
                    // Update remaining fk_output
                    if (!transactionIdsWritten.isEmpty()) {
                        updateFkOutputAndLineMapInBatches(
                                transactionIdsWritten,
                                transactionLineEntriesWritten,
                                currentOutput.getId(),
                                isResolvedTransaction
                        );
                        transactionLineEntriesWritten.clear();
                    }
                }
                break;
            }

            // Process batch - respect 1M record limit per file
            Object lastWrittenTransaction = null;
            boolean limitReached = false;
            int transactionsProcessedInBatch = 0;
            
            for (Object t : batch) {
                // Check if we've reached the limit BEFORE writing this transaction
                if (recordsInCurrentFile >= rowsPerOutputFile) {
                    limitReached = true;
                    break; // Exit loop - will finalize file below
                }

                // Write transaction to current file
                if (currentFileBw != null) {
                    if (isResolvedTransaction) {
                        currentFileBw.write(OutputFileFormatter.toOutputFileString((ResolvedTransaction) t, codiceFiscale));
                        transactionIdsWritten.add(((ResolvedTransaction) t).getId());
                        lastWrittenTransaction = t;
                    } else {
                        int dataRowNum = recordsInCurrentFile + 1; // Header excluded: first data row is 1
                        currentFileBw.write(OutputFileFormatter.toOutputFileString((Transaction) t, codiceFiscale));
                        Long transactionId = ((Transaction) t).getId();
                        transactionIdsWritten.add(transactionId);
                        transactionLineEntriesWritten.add(new OutputTransactionLineMapEntry(transactionId, dataRowNum));
                        lastWrittenTransaction = t;
                    }
                    recordsInCurrentFile++;
                    transactionsProcessedInBatch++;
                }
            }

            // If limit reached, finalize current file
            if (limitReached && currentOutput != null) {
                currentFileBw.write(OutputFileFormatter.createFooter(codiceFiscale));
                currentFileBw.flush();
                currentFileBw.close();
                
                String s3Key = currentOutput.getFullPath();
                log.info("Uploading file to S3: {} for outputId={}. File size: {} bytes, Records: {} (limit {} reached). Processed {}/{} transactions from batch.", 
                        s3Key, currentOutput.getId(), currentFileBaos.size(), recordsInCurrentFile, rowsPerOutputFile, 
                        transactionsProcessedInBatch, batch.size());
                try (ByteArrayInputStream fileInputStream = new ByteArrayInputStream(currentFileBaos.toByteArray())) {
                    s3Service.uploadFile(s3Key, fileInputStream, "text/plain");
                    log.info("File successfully uploaded to S3: {} for outputId={}", s3Key, currentOutput.getId());
                    filesGenerated++;
                }
                
                // Update fk_output for records written to this file
                if (!transactionIdsWritten.isEmpty()) {
                    updateFkOutputAndLineMapInBatches(
                            transactionIdsWritten,
                            transactionLineEntriesWritten,
                            currentOutput.getId(),
                            isResolvedTransaction
                    );
                    transactionIdsWritten.clear();
                    transactionLineEntriesWritten.clear();
                }
                
                // CRITICAL: Update cursor to last written transaction
                // The next query will use pk_transaction > lastId AND fk_output IS NULL
                // Since we just updated fk_output for written transactions, the next query will
                // correctly skip them and include only the unprocessed transactions from this batch
                if (lastWrittenTransaction != null) {
                    if (isResolvedTransaction) {
                        lastId = ((ResolvedTransaction) lastWrittenTransaction).getId();
                    } else {
                        lastId = ((Transaction) lastWrittenTransaction).getId();
                    }
                    log.debug("Limit reached: updating cursor to last written transaction ID: {} (processed {}/{} from batch). Remaining transactions will be processed in next iteration.", 
                            lastId, transactionsProcessedInBatch, batch.size());
                } else {
                    log.warn("Limit reached but lastWrittenTransaction is null. Cursor not updated - this may cause issues.");
                }
                
                // Reset for next file
                currentOutput = null;
                currentFileBaos = null;
                currentFileBw = null;
                currentPartNumber++;
                recordsInCurrentFile = 0;
                
                // Continue loop to process remaining transactions in next iteration
                continue;
            }

            // Update fk_output incrementally (if not at limit)
            if (!limitReached && transactionIdsWritten.size() >= FK_UPDATE_BATCH_SIZE && currentOutput != null) {
                updateFkOutputAndLineMapInBatches(
                        transactionIdsWritten,
                        transactionLineEntriesWritten,
                        currentOutput.getId(),
                        isResolvedTransaction
                );
                transactionIdsWritten.clear();
                transactionLineEntriesWritten.clear();
            }

            // Update cursor with last written transaction (or last in batch if all written)
            // Only update if we didn't reach the limit (limitReached case is handled above with continue)
            if (!batch.isEmpty() && !limitReached) {
                if (lastWrittenTransaction != null) {
                    // Use last written transaction as cursor
                    if (isResolvedTransaction) {
                        lastId = ((ResolvedTransaction) lastWrittenTransaction).getId();
                    } else {
                        lastId = ((Transaction) lastWrittenTransaction).getId();
                    }
                } else {
                    // All transactions in batch were written
                    if (isResolvedTransaction) {
                        lastId = ((ResolvedTransaction) batch.get(batch.size() - 1)).getId();
                    } else {
                        lastId = ((Transaction) batch.get(batch.size() - 1)).getId();
                    }
                }
            }

            // Log progress periodically
            if (recordsInCurrentFile % 100000 == 0) {
                log.info("Processed {} records in current file (outputId={}, partNumber={})", 
                        recordsInCurrentFile, currentOutput != null ? currentOutput.getId() : "N/A", currentPartNumber);
            }
        }

        // Cleanup: close file if still open
        if (currentFileBw != null) {
            try {
                currentFileBw.close();
            } catch (IOException e) {
                log.warn("Error closing file writer: {}", e.getMessage());
            }
        }

        log.info("Completed LAZY processing for {} transactions. Generated {} files.", 
                isResolvedTransaction ? "resolved" : "regular", filesGenerated);
        return filesGenerated;
    }

    /**
     * Update fk_output in small batches to avoid timeout
     */
    private void updateFkOutputAndLineMapInBatches(List<Long> transactionIds,
                                                   List<OutputTransactionLineMapEntry> lineEntries,
                                                   Long outputId,
                                                   boolean isResolved) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            return;
        }

        log.debug("Updating fk_output for {} transaction IDs in batches for outputId={}", transactionIds.size(), outputId);

        // Split into smaller batches
        for (int i = 0; i < transactionIds.size(); i += UPDATE_BATCH_SIZE) {
            int endIndex = Math.min(i + UPDATE_BATCH_SIZE, transactionIds.size());
            List<Long> batch = transactionIds.subList(i, endIndex);
            List<OutputTransactionLineMapEntry> lineBatch = (!isResolved && lineEntries != null && !lineEntries.isEmpty())
                    ? lineEntries.subList(i, endIndex)
                    : Collections.emptyList();

            TransactionTemplate batchTemplate = createBatchTransactionTemplate(600);
            batchTemplate.execute(status -> {
                if (isResolved) {
                    return resolvedTransactionRepository.updateOutputForeignKeyOptimized(batch, outputId);
                } else {
                    int updatedRows = transactionRepository.updateOutputForeignKeyOptimized(batch, outputId);
                    if (!lineBatch.isEmpty()) {
                        outputTransactionLineMapRepository.bulkInsert(outputId, lineBatch);
                    }
                    return updatedRows;
                }
            });
        }
    }

    @Override
    public void generateSubmissionOutputTxtAsync(Long submissionId) {
        if (ecsEnabled && ecsTaskService != null) {
            try {
                log.info("Launching ECS task for LAZY output generation - submissionId: {}", submissionId);
                String taskArn = ecsTaskService.launchOutputGenerationTask(submissionId);
                log.info("ECS task launched successfully - taskArn: {}, submissionId: {}", taskArn, submissionId);
            } catch (Exception e) {
                log.error("Failed to launch ECS task for output generation - submissionId: {}, error: {}", 
                        submissionId, e.getMessage(), e);
                log.warn("Falling back to synchronous generation");
                try {
                    generateSubmissionOutputTxt(submissionId);
                } catch (NotFoundRecordException | IOException ex) {
                    log.error("Error generating output synchronously (fallback) for submissionId={}: {}", 
                            submissionId, ex.getMessage(), e);
                }
            }
        } else {
            log.warn("ECS not enabled or EcsTaskService not available. Generating output synchronously");
            try {
                generateSubmissionOutputTxt(submissionId);
            } catch (NotFoundRecordException | IOException e) {
                log.error("Error generating output synchronously for submissionId={}: {}", submissionId, e.getMessage(), e);
            }
        }
    }
}
