package it.deloitte.postrxade.repository;

import it.deloitte.postrxade.entity.ResolvedTransaction;
import it.deloitte.postrxade.entity.Submission;

import java.util.List;
import java.util.Map;

public interface ResolvedTransactionRepositoryCustom {
    void bulkInsert(List<ResolvedTransaction> transactions, Submission currentSubmission, Submission oldSubmission);
    Map<String, Integer> checkExisting(List<ResolvedTransaction> transactions);
    /**
     * Cursor-based pagination: uses lastId instead of offset for constant-time performance.
     * @param outputId The output ID to filter by
     * @param lastId The last resolved transaction ID from previous batch (null for first batch)
     * @param limit Maximum number of records to fetch
     * @return List of resolved transactions with merchants
     */
    List<ResolvedTransaction> findByOutputIdWithMerchantsBulkFetched(Long outputId, Long lastId, int limit);
    List<Long> findResolvedTransactionIdsByCurrentSubmissionIdAndNullOutput(Long submissionId, int rowsPerPage);
    /**
     * Count resolved transactions with fk_current_submission = X AND fk_output IS NULL
     * Used by lazy output generation approach
     */
    Long countByCurrentSubmissionIdAndNullOutput(Long submissionId);
    /**
     * Cursor-based pagination: fetch resolved transactions with fk_current_submission = X AND fk_output IS NULL
     * Used by lazy output generation approach - reads directly without updating fk_output first
     * @param submissionId The current submission ID to filter by
     * @param lastId The last resolved transaction ID from previous batch (null for first batch)
     * @param limit Maximum number of records to fetch
     * @return List of resolved transactions with merchants
     */
    List<ResolvedTransaction> findBySubmissionIdAndNullOutputWithMerchantsBulkFetched(Long submissionId, Long lastId, int limit);
    /**
     * Optimized bulk update using temporary table with JOIN instead of IN clause.
     * Much faster than IN clause for large batches (1000+ records).
     */
    int updateOutputForeignKeyOptimized(List<Long> transactionIds, Long outputId);
}

