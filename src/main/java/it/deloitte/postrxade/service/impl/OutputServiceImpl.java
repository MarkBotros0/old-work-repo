package it.deloitte.postrxade.service.impl;

import it.deloitte.postrxade.entity.Obbligation;
import it.deloitte.postrxade.entity.Output;
import it.deloitte.postrxade.entity.Period;
import it.deloitte.postrxade.entity.ResolvedTransaction;
import it.deloitte.postrxade.entity.Submission;
import it.deloitte.postrxade.entity.Transaction;
import it.deloitte.postrxade.exception.NotFoundRecordException;
import it.deloitte.postrxade.formatter.OutputFileFormatter;
import it.deloitte.postrxade.repository.OutputRepository;
import it.deloitte.postrxade.repository.ResolvedTransactionRepository;
import it.deloitte.postrxade.repository.SubmissionRepository;
import it.deloitte.postrxade.repository.TransactionRepository;
import it.deloitte.postrxade.service.EcsTaskService;
import it.deloitte.postrxade.service.OutputService;
import it.deloitte.postrxade.service.S3Service;
import it.deloitte.postrxade.tenant.TenantConfiguration;
import it.deloitte.postrxade.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

@Slf4j
@Service
public class OutputServiceImpl implements OutputService {

    // Number of rows per output file (configurable via environment variable OUTPUT_ROWS_PER_FILE)
    // Default: 1000000 (calculated to produce ~300MB files based on 30MB for 100k records)
    @Value("${output.rows-per-file:1000000}")
    private int rowsPerOutputFile;
    
    private static final int UPDATE_BATCH_SIZE = 100; // Batch size for database updates (reduced from 500 - UPDATE on very large TRANSACTION table (30M+ records) needs smaller batches)
    private static final int FETCH_BATCH_SIZE = 5000; // Batch size for fetching transactions from database (increased from 1000 for better performance)
    private static final String OUTPUT_FILE_EXTENSION = "txt";
    @Value("${aws.s3.output-folder}")
    private String s3OutputFolder;

    @Autowired
    private OutputRepository outputRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private ResolvedTransactionRepository resolvedTransactionRepository;

    @Autowired
    private S3Service s3Service;

    @Autowired(required = false)
    private EcsTaskService ecsTaskService;

    @Autowired(required = false)
    private TenantConfiguration tenantConfiguration;

    @Value("${aws.s3.output-folder}")
    private String outputFolder;

    @Value("${aws.ecs.enabled:true}")
    private boolean ecsEnabled;

    // TransactionTemplate for programmatic transaction management
    // Used because Spring AOP cannot intercept method calls within the same class
    private TransactionTemplate transactionTemplate;
    private PlatformTransactionManager transactionManager;

    @Autowired
    public void initTransactionTemplate(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
        // Create TransactionTemplate with 2-minute timeout (120 seconds)
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setTimeout(120); // 2 minutes timeout
    }

    /**
     * Creates a new TransactionTemplate with a specific timeout for batch operations.
     * Each batch update will use its own transaction to prevent overall timeout.
     *
     * @param timeoutSeconds Timeout in seconds for the transaction
     * @return A new TransactionTemplate instance
     */
    private TransactionTemplate createBatchTransactionTemplate(int timeoutSeconds) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setTimeout(timeoutSeconds);
        return template;
    }

    /**
     * Divides a list into smaller batches to prevent database query timeouts.
     * This is used when updating large numbers of records with IN clauses.
     *
     * @param list The list to divide
     * @param batchSize The size of each batch
     * @param <T> The type of elements in the list
     * @return A list of batches
     */
    private <T> List<List<T>> partitionList(List<T> list, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            batches.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return batches;
    }

    /**
     * Builds the submission-specific directory path.
     * Format: SUBMISSION_ID_periodName_fiscalYear
     * Example: 174_112025 for submission 174, period "11" (novembre), fiscal year 2025
     *
     * @param submission The submission entity
     * @return The directory path for the submission, or just submission ID if obligation data is missing
     */
    private String buildSubmissionPath(Submission submission) {
        if (submission == null || submission.getId() == null) {
            return "unknown";
        }

        // Ensure obligation is loaded (it's LAZY by default)
        Obbligation obbligation = submission.getObbligation();
        if (obbligation == null) {
            log.warn("Submission {} has no obligation, using submission ID only for path", submission.getId());
            return String.valueOf(submission.getId());
        }

        // Ensure period is loaded (it's LAZY by default)
        Period period = obbligation.getPeriod();
        Integer fiscalYear = obbligation.getFiscalYear();

        if (period == null || period.getName() == null || fiscalYear == null) {
            log.warn("Submission {} has incomplete obligation data (period={}, fiscalYear={}), using submission ID only for path",
                    submission.getId(), period != null ? period.getName() : "null", fiscalYear);
            return String.valueOf(submission.getId());
        }

        // Build path: SUBMISSION_ID_periodName_fiscalYear
        // Example: 174_112025
        return String.format("%d_%s%d", submission.getId(), period.getName(), fiscalYear);
    }

    /**
     * Normalizes a path by removing duplicate slashes and ensuring proper format.
     * Removes trailing slashes from base path and ensures single slash between path components.
     *
     * @param basePath The base path (may end with /)
     * @param subPath The sub path (should not start with /)
     * @return Normalized path
     */
    private String normalizePath(String basePath, String subPath) {
        if (basePath == null) basePath = "";
        if (subPath == null) subPath = "";

        // Remove trailing slash from basePath
        basePath = basePath.replaceAll("/+$", "");
        // Remove leading slash from subPath
        subPath = subPath.replaceAll("^/+", "");

        if (basePath.isEmpty()) {
            return subPath;
        }
        if (subPath.isEmpty()) {
            return basePath;
        }

        return basePath + "/" + subPath;
    }

    @Override
    public List<Output> generateOutput(Submission submission) {
        if (submission == null || submission.getId() == null) {
            return Collections.emptyList();
        }

        log.info("Starting generateOutput for submissionId={}", submission.getId());
        List<Output> outputs = new ArrayList<>();
        int pageNumber = 0;
        boolean hasNextPage = true;

        // Process transactions in pages
        while (hasNextPage) {
            // Find transaction IDs (outside transaction to avoid long-running transaction)
            log.info("Searching for transactions with null fk_output for submissionId={}, page={}, rowsPerFile={}", 
                    submission.getId(), pageNumber, rowsPerOutputFile);
            List<Long> transactionIds = transactionRepository.findTransactionIdsBySubmissionIdAndNullOutput(
                    submission.getId(),
                    rowsPerOutputFile
            );

            log.info("Page {} for submissionId={}. Transactions found: {} (expected max: {})", 
                    pageNumber, submission.getId(), transactionIds.size(), rowsPerOutputFile);

            if (transactionIds.isEmpty()) {
                break;
            }

            // CRITICAL: Ensure we never assign more than rowsPerOutputFile transactions to a single Output
            // The query should already be limited by LIMIT clause, but this is a safety check
            // If the query returns more than expected, it's a bug - we truncate to prevent data inconsistency
            if (transactionIds.size() > rowsPerOutputFile) {
                log.error("BUG DETECTED: Query returned {} transactions, but LIMIT was set to {}. " +
                        "This should never happen - truncating to {} to prevent data inconsistency. " +
                        "Please investigate why the LIMIT clause is not being respected.", 
                        transactionIds.size(), rowsPerOutputFile, rowsPerOutputFile);
                transactionIds = transactionIds.subList(0, rowsPerOutputFile);
            }

            if (transactionIds.size() < rowsPerOutputFile) {
                hasNextPage = false;
            }

            // Save Output entity in a separate short transaction
            final int currentPageNumber = pageNumber; // Make final for lambda
            final Long submissionId = submission.getId(); // Store ID to reload inside transaction
            // Build path INSIDE transaction to ensure Hibernate session is active
            Output outputEntity = transactionTemplate.execute(status -> {
                // Reload submission inside transaction to ensure it's attached to the session
                Submission attachedSubmission = submissionRepository.findOneById(submissionId)
                        .orElseThrow(() -> new RuntimeException("Submission not found: " + submissionId));
                // Build submission path inside transaction to access lazy-loaded Period
                String submissionPath = buildSubmissionPath(attachedSubmission);
                
                Output output = new Output();
                output.setSubmission(attachedSubmission);
                output.setGeneratedAt(LocalDateTime.now().toString());
                output.setExtensionType(OUTPUT_FILE_EXTENSION);

                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                String fileName = String.format(
                        "TRXPOSADE_OUTPUT_%s_%d_part%03d.%s",
                        timestamp,
                        attachedSubmission.getId(),
                        currentPageNumber + 1,
                        OUTPUT_FILE_EXTENSION
                );

                // Build full path: outputFolder/submissionPath/fileName
                // Example: OUTPUT/174_112025/TRXPOSADE_OUTPUT_20260121143000_174_part001.txt
                String fullPath = normalizePath(s3OutputFolder, normalizePath(submissionPath, fileName));
                output.setFullPath(fullPath);
                return outputRepository.save(output);
            });

            // Update foreign keys in batches, each batch in its own transaction
            // This prevents the entire operation from timing out
            if (transactionIds.isEmpty()) {
                log.warn("No transaction IDs to update for outputId={}. Output entity created but no transactions will be linked.", 
                        outputEntity.getId());
                // Still add the output to the list - it might be processed later if transactions are found
                outputs.add(outputEntity);
                pageNumber++;
                continue; // Skip to next page
            }
            
            List<List<Long>> transactionIdBatches = partitionList(transactionIds, UPDATE_BATCH_SIZE);
            log.info("Updating {} transactions in {} batches for outputId={}. First batch size: {}", 
                    transactionIds.size(), transactionIdBatches.size(), outputEntity.getId(),
                    transactionIdBatches.isEmpty() ? 0 : transactionIdBatches.get(0).size());
            
            int batchNumber = 0;
            for (List<Long> batch : transactionIdBatches) {
                batchNumber++;
                final int currentBatchNumber = batchNumber; // Make final for lambda
                final int totalBatches = transactionIdBatches.size(); // Make final for lambda
                log.info("Starting batch {}/{} for outputId={}. Batch size: {}", 
                        currentBatchNumber, totalBatches, outputEntity.getId(), batch.size());
                // Each batch update runs in its own transaction with 10-minute timeout
                // Increased from 2 minutes because UPDATE on large TRANSACTION table can take longer
                TransactionTemplate batchTemplate = createBatchTransactionTemplate(600);
                try {
                    int updatedRows = batchTemplate.execute(status -> {
                        log.info("Executing updateOutputForeignKeyOptimized for batch {}/{} with {} transaction IDs for outputId={}", 
                                currentBatchNumber, totalBatches, batch.size(), outputEntity.getId());
                        // Use optimized method with helper table (pre-created by admin, no DDL privileges needed)
                        return transactionRepository.updateOutputForeignKeyOptimized(batch, outputEntity.getId());
                    });
                    log.info("Completed batch {}/{} for outputId={}. Updated approximately {} rows", 
                            currentBatchNumber, totalBatches, outputEntity.getId(), updatedRows);
                } catch (Exception e) {
                    log.error("Error updating batch {}/{} for outputId={}: {}", 
                            currentBatchNumber, totalBatches, outputEntity.getId(), e.getMessage(), e);
                    throw e; // Re-throw to fail the entire operation
                }
            }
            log.info("Finished updating all {} batches for outputId={}", transactionIdBatches.size(), outputEntity.getId());
            
            outputs.add(outputEntity);
            pageNumber++;
        }

        // Process resolved transactions
        int resolvedPageNumber = 0;
        boolean hasNextResolvedPage = true;

        while (hasNextResolvedPage) {
            // Find resolved transaction IDs (outside transaction)
            log.info("Searching for resolved transactions with null fk_output for submissionId={}, page={}", 
                    submission.getId(), resolvedPageNumber);
            List<Long> resolvedIds = resolvedTransactionRepository.findResolvedTransactionIdsByCurrentSubmissionIdAndNullOutput(
                    submission.getId(), rowsPerOutputFile);

            final int currentResolvedPageNumber = resolvedPageNumber; // Make final for lambda (before any usage)
            log.info("Resolved page {} for submissionId={}. Resolved transactions found: {} (expected max: {})", 
                    currentResolvedPageNumber, submission.getId(), resolvedIds.size(), rowsPerOutputFile);

            if (resolvedIds.isEmpty()) {
                break;
            }

            // CRITICAL: Ensure we never assign more than rowsPerOutputFile transactions to a single Output
            // The query should already be limited by LIMIT clause, but this is a safety check
            // If the query returns more than expected, it's a bug - we truncate to prevent data inconsistency
            if (resolvedIds.size() > rowsPerOutputFile) {
                log.error("BUG DETECTED: Query returned {} resolved transactions, but LIMIT was set to {}. " +
                        "This should never happen - truncating to {} to prevent data inconsistency. " +
                        "Please investigate why the LIMIT clause is not being respected.", 
                        resolvedIds.size(), rowsPerOutputFile, rowsPerOutputFile);
                resolvedIds = resolvedIds.subList(0, rowsPerOutputFile);
            }

            if (resolvedIds.size() < rowsPerOutputFile) {
                hasNextResolvedPage = false;
            }

            // Save Output entity in a separate short transaction
            final Long submissionIdForResolved = submission.getId(); // Store ID to reload inside transaction
            // Build path INSIDE transaction to ensure Hibernate session is active
            Output resolvedOutput = transactionTemplate.execute(status -> {
                // Reload submission inside transaction to ensure it's attached to the session
                Submission attachedSubmissionForResolved = submissionRepository.findOneById(submissionIdForResolved)
                        .orElseThrow(() -> new RuntimeException("Submission not found: " + submissionIdForResolved));
                // Build submission path inside transaction to access lazy-loaded Period
                String submissionPathForResolved = buildSubmissionPath(attachedSubmissionForResolved);
                
                Output output = new Output();
                output.setSubmission(attachedSubmissionForResolved);
                output.setGeneratedAt(LocalDateTime.now().toString());
                output.setExtensionType(OUTPUT_FILE_EXTENSION);

                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                String resolvedFileName = String.format(
                        "TRXPOSADE_OUTPUT_RESOLVED_%S_%d_resolved_part%03d.%s",
                        timestamp,
                        attachedSubmissionForResolved.getId(),
                        currentResolvedPageNumber + 1,
                        OUTPUT_FILE_EXTENSION
                );

                // Build full path: outputFolder/submissionPath/fileName
                // Example: OUTPUT/174_112025/TRXPOSADE_OUTPUT_RESOLVED_20260121143000_174_resolved_part001.txt
                String fullPath = normalizePath(s3OutputFolder, normalizePath(submissionPathForResolved, resolvedFileName));
                output.setFullPath(fullPath);
                return outputRepository.save(output);
            });

            // Update foreign keys in batches, each batch in its own transaction
            List<List<Long>> resolvedIdBatches = partitionList(resolvedIds, UPDATE_BATCH_SIZE);
            log.info("Updating {} resolved transactions in {} batches for outputId={}", 
                    resolvedIds.size(), resolvedIdBatches.size(), resolvedOutput.getId());
            
            int batchNumber = 0;
            for (List<Long> batch : resolvedIdBatches) {
                batchNumber++;
                final int currentResolvedBatchNumber = batchNumber; // Make final for lambda
                final int totalResolvedBatches = resolvedIdBatches.size(); // Make final for lambda
                log.info("Starting resolved batch {}/{} for outputId={}. Batch size: {}", 
                        currentResolvedBatchNumber, totalResolvedBatches, resolvedOutput.getId(), batch.size());
                // Each batch update runs in its own transaction with 10-minute timeout
                // Increased from 2 minutes because UPDATE on large RESOLVED_TRANSACTION table can take longer
                TransactionTemplate batchTemplate = createBatchTransactionTemplate(600);
                try {
                    int updatedRows = batchTemplate.execute(status -> {
                        log.info("Executing updateOutputForeignKeyOptimized for resolved batch {}/{} with {} transaction IDs for outputId={}", 
                                currentResolvedBatchNumber, totalResolvedBatches, batch.size(), resolvedOutput.getId());
                        // Use optimized method with helper table (pre-created by admin, no DDL privileges needed)
                        return resolvedTransactionRepository.updateOutputForeignKeyOptimized(batch, resolvedOutput.getId());
                    });
                    log.info("Completed resolved batch {}/{} for outputId={}. Updated approximately {} rows", 
                            currentResolvedBatchNumber, totalResolvedBatches, resolvedOutput.getId(), updatedRows);
                } catch (Exception e) {
                    log.error("Error updating resolved batch {}/{} for outputId={}: {}", 
                            currentResolvedBatchNumber, totalResolvedBatches, resolvedOutput.getId(), e.getMessage(), e);
                    throw e; // Re-throw to fail the entire operation
                }
            }
            log.info("Finished updating all {} resolved batches for outputId={}", resolvedIdBatches.size(), resolvedOutput.getId());
            
            outputs.add(resolvedOutput);
            resolvedPageNumber++;
        }
        
        log.info("generateOutput completed for submissionId={}. Total outputs created: {}", submission.getId(), outputs.size());
        return outputs;
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

    @Override
    public void generateSubmissionOutputTxt(Long submissionId) throws NotFoundRecordException, IOException {
        log.info("Generating TXT outputs for submissionId={}", submissionId);

        Submission submission = submissionRepository.findOneById(submissionId)
                .orElseThrow(() -> new NotFoundRecordException("Submission not found with id: " + submissionId));

        // Ensure obligation and period are loaded (they are LAZY by default)
        // Access them to trigger lazy loading within the transaction
        if (submission.getObbligation() != null) {
            submission.getObbligation().getPeriod(); // Trigger lazy load
            submission.getObbligation().getFiscalYear(); // Ensure it's loaded
        }

        List<Output> outputs = generateOutput(submission);

        // If no new outputs were created (all transactions already have fk_output),
        // retrieve existing outputs for this submission to generate missing TXT files
        if (outputs == null || outputs.isEmpty()) {
            log.info("No new outputs created for submissionId={}. Checking for existing outputs to process...", submissionId);
            outputs = outputRepository.findBySubmissionId(submissionId);
            
            if (outputs == null || outputs.isEmpty()) {
                throw new NotFoundRecordException("No outputs found for submission with id: " + submissionId);
            }
            
            log.info("Found {} existing output(s) for submissionId={}. Will generate TXT files for them.", 
                    outputs.size(), submissionId);
        }

        int batchSize = FETCH_BATCH_SIZE; // Use optimized batch size for fetching
        int filesGenerated = 0;

        // Generate each TXT file and upload directly to S3
        // Each file is ~300MB (1M rows, configurable via OUTPUT_ROWS_PER_FILE)
        for (Output output : outputs) {
            log.info("Processing outputId={} for submissionId={}", output.getId(), submissionId);

            // Check if file already exists on S3 to avoid regenerating it
            String s3Key = output.getFullPath();
            if (s3Service.fileExists(s3Key)) {
                log.info("File already exists on S3: {} for outputId={}, submissionId={}. Skipping generation.", 
                        s3Key, output.getId(), submissionId);
                filesGenerated++;
                continue; // Skip to next output
            }

            boolean isResolvedTransaction = output.getFullPath() != null && output.getFullPath().toLowerCase().contains("resolved");

            String codiceFiscale = resolveOutputCodiceFiscale();
            String header = OutputFileFormatter.createHeader(codiceFiscale);
            String footer = OutputFileFormatter.createFooter(codiceFiscale);

            // Generate file content in memory (each file is ~300MB, safe for memory)
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 OutputStreamWriter writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
                 BufferedWriter bw = new BufferedWriter(writer)) {

                // Write header
                bw.write(header);

                Long lastId = null; // Cursor-based pagination: use last ID instead of offset
                int totalRecordsFetched = 0;
                int batchCount = 0;
                // IMPORTANT: Recupera TUTTE le transazioni con questo fk_output, senza limiti
                // Il raggruppamento è per fk_output: tutte le transazioni con lo stesso fk_output vanno nello stesso file
                // Se per qualche motivo ci sono più di 1M transazioni con lo stesso fk_output, vanno comunque tutte nello stesso file
                log.info("Starting to fetch ALL transactions for outputId={}. Will fetch in batches of {} (using cursor-based pagination). " +
                        "All transactions with fk_output={} will be included in this file.", 
                        output.getId(), batchSize, output.getId());
                
                while (true) {
                    String bodyChunk;
                    batchCount++;

                    if (isResolvedTransaction) {
                        log.debug("Fetching resolved transaction batch {} for outputId={}, lastId={}, limit={}", 
                                batchCount, output.getId(), lastId, batchSize);
                        List<ResolvedTransaction> batch = resolvedTransactionRepository
                                .findByOutputIdWithMerchantsBulkFetched(output.getId(), lastId, batchSize);

                        if (batch.isEmpty()) {
                            log.info("No more resolved transactions to fetch for outputId={}. Total fetched: {}", 
                                    output.getId(), totalRecordsFetched);
                            break;
                        }

                        totalRecordsFetched += batch.size();
                        if (batchCount % 10 == 0 || batch.size() < batchSize) {
                            log.info("Fetched {} resolved transactions so far for outputId={} (batch {})", 
                                    totalRecordsFetched, output.getId(), batchCount);
                        }

                        // Use StringBuilder for better performance with large batches
                        // parallelStream overhead not worth it for batches of 5000
                        StringBuilder sb = new StringBuilder(batch.size() * 350); // Pre-allocate ~350 chars per record
                        for (ResolvedTransaction rt : batch) {
                            sb.append(OutputFileFormatter.toOutputFileString(rt, codiceFiscale));
                        }
                        bodyChunk = sb.toString();

                        // Update cursor: use the last ID from this batch for next iteration
                        lastId = batch.get(batch.size() - 1).getId();
                        bw.write(bodyChunk);

                        if (batch.size() < batchSize) break;
                    } else {
                        log.debug("Fetching transaction batch {} for outputId={}, lastId={}, limit={}", 
                                batchCount, output.getId(), lastId, batchSize);
                        List<Transaction> batch = transactionRepository
                                .findByOutputIdWithMerchantsBulkFetched(output.getId(), lastId, batchSize);

                        if (batch.isEmpty()) {
                            log.info("No more transactions to fetch for outputId={}. Total fetched: {}", 
                                    output.getId(), totalRecordsFetched);
                            break;
                        }

                        totalRecordsFetched += batch.size();
                        if (batchCount % 10 == 0 || batch.size() < batchSize) {
                            log.info("Fetched {} transactions so far for outputId={} (batch {})", 
                                    totalRecordsFetched, output.getId(), batchCount);
                        }

                        // Use StringBuilder for better performance with large batches
                        // parallelStream overhead not worth it for batches of 5000
                        StringBuilder sb = new StringBuilder(batch.size() * 350); // Pre-allocate ~350 chars per record
                        for (Transaction t : batch) {
                            sb.append(OutputFileFormatter.toOutputFileString(t, codiceFiscale));
                        }
                        bodyChunk = sb.toString();

                        // Update cursor: use the last ID from this batch for next iteration
                        lastId = batch.get(batch.size() - 1).getId();
                        bw.write(bodyChunk);

                        if (batch.size() < batchSize) break;
                    }
                }
                
                log.info("Finished fetching all transactions for outputId={}. Total records: {}, total batches: {}", 
                        output.getId(), totalRecordsFetched, batchCount);

                // Write footer
                bw.write(footer);
                bw.flush();

                // Upload file to S3
                // Use the fullPath directly as it already contains the complete path structure
                // Format: outputFolder/submissionPath/fileName
                // Example: OUTPUT/174_112025/TRXPOSADE_OUTPUT_20260121143000_174_part001.txt
                // Note: s3Key was already set above, but we use it here for clarity

                log.info("Uploading file to S3: {} for outputId={}, submissionId={}. File size: {} bytes", 
                        s3Key, output.getId(), submissionId, baos.size());
                try (ByteArrayInputStream fileInputStream = new ByteArrayInputStream(baos.toByteArray())) {
                    s3Service.uploadFile(s3Key, fileInputStream, "text/plain");
                    log.info("File successfully uploaded to S3: {} for outputId={}, submissionId={}", s3Key, output.getId(), submissionId);
                    filesGenerated++;
                } catch (Exception e) {
                    log.error("Failed to upload file to S3: {} for outputId={}, submissionId={}. Error: {}", 
                            s3Key, output.getId(), submissionId, e.getMessage(), e);
                    // Re-throw to fail the entire operation - we don't want partial success
                    throw new IOException("Failed to upload file to S3: " + s3Key, e);
                }
            }
        }

        log.info("Output generation completed successfully for submissionId={}, {} file(s) generated and uploaded to S3", 
                submissionId, filesGenerated);
    }

    @Override
    public void generateSubmissionOutputTxtAsync(Long submissionId) {
        if (ecsEnabled && ecsTaskService != null) {
            // Launch ECS task for output generation
            try {
                log.info("Launching ECS task for output generation - submissionId: {}", submissionId);
                String taskArn = ecsTaskService.launchOutputGenerationTask(submissionId);
                log.info("ECS task launched successfully - taskArn: {}, submissionId: {}", taskArn, submissionId);
            } catch (Exception e) {
                log.error("Failed to launch ECS task for output generation - submissionId: {}, error: {}", 
                        submissionId, e.getMessage(), e);
                // Fallback: try to generate synchronously (not recommended for large datasets)
                log.warn("Falling back to synchronous generation (not recommended for large datasets)");
                try {
                    generateSubmissionOutputTxt(submissionId);
                } catch (NotFoundRecordException | IOException ex) {
                    log.error("Error generating output synchronously (fallback) for submissionId={}: {}", 
                            submissionId, ex.getMessage(), ex);
                }
            }
        } else {
            // ECS not enabled or not available - generate synchronously
            log.warn("ECS not enabled or EcsTaskService not available. Generating output synchronously (not recommended for large datasets)");
            try {
                generateSubmissionOutputTxt(submissionId);
            } catch (NotFoundRecordException | IOException e) {
                log.error("Error generating output synchronously for submissionId={}: {}", submissionId, e.getMessage(), e);
            }
        }
    }

}