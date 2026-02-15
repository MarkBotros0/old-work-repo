package it.deloitte.postrxade.repository.impl;

import it.deloitte.postrxade.entity.Ingestion;
import it.deloitte.postrxade.entity.Merchant;
import it.deloitte.postrxade.entity.Output;
import it.deloitte.postrxade.entity.ResolvedTransaction;
import it.deloitte.postrxade.entity.Submission;
import it.deloitte.postrxade.entity.Transaction;
import it.deloitte.postrxade.repository.TransactionRepositoryCustom;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import java.util.StringJoiner;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Repository
@Transactional(timeout = 120) // 2 minutes timeout to prevent lock wait timeout
public class TransactionRepositoryImpl implements TransactionRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    // Maximum records per single INSERT to avoid lock timeout
    // Smaller batches = shorter transactions = less lock contention
    private static final int MAX_INSERT_BATCH_SIZE = 500;

    @Override
    public void bulkInsert(List<Transaction> transactions, Submission submission) {
        if (transactions == null || transactions.isEmpty()) {
            return;
        }

        // Split into smaller batches to reduce transaction time and lock contention
        for (int i = 0; i < transactions.size(); i += MAX_INSERT_BATCH_SIZE) {
            int endIndex = Math.min(i + MAX_INSERT_BATCH_SIZE, transactions.size());
            List<Transaction> batch = transactions.subList(i, endIndex);
            
            String sql = buildInsertSql(batch.size());
            Query query = entityManager.createNativeQuery(sql);

            for (int j = 0; j < batch.size(); j++) {
                setParams(query, j, batch.get(j), submission);
            }

            query.executeUpdate();
            entityManager.flush(); // Flush to release locks earlier
            entityManager.clear(); // Clear to free memory
        }
    }

    private String buildInsertSql(int batchSize) {
        StringJoiner values = new StringJoiner(", ");
        for (int i = 0; i < batchSize; i++) {
            values.add("( :fk_ingestion_" + i
                    + ", :fk_submission_" + i
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
                "INSERT INTO TRANSACTION (fk_ingestion, fk_submission, tp_rec, id_intermediario, id_esercente, "
                        + "chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag, "
                        + "imp_ope, tot_ope, fk_output, created_at) VALUES %s",
                values);
    }

    private void setParams(Query query, int index, Transaction transaction, Submission submission) {
        query.setParameter("fk_ingestion_" + index,
                transaction.getIngestion() != null ? transaction.getIngestion().getId() : null);
        query.setParameter("fk_submission_" + index, submission.getId());
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
    @Transactional(timeout = 60, readOnly = true) // Read-only transaction with shorter timeout
    public Map<String, Integer> checkExisting(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return new HashMap<>();
        }

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
                    CASE 
                        WHEN t.id_esercente IS NOT NULL OR rt.id_esercente IS NOT NULL 
                        THEN 1 ELSE 0 
                    END AS ExistsFlag
                FROM TRANSACTION_INPUT ti
                LEFT JOIN TRANSACTION t ON 
                    t.id_esercente = ti.id_esercente AND t.chiave_banca = ti.chiave_banca AND 
                    t.id_pos = ti.id_pos AND t.tipo_ope = ti.tipo_ope AND 
                    t.dt_ope = ti.dt_ope AND t.divisa_ope = ti.divisa_ope AND t.tipo_pag = ti.tipo_pag
                LEFT JOIN RESOLVED_TRANSACTION rt ON 
                    rt.id_esercente = ti.id_esercente AND rt.chiave_banca = ti.chiave_banca AND 
                    rt.id_pos = ti.id_pos AND rt.tipo_ope = ti.tipo_ope AND 
                    rt.dt_ope = ti.dt_ope AND rt.divisa_ope = ti.divisa_ope AND rt.tipo_pag = ti.tipo_pag
            """.formatted(valuesBlock);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager
                .createNativeQuery(sql)
                .getResultList();

        Map<String, Integer> result = new HashMap<>();
        for (Object[] row : rows) {
            // Constructing the key safely
            String key = String.valueOf(row[0]) + "_" + // id_esercente
                    String.valueOf(row[1]) + "_" + // chiave_banca
                    String.valueOf(row[2]) + "_" + // id_pos
                    String.valueOf(row[3]) + "_" + // tipo_ope
                    String.valueOf(row[4]) + "_" + // dt_ope
                    String.valueOf(row[5]);        // divisa_ope

            Number existsNumber = (row[6] instanceof Number) ? (Number) row[6] : 0;
            result.put(key, existsNumber.intValue());
        }

        // Clear persistence context to free memory (read-only query, but Hibernate may cache metadata)
        entityManager.clear();
        return result;
    }

//    @Override
//    public Map<String, Integer> checkExistingWithResolved(List<Transaction> transactions) {
//        if (transactions == null || transactions.isEmpty()) {
//            return new HashMap<>();
//        }
//
//        String valuesBlock = buildValuesBlock(transactions);
//
//        // SQL logic: Join both tables and return 1 if found in either
//        String sql = """
//                WITH TRANSACTION_INPUT (id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope) AS (
//                    %s
//                )
//                SELECT
//                    ti.id_esercente,
//                    ti.chiave_banca,
//                    ti.id_pos,
//                    ti.tipo_ope,
//                    ti.dt_ope,
//                    ti.divisa_ope,
//                    CASE
//                        WHEN t.pk_transaction IS NOT NULL OR rt.pk_resolved_transaction IS NOT NULL
//                        THEN 1 ELSE 0
//                    END AS ExistsFlag
//                FROM TRANSACTION_INPUT ti
//                LEFT JOIN TRANSACTION t ON
//                    t.id_esercente = ti.id_esercente AND t.chiave_banca = ti.chiave_banca AND
//                    t.id_pos = ti.id_pos AND t.tipo_ope = ti.tipo_ope AND
//                    t.dt_ope = ti.dt_ope AND t.divisa_ope = ti.divisa_ope
//                LEFT JOIN RESOLVED_TRANSACTION rt ON
//                    rt.id_esercente = ti.id_esercente AND rt.chiave_banca = ti.chiave_banca AND
//                    rt.id_pos = ti.id_pos AND rt.tipo_ope = ti.tipo_ope AND
//                    rt.dt_ope = ti.dt_ope AND rt.divisa_ope = ti.divisa_ope
//            """.formatted(valuesBlock);
//
//        @SuppressWarnings("unchecked")
//        List<Object[]> rows = entityManager
//                .createNativeQuery(sql)
//                .getResultList();
//
//        Map<String, Integer> result = new HashMap<>();
//        for (Object[] row : rows) {
//            // Using String.valueOf to be null-safe and handle different DB types for dtOpe
//            String key = (String) row[0] + "_" + // id_esercente
//                    (String) row[1] + "_" + // chiave_banca
//                    (String) row[2] + "_" + // id_pos
//                    (String) row[3] + "_" + // tipo_ope
//                    (String) row[4] + "_" + // dt_ope
//                    (String) row[5];        // divisa_ope
//
//            Number existsNumber = (row[6] instanceof Number) ? (Number) row[6] : 0;
//            result.put(key, existsNumber.intValue());
//        }
//
//        return result;
//    }

    private String buildValuesBlock(List<Transaction> transactions) {
        // Optimization: Remove exact duplicates WITHIN the same batch to reduce CTE query size
        // This does NOT affect duplicate detection between batches - that's handled by the DB query
        // The checkExisting() method queries the DB for ALL transactions in the batch,
        // so duplicates across batches are still detected correctly
        Set<String> uniqueRows = new LinkedHashSet<>();
        for (Transaction t : transactions) {
            uniqueRows.add("SELECT '"
                    + escape(t.getIdEsercente()) + "' AS id_esercente, '"
                    + escape(t.getChiaveBanca()) + "' AS chiave_banca, '"
                    + escape(t.getIdPos()) + "' AS id_pos, '"
                    + escape(t.getTipoOpe()) + "' AS tipo_ope, '"
                    + escape(t.getDtOpe()) + "' AS dt_ope, '"
                    + escape(t.getDivisaOpe()) + "' AS divisa_ope, '"
                    + escape(t.getTipoPag()) + "' AS tipo_pag");
        }
        return String.join(" UNION ALL ", uniqueRows);
    }

    @Override
    @Transactional(timeout = 60, readOnly = true) // Read-only transaction with shorter timeout
    public Map<String, Integer> checkExistingWithResolved(List<ResolvedTransaction> resolvedTransactions) {
        if (resolvedTransactions == null || resolvedTransactions.isEmpty()) {
            return new HashMap<>();
        }

        String valuesBlock = buildResolvedValuesBlock(resolvedTransactions);

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
                CASE 
                    WHEN t.pk_transaction IS NOT NULL OR rt.pk_resolved_transaction IS NOT NULL 
                    THEN 1 ELSE 0 
                END AS ExistsFlag
            FROM TRANSACTION_INPUT ti
            LEFT JOIN TRANSACTION t ON 
                t.id_esercente = ti.id_esercente AND t.chiave_banca = ti.chiave_banca AND 
                t.id_pos = ti.id_pos AND t.tipo_ope = ti.tipo_ope AND 
                t.dt_ope = ti.dt_ope AND t.divisa_ope = ti.divisa_ope AND t.tipo_pag = ti.tipo_pag
            LEFT JOIN RESOLVED_TRANSACTION rt ON 
                rt.id_esercente = ti.id_esercente AND rt.chiave_banca = ti.chiave_banca AND 
                rt.id_pos = ti.id_pos AND rt.tipo_ope = ti.tipo_ope AND 
                rt.dt_ope = ti.dt_ope AND rt.divisa_ope = ti.divisa_ope AND rt.tipo_pag = ti.tipo_pag
        """.formatted(valuesBlock);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager
                .createNativeQuery(sql)
                .getResultList();

        Map<String, Integer> result = new HashMap<>();
        for (Object[] row : rows) {
            // Construct the composite key to match the records in memory later
            String key = String.valueOf(row[0]) + "_" + // id_esercente
                    String.valueOf(row[1]) + "_" + // chiave_banca
                    String.valueOf(row[2]) + "_" + // id_pos
                    String.valueOf(row[3]) + "_" + // tipo_ope
                    String.valueOf(row[4]) + "_" + // dt_ope
                    String.valueOf(row[5]) + "_" + // divisa_ope
                    String.valueOf(row[6]);        // tipo_pag

            Number existsNumber = (row[7] instanceof Number) ? (Number) row[7] : 0;
            result.put(key, existsNumber.intValue());
        }

        return result;
    }

    private String buildResolvedValuesBlock(List<ResolvedTransaction> transactions) {
        // Optimized: Use LinkedHashSet to remove exact duplicates within batch
        Set<String> uniqueRows = new LinkedHashSet<>();
        for (ResolvedTransaction t : transactions) {
            uniqueRows.add("SELECT '"
                    + escape(t.getIdEsercente()) + "' AS id_esercente, '"
                    + escape(t.getChiaveBanca()) + "' AS chiave_banca, '"
                    + escape(t.getIdPos()) + "' AS id_pos, '"
                    + escape(t.getTipoOpe()) + "' AS tipo_ope, '"
                    + escape(t.getDtOpe()) + "' AS dt_ope, '"
                    + escape(t.getDivisaOpe()) + "' AS divisa_ope, '"
                    + escape(t.getTipoPag()) + "' AS tipo_pag");
        }
        return String.join(" UNION ALL ", uniqueRows);
    }

    private String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("'", "''");
    }

    @Override
    public List<Transaction> findByOutputIdWithMerchantsBulkFetched(Long outputId, Long lastId, int limit) {
        // Cursor-based pagination: uses WHERE pk_transaction > :lastId instead of OFFSET
        // This provides constant-time performance regardless of how many records have been processed
        // Uses index on (fk_output, pk_transaction) for optimal performance
        String nativeSql;
        if (lastId == null) {
            // First batch: no WHERE clause for lastId
            nativeSql = """
                SELECT 
                    t.pk_transaction,
                    t.fk_ingestion,
                    t.fk_submission,
                    t.tp_rec,
                    t.id_intermediario,
                    t.id_esercente,
                    t.chiave_banca,
                    t.id_pos,
                    t.tipo_ope,
                    t.dt_ope,
                    t.divisa_ope,
                    t.tipo_pag,
                    t.imp_ope,
                    t.tot_ope,
                    t.fk_output,
                    t.created_at,
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
                FROM TRANSACTION t
                LEFT JOIN MERCHANT m ON t.id_esercente = m.id_esercente 
                    AND t.id_intermediario = m.id_intermediario
                    AND t.fk_submission = m.fk_submission
                WHERE t.fk_output = :outputId
                ORDER BY t.pk_transaction ASC
                LIMIT :limit
                """;
        } else {
            // Subsequent batches: use WHERE pk_transaction > :lastId
            nativeSql = """
                SELECT 
                    t.pk_transaction,
                    t.fk_ingestion,
                    t.fk_submission,
                    t.tp_rec,
                    t.id_intermediario,
                    t.id_esercente,
                    t.chiave_banca,
                    t.id_pos,
                    t.tipo_ope,
                    t.dt_ope,
                    t.divisa_ope,
                    t.tipo_pag,
                    t.imp_ope,
                    t.tot_ope,
                    t.fk_output,
                    t.created_at,
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
                FROM TRANSACTION t
                LEFT JOIN MERCHANT m ON t.id_esercente = m.id_esercente 
                    AND t.id_intermediario = m.id_intermediario
                    AND t.fk_submission = m.fk_submission
                WHERE t.fk_output = :outputId
                    AND t.pk_transaction > :lastId
                ORDER BY t.pk_transaction ASC
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

        // Map results to Transaction entities with Merchant
        List<Transaction> transactions = new ArrayList<>(results.size());
        for (Object[] row : results) {
            Transaction t = new Transaction();
            int idx = 0;
            t.setId(((Number) row[idx++]).longValue());
            t.setIngestion(row[idx] != null ? entityManager.getReference(Ingestion.class, ((Number) row[idx]).longValue()) : null);
            idx++;
            t.setSubmission(row[idx] != null ? entityManager.getReference(Submission.class, ((Number) row[idx]).longValue()) : null);
            idx++;
            // Use String.valueOf to handle both Character and String types (MySQL returns Character for CHAR(1) columns)
            t.setTpRec(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setIdIntermediario(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setIdEsercente(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setChiaveBanca(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setIdPos(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setTipoOpe(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setDtOpe(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setDivisaOpe(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setTipoPag(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setImpOpe(row[idx] != null ? (BigDecimal) row[idx] : null);
            idx++;
            t.setTotOpe(row[idx] != null ? ((Number) row[idx]).intValue() : null);
            idx++;
            t.setOutput(row[idx] != null ? entityManager.getReference(Output.class, ((Number) row[idx]).longValue()) : null);
            idx++;
            t.setCreatedAt(row[idx] != null ? ((java.sql.Timestamp) row[idx]).toLocalDateTime() : null);
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
                t.setMerchant(m);
                // Detach merchant immediately to avoid "shared references" error
                entityManager.detach(m);
            }
            transactions.add(t);
        }

        return transactions;
    }

    @Override
    public List<Long> findTransactionIdsBySubmissionIdAndNullOutput(Long submissionId, int rowsPerPage) {
        // OPTIMIZED: Use fk_submission directly instead of JOIN through INGESTION
        // TRANSACTION table already has fk_submission column, so JOIN is unnecessary
        String nativeSql = "SELECT t.pk_transaction " +
                "FROM TRANSACTION t " +
                "WHERE t.fk_submission = :submissionId " +
                "AND t.fk_output IS NULL " +
                "ORDER BY t.pk_transaction ASC " +
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
    public Long countBySubmissionIdAndNullOutput(Long submissionId) {
        String nativeSql = "SELECT COUNT(*) " +
                "FROM TRANSACTION t " +
                "WHERE t.fk_submission = :submissionId " +
                "AND t.fk_output IS NULL";

        Query query = entityManager.createNativeQuery(nativeSql);
        query.setParameter("submissionId", submissionId);

        Object result = query.getSingleResult();
        return result != null ? ((Number) result).longValue() : 0L;
    }

    @Override
    public List<Transaction> findBySubmissionIdAndNullOutputWithMerchantsBulkFetched(Long submissionId, Long lastId, int limit) {
        // Similar to findByOutputIdWithMerchantsBulkFetched but filters by fk_submission AND fk_output IS NULL
        // Used by lazy output generation approach
        String nativeSql;
        if (lastId == null) {
            nativeSql = """
                SELECT 
                    t.pk_transaction,
                    t.fk_ingestion,
                    t.fk_submission,
                    t.tp_rec,
                    t.id_intermediario,
                    t.id_esercente,
                    t.chiave_banca,
                    t.id_pos,
                    t.tipo_ope,
                    t.dt_ope,
                    t.divisa_ope,
                    t.tipo_pag,
                    t.imp_ope,
                    t.tot_ope,
                    t.fk_output,
                    t.created_at,
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
                FROM TRANSACTION t
                LEFT JOIN MERCHANT m ON t.id_esercente = m.id_esercente 
                    AND t.id_intermediario = m.id_intermediario
                    AND t.fk_submission = m.fk_submission
                WHERE t.fk_submission = :submissionId
                    AND t.fk_output IS NULL
                ORDER BY t.pk_transaction ASC
                LIMIT :limit
                """;
        } else {
            nativeSql = """
                SELECT 
                    t.pk_transaction,
                    t.fk_ingestion,
                    t.fk_submission,
                    t.tp_rec,
                    t.id_intermediario,
                    t.id_esercente,
                    t.chiave_banca,
                    t.id_pos,
                    t.tipo_ope,
                    t.dt_ope,
                    t.divisa_ope,
                    t.tipo_pag,
                    t.imp_ope,
                    t.tot_ope,
                    t.fk_output,
                    t.created_at,
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
                FROM TRANSACTION t
                LEFT JOIN MERCHANT m ON t.id_esercente = m.id_esercente 
                    AND t.id_intermediario = m.id_intermediario
                    AND t.fk_submission = m.fk_submission
                WHERE t.fk_submission = :submissionId
                    AND t.fk_output IS NULL
                    AND t.pk_transaction > :lastId
                ORDER BY t.pk_transaction ASC
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

        // Map results to Transaction entities with Merchant (same logic as findByOutputIdWithMerchantsBulkFetched)
        List<Transaction> transactions = new ArrayList<>(results.size());
        for (Object[] row : results) {
            Transaction t = new Transaction();
            int idx = 0;
            t.setId(((Number) row[idx++]).longValue());
            t.setIngestion(row[idx] != null ? entityManager.getReference(Ingestion.class, ((Number) row[idx]).longValue()) : null);
            idx++;
            t.setSubmission(row[idx] != null ? entityManager.getReference(Submission.class, ((Number) row[idx]).longValue()) : null);
            idx++;
            t.setTpRec(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setIdIntermediario(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setIdEsercente(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setChiaveBanca(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setIdPos(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setTipoOpe(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setDtOpe(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setDivisaOpe(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setTipoPag(row[idx] != null ? String.valueOf(row[idx]) : null);
            idx++;
            t.setImpOpe(row[idx] != null ? (BigDecimal) row[idx] : null);
            idx++;
            t.setTotOpe(row[idx] != null ? ((Number) row[idx]).intValue() : null);
            idx++;
            t.setOutput(row[idx] != null ? entityManager.getReference(Output.class, ((Number) row[idx]).longValue()) : null);
            idx++;
            t.setCreatedAt(row[idx] != null ? ((java.sql.Timestamp) row[idx]).toLocalDateTime() : null);
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
                t.setMerchant(m);
                entityManager.detach(m);
            }
            transactions.add(t);
        }

        return transactions;
    }

    /**
     * Optimized bulk update using direct UPDATE with IN clause on small batches.
     * For very large tables (30M+ records), JOIN-based updates are too slow.
     * Using small batches (50 records) with IN clause is faster and avoids timeout.
     * 
     * @param transactionIds List of transaction IDs to update
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
                inClause.add(":pk_transaction_" + j);
            }

            // Direct UPDATE with IN clause - much faster for small batches on large tables
            // WHERE fk_output IS NULL prevents updating already-updated rows
            Query updateQuery = entityManager.createNativeQuery(String.format("""
                UPDATE TRANSACTION
                SET fk_output = :outputId
                WHERE pk_transaction IN (%s)
                  AND fk_output IS NULL
                """, inClause.toString()));

            updateQuery.setParameter("outputId", outputId);
            for (int j = 0; j < batch.size(); j++) {
                updateQuery.setParameter("pk_transaction_" + j, batch.get(j));
            }

            int updatedRows = updateQuery.executeUpdate();
            totalUpdated += updatedRows;

            // Flush after each batch to release locks early
            entityManager.flush();
        }

        return totalUpdated;
    }
}

