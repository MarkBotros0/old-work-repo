package it.deloitte.postrxade.repository;

import it.deloitte.postrxade.entity.Transaction;
import it.deloitte.postrxade.records.TransactionDateCount;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long>, TransactionRepositoryCustom {
    long countByIngestionId(Long ingestionId);

    @Modifying
    @Query(value = "UPDATE TRANSACTION SET fk_output = :outputId WHERE pk_transaction IN (:ids)", nativeQuery = true)
    void updateOutputForeignKey(@Param("ids") List<Long> transactionIds, @Param("outputId") Long outputId);

    @Query("""
              SELECT COUNT(t)
              FROM Transaction t
              JOIN t.ingestion i
              JOIN i.submission s
              JOIN s.obbligation o
              WHERE o.id = :obligationId
            """)
    long countTransactionsByObligation(@Param("obligationId") Long obligationId);

    @Query("""
              SELECT COUNT(t)
              FROM Transaction t
              WHERE t.submission.id = :submissionId
            """)
    long countTransactionsBySubmission(@Param("submissionId") Long submissionId);

    @Query("""
             SELECT COUNT(t)
             FROM Transaction t
             WHERE t.submission.id IN :submissionIds
            """)
    Long countTransactionsBySubmissionIds(@Param("submissionIds") List<Long> submissionIds);

    @Query("""
             SELECT COUNT(t)
             FROM Transaction t
             WHERE t.submission.id IN :submissionIds
             AND t.tipoPag = :tipoPag
            """)
    Long countTransactionsByObligationAndTipoPag(
            @Param("submissionIds") List<Long> submissionIds,
            @Param("tipoPag") String tipoPag);

    /**
     * Counts transactions grouped by operation date for the given submissions.
     * Uses direct submission FK for better index usage (fk_submission, dt_ope).
     */
    @Query("""
             SELECT new it.deloitte.postrxade.records.TransactionDateCount(t.dtOpe, COUNT(t))
             FROM Transaction t
             WHERE t.submission.id IN :submissionIds
             GROUP BY t.dtOpe
             ORDER BY t.dtOpe
            """)
    List<TransactionDateCount> countTransactionsGroupedByDate(
            @Param("submissionIds") List<Long> submissionIds
    );

    /**
     * Single query: counts per payment type (tipo_pag) for the given submissions.
     * Prefer this over multiple countTransactionsByObligationAndTipoPag calls to avoid N round-trips.
     */
    @Query("""
             SELECT t.tipoPag, COUNT(t)
             FROM Transaction t
             WHERE t.submission.id IN :submissionIds
             GROUP BY t.tipoPag
            """)
    List<Object[]> countTransactionsGroupedByTipoPag(@Param("submissionIds") List<Long> submissionIds);

    @Modifying
    @Transactional
    @Query("""
            DELETE FROM Transaction t
            WHERE t.submission.id = :submissionId
            """)
    int deleteBySubmissionId(@Param("submissionId") Long submissionId);
}
