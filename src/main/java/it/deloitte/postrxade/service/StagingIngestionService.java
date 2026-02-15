package it.deloitte.postrxade.service;

import it.deloitte.postrxade.entity.*;
import it.deloitte.postrxade.parser.transaction.RemoteFile;
import it.deloitte.postrxade.records.StagingResult;

import java.io.IOException;
import java.util.List;

/**
 * Service for high-performance file ingestion using staging tables.
 * 
 * <h2>Architecture Overview</h2>
 * <p>
 * Instead of processing records row-by-row with individual duplicate checks,
 * this service uses a 3-phase ETL approach:
 * </p>
 * <ol>
 *   <li><b>Load Phase:</b> Bulk insert all parsed records into staging tables (no validation)</li>
 *   <li><b>Transform Phase:</b> Set-based operations to identify duplicates, missing references, and errors</li>
 *   <li><b>Extract Phase:</b> Single INSERT INTO ... SELECT to move valid records to final tables</li>
 * </ol>
 * 
 * <h2>Performance Benefits</h2>
 * <ul>
 *   <li>Reduces N database roundtrips to 2-3 set-based operations</li>
 *   <li>Leverages database indexes for duplicate detection</li>
 *   <li>Minimizes connection usage and lock contention</li>
 *   <li>Scales efficiently for millions of records</li>
 * </ul>
 */
public interface StagingIngestionService {

    /**
     * Process a merchant file using staging tables.
     *
     * @param file the remote file to process
     * @param ingestion the ingestion entity
     * @param submission the submission entity
     * @return result with counts of processed, inserted, duplicates, and errors
     * @throws IOException if file reading fails
     */
    StagingResult processMerchantFile(RemoteFile file, Ingestion ingestion, Submission submission) throws IOException;

    /**
     * Process a transaction file using staging tables.
     *
     * @param file the remote file to process
     * @param ingestion the ingestion entity
     * @param submission the submission entity
     * @param obbligation the obligation for date validation
     * @return result with counts of processed, inserted, duplicates, missing merchants, and errors
     * @throws IOException if file reading fails
     */
    StagingResult processTransactionFile(RemoteFile file, Ingestion ingestion, Submission submission, Obbligation obbligation) throws IOException;

    /**
     * Process merchant records directly (already parsed and validated).
     *
     * @param merchants list of parsed merchant entities
     * @param errorRecords list of error records from validation phase
     * @param ingestion the ingestion entity
     * @param submission the submission entity
     * @return result with counts
     */
    StagingResult processMerchants(List<Merchant> merchants, List<ErrorRecord> errorRecords, Ingestion ingestion, Submission submission);

    /**
     * Process transaction records directly (already parsed and validated).
     *
     * @param transactions list of parsed transaction entities
     * @param errorRecords list of error records from validation phase
     * @param ingestion the ingestion entity
     * @param submission the submission entity
     * @return result with counts
     */
    StagingResult processTransactions(List<Transaction> transactions, List<ErrorRecord> errorRecords, Ingestion ingestion, Submission submission);

    /**
     * Clean up staging tables for a submission.
     *
     * @param submissionId the submission ID to clean up
     */
    void cleanupStaging(Long submissionId);

    /**
     * Clean up only transaction staging table for a submission.
     * Used for incremental cleanup between multiple transaction files.
     *
     * @param submissionId the submission ID to clean up
     */
    void cleanupTransactionStaging(Long submissionId);

    /**
     * Create ErrorRecords for duplicate merchants found in staging (DB duplicates).
     * Used when resuming ingestion from staging.
     *
     * @param ingestion the ingestion entity
     * @param submission the submission entity
     * @return number of error records created
     */
    int createErrorRecordsForDuplicateMerchants(Ingestion ingestion, Submission submission);

    /**
     * Create ErrorRecords for duplicate transactions found in staging (DB duplicates).
     * Used when resuming ingestion from staging.
     *
     * @param ingestion the ingestion entity
     * @param submission the submission entity
     * @return number of error records created
     */
    int createErrorRecordsForDuplicateTransactions(Ingestion ingestion, Submission submission);

    /**
     * Create ErrorRecords for transactions with missing merchants.
     * Used when resuming ingestion from staging.
     *
     * @param ingestion the ingestion entity
     * @param submission the submission entity
     * @return number of error records created
     */
    int createErrorRecordsForMissingMerchantTransactions(Ingestion ingestion, Submission submission);

    /**
     * Create ErrorRecords for missing merchant transactions in chunks (for chunk-based processing).
     * Processes records in chunks without updating process_status on STG tables.
     *
     * @param ingestion the ingestion entity
     * @param submission the submission entity
     * @return number of error records created
     */
    int createErrorRecordsForMissingMerchantTransactionsChunk(Ingestion ingestion, Submission submission);

    /**
     * Create ErrorRecords for duplicate transactions (already in TRANSACTION) in chunks (for chunk-based processing).
     * Processes records in chunks without updating process_status on STG tables.
     *
     * @param ingestion the ingestion entity
     * @param submission the submission entity
     * @return number of error records created
     */
    int createErrorRecordsForDuplicateTransactionsChunk(Ingestion ingestion, Submission submission);

    /**
     * Create ErrorRecords for duplicate transactions within the same batch/file in chunks (for chunk-based processing).
     * Processes records in chunks without updating process_status on STG tables.
     *
     * @param ingestion the ingestion entity
     * @param submission the submission entity
     * @return number of error records created
     */
    int createErrorRecordsForBatchDuplicateTransactionsChunk(Ingestion ingestion, Submission submission);
}
