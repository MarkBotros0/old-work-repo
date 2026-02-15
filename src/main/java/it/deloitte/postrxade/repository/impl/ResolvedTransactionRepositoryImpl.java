package it.deloitte.postrxade.repository.impl;

import it.deloitte.postrxade.entity.Ingestion;
import it.deloitte.postrxade.entity.Merchant;
import it.deloitte.postrxade.entity.Output;
import it.deloitte.postrxade.entity.ResolvedTransaction;
import it.deloitte.postrxade.entity.Submission;

import it.deloitte.postrxade.repository.ResolvedTransactionRepositoryCustom;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

@Slf4j
@Repository
@Transactional(timeout = 120) // 2 minutes timeout to prevent lock wait timeout
public class ResolvedTransactionRepositoryImpl implements ResolvedTransactionRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void bulkInsert(List<ResolvedTransaction> transactions, Submission currentSubmission, Submission oldSubmission) {
        if (transactions == null || transactions.isEmpty()) {
            return;
        }

        String sql = buildInsertSql(transactions.size());
        Query query = entityManager.createNativeQuery(sql);

        for (int i = 0; i < transactions.size(); i++) {
            setParams(query, i, transactions.get(i), currentSubmission, oldSubmission);
        }

        query.executeUpdate();
    }

    private String buildInsertSql(int batchSize) {
        StringJoiner values = new StringJoiner(", ");
        for (int i = 0; i < batchSize; i++) {
            values.add("( :fk_ingestion_" + i
                    + ", :fk_submission_" + i
                    + ", :fk_current_submission_" + i
                    + ", :tp_rec_" + i
                    + ", :id_intermediario_" + i
                    + ", :id_esercente_" + i
                    + ", :chiave_banca_" + i
                    + ", :id_pos_" + i
                    + ", :tipo_ope_" + i
                    + ", :dt_ope_" + i
                    + ", :divisa_ope_" + i
                    + ", :tipo_pag_" + i
                    + ", :imp_ope_" + i
                    + ", :tot_ope_" + i
                    + ", :fk_output_" + i
                    + ", CURRENT_TIMESTAMP)");
        }

        return String.format(
                "INSERT INTO RESOLVED_TRANSACTION (fk_ingestion, fk_submission, fk_current_submission, tp_rec, id_intermediario, id_esercente, "
                        + "chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag, "
                        + "imp_ope, tot_ope, fk_output, created_at) VALUES %s",
                values);
    }

    private void setParams(Query query, int index, ResolvedTransaction transaction, Submission currentSubmission, Submission oldSubmission) {
        query.setParameter("fk_ingestion_" + index,
                transaction.getIngestion() != null ? transaction.getIngestion().getId() : null);
        query.setParameter("fk_submission_" + index, oldSubmission.getId());
        query.setParameter("fk_current_submission_" + index, currentSubmission.getId());
        query.setParameter("tp_rec_" + index, transaction.getTpRec());
        query.setParameter("id_intermediario_" + index, transaction.getIdIntermediario());
        query.setParameter("id_esercente_" + index, transaction.getIdEsercente());
        query.setParameter("chiave_banca_" + index, transaction.getChiaveBanca());
        query.setParameter("id_pos_" + index, transaction.getIdPos());
        query.setParameter("tipo_ope_" + index, transaction.getTipoOpe());
        query.setParameter("dt_ope_" + index, transaction.getDtOpe());
        query.setParameter("divisa_ope_" + index, transaction.getDivisaOpe());
        query.setParameter("tipo_pag_" + index, transaction.getTipoPag());
        query.setParameter("imp_ope_" + index, transaction.getImpOpe());
        query.setParameter("tot_ope_" + index, transaction.getTotOpe());
        query.setParameter("fk_output_" + index,
                transaction.getOutput() != null ? transaction.getOutput().getId() : null);
    }

    @Override
    public Map<String, Integer> checkExisting(List<ResolvedTransaction> transactions) {
        String valuesBlock = buildValuesBlock(transactions);

        String sql = """
                    WITH TRANSACTION_INPUT (id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag) AS (
                        %s
                    )
                    SELECT 
                        ti.id_esercente,
                        ti.chiave_banca,
                        ti.id_pos,
                        ti.tipo_ope,
                        ti.dt_ope,
                        ti.divisa_ope,
                        ti.tipo_pag,
                        CASE WHEN t.id_esercente IS NOT NULL THEN 1 ELSE 0 END AS ExistsFlag
                    FROM TRANSACTION_INPUT ti
                    LEFT JOIN TRANSACTION t ON t.id_esercente = ti.id_esercente 
                        AND t.chiave_banca = ti.chiave_banca
                        AND t.id_pos = ti.id_pos
                        AND t.tipo_ope = ti.tipo_ope
                        AND t.dt_ope = ti.dt_ope
                        AND t.divisa_ope = ti.divisa_ope
                        AND t.tipo_pag = ti.tipo_pag
                """.formatted(valuesBlock);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager
                .createNativeQuery(sql)
                .getResultList();

        Map<String, Integer> result = new HashMap<>();
        for (Object[] row : rows) {
            // Use String.valueOf to be null-safe and handle JDBC drivers returning non-String types
            // (e.g. dt_ope as java.sql.Date/Timestamp).
            String idEsercente = String.valueOf(row[0]);
            String chiaveBanca = String.valueOf(row[1]);
            String idPos = String.valueOf(row[2]);
            String tipoOpe = String.valueOf(row[3]);
            String dtOpe = String.valueOf(row[4]);
            String divisaOpe = String.valueOf(row[5]);
            String tipoPag = String.valueOf(row[6]);
            Number existsNumber = row[7] instanceof Number ? (Number) row[7] : 0;
            Integer existsFlag = existsNumber != null ? existsNumber.intValue() : 0;
            String key = idEsercente
                    + "_" + chiaveBanca
                    + "_" + idPos
                    + "_" + tipoOpe
                    + "_" + dtOpe
                    + "_" + divisaOpe
                    + "_" + tipoPag;
            result.put(key, existsFlag);
        }
        
        // Clear persistence context to free memory (read-only query, but Hibernate may cache metadata)
        entityManager.clear();
        return result;
    }

    private String buildValuesBlock(List<ResolvedTransaction> transactions) {
        return transactions.stream()
                .map(t -> "SELECT '"
                        + escape(t.getIdEsercente()) + "' AS id_esercente, '"
                        + escape(t.getChiaveBanca()) + "' AS chiave_banca, '"
                        + escape(t.getIdPos()) + "' AS id_pos, '"
                        + escape(t.getTipoOpe()) + "' AS tipo_ope, '"
                        + escape(t.getDtOpe()) + "' AS dt_ope, '"
                        + escape(t.getDivisaOpe()) + "' AS divisa_ope, '"
                        + escape(t.getTipoPag()) + "' AS tipo_pag")
                .collect(Collectors.joining(" UNION ALL "));
    }

    private String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("'", "''");
    }

    @Override
    public List<Long> findResolvedTransactionIdsByCurrentSubmissionIdAndNullOutput(Long submissionId, int rowsPerPage) {
        String nativeSql = "SELECT rt.pk_resolved_transaction " +
                "FROM RESOLVED_TRANSACTION rt " +
                "WHERE rt.fk_current_submission = :submissionId " +
                "AND rt.fk_output IS NULL " +
                "ORDER BY rt.pk_resolved_transaction ASC " +
                "LIMIT :limit";

        Query query = entityManager.createNativeQuery(nativeSql);
        query.setParameter("submissionId", submissionId);
        query.setParameter("limit", rowsPerPage);

        @SuppressWarnings("unchecked")
        List<Object> results = query.getResultList();

        return results.stream()
                .filter(obj -> obj instanceof Number) // Filter out nulls and non-Number types
                .map(obj -> ((Number) obj).longValue())
                .collect(Collectors.toList());
    }

    @Override
    public Long countByCurrentSubmissionIdAndNullOutput(Long submissionId) {
        String nativeSql = "SELECT COUNT(*) " +
                "FROM RESOLVED_TRANSACTION rt " +
                "WHERE rt.fk_current_submission = :submissionId " +
                "AND rt.fk_output IS NULL";

        Query query = entityManager.createNativeQuery(nativeSql);
        query.setParameter("submissionId", submissionId);

        Object result = query.getSingleResult();
        return result != null ? ((Number) result).longValue() : 0L;
    }

    @Override
    public List<ResolvedTransaction> findBySubmissionIdAndNullOutputWithMerchantsBulkFetched(Long submissionId, Long lastId, int limit) {
        // Similar to findByOutputIdWithMerchantsBulkFetched but filters by fk_current_submission AND fk_output IS NULL
        // Used by lazy output generation approach
        String nativeSql;
        if (lastId == null) {
            nativeSql = """
                SELECT 
                    rt.pk_resolved_transaction,
                    rt.fk_ingestion,
                    rt.fk_submission,
                    rt.fk_current_submission,
                    rt.tp_rec,
                    rt.id_intermediario,
                    rt.id_esercente,
                    rt.chiave_banca,
                    rt.id_pos,
                    rt.tipo_ope,
                    rt.dt_ope,
                    rt.divisa_ope,
                    rt.tipo_pag,
                    rt.imp_ope,
                    rt.tot_ope,
                    rt.fk_output,
                    rt.created_at,
                    m.pk_merchant,
                    m.fk_ingestion as m_fk_ingestion,
                    m.fk_submission as m_fk_submission,
                    m.tp_rec as m_tp_rec,
                    m.id_intermediario as m_id_intermediario,
                    m.id_esercente as m_id_esercente,
                    m.cod_fiscale,
                    m.partita_iva,
                    m.id_salmov,
                    m.created_at as m_created_at
                FROM RESOLVED_TRANSACTION rt
                LEFT JOIN MERCHANT m ON rt.id_esercente = m.id_esercente 
                    AND rt.id_intermediario = m.id_intermediario
                    AND rt.fk_current_submission = m.fk_submission
                WHERE rt.fk_current_submission = :submissionId
                    AND rt.fk_output IS NULL
                ORDER BY rt.pk_resolved_transaction ASC
                LIMIT :limit
                """;
        } else {
            nativeSql = """
                SELECT 
                    rt.pk_resolved_transaction,
                    rt.fk_ingestion,
                    rt.fk_submission,
                    rt.fk_current_submission,
                    rt.tp_rec,
                    rt.id_intermediario,
                    rt.id_esercente,
                    rt.chiave_banca,
                    rt.id_pos,
                    rt.tipo_ope,
                    rt.dt_ope,
                    rt.divisa_ope,
                    rt.tipo_pag,
                    rt.imp_ope,
                    rt.tot_ope,
                    rt.fk_output,
                    rt.created_at,
                    m.pk_merchant,
                    m.fk_ingestion as m_fk_ingestion,
                    m.fk_submission as m_fk_submission,
                    m.tp_rec as m_tp_rec,
                    m.id_intermediario as m_id_intermediario,
                    m.id_esercente as m_id_esercente,
                    m.cod_fiscale,
                    m.partita_iva,
                    m.id_salmov,
                    m.created_at as m_created_at
                FROM RESOLVED_TRANSACTION rt
                LEFT JOIN MERCHANT m ON rt.id_esercente = m.id_esercente 
                    AND rt.id_intermediario = m.id_intermediario
                    AND rt.fk_current_submission = m.fk_submission
                WHERE rt.fk_current_submission = :submissionId
                    AND rt.fk_output IS NULL
                    AND rt.pk_resolved_transaction > :lastId
                ORDER BY rt.pk_resolved_transaction ASC
                LIMIT :limit
                """;
        }

        @SuppressWarnings("unchecked")
        Query query = entityManager
                .createNativeQuery(nativeSql)
                .setParameter("submissionId", submissionId)
                .setParameter("limit", limit);
        
        if (lastId != null) {
            query.setParameter("lastId", lastId);
        }
        
        List<Object[]> results = query.getResultList();

        // Map results to ResolvedTransaction entities with Merchant (same logic as findByOutputIdWithMerchantsBulkFetched)
        List<ResolvedTransaction> transactions = new ArrayList<>(results.size());
        for (Object[] row : results) {
            ResolvedTransaction rt = new ResolvedTransaction();
            int idx = 0;
            rt.setId(((Number) row[idx++]).longValue());
            rt.setIngestion(row[idx] != null ? entityManager.getReference(Ingestion.class, ((Number) row[idx]).longValue()) : null);
            idx++;
            rt.setSubmission(row[idx] != null ? entityManager.getReference(Submission.class, ((Number) row[idx]).longValue()) : null);
            idx++;
            rt.setCurrentSubmission(row[idx] != null ? entityManager.getReference(Submission.class, ((Number) row[idx]).longValue()) : null);
            idx++;
            rt.setTpRec(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setIdIntermediario(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setIdEsercente(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setChiaveBanca(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setIdPos(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setTipoOpe(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setDtOpe(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setDivisaOpe(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setTipoPag(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setImpOpe(row[idx] != null ? (BigDecimal) row[idx] : null);
            idx++;
            rt.setTotOpe(row[idx] != null ? ((Number) row[idx]).intValue() : null);
            idx++;
            rt.setOutput(row[idx] != null ? entityManager.getReference(Output.class, ((Number) row[idx]).longValue()) : null);
            idx++;
            rt.setCreatedAt(row[idx] != null ? ((java.sql.Timestamp) row[idx]).toLocalDateTime() : null);
            idx++;

            // Map Merchant if present
            if (row[idx] != null) {
                Merchant m = new Merchant();
                m.setId(((Number) row[idx++]).longValue());
                m.setIngestion(row[idx] != null ? entityManager.getReference(Ingestion.class, ((Number) row[idx]).longValue()) : null);
                idx++;
                m.setSubmission(row[idx] != null ? entityManager.getReference(Submission.class, ((Number) row[idx]).longValue()) : null);
                idx++;
                m.setTpRec(row[idx] != null ? String.valueOf(row[idx]) : null);
                idx++;
                m.setIdIntermediario(row[idx] != null ? String.valueOf(row[idx]) : null);
                idx++;
                m.setIdEsercente(row[idx] != null ? String.valueOf(row[idx]) : null);
                idx++;
                m.setCodFiscale(row[idx] != null ? String.valueOf(row[idx]) : null);
                idx++;
                m.setPartitaIva(row[idx] != null ? String.valueOf(row[idx]) : null);
                idx++;
                m.setIdSalmov(row[idx] != null ? String.valueOf(row[idx]) : null);
                idx++;
                m.setCreatedAt(row[idx] != null ? ((java.sql.Timestamp) row[idx]).toLocalDateTime() : null);
                rt.setMerchant(m);
                entityManager.detach(m);
            }
            transactions.add(rt);
        }

        return transactions;
    }

    @Override
    public List<ResolvedTransaction> findByOutputIdWithMerchantsBulkFetched(Long outputId, Long lastId, int limit) {
        // Cursor-based pagination: uses WHERE pk_resolved_transaction > :lastId instead of OFFSET
        // This provides constant-time performance regardless of how many records have been processed
        // Uses index on (fk_output, pk_resolved_transaction) for optimal performance
        String nativeSql;
        if (lastId == null) {
            // First batch: no WHERE clause for lastId
            nativeSql = """
                SELECT 
                    rt.pk_resolved_transaction,
                    rt.fk_ingestion,
                    rt.fk_submission,
                    rt.fk_current_submission,
                    rt.tp_rec,
                    rt.id_intermediario,
                    rt.id_esercente,
                    rt.chiave_banca,
                    rt.id_pos,
                    rt.tipo_ope,
                    rt.dt_ope,
                    rt.divisa_ope,
                    rt.tipo_pag,
                    rt.imp_ope,
                    rt.tot_ope,
                    rt.fk_output,
                    rt.created_at,
                    m.pk_merchant,
                    m.fk_ingestion as m_fk_ingestion,
                    m.fk_submission as m_fk_submission,
                    m.tp_rec as m_tp_rec,
                    m.id_intermediario as m_id_intermediario,
                    m.id_esercente as m_id_esercente,
                    m.cod_fiscale,
                    m.partita_iva,
                    m.id_salmov,
                    m.created_at as m_created_at
                FROM RESOLVED_TRANSACTION rt
                LEFT JOIN MERCHANT m ON rt.id_esercente = m.id_esercente 
                    AND rt.id_intermediario = m.id_intermediario
                    AND rt.fk_current_submission = m.fk_submission
                WHERE rt.fk_output = :outputId
                ORDER BY rt.pk_resolved_transaction ASC
                LIMIT :limit
                """;
        } else {
            // Subsequent batches: use WHERE pk_resolved_transaction > :lastId
            nativeSql = """
                SELECT 
                    rt.pk_resolved_transaction,
                    rt.fk_ingestion,
                    rt.fk_submission,
                    rt.fk_current_submission,
                    rt.tp_rec,
                    rt.id_intermediario,
                    rt.id_esercente,
                    rt.chiave_banca,
                    rt.id_pos,
                    rt.tipo_ope,
                    rt.dt_ope,
                    rt.divisa_ope,
                    rt.tipo_pag,
                    rt.imp_ope,
                    rt.tot_ope,
                    rt.fk_output,
                    rt.created_at,
                    m.pk_merchant,
                    m.fk_ingestion as m_fk_ingestion,
                    m.fk_submission as m_fk_submission,
                    m.tp_rec as m_tp_rec,
                    m.id_intermediario as m_id_intermediario,
                    m.id_esercente as m_id_esercente,
                    m.cod_fiscale,
                    m.partita_iva,
                    m.id_salmov,
                    m.created_at as m_created_at
                FROM RESOLVED_TRANSACTION rt
                LEFT JOIN MERCHANT m ON rt.id_esercente = m.id_esercente 
                    AND rt.id_intermediario = m.id_intermediario
                    AND rt.fk_current_submission = m.fk_submission
                WHERE rt.fk_output = :outputId
                    AND rt.pk_resolved_transaction > :lastId
                ORDER BY rt.pk_resolved_transaction ASC
                LIMIT :limit
                """;
        }

        @SuppressWarnings("unchecked")
        Query query = entityManager
                .createNativeQuery(nativeSql)
                .setParameter("outputId", outputId)
                .setParameter("limit", limit);
        
        if (lastId != null) {
            query.setParameter("lastId", lastId);
        }
        
        List<Object[]> results = query.getResultList();

        // Map results to ResolvedTransaction entities with Merchant
        List<ResolvedTransaction> transactions = new ArrayList<>(results.size());
        for (Object[] row : results) {
            ResolvedTransaction rt = new ResolvedTransaction();
            int idx = 0;
            rt.setId(((Number) row[idx++]).longValue());
            rt.setIngestion(row[idx] != null ? entityManager.getReference(Ingestion.class, ((Number) row[idx]).longValue()) : null);
            idx++;
            rt.setSubmission(row[idx] != null ? entityManager.getReference(Submission.class, ((Number) row[idx]).longValue()) : null);
            idx++;
            rt.setCurrentSubmission(row[idx] != null ? entityManager.getReference(Submission.class, ((Number) row[idx]).longValue()) : null);
            idx++;
            // Use String.valueOf to handle both Character and String types (MySQL returns Character for CHAR(1) columns)
            rt.setTpRec(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setIdIntermediario(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setIdEsercente(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setChiaveBanca(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setIdPos(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setTipoOpe(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setDtOpe(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setDivisaOpe(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setTipoPag(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            rt.setImpOpe(row[idx] != null ? (BigDecimal) row[idx] : null);
            idx++;
            rt.setTotOpe(row[idx] != null ? ((Number) row[idx]).intValue() : null);
            idx++;
            rt.setOutput(row[idx] != null ? entityManager.getReference(Output.class, ((Number) row[idx]).longValue()) : null);
            idx++;
            rt.setCreatedAt(row[idx] != null ? ((java.sql.Timestamp) row[idx]).toLocalDateTime() : null);
            idx++;

            // Map Merchant if present
            if (row[idx] != null) {
                Merchant m = new Merchant();
                m.setId(((Number) row[idx++]).longValue());
                m.setIngestion(row[idx] != null ? entityManager.getReference(Ingestion.class, ((Number) row[idx]).longValue()) : null);
                idx++;
                m.setSubmission(row[idx] != null ? entityManager.getReference(Submission.class, ((Number) row[idx]).longValue()) : null);
                idx++;
                // Use String.valueOf to handle both Character and String types (MySQL returns Character for CHAR(1) columns)
                m.setTpRec(row[idx] != null ? String.valueOf(row[idx]) : null);
                idx++;
                m.setIdIntermediario(row[idx] != null ? String.valueOf(row[idx]) : null);
                idx++;
                m.setIdEsercente(row[idx] != null ? String.valueOf(row[idx]) : null);
                idx++;
                m.setCodFiscale(row[idx] != null ? String.valueOf(row[idx]) : null);
                idx++;
                m.setPartitaIva(row[idx] != null ? String.valueOf(row[idx]) : null);
                idx++;
                m.setIdSalmov(row[idx] != null ? String.valueOf(row[idx]) : null);
                idx++;
                m.setCreatedAt(row[idx] != null ? ((java.sql.Timestamp) row[idx]).toLocalDateTime() : null);
                rt.setMerchant(m);
                // Detach merchant immediately to avoid "shared references" error
                entityManager.detach(m);
            }
            transactions.add(rt);
        }

        return transactions;
    }

    /**
     * Optimized bulk update using direct UPDATE with IN clause on small batches.
     * For very large tables (30M+ records), JOIN-based updates are too slow.
     * Using small batches (50 records) with IN clause is faster and avoids timeout.
     * 
     * @param transactionIds List of resolved transaction IDs to update
     * @param outputId The output ID to set
     * @return Number of rows updated
     */
    @Override
    public int updateOutputForeignKeyOptimized(List<Long> transactionIds, Long outputId) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            return 0;
        }

        // Set MySQL session timeouts to prevent lock wait timeout and query timeout
        try {
            entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = 300").executeUpdate(); // 5 min
            entityManager.createNativeQuery("SET SESSION max_execution_time = 600000").executeUpdate(); // 10 min (600000 ms)
        } catch (Exception e) {
            log.debug("Failed to set MySQL session timeouts (non-critical): {}", e.getMessage());
        }

        // Process in small batches to avoid timeout on large tables (30M+ records)
        // Small batches (50 records) with IN clause are faster than JOIN on very large tables
        final int UPDATE_BATCH_SIZE = 50;
        int totalUpdated = 0;

        for (int i = 0; i < transactionIds.size(); i += UPDATE_BATCH_SIZE) {
            int endIndex = Math.min(i + UPDATE_BATCH_SIZE, transactionIds.size());
            List<Long> batch = transactionIds.subList(i, endIndex);

            // Build IN clause with parameters
            StringJoiner inClause = new StringJoiner(", ");
            for (int j = 0; j < batch.size(); j++) {
                inClause.add(":pk_resolved_transaction_" + j);
            }

            // Direct UPDATE with IN clause - much faster for small batches on large tables
            // WHERE fk_output IS NULL prevents updating already-updated rows
            Query updateQuery = entityManager.createNativeQuery(String.format("""
                UPDATE RESOLVED_TRANSACTION
                SET fk_output = :outputId
                WHERE pk_resolved_transaction IN (%s)
                  AND fk_output IS NULL
                """, inClause.toString()));

            updateQuery.setParameter("outputId", outputId);
            for (int j = 0; j < batch.size(); j++) {
                updateQuery.setParameter("pk_resolved_transaction_" + j, batch.get(j));
            }

            int updatedRows = updateQuery.executeUpdate();
            totalUpdated += updatedRows;

            // Flush after each batch to release locks early
            entityManager.flush();
        }

        return totalUpdated;
    }
}

