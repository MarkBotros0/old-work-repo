package it.deloitte.postrxade.repository;

import it.deloitte.postrxade.entity.ResolvedTransaction;
import it.deloitte.postrxade.entity.Submission;
import it.deloitte.postrxade.entity.Transaction;

import java.util.List;
import java.util.Map;

public interface TransactionRepositoryCustom {
    void bulkInsert(List<Transaction> transactions, Submission submission);
    Map<String, Integer> checkExisting(List<Transaction> transactions);
    Map<String, Integer> checkExistingWithResolved(List<ResolvedTransaction> resolvedTransactions);
    /**
     * Cursor-based pagination: uses lastId instead of offset for constant-time performance.
     * @param outputId The output ID to filter by
     * @param lastId The last transaction ID from previous batch (null for first batch)
     * @param limit Maximum number of records to fetch
     * @return List of transactions with merchants
     */
    List<Transaction> findByOutputIdWithMerchantsBulkFetched(Long outputId, Long lastId, int limit);
    List<Long> findTransactionIdsBySubmissionIdAndNullOutput(Long submissionId, int rowsPerPage);
    /**
     * Count transactions with fk_submission = X AND fk_output IS NULL
     * Used by lazy output generation approach
     */
    Long countBySubmissionIdAndNullOutput(Long submissionId);
    /**
     * Cursor-based pagination: fetch transactions with fk_submission = X AND fk_output IS NULL
     * Used by lazy output generation approach - reads directly without updating fk_output first
     * @param submissionId The submission ID to filter by
     * @param lastId The last transaction ID from previous batch (null for first batch)
     * @param limit Maximum number of records to fetch
     * @return List of transactions with merchants
     */
    List<Transaction> findBySubmissionIdAndNullOutputWithMerchantsBulkFetched(Long submissionId, Long lastId, int limit);
    /**
     * Optimized bulk update using temporary table with JOIN instead of IN clause.
     * Much faster than IN clause for large batches (1000+ records).
     */
    int updateOutputForeignKeyOptimized(List<Long> transactionIds, Long outputId);
}

