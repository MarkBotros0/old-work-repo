package it.deloitte.postrxade.repository;

import it.deloitte.postrxade.entity.Merchant;
import it.deloitte.postrxade.entity.Transaction;
import it.deloitte.postrxade.records.StagingResult;

import java.util.List;

/**
 * Repository for staging table operations.
 * Used for high-performance ETL ingestion with set-based operations.
 */
public interface StagingRepository {

    /**
     * Clear all staging data for a submission.
     */
    void clearStaging(Long submissionId);

    /**
     * Clear only transaction staging data for a submission.
     * Used for incremental cleanup between multiple transaction files.
     */
    void clearTransactionStaging(Long submissionId);

    /**
     * Bulk load merchants into staging table.
     * No duplicate checks during load - handled later by set-based operations.
     *
     * @param merchants list of merchants to load
     * @param ingestionId the ingestion ID
     * @param submissionId the submission ID
     */
    void bulkLoadMerchantsToStaging(List<Merchant> merchants, Long ingestionId, Long submissionId);

    /**
     * Bulk load transactions into staging table.
     * No duplicate checks during load - handled later by set-based operations.
     *
     * @param transactions list of transactions to load
     * @param ingestionId the ingestion ID
     * @param submissionId the submission ID
     */
    void bulkLoadTransactionsToStaging(List<Transaction> transactions, Long ingestionId, Long submissionId);

    /**
     * Process merchants from staging to final table using set-based operations.
     * Identifies duplicates and inserts new records.
     *
     * @param submissionId the submission ID
     * @return result with counts of inserted, duplicates, and errors
     */
    StagingResult processMerchantsFromStaging(Long submissionId);

    /**
     * Process transactions from staging to final table using set-based operations.
     * Identifies duplicates, missing merchants, and inserts new records.
     *
     * @param submissionId the submission ID
     * @return result with counts of inserted, duplicates, missing merchants, and errors
     */
    StagingResult processTransactionsFromStaging(Long submissionId);

    /**
     * Get raw rows of merchants that were marked as duplicates.
     *
     * @param submissionId the submission ID
     * @return list of raw row strings
     */
    List<String> getDuplicateMerchantRawRows(Long submissionId);

    /**
     * Get raw rows of transactions that were marked as duplicates.
     *
     * @param submissionId the submission ID
     * @return list of raw row strings
     */
    List<String> getDuplicateTransactionRawRows(Long submissionId);

    /**
     * Get raw rows of transactions with missing merchants.
     *
     * @param submissionId the submission ID
     * @return list of raw row strings
     */
    List<String> getMissingMerchantTransactionRawRows(Long submissionId);

    /**
     * Get detailed info for duplicate merchants (for ErrorRecord creation).
     * Returns: raw_row, error_message
     *
     * @param submissionId the submission ID
     * @return list of Object[] with [raw_row, error_message]
     */
    List<Object[]> getDuplicateMerchantDetails(Long submissionId);

    /**
     * Get detailed info for duplicate transactions (for ErrorRecord creation).
     * Returns: raw_row, error_message
     *
     * @param submissionId the submission ID
     * @return list of Object[] with [raw_row, error_message]
     */
    List<Object[]> getDuplicateTransactionDetails(Long submissionId);

    /**
     * Get detailed info for transactions with missing merchants (for ErrorRecord creation).
     * Returns: raw_row, error_message
     *
     * @param submissionId the submission ID
     * @return list of Object[] with [raw_row, error_message]
     */
    List<Object[]> getMissingMerchantTransactionDetails(Long submissionId);

    /**
     * Get detailed info for missing merchant transactions in a chunk (for ErrorRecord creation).
     * Returns: raw_row, error_message, pk_stg_transaction
     * This method does NOT update process_status - it only SELECTs records.
     *
     * @param submissionId the submission ID
     * @param minPk minimum pk_stg_transaction (inclusive)
     * @param maxPk maximum pk_stg_transaction (exclusive)
     * @return list of Object[] with [raw_row, error_message, pk_stg_transaction]
     */
    List<Object[]> getMissingMerchantTransactionDetailsChunk(Long submissionId, Long minPk, Long maxPk);

    /**
     * Get detailed info for duplicate transactions that already exist in TRANSACTION (for ErrorRecord creation).
     * Returns: raw_row, error_message, pk_stg_transaction
     * This method does NOT update process_status - it only SELECTs records.
     *
     * @param submissionId the submission ID
     * @param minPk minimum pk_stg_transaction (inclusive)
     * @param maxPk maximum pk_stg_transaction (exclusive)
     * @return list of Object[] with [raw_row, error_message, pk_stg_transaction]
     */
    List<Object[]> getDuplicateTransactionDetailsChunk(Long submissionId, Long minPk, Long maxPk);

    /**
     * Get detailed info for duplicate transactions within the same batch/file (for ErrorRecord creation).
     * Returns: raw_row, error_message, pk_stg_transaction
     * This method does NOT update process_status - it only SELECTs records.
     *
     * @param submissionId the submission ID
     * @param minPk minimum pk_stg_transaction (inclusive)
     * @param maxPk maximum pk_stg_transaction (exclusive)
     * @return list of Object[] with [raw_row, error_message, pk_stg_transaction]
     */
    List<Object[]> getBatchDuplicateTransactionDetailsChunk(Long submissionId, Long minPk, Long maxPk);

    /**
     * Get all duplicate transactions within the same batch/file in one query (for ErrorRecord creation).
     * Prefer this over chunked version to avoid running the heavy GROUP BY once per chunk.
     * Returns: raw_row, error_message, pk_stg_transaction
     *
     * @param submissionId the submission ID
     * @return list of Object[] with [raw_row, error_message, pk_stg_transaction]
     */
    List<Object[]> getBatchDuplicateTransactionDetailsAll(Long submissionId);

    /**
     * Count pending records (process_status IS NULL) in staging tables for a submission.
     * Used to determine if ingestion can be resumed from staging.
     *
     * @param submissionId the submission ID
     * @return array with [pendingTransactionsCount, pendingMerchantsCount]
     */
    long[] countPendingRecords(Long submissionId);

    /**
     * Reset process_status for records that were inserted (status = 1) but then deleted by cleanup.
     * This allows re-processing these records when resuming ingestion.
     * Only resets status = 1 to NULL (does not touch duplicates or errors).
     *
     * @param submissionId the submission ID
     * @return array with [resetTransactionsCount, resetMerchantsCount]
     */
    long[] resetInsertedRecordsToPending(Long submissionId);

    /**
     * Get the minimum pending pk_stg_transaction for chunking.
     * Used for processing records in chunks without updating process_status.
     *
     * @param submissionId the submission ID
     * @param minPkExclude optional minimum PK to exclude (to skip already-processed ranges)
     * @return minimum pk_stg_transaction or null if no more records
     */
    Long getMinPendingPkForChunk(Long submissionId, Long minPkExclude);
}
