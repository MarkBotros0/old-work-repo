package it.deloitte.postrxade.repository.impl;

import it.deloitte.postrxade.entity.Merchant;
import it.deloitte.postrxade.entity.Transaction;
import it.deloitte.postrxade.records.StagingResult;
import it.deloitte.postrxade.repository.StagingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of staging repository for high-performance ETL operations.
 * Uses native SQL for maximum performance with bulk operations.
 */
@Repository
@Slf4j
public class StagingRepositoryImpl implements StagingRepository {

    @PersistenceContext
    private EntityManager entityManager;

    // TransactionTemplate for programmatic transaction management
    // Used for chunk methods because Spring AOP cannot intercept private method calls
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void initTransactionTemplate(PlatformTransactionManager transactionManager) {
        // Create TransactionTemplate with 10-minute timeout (600 seconds)
        // This ensures each chunk operation has its own transaction with proper timeout
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setTimeout(600); // 10 minutes per chunk operation
    }

    // Batch size for bulk inserts into staging
    private static final int STAGING_BATCH_SIZE = 5000;

    @Override
    @Transactional
    public void clearStaging(Long submissionId) {
        log.info("Clearing staging tables (TRUNCATE) for submission: {} using stored procedure", submissionId);
        
        // Use stored procedure with TRUNCATE for better performance
        // TRUNCATE svuota completamente le tabelle STG (non serve WHERE)
        // Le tabelle STG sono temporanee e devono essere vuote all'inizio e alla fine
        entityManager.createNativeQuery("CALL sp_clear_staging()")
                .executeUpdate();
        
        log.info("Staging tables cleared (TRUNCATE) for submission: {} via stored procedure", submissionId);
    }

    @Override
    // NO @Transactional here - each batch has its own transaction via TransactionTemplate
    // This prevents long-running transactions that cause connection timeouts
    public void clearTransactionStaging(Long submissionId) {
        log.info("Clearing transaction staging table for submission: {}", submissionId);
        long startTime = System.currentTimeMillis();
        
        // Delete in smaller batches with separate transactions to avoid timeouts
        // Each batch commits immediately, preventing connection leaks and timeouts
        final int DELETE_BATCH_SIZE = 50000; // 50k per batch for faster commits
        int totalDeleted = 0;
        int batchNumber = 0;
        
        while (true) {
            batchNumber++;
            
            // Use TransactionTemplate for each batch - commits immediately after each batch
            // This prevents long-running transactions that exceed socketTimeout
            Integer deleted = transactionTemplate.execute(status -> {
                // Use LIMIT with hardcoded value (MySQL doesn't support LIMIT as parameter in DELETE)
                // Build query dynamically to include batch size
                String deleteQuery = String.format(
                    "DELETE FROM STG_TRANSACTION WHERE fk_submission = :submissionId LIMIT %d",
                    DELETE_BATCH_SIZE
                );
                
                int deletedCount = entityManager.createNativeQuery(deleteQuery)
                        .setParameter("submissionId", submissionId)
                        .executeUpdate();
                
                entityManager.flush();
                entityManager.clear();
                
                return deletedCount;
            });
            
            if (deleted == null || deleted == 0) {
                // No more records to delete
                break;
            }
            
            totalDeleted += deleted;
            
            if (batchNumber % 10 == 0) {
                log.info("Deleted {} batches ({} records so far) from staging for submission: {}", 
                        batchNumber, totalDeleted, submissionId);
            }
        }
        
        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Cleared {} transaction records from staging for submission: {} in {}ms ({} batches)", 
                totalDeleted, submissionId, elapsed, batchNumber);
    }

    @Override
    @Transactional
    public void bulkLoadMerchantsToStaging(List<Merchant> merchants, Long ingestionId, Long submissionId) {
        if (merchants == null || merchants.isEmpty()) {
            return;
        }

        log.info("Loading {} merchants to staging for submission: {}", merchants.size(), submissionId);
        long startTime = System.currentTimeMillis();

        // Process in batches to avoid memory issues
        for (int i = 0; i < merchants.size(); i += STAGING_BATCH_SIZE) {
            int endIndex = Math.min(i + STAGING_BATCH_SIZE, merchants.size());
            List<Merchant> batch = merchants.subList(i, endIndex);
            
            insertMerchantBatchToStaging(batch, ingestionId, submissionId);
            
            // Clear persistence context to free memory
            entityManager.flush();
            entityManager.clear();
            
            if (i > 0 && i % 50000 == 0) {
                log.info("Loaded {}/{} merchants to staging", i, merchants.size());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Loaded {} merchants to staging in {}ms", merchants.size(), elapsed);
    }

    private void insertMerchantBatchToStaging(List<Merchant> merchants, Long ingestionId, Long submissionId) {
        if (merchants.isEmpty()) return;

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO STG_MERCHANT (id_esercente, id_intermediario, tp_rec, cod_fiscale, partita_iva, id_salmov, fk_ingestion, fk_submission, raw_row) VALUES ");

        List<String> valueRows = new ArrayList<>();
        for (Merchant m : merchants) {
            valueRows.add(String.format("('%s', '%s', '%s', '%s', '%s', '%s', %d, %d, '%s')",
                    escape(m.getIdEsercente()),
                    escape(m.getIdIntermediario()),
                    escape(m.getTpRec()),
                    escape(m.getCodFiscale()),
                    escape(m.getPartitaIva()),
                    escape(m.getIdSalmov()),
                    ingestionId,
                    submissionId,
                    escape(truncate(m.getRawRow(), 250))
            ));
        }

        sql.append(String.join(", ", valueRows));
        entityManager.createNativeQuery(sql.toString()).executeUpdate();
    }

    @Override
    @Transactional
    public void bulkLoadTransactionsToStaging(List<Transaction> transactions, Long ingestionId, Long submissionId) {
        if (transactions == null || transactions.isEmpty()) {
            return;
        }

        log.info("Loading {} transactions to staging for submission: {}", transactions.size(), submissionId);
        long startTime = System.currentTimeMillis();

        // Process in batches to avoid memory issues
        for (int i = 0; i < transactions.size(); i += STAGING_BATCH_SIZE) {
            int endIndex = Math.min(i + STAGING_BATCH_SIZE, transactions.size());
            List<Transaction> batch = transactions.subList(i, endIndex);
            
            insertTransactionBatchToStaging(batch, ingestionId, submissionId);
            
            // Clear persistence context to free memory
            entityManager.flush();
            entityManager.clear();
            
            if (i > 0 && i % 50000 == 0) {
                log.info("Loaded {}/{} transactions to staging", i, transactions.size());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Loaded {} transactions to staging in {}ms", transactions.size(), elapsed);
    }

    private void insertTransactionBatchToStaging(List<Transaction> transactions, Long ingestionId, Long submissionId) {
        if (transactions.isEmpty()) return;

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO STG_TRANSACTION (id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, ");
        sql.append("tp_rec, id_intermediario, tipo_pag, imp_ope, tot_ope, fk_ingestion, fk_submission, raw_row) VALUES ");

        List<String> valueRows = new ArrayList<>();
        for (Transaction t : transactions) {
            String impOpe = t.getImpOpe() != null ? t.getImpOpe().toString() : "NULL";
            String totOpe = t.getTotOpe() != null ? t.getTotOpe().toString() : "NULL";
            
            valueRows.add(String.format("('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', %s, %s, %d, %d, '%s')",
                    escape(t.getIdEsercente()),
                    escape(t.getChiaveBanca()),
                    escape(t.getIdPos()),
                    escape(t.getTipoOpe()),
                    escape(t.getDtOpe()),
                    escape(t.getDivisaOpe()),
                    escape(t.getTpRec()),
                    escape(t.getIdIntermediario()),
                    escape(t.getTipoPag()),
                    impOpe,
                    totOpe,
                    ingestionId,
                    submissionId,
                    escape(truncate(t.getRawRow(), 250))
            ));
        }

        sql.append(String.join(", ", valueRows));
        entityManager.createNativeQuery(sql.toString()).executeUpdate();
    }

    @Override
    @Transactional
    public StagingResult processMerchantsFromStaging(Long submissionId) {
        log.info("Processing merchants from staging for submission: {}", submissionId);
        long startTime = System.currentTimeMillis();

        // Step 1: Mark duplicates (exist in MERCHANT for same submission)
        int existingDuplicates = entityManager.createNativeQuery("""
                UPDATE STG_MERCHANT stg
                INNER JOIN MERCHANT m 
                    ON stg.id_esercente = m.id_esercente 
                    AND stg.id_intermediario = m.id_intermediario 
                    AND stg.fk_submission = m.fk_submission
                SET stg.process_status = 2,
                    stg.error_message = 'Duplicate: merchant already exists'
                WHERE stg.fk_submission = :submissionId
                  AND stg.process_status IS NULL
                """)
                .setParameter("submissionId", submissionId)
                .executeUpdate();

        // Step 2: Mark duplicates within staging batch (keep first occurrence)
        int batchDuplicates = entityManager.createNativeQuery("""
                UPDATE STG_MERCHANT stg
                INNER JOIN (
                    SELECT id_esercente, id_intermediario, MIN(pk_stg_merchant) as first_pk
                    FROM STG_MERCHANT
                    WHERE fk_submission = :submissionId AND process_status IS NULL
                    GROUP BY id_esercente, id_intermediario
                    HAVING COUNT(*) > 1
                ) dups ON stg.id_esercente = dups.id_esercente 
                      AND stg.id_intermediario = dups.id_intermediario
                      AND stg.pk_stg_merchant > dups.first_pk
                SET stg.process_status = 2,
                    stg.error_message = 'Duplicate within batch'
                WHERE stg.fk_submission = :submissionId
                  AND stg.process_status IS NULL
                """)
                .setParameter("submissionId", submissionId)
                .executeUpdate();

        int totalDuplicates = existingDuplicates + batchDuplicates;

        // Step 3: Insert new merchants (those still pending)
        int insertedCount = entityManager.createNativeQuery("""
                INSERT INTO MERCHANT (fk_ingestion, fk_submission, tp_rec, id_intermediario, id_esercente, 
                                     cod_fiscale, partita_iva, id_salmov, created_at)
                SELECT fk_ingestion, fk_submission, tp_rec, id_intermediario, id_esercente, 
                       cod_fiscale, partita_iva, id_salmov, CURRENT_TIMESTAMP
                FROM STG_MERCHANT
                WHERE fk_submission = :submissionId
                  AND process_status IS NULL
                """)
                .setParameter("submissionId", submissionId)
                .executeUpdate();

        // Step 4: Mark as inserted
        entityManager.createNativeQuery("""
                UPDATE STG_MERCHANT
                SET process_status = 1
                WHERE fk_submission = :submissionId
                  AND process_status IS NULL
                """)
                .setParameter("submissionId", submissionId)
                .executeUpdate();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Processed merchants from staging in {}ms: inserted={}, duplicates={}", 
                elapsed, insertedCount, totalDuplicates);

        return new StagingResult(insertedCount, totalDuplicates);
    }

    // Chunk size for processing large datasets (prevents connection leaks and improves performance)
    // Reduced to 100k for better performance on UPDATE queries with JOINs
    private static final int PROCESSING_CHUNK_SIZE = 100_000; // Process 100k records per chunk

    /** Static table (created by admin) that tracks inserted transaction keys during chunk processing. All connections see it. Cleared at start here and at end by sp_clear_staging. */
    private static final String INSERTED_TRANSACTIONS_TRACKING_TABLE = "temp_inserted_transactions";

    @Override
    // CRITICAL: NO @Transactional here - each chunk has its own transaction
    // The wrapper transaction was causing timeout after 15 minutes (process takes 90+ minutes)
    // Each chunk method uses TransactionTemplate to handle its own transaction and commit
    public StagingResult processTransactionsFromStaging(Long submissionId) {
        log.info("Processing transactions from staging for submission: {}", submissionId);
        
        // Get total count for chunking strategy
        // OPTIMIZED: This COUNT(*) query uses index idx_stg_trx_submission_status for fast counting
        // If it times out, we can skip it and always use chunk-based processing
        // NOTE: COUNT(*) is executed in a read-only transaction via TransactionTemplate
        Long totalRecords = 0L;
        try {
            totalRecords = transactionTemplate.execute(status -> {
                return ((Number) entityManager.createNativeQuery("""
                        SELECT COUNT(*) 
                        FROM STG_TRANSACTION 
                        WHERE fk_submission = :submissionId AND process_status IS NULL
                        """)
                        .setParameter("submissionId", submissionId)
                        .getSingleResult()).longValue();
            });
            
            log.info("Total records to process: {}", totalRecords);
        } catch (Exception e) {
            // If COUNT(*) fails (timeout, etc.), assume large dataset and use chunk-based processing
            log.warn("Failed to get total record count (timeout?): {}. Assuming large dataset and using chunk-based processing.", e.getMessage());
            totalRecords = Long.MAX_VALUE; // Force chunk-based processing
        }
        
        // CRITICAL: Always use chunk-based processing for datasets > 5k to avoid timeout
        // Single-pass queries (even with NOT EXISTS) can be slow on large datasets
        // Chunk-based processes in smaller batches (100k each) which is much faster
        if (totalRecords > 5_000) {
            log.info("Using chunk-based processing for {} records (chunk size: {})", totalRecords, PROCESSING_CHUNK_SIZE);
            log.warn("Large dataset detected ({} records) - using chunk-based approach to avoid timeout", totalRecords);
            return processTransactionsInChunks(submissionId, totalRecords);
        } else {
            log.info("Using single-pass processing for {} records (small dataset)", totalRecords);
            return processTransactionsSinglePass(submissionId);
        }
    }

    /**
     * Process transactions in chunks using optimized approach:
     * - No UPDATE on STG during insertion (reduces locks)
     * - GROUP BY limited to chunk (faster than global GROUP BY)
     * - Use STG_MERCHANT instead of MERCHANT (much smaller, constant performance)
     * - Use temporary table instead of TRANSACTION for duplicate check (avoids 200M+ record JOIN)
     * - Error identification at the end (duplicates and missing merchants)
     */
    private StagingResult processTransactionsInChunks(Long submissionId, Long totalRecords) {
        log.info("Starting optimized chunk-based processing for submission: {} ({} records)", submissionId, totalRecords);
        long startTime = System.currentTimeMillis();
        
        // Static table temp_inserted_transactions is created by admin; posappusr has SELECT, INSERT, DELETE.
        // Clear it at start so this submission starts with an empty tracking table (no CREATE/DROP needed).
        log.info("Clearing static tracking table {} for this submission...", INSERTED_TRANSACTIONS_TRACKING_TABLE);
        transactionTemplate.execute(status -> {
            entityManager.createNativeQuery("DELETE FROM " + INSERTED_TRANSACTIONS_TRACKING_TABLE).executeUpdate();
            return null;
        });
        
        int totalInserted = 0;
        int chunkNumber = 0;
        
        // OPTIMIZED APPROACH: Insert valid transactions in chunks
        log.info("Inserting valid transactions in chunks (optimized approach)...");
        Long lastProcessedMaxPk = null; // Track last processed range
        int consecutiveEmptyChunks = 0; // Track consecutive chunks with no inserts
        while (true) {
                Long minPk = getMinPendingPk(submissionId, lastProcessedMaxPk);
                if (minPk == null) {
                    log.info("No more records to process after {} chunks", chunkNumber);
                    break; // No more records with process_status IS NULL
                }
                
                chunkNumber++;
                Long maxPk = minPk + PROCESSING_CHUNK_SIZE;
                
                // Update persistent table and insert in the same transaction (avoids nested transactions)
                // NOTE: updateTempTable=true means we'll populate the inserted-transactions table AFTER insert
                int inserted = processInsertChunkOptimized(submissionId, minPk, maxPk, true);
                totalInserted += inserted;
                
                // Always advance to next range to prevent infinite loop
                lastProcessedMaxPk = maxPk;
                
                // Safety check: if we have 3 consecutive chunks with no inserts, stop
                // This means we've processed all insertable records (remaining are errors)
                if (inserted == 0) {
                    consecutiveEmptyChunks++;
                    if (consecutiveEmptyChunks >= 3) {
                        log.info("Stopping after {} consecutive chunks with no inserts (remaining records are errors)", consecutiveEmptyChunks);
                        break;
                    }
                } else {
                    consecutiveEmptyChunks = 0; // Reset counter when we insert something
                }
                
                if (chunkNumber % 10 == 0) {
                    log.info("Processed {} chunks for insert, inserted {} so far", chunkNumber, totalInserted);
                }
            }
        log.info("Insert phase completed: {} transactions inserted in {} chunks", totalInserted, chunkNumber);
        
        // Debug: Log final state to help diagnose issues
        if (totalInserted == 0 && chunkNumber > 0) {
            log.warn("WARNING: No transactions inserted despite processing {} chunks! This might indicate:", chunkNumber);
            log.warn("  1. All records have process_status != NULL (from previous run) - check if cleanup was called");
            log.warn("  2. No records have valid merchants in STG_MERCHANT");
            log.warn("  3. All records are already in temp_inserted_transactions (duplicates)");
            log.warn("  4. No records exist in the PK ranges processed");
            
            // Count records in STG for this submission
            try {
                Number totalInStg = (Number) transactionTemplate.execute(status -> {
                    return entityManager.createNativeQuery("""
                        SELECT COUNT(*) 
                        FROM STG_TRANSACTION 
                        WHERE fk_submission = :submissionId
                        """)
                        .setParameter("submissionId", submissionId)
                        .getSingleResult();
                });
                
                Number withNullStatus = (Number) transactionTemplate.execute(status -> {
                    return entityManager.createNativeQuery("""
                        SELECT COUNT(*) 
                        FROM STG_TRANSACTION 
                        WHERE fk_submission = :submissionId
                          AND process_status IS NULL
                        """)
                        .setParameter("submissionId", submissionId)
                        .getSingleResult();
                });
                
                Number withMerchant = (Number) transactionTemplate.execute(status -> {
                    return entityManager.createNativeQuery("""
                        SELECT COUNT(*) 
                        FROM STG_TRANSACTION stg
                        INNER JOIN STG_MERCHANT m_stg 
                            ON stg.id_esercente = m_stg.id_esercente 
                            AND stg.id_intermediario = m_stg.id_intermediario
                            AND m_stg.fk_submission = stg.fk_submission
                        WHERE stg.fk_submission = :submissionId
                        """)
                        .setParameter("submissionId", submissionId)
                        .getSingleResult();
                });
                
                log.warn("DEBUG INFO for submission {}: totalInStg={}, withNullStatus={}, withMerchant={}", 
                        submissionId, totalInStg, withNullStatus, withMerchant);
            } catch (Exception e) {
                log.warn("Failed to get debug counts: {}", e.getMessage());
            }
        }
        
        // Phase 1.5: Count duplicates within batch (same file) - SELECT only, no UPDATE on STG
        // These are duplicates that appear multiple times in the same file
        log.info("Phase 1.5: Counting duplicates within batch (same file) - SELECT only, no UPDATE...");
        int totalBatchDuplicates = 0;
        try {
            Number count = (Number) transactionTemplate.execute(status -> {
                return entityManager.createNativeQuery("""
                    SELECT COUNT(*)
                    FROM STG_TRANSACTION stg
                    INNER JOIN STG_MERCHANT m_stg 
                        ON stg.id_esercente = m_stg.id_esercente 
                        AND stg.id_intermediario = m_stg.id_intermediario
                        AND m_stg.fk_submission = stg.fk_submission
                    INNER JOIN (
                        SELECT id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag,
                               MIN(pk_stg_transaction) as first_pk
                        FROM STG_TRANSACTION
                        WHERE fk_submission = :submissionId
                        GROUP BY id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag
                        HAVING COUNT(*) > 1
                    ) dups 
                        ON stg.id_esercente = dups.id_esercente 
                        AND stg.chiave_banca = dups.chiave_banca
                        AND stg.id_pos = dups.id_pos
                        AND stg.tipo_ope = dups.tipo_ope
                        AND stg.dt_ope = dups.dt_ope
                        AND stg.divisa_ope = dups.divisa_ope
                        AND stg.tipo_pag = dups.tipo_pag
                        AND stg.pk_stg_transaction > dups.first_pk
                    WHERE stg.fk_submission = :submissionId
                    """)
                    .setParameter("submissionId", submissionId)
                    .getSingleResult();
            });
            totalBatchDuplicates = count != null ? count.intValue() : 0;
            log.info("CHUNK-BASED: Found {} duplicate transactions within batch (same file)", totalBatchDuplicates);
        } catch (Exception e) {
            log.error("Error counting batch duplicate transactions for submission {}: {}", submissionId, e.getMessage(), e);
        }
        
        // Phase 2: Identify missing merchants in chunks (SELECT only, no UPDATE on STG)
        // Process records that don't have a merchant in STG_MERCHANT and weren't inserted
        log.info("Phase 2: Identifying missing merchants in chunks (SELECT only, no UPDATE)...");
        long missingMerchantStart = System.currentTimeMillis();
        int totalMissingMerchants = 0;
        Long lastMissingMerchantMaxPk = null;
        int missingMerchantChunkNumber = 0;
        
        while (true) {
            Long minPk = getMinPendingPk(submissionId, lastMissingMerchantMaxPk);
            if (minPk == null) {
                log.info("No more records to check for missing merchants after {} chunks", missingMerchantChunkNumber);
                break;
            }
            
            missingMerchantChunkNumber++;
            Long maxPk = minPk + PROCESSING_CHUNK_SIZE;
            
            // Get missing merchant records for this chunk (SELECT only, no UPDATE)
            List<Object[]> missingMerchantRecords = getMissingMerchantTransactionDetailsChunk(submissionId, minPk, maxPk);
            totalMissingMerchants += missingMerchantRecords.size();
            
            // Always advance to next range
            lastMissingMerchantMaxPk = maxPk;
            
            // If no records found in this chunk, check a few more chunks before stopping
            if (missingMerchantRecords.isEmpty()) {
                // Try a few more chunks to be sure
                boolean foundMore = false;
                for (int i = 0; i < 3; i++) {
                    Long nextMinPk = getMinPendingPk(submissionId, lastMissingMerchantMaxPk);
                    if (nextMinPk == null) break;
                    Long nextMaxPk = nextMinPk + PROCESSING_CHUNK_SIZE;
                    List<Object[]> nextChunk = getMissingMerchantTransactionDetailsChunk(submissionId, nextMinPk, nextMaxPk);
                    if (!nextChunk.isEmpty()) {
                        foundMore = true;
                        break;
                    }
                    lastMissingMerchantMaxPk = nextMaxPk;
                }
                if (!foundMore) {
                    log.info("Stopping missing merchant identification after {} chunks with no records", missingMerchantChunkNumber);
                    break;
                }
            }
            
            if (missingMerchantChunkNumber % 10 == 0) {
                log.info("Processed {} chunks for missing merchants, found {} so far", missingMerchantChunkNumber, totalMissingMerchants);
            }
        }
        
        long missingMerchantDuration = System.currentTimeMillis() - missingMerchantStart;
        log.info("Missing merchant identification completed in {}ms: {} records found in {} chunks", 
                missingMerchantDuration, totalMissingMerchants, missingMerchantChunkNumber);
        
        // Drop temporary table after insert phase (no longer needed for error identification)
        log.info("Dropping temporary table (no longer needed for error identification)...");
        TransactionTemplate isolatedDropTemplate = new TransactionTemplate(transactionTemplate.getTransactionManager());
        isolatedDropTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        isolatedDropTemplate.setTimeout(60); // 1 minute should be enough
        isolatedDropTemplate.execute(status -> {
            try {
                entityManager.createNativeQuery("DROP TEMPORARY TABLE IF EXISTS temp_inserted_transactions")
                    .executeUpdate();
                log.debug("Temporary table temp_inserted_transactions dropped");
            } catch (Exception e) {
                log.warn("Failed to drop temporary table: {}", e.getMessage());
            }
            return null;
        });
        
        // Phase 3: Identify duplicates (already inserted in TRANSACTION) in chunks (SELECT only, no UPDATE on STG)
        // Process records that already exist in TRANSACTION and weren't inserted in this batch
        log.info("Phase 3: Identifying duplicates (already in TRANSACTION) in chunks (SELECT only, no UPDATE)...");
        long duplicateStart = System.currentTimeMillis();
        int totalExistingDuplicates = 0;
        Long lastDuplicateMaxPk = null;
        int duplicateChunkNumber = 0;
        
        while (true) {
            Long minPk = getMinPendingPk(submissionId, lastDuplicateMaxPk);
            if (minPk == null) {
                log.info("No more records to check for duplicates after {} chunks", duplicateChunkNumber);
                break;
            }
            
            duplicateChunkNumber++;
            Long maxPk = minPk + PROCESSING_CHUNK_SIZE;
            
            // Get duplicate records for this chunk (SELECT only, no UPDATE)
            List<Object[]> duplicateRecords = getDuplicateTransactionDetailsChunk(submissionId, minPk, maxPk);
            totalExistingDuplicates += duplicateRecords.size();
            
            // Always advance to next range
            lastDuplicateMaxPk = maxPk;
            
            // If no records found in this chunk, check a few more chunks before stopping
            if (duplicateRecords.isEmpty()) {
                // Try a few more chunks to be sure
                boolean foundMore = false;
                for (int i = 0; i < 3; i++) {
                    Long nextMinPk = getMinPendingPk(submissionId, lastDuplicateMaxPk);
                    if (nextMinPk == null) break;
                    Long nextMaxPk = nextMinPk + PROCESSING_CHUNK_SIZE;
                    List<Object[]> nextChunk = getDuplicateTransactionDetailsChunk(submissionId, nextMinPk, nextMaxPk);
                    if (!nextChunk.isEmpty()) {
                        foundMore = true;
                        break;
                    }
                    lastDuplicateMaxPk = nextMaxPk;
                }
                if (!foundMore) {
                    log.info("Stopping duplicate identification after {} chunks with no records", duplicateChunkNumber);
                    break;
                }
            }
            
            if (duplicateChunkNumber % 10 == 0) {
                log.info("Processed {} chunks for duplicates, found {} so far", duplicateChunkNumber, totalExistingDuplicates);
            }
        }
        
        long duplicateDuration = System.currentTimeMillis() - duplicateStart;
        log.info("Duplicate identification completed in {}ms: {} records found in {} chunks", 
                duplicateDuration, totalExistingDuplicates, duplicateChunkNumber);
        
        int totalDuplicates = totalBatchDuplicates + totalExistingDuplicates;
        
        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Optimized chunk-based processing completed in {}ms: total inserted={}, duplicates={} ({} within batch + {} already in TRANSACTION), missingMerchants={}", 
                elapsed, totalInserted, totalDuplicates, totalBatchDuplicates, totalExistingDuplicates, totalMissingMerchants);
        
        return new StagingResult(totalInserted, totalDuplicates, totalMissingMerchants, 0);
    }

    /**
     * Update static inserted-transactions table with keys of transactions that were actually inserted.
     * This allows subsequent chunks to avoid duplicates with this chunk.
     *
     * NOTE: This method is called AFTER the INSERT, not before.
     * tableName is the static table created by admin (e.g. temp_inserted_transactions).
     */
    private void updateInsertedTransactionsTableAfterInsertDirect(EntityManager em, String tableName, Long submissionId, Long minPk, Long maxPk) {
        log.debug("Updating table {} AFTER insert for chunk: submissionId={}, minPk={}, maxPk={}", tableName, submissionId, minPk, maxPk);
        try {
            em.createNativeQuery(
                "INSERT IGNORE INTO " + tableName + " " +
                "(id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag) " +
                "SELECT DISTINCT stg.id_esercente, stg.chiave_banca, stg.id_pos, stg.tipo_ope, stg.dt_ope, stg.divisa_ope, stg.tipo_pag " +
                "FROM STG_TRANSACTION stg " +
                "INNER JOIN STG_MERCHANT m_stg " +
                "    ON stg.id_esercente = m_stg.id_esercente " +
                "    AND stg.id_intermediario = m_stg.id_intermediario " +
                "    AND m_stg.fk_submission = stg.fk_submission " +
                "INNER JOIN ( " +
                "    SELECT id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag, " +
                "           MIN(pk_stg_transaction) as first_pk " +
                "    FROM STG_TRANSACTION " +
                "    WHERE fk_submission = :submissionId " +
                "      AND pk_stg_transaction >= :minPk " +
                "      AND pk_stg_transaction < :maxPk " +
                "    GROUP BY id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag " +
                ") first_occurrence " +
                "    ON stg.pk_stg_transaction = first_occurrence.first_pk " +
                "WHERE stg.fk_submission = :submissionId " +
                "  AND stg.pk_stg_transaction >= :minPk " +
                "  AND stg.pk_stg_transaction < :maxPk"
            )
                .setParameter("submissionId", submissionId)
                .setParameter("minPk", minPk)
                .setParameter("maxPk", maxPk)
                .executeUpdate();
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : "";
            if (errorMessage.contains("table") && errorMessage.contains("full")) {
                log.error("CRITICAL: Table {} is full! Error: {}", tableName, errorMessage);
                throw new RuntimeException("Inserted-transactions table is full", e);
            }
            log.warn("Failed to update table {} after insert: {}", tableName, errorMessage);
        }
    }

    /**
     * Update temporary table with keys of transactions we're about to insert.
     * DEPRECATED: This method is no longer used because it causes all records to be excluded
     * by the LEFT JOIN in the INSERT query. We now populate the table AFTER insert instead.
     * 
     * @deprecated Use updateInsertedTransactionsTableAfterInsertDirect instead
     */
    @Deprecated
    private void updateTemporaryInsertedTransactionsTableBeforeInsertDirect(EntityManager em, Long submissionId, Long minPk, Long maxPk) {
        log.debug("Updating temporary table before insert for chunk: submissionId={}, minPk={}, maxPk={}", submissionId, minPk, maxPk);
        try {
            // Insert keys of transactions we're about to insert (those that pass all filters)
            // NOTE: Cannot use LEFT JOIN with temp_inserted_transactions in the same query that inserts into it
            // MySQL error: "Can't reopen table". INSERT IGNORE handles duplicates automatically via PRIMARY KEY.
                em.createNativeQuery("""
                INSERT IGNORE INTO temp_inserted_transactions 
                (id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag)
                SELECT DISTINCT stg.id_esercente, stg.chiave_banca, stg.id_pos, stg.tipo_ope, stg.dt_ope, stg.divisa_ope, stg.tipo_pag
                FROM STG_TRANSACTION stg
                INNER JOIN STG_MERCHANT m_stg 
                    ON stg.id_esercente = m_stg.id_esercente 
                    AND stg.id_intermediario = m_stg.id_intermediario
                    AND m_stg.fk_submission = stg.fk_submission
                INNER JOIN (
                    SELECT id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag,
                           MIN(pk_stg_transaction) as first_pk
                    FROM STG_TRANSACTION
                    WHERE fk_submission = :submissionId
                      AND pk_stg_transaction >= :minPk
                      AND pk_stg_transaction < :maxPk
                    GROUP BY id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag
                ) first_occurrence 
                    ON stg.pk_stg_transaction = first_occurrence.first_pk
                WHERE stg.fk_submission = :submissionId
                  AND stg.pk_stg_transaction >= :minPk
                  AND stg.pk_stg_transaction < :maxPk
                """)
                .setParameter("submissionId", submissionId)
                .setParameter("minPk", minPk)
                .setParameter("maxPk", maxPk)
                .executeUpdate();
        } catch (Exception e) {
            // Check if it's a "table is full" error - this is critical and should fail
            String errorMessage = e.getMessage() != null ? e.getMessage() : "";
            if (errorMessage.contains("table") && errorMessage.contains("full")) {
                log.error("CRITICAL: Temporary table temp_inserted_transactions is full! This should not happen with InnoDB engine. " +
                         "Consider increasing max_heap_table_size or using a different approach. Error: {}", errorMessage);
                throw new RuntimeException("Temporary table is full - cannot continue processing. " +
                        "This may indicate a configuration issue or extremely large dataset.", e);
            }
            // For other errors, log and continue (might be empty or table doesn't exist yet)
            log.warn("Failed to update temporary table before insert: {}", errorMessage);
        }
    }

    /**
     * Update temporary table with keys of transactions we're about to insert.
     * Wrapper that uses TransactionTemplate (for standalone calls).
     */
    private void updateTemporaryInsertedTransactionsTableBeforeInsert(Long submissionId, Long minPk, Long maxPk) {
        transactionTemplate.execute(status -> {
            updateTemporaryInsertedTransactionsTableBeforeInsertDirect(entityManager, submissionId, minPk, maxPk);
            return null;
        });
    }

    /**
     * Process missing merchants check for one chunk.
     * Uses LEFT JOIN instead of NOT EXISTS to avoid MySQL "multi-table update" error (SQL 1105).
     * Each chunk processes 100k records, should complete in < 5 minutes with proper indexes.
     * 
     * IMPORTANT: Uses TransactionTemplate programmatically because Spring AOP cannot intercept
     * private/protected method calls within the same class.
     */
    /**
     * Process missing merchants check for one chunk.
     * Uses LEFT JOIN instead of NOT EXISTS to avoid MySQL "multi-table update" error (SQL 1105).
     * Each chunk processes 100k records, should complete in < 5 minutes with proper indexes.
     * 
     * IMPORTANT: Uses TransactionTemplate programmatically because Spring AOP cannot intercept
     * private/protected method calls within the same class.
     * 
     * @param submissionId the submission ID
     * @param minPk the minimum PK to process (inclusive)
     * @param maxPk the maximum PK to process (exclusive)
     * @return number of records updated (missing merchants marked as error)
     */
    private int processMissingMerchantsChunk(Long submissionId, Long minPk, Long maxPk) {
        // Use TransactionTemplate to ensure transaction is active
        // Spring AOP cannot intercept private method calls within the same class
        return transactionTemplate.execute(status -> {
            // Set MySQL session timeouts for this chunk's transaction
            try {
                entityManager.createNativeQuery("SET SESSION max_execution_time = 900000").executeUpdate(); // 15 min
                entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = 300").executeUpdate(); // 5 min
            } catch (Exception e) {
                log.debug("Failed to set MySQL session timeouts in chunk (non-critical): {}", e.getMessage());
            }
            
            return entityManager.createNativeQuery("""
                    UPDATE STG_TRANSACTION stg USE INDEX (idx_stg_trx_sub_status_pk_merchant)
                    LEFT JOIN MERCHANT m 
                        ON stg.id_esercente = m.id_esercente 
                        AND stg.id_intermediario = m.id_intermediario
                        AND m.fk_submission = stg.fk_submission
                    SET stg.process_status = 3,
                        stg.error_message = CONCAT('Merchant not found: ', stg.id_esercente, '/', stg.id_intermediario, ' (submission: ', stg.fk_submission, ')')
                    WHERE stg.fk_submission = :submissionId
                      AND stg.process_status IS NULL
                      AND stg.pk_stg_transaction >= :minPk
                      AND stg.pk_stg_transaction < :maxPk
                      AND m.pk_merchant IS NULL
                    """)
                    .setParameter("submissionId", submissionId)
                    .setParameter("minPk", minPk)
                    .setParameter("maxPk", maxPk)
                    .executeUpdate();
        });
    }
    
    /**
     * Get the minimum pending pk_stg_transaction for chunking.
     * OPTIMIZED: Uses ORDER BY + LIMIT 1 instead of MIN() for better index usage.
     * This is much faster on large tables (16M+ records) because it can use
     * the primary key index with a filter on (fk_submission, process_status).
     * 
     * CRITICAL: This query is called once per chunk (potentially 160+ times for 16M records).
     * It MUST complete in < 1 second with proper index idx_stg_trx_submission_status_pk.
     * 
     * OPTIMIZATION: Uses TransactionTemplate to ensure transaction is active for the query.
     * 
     * @param submissionId the submission ID
     * @param minPkExclude optional minimum PK to exclude (to skip already-processed ranges)
     */
    @Override
    public Long getMinPendingPkForChunk(Long submissionId, Long minPkExclude) {
        return getMinPendingPk(submissionId, minPkExclude);
    }

    /**
     * Get the minimum pending pk_stg_transaction for chunking.
     * OPTIMIZED: Uses ORDER BY + LIMIT 1 instead of MIN() for better index usage.
     * This is much faster on large tables (16M+ records) because it can use
     * the primary key index with a filter on (fk_submission, process_status).
     * 
     * CRITICAL: This query is called once per chunk (potentially 160+ times for 16M records).
     * It MUST complete in < 1 second with proper index idx_stg_trx_submission_status_pk.
     * 
     * OPTIMIZATION: Uses TransactionTemplate to ensure transaction is active for the query.
     * 
     * @param submissionId the submission ID
     * @param minPkExclude optional minimum PK to exclude (to skip already-processed ranges when updated=0)
     */
    private Long getMinPendingPk(Long submissionId, Long minPkExclude) {
        try {
            // Use TransactionTemplate to ensure transaction is active
            // This query is read-only but needs a transaction for EntityManager
            return transactionTemplate.execute(status -> {
                try {
                    String queryStr;
                    jakarta.persistence.Query nativeQuery;
                    
                    if (minPkExclude != null) {
                        // Skip records with pk < minPkExclude to prevent infinite loop
                        // NOTE: In new approach, we don't use process_status to track processed records
                        // We use only PK range to advance through chunks
                        queryStr = """
                                SELECT pk_stg_transaction
                                FROM STG_TRANSACTION
                                WHERE fk_submission = :submissionId
                                  AND pk_stg_transaction >= :minPkExclude
                                ORDER BY pk_stg_transaction ASC
                                LIMIT 1
                                """;
                        nativeQuery = entityManager.createNativeQuery(queryStr);
                        nativeQuery.setParameter("submissionId", submissionId);
                        nativeQuery.setParameter("minPkExclude", minPkExclude);
                    } else {
                        queryStr = """
                                SELECT pk_stg_transaction
                                FROM STG_TRANSACTION
                                WHERE fk_submission = :submissionId
                                ORDER BY pk_stg_transaction ASC
                                LIMIT 1
                                """;
                        nativeQuery = entityManager.createNativeQuery(queryStr);
                        nativeQuery.setParameter("submissionId", submissionId);
                    }
                    
                    Object result = nativeQuery.getSingleResult();
                    
                    return result != null ? ((Number) result).longValue() : null;
                } catch (jakarta.persistence.NoResultException e) {
                    // No results found - normal case when all records are processed
                    return null;
                }
            });
        } catch (Exception e) {
            // Check if it's a MySQL query interruption (timeout or kill)
            String exceptionClass = e.getClass().getName();
            if (exceptionClass.contains("MySQLQueryInterruptedException") || 
                (e.getMessage() != null && e.getMessage().contains("Query execution was interrupted"))) {
                // Query was interrupted by MySQL (timeout or kill)
                log.error("getMinPendingPk() query was interrupted for submission {}: {}. " +
                        "This indicates the query is taking too long (>15min) or was killed. " +
                        "Check if index idx_stg_trx_submission_status_pk exists and is being used.",
                        submissionId, e.getMessage());
                throw new RuntimeException("getMinPendingPk() query timeout - check database indexes", e);
            }
            // Other errors (connection issues, etc.)
            log.error("getMinPendingPk() failed for submission {}: {}", submissionId, e.getMessage(), e);
            throw new RuntimeException("getMinPendingPk() failed", e);
        }
    }

    // NOTE: Removed processExistingDuplicatesChunk - we now only check for duplicates
    // within the same submission/staging batch, not across different submissions

    /**
     * Insert new transactions for one chunk using optimized approach.
     * OPTIMIZED: 
     * - GROUP BY limited to chunk (100k records instead of 10M+)
     * - LEFT JOIN with TRANSACTION to avoid duplicates already inserted
     * - No UPDATE on STG (reduces locks)
     * 
     * Process:
     * 1. GROUP BY limited to chunk to find first occurrence of duplicates
     * 2. INNER JOIN with MERCHANT to ensure merchant exists
     * 3. LEFT JOIN with TRANSACTION to avoid duplicates already inserted
     * 4. INSERT only valid transactions (not duplicates, with merchant, not already inserted)
     * 
     * IMPORTANT: Uses TransactionTemplate programmatically because Spring AOP cannot intercept
     * private/protected method calls within the same class.
     * 
     * @param submissionId the submission ID
     * @param minPk the minimum PK to process (inclusive)
     * @param maxPk the maximum PK to process (exclusive)
     * @return number of records inserted
     */
    private int processInsertChunkOptimized(Long submissionId, Long minPk, Long maxPk) {
        return processInsertChunkOptimized(submissionId, minPk, maxPk, false);
    }

    private int processInsertChunkOptimized(Long submissionId, Long minPk, Long maxPk, boolean updateTempTable) {
        log.info("CHUNK-BASED: processInsertChunkOptimized called with submissionId={}, minPk={}, maxPk={}, updateTempTable={}", 
                submissionId, minPk, maxPk, updateTempTable);
        // Use TransactionTemplate with PROPAGATION_REQUIRES_NEW to isolate from outer transactions
        TransactionTemplate isolatedTemplate = new TransactionTemplate(transactionTemplate.getTransactionManager());
        isolatedTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        isolatedTemplate.setTimeout(600); // 10 minutes
        
        return isolatedTemplate.execute(status -> {
            // Set MySQL session timeouts for this chunk's transaction
            try {
                entityManager.createNativeQuery("SET SESSION max_execution_time = 900000").executeUpdate(); // 15 min
                entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = 300").executeUpdate(); // 5 min
            } catch (Exception e) {
                log.debug("Failed to set MySQL session timeouts in chunk (non-critical): {}", e.getMessage());
            }
            
            // NOTE: We populate the persistent table AFTER insert (not before) so subsequent chunks see keys from previous chunks.
            // For the first chunk the table is empty; for later chunks it contains keys from earlier chunks.
            
            // Debug: Count records in this chunk
            try {
                Number totalInChunk = (Number) entityManager.createNativeQuery("""
                    SELECT COUNT(*) 
                    FROM STG_TRANSACTION 
                    WHERE fk_submission = :submissionId
                      AND pk_stg_transaction >= :minPk
                      AND pk_stg_transaction < :maxPk
                    """)
                    .setParameter("submissionId", submissionId)
                    .setParameter("minPk", minPk)
                    .setParameter("maxPk", maxPk)
                    .getSingleResult();
                
                Number withMerchant = (Number) entityManager.createNativeQuery("""
                    SELECT COUNT(*) 
                    FROM STG_TRANSACTION stg
                    INNER JOIN STG_MERCHANT m_stg 
                        ON stg.id_esercente = m_stg.id_esercente 
                        AND stg.id_intermediario = m_stg.id_intermediario
                        AND m_stg.fk_submission = stg.fk_submission
                    WHERE stg.fk_submission = :submissionId
                      AND stg.pk_stg_transaction >= :minPk
                      AND stg.pk_stg_transaction < :maxPk
                    """)
                    .setParameter("submissionId", submissionId)
                    .setParameter("minPk", minPk)
                    .setParameter("maxPk", maxPk)
                    .getSingleResult();
                
                Number uniqueKeys = (Number) entityManager.createNativeQuery("""
                    SELECT COUNT(DISTINCT CONCAT(id_esercente, '|', chiave_banca, '|', id_pos, '|', tipo_ope, '|', dt_ope, '|', divisa_ope, '|', tipo_pag))
                    FROM STG_TRANSACTION
                    WHERE fk_submission = :submissionId
                      AND pk_stg_transaction >= :minPk
                      AND pk_stg_transaction < :maxPk
                    """)
                    .setParameter("submissionId", submissionId)
                    .setParameter("minPk", minPk)
                    .setParameter("maxPk", maxPk)
                    .getSingleResult();
                
                // Count records already in the persistent inserted-transactions table
                Number alreadyInTemp = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) " +
                    "FROM STG_TRANSACTION stg " +
                    "INNER JOIN STG_MERCHANT m_stg " +
                    "    ON stg.id_esercente = m_stg.id_esercente " +
                    "    AND stg.id_intermediario = m_stg.id_intermediario " +
                    "    AND m_stg.fk_submission = stg.fk_submission " +
                    "INNER JOIN ( " +
                    "    SELECT id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag, " +
                    "           MIN(pk_stg_transaction) as first_pk " +
                    "    FROM STG_TRANSACTION " +
                    "    WHERE fk_submission = :submissionId " +
                    "      AND pk_stg_transaction >= :minPk " +
                    "      AND pk_stg_transaction < :maxPk " +
                    "    GROUP BY id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag " +
                    ") first_occurrence " +
                    "    ON stg.pk_stg_transaction = first_occurrence.first_pk " +
                    "INNER JOIN " + INSERTED_TRANSACTIONS_TRACKING_TABLE + " t " +
                    "    ON stg.id_esercente = t.id_esercente " +
                    "    AND stg.chiave_banca = t.chiave_banca " +
                    "    AND stg.id_pos = t.id_pos " +
                    "    AND stg.tipo_ope = t.tipo_ope " +
                    "    AND stg.dt_ope = t.dt_ope " +
                    "    AND stg.divisa_ope = t.divisa_ope " +
                    "    AND stg.tipo_pag = t.tipo_pag " +
                    "WHERE stg.fk_submission = :submissionId " +
                    "  AND stg.pk_stg_transaction >= :minPk " +
                    "  AND stg.pk_stg_transaction < :maxPk"
                )
                    .setParameter("submissionId", submissionId)
                    .setParameter("minPk", minPk)
                    .setParameter("maxPk", maxPk)
                    .getSingleResult();
                
                log.info("CHUNK DEBUG (minPk={}, maxPk={}): total={}, withMerchant={}, uniqueKeys={}, alreadyInTemp={}", 
                        minPk, maxPk, totalInChunk, withMerchant, uniqueKeys, alreadyInTemp);
            } catch (Exception e) {
                log.debug("Failed to get debug counts for chunk: {}", e.getMessage());
            }
            
            try {
                int inserted = entityManager.createNativeQuery(
                    "INSERT INTO TRANSACTION (fk_ingestion, fk_submission, tp_rec, id_intermediario, id_esercente, " +
                    "chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag, imp_ope, tot_ope, created_at) " +
                    "SELECT stg.fk_ingestion, stg.fk_submission, stg.tp_rec, stg.id_intermediario, stg.id_esercente, " +
                    "stg.chiave_banca, stg.id_pos, stg.tipo_ope, stg.dt_ope, stg.divisa_ope, stg.tipo_pag, stg.imp_ope, stg.tot_ope, CURRENT_TIMESTAMP " +
                    "FROM STG_TRANSACTION stg " +
                    "INNER JOIN STG_MERCHANT m_stg " +
                    "ON stg.id_esercente = m_stg.id_esercente " +
                    "AND stg.id_intermediario = m_stg.id_intermediario " +
                    "AND m_stg.fk_submission = stg.fk_submission " +
                    "INNER JOIN INGESTION ing " +
                    "ON stg.fk_ingestion = ing.pk_ingestion " +
                    "INNER JOIN ( " +
                    "SELECT id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag, " +
                    "MIN(pk_stg_transaction) as first_pk " +
                    "FROM STG_TRANSACTION " +
                    "WHERE fk_submission = :submissionId " +
                    "AND pk_stg_transaction >= :minPk " +
                    "AND pk_stg_transaction < :maxPk " +
                    "GROUP BY id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag " +
                    ") first_occurrence " +
                    "ON stg.pk_stg_transaction = first_occurrence.first_pk " +
                    "LEFT JOIN " + INSERTED_TRANSACTIONS_TRACKING_TABLE + " t " +
                    "ON stg.id_esercente = t.id_esercente " +
                    "AND stg.chiave_banca = t.chiave_banca " +
                    "AND stg.id_pos = t.id_pos " +
                    "AND stg.tipo_ope = t.tipo_ope " +
                    "AND stg.dt_ope = t.dt_ope " +
                    "AND stg.divisa_ope = t.divisa_ope " +
                    "AND stg.tipo_pag = t.tipo_pag " +
                    "WHERE stg.fk_submission = :submissionId " +
                    "AND stg.pk_stg_transaction >= :minPk " +
                    "AND stg.pk_stg_transaction < :maxPk " +
                    "AND t.id_esercente IS NULL"
                )
                    .setParameter("submissionId", submissionId)
                    .setParameter("minPk", minPk)
                    .setParameter("maxPk", maxPk)
                    .executeUpdate();

                log.info("CHUNK INSERT RESULT (minPk={}, maxPk={}): {} records inserted", minPk, maxPk, inserted);
                
                if (updateTempTable && inserted > 0) {
                    updateInsertedTransactionsTableAfterInsertDirect(entityManager, INSERTED_TRANSACTIONS_TRACKING_TABLE, submissionId, minPk, maxPk);
                }
                
                return inserted;

            } catch (Exception e) {
                log.error("Error during optimized transaction insertion in chunk (minPk={}, maxPk={}): {}", minPk, maxPk, e.getMessage(), e);
                throw new RuntimeException("Failed to insert transactions in chunk", e);
            }
        });
    }

    /**
     * Insert new transactions for one chunk.
     * OPTIMIZED: Uses PK range instead of LIMIT in subquery (MySQL limitation).
     * 
     * Process:
     * 1. First, mark any remaining missing merchants as errors (process_status = 3)
     * 2. Then, insert only transactions with valid merchants
     * 3. Mark inserted transactions as processed (process_status = 1)
     * 
     * IMPORTANT: Uses TransactionTemplate programmatically because Spring AOP cannot intercept
     * private/protected method calls within the same class.
     * 
     * @param submissionId the submission ID
     * @param minPk the minimum PK to process (inclusive)
     * @param maxPk the maximum PK to process (exclusive)
     * @return number of records inserted
     * @deprecated Use processInsertChunkOptimized instead
     */
    @Deprecated
    private int processInsertChunk(Long submissionId, Long minPk, Long maxPk) {
        // Use TransactionTemplate to ensure transaction is active
        return transactionTemplate.execute(status -> {
            // Set MySQL session timeouts for this chunk's transaction
            try {
                entityManager.createNativeQuery("SET SESSION max_execution_time = 900000").executeUpdate(); // 15 min
                entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = 300").executeUpdate(); // 5 min
            } catch (Exception e) {
                log.debug("Failed to set MySQL session timeouts in chunk (non-critical): {}", e.getMessage());
            }
            
            // Step 1: Mark any remaining missing merchants as errors (process_status = 3)
            // This ensures we catch any records that Step 1 might have missed
            // NOTE: Using LEFT JOIN instead of NOT EXISTS to avoid MySQL "multi-table update" error (SQL 1105)
            int missingMerchantsMarked = entityManager.createNativeQuery("""
                UPDATE STG_TRANSACTION stg USE INDEX (idx_stg_trx_sub_status_pk_merchant)
                LEFT JOIN MERCHANT m 
                    ON stg.id_esercente = m.id_esercente 
                    AND stg.id_intermediario = m.id_intermediario
                    AND m.fk_submission = stg.fk_submission
                SET stg.process_status = 3,
                    stg.error_message = CONCAT('Merchant not found: ', stg.id_esercente, '/', stg.id_intermediario, ' (submission: ', stg.fk_submission, ')')
                WHERE stg.fk_submission = :submissionId
                  AND stg.process_status IS NULL
                  AND stg.pk_stg_transaction >= :minPk
                  AND stg.pk_stg_transaction < :maxPk
                  AND m.pk_merchant IS NULL
                """)
                    .setParameter("submissionId", submissionId)
                    .setParameter("minPk", minPk)
                    .setParameter("maxPk", maxPk)
                    .executeUpdate();
            
            if (missingMerchantsMarked > 0) {
                log.warn("Step 3 - Chunk: Found {} additional missing merchants that Step 1 missed - marked as errors", missingMerchantsMarked);
            }
            
            // Step 2: Insert only transactions with valid merchants (INNER JOIN ensures FK constraint)
            try {
                int inserted = entityManager.createNativeQuery("""
                    INSERT INTO TRANSACTION (fk_ingestion, fk_submission, tp_rec, id_intermediario, id_esercente, 
                                            chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag, imp_ope, tot_ope, created_at)
                    SELECT stg.fk_ingestion, stg.fk_submission, stg.tp_rec, stg.id_intermediario, stg.id_esercente,
                           stg.chiave_banca, stg.id_pos, stg.tipo_ope, stg.dt_ope, stg.divisa_ope, stg.tipo_pag, stg.imp_ope, stg.tot_ope, CURRENT_TIMESTAMP
                    FROM STG_TRANSACTION stg
                    INNER JOIN MERCHANT m 
                        ON stg.id_esercente = m.id_esercente 
                        AND stg.id_intermediario = m.id_intermediario
                        AND m.fk_submission = stg.fk_submission
                    WHERE stg.fk_submission = :submissionId
                      AND stg.process_status IS NULL
                      AND stg.pk_stg_transaction >= :minPk
                      AND stg.pk_stg_transaction < :maxPk
                    """)
                        .setParameter("submissionId", submissionId)
                        .setParameter("minPk", minPk)
                        .setParameter("maxPk", maxPk)
                        .executeUpdate();
                
                // Step 3: Mark inserted transactions as processed (process_status = 1)
                if (inserted > 0) {
                    entityManager.createNativeQuery("""
                        UPDATE STG_TRANSACTION stg
                        INNER JOIN MERCHANT m 
                            ON stg.id_esercente = m.id_esercente 
                            AND stg.id_intermediario = m.id_intermediario
                            AND m.fk_submission = stg.fk_submission
                        SET stg.process_status = 1
                        WHERE stg.fk_submission = :submissionId
                          AND stg.process_status IS NULL
                          AND stg.pk_stg_transaction >= :minPk
                          AND stg.pk_stg_transaction < :maxPk
                        """)
                            .setParameter("submissionId", submissionId)
                            .setParameter("minPk", minPk)
                            .setParameter("maxPk", maxPk)
                            .executeUpdate();
                }

                return inserted;

            } catch (Exception e) {
                log.error("Error during transaction insertion in chunk (minPk={}, maxPk={}): {}", minPk, maxPk, e.getMessage(), e);
                throw new RuntimeException("Failed to insert transactions in chunk", e);
            }
        });
    }

    /**
     * Mark remaining transactions as processed (final cleanup).
     * IMPORTANT: Uses TransactionTemplate programmatically because Spring AOP cannot intercept
     * private method calls within the same class.
     */
    private void processMarkAsProcessed(Long submissionId) {
        transactionTemplate.execute(status -> {
            // Set MySQL session timeouts for this transaction
            try {
                entityManager.createNativeQuery("SET SESSION max_execution_time = 900000").executeUpdate(); // 15 min
                entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = 300").executeUpdate(); // 5 min
            } catch (Exception e) {
                log.debug("Failed to set MySQL session timeouts (non-critical): {}", e.getMessage());
            }
            
            return entityManager.createNativeQuery("""
                    UPDATE STG_TRANSACTION
                    SET process_status = 1
                    WHERE fk_submission = :submissionId
                      AND process_status IS NULL
                    """)
                    .setParameter("submissionId", submissionId)
                    .executeUpdate();
        });
    }

    /**
     * Process all transactions in a single pass (for smaller datasets).
     */
    /**
     * Process transactions in a single pass (for small datasets < 5k records).
     * Uses optimized approach with STG_MERCHANT and temporary table for consistency.
     * IMPORTANT: Uses TransactionTemplate with PROPAGATION_REQUIRES_NEW to isolate from outer transactions.
     */
    private StagingResult processTransactionsSinglePass(Long submissionId) {
        log.info("==========================================");
        log.info("SINGLE-PASS PROCESSING - VERSION WITHOUT temp_inserted_transactions");
        log.info("Starting optimized single-pass processing for submission: {}", submissionId);
        log.info("==========================================");
        long startTime = System.currentTimeMillis();
        
        // Create TransactionTemplate with PROPAGATION_REQUIRES_NEW to isolate from outer transactions
        // This prevents "rollback-only" errors when outer transaction fails
        TransactionTemplate isolatedTransactionTemplate = new TransactionTemplate(transactionTemplate.getTransactionManager());
        isolatedTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        isolatedTransactionTemplate.setTimeout(600); // 10 minutes
        
        // Use isolated TransactionTemplate to ensure transaction is active for all operations
        // NOTE: For single-pass processing, we don't need temp_inserted_transactions
        // because we're processing all records at once (no chunks).
        return isolatedTransactionTemplate.execute(status -> {
            // Set MySQL session timeouts
            try {
                entityManager.createNativeQuery("SET SESSION max_execution_time = 900000").executeUpdate(); // 15 min
                entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = 300").executeUpdate(); // 5 min
            } catch (Exception e) {
                log.debug("Failed to set MySQL session timeouts (non-critical): {}", e.getMessage());
            }
            
            // Step 1: Insert valid transactions (using optimized approach)
            long stepStart = System.currentTimeMillis();
            log.info("Step 1: Inserting valid transactions...");
            log.info("SINGLE-PASS: About to execute INSERT query WITHOUT temp_inserted_transactions table");
            
            // Debug: Check how many transactions have matching merchants in STG_MERCHANT
            try {
                Number countWithMerchant = (Number) entityManager.createNativeQuery("""
                    SELECT COUNT(*)
                    FROM STG_TRANSACTION stg
                    INNER JOIN STG_MERCHANT m_stg 
                        ON stg.id_esercente = m_stg.id_esercente 
                        AND stg.id_intermediario = m_stg.id_intermediario
                        AND m_stg.fk_submission = stg.fk_submission
                    WHERE stg.fk_submission = :submissionId
                    """)
                    .setParameter("submissionId", submissionId)
                    .getSingleResult();
                log.info("Debug: Found {} transactions with matching merchants in STG_MERCHANT for submission {}", 
                        countWithMerchant.intValue(), submissionId);
                
                Number totalStgTransactions = (Number) entityManager.createNativeQuery("""
                    SELECT COUNT(*) FROM STG_TRANSACTION WHERE fk_submission = :submissionId
                    """)
                    .setParameter("submissionId", submissionId)
                    .getSingleResult();
                log.info("Debug: Total transactions in STG_TRANSACTION for submission {}: {}", 
                        submissionId, totalStgTransactions.intValue());
                
                // Debug: Check how many merchants are in STG_MERCHANT for this submission
                Number totalStgMerchants = (Number) entityManager.createNativeQuery("""
                    SELECT COUNT(*) FROM STG_MERCHANT WHERE fk_submission = :submissionId
                    """)
                    .setParameter("submissionId", submissionId)
                    .getSingleResult();
                log.info("Debug: Total merchants in STG_MERCHANT for submission {}: {}", 
                        submissionId, totalStgMerchants.intValue());
                
                // Debug: Check how many transactions would be inserted (with GROUP BY and first_occurrence)
                Number countFirstOccurrence = (Number) entityManager.createNativeQuery("""
                    SELECT COUNT(DISTINCT CONCAT(stg.id_esercente, '|', stg.chiave_banca, '|', stg.id_pos, '|', stg.tipo_ope, '|', stg.dt_ope, '|', stg.divisa_ope))
                    FROM STG_TRANSACTION stg
                    INNER JOIN STG_MERCHANT m_stg 
                        ON stg.id_esercente = m_stg.id_esercente 
                        AND stg.id_intermediario = m_stg.id_intermediario
                        AND m_stg.fk_submission = stg.fk_submission
                    WHERE stg.fk_submission = :submissionId
                    """)
                    .setParameter("submissionId", submissionId)
                    .getSingleResult();
                log.info("Debug: Unique transaction keys (with merchant) for submission {}: {}", 
                        submissionId, countFirstOccurrence.intValue());
            } catch (Exception e) {
                log.warn("Debug query failed: {}", e.getMessage(), e);
            }
            
            // NOTE: For single-pass processing, we don't use temp_inserted_transactions
            // because we're processing all records at once (no chunks).
            // The temp table is only needed for chunk-based processing to avoid duplicates between chunks.
            // NOTE: We don't update process_status - errors are identified via SELECT queries only.
            
            log.info("SINGLE-PASS: Executing INSERT query - NO temp_inserted_transactions table will be used");
            log.info("SINGLE-PASS: Query does NOT contain LEFT JOIN temp_inserted_transactions");
            log.info("SINGLE-PASS: Query does NOT contain AND t.id_esercente IS NULL");
            
            // Step 1: Insert valid transactions (only first occurrences, not duplicates)
            // NOTE: The UNIQUE constraint on TRANSACTION includes fk_submission, so duplicates are prevented
            // at the database level within the same submission, but the same transaction can exist in different submissions.
            int insertedCount = entityManager.createNativeQuery("""
                    INSERT INTO TRANSACTION (fk_ingestion, fk_submission, tp_rec, id_intermediario, id_esercente, 
                                            chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag, imp_ope, tot_ope, created_at)
                    SELECT stg.fk_ingestion, stg.fk_submission, stg.tp_rec, stg.id_intermediario, stg.id_esercente,
                           stg.chiave_banca, stg.id_pos, stg.tipo_ope, stg.dt_ope, stg.divisa_ope, stg.tipo_pag, stg.imp_ope, stg.tot_ope, CURRENT_TIMESTAMP
                    FROM STG_TRANSACTION stg
                    INNER JOIN STG_MERCHANT m_stg 
                        ON stg.id_esercente = m_stg.id_esercente 
                        AND stg.id_intermediario = m_stg.id_intermediario
                        AND m_stg.fk_submission = stg.fk_submission
                    INNER JOIN (
                        SELECT id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag,
                               MIN(pk_stg_transaction) as first_pk
                        FROM STG_TRANSACTION
                        WHERE fk_submission = :submissionId AND process_status IS NULL
                        GROUP BY id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag
                    ) first_occurrence 
                        ON stg.pk_stg_transaction = first_occurrence.first_pk
                    WHERE stg.fk_submission = :submissionId
                      AND stg.process_status IS NULL
                    """)
                    .setParameter("submissionId", submissionId)
                    .executeUpdate();
            
            long stepDuration = System.currentTimeMillis() - stepStart;
            log.info("SINGLE-PASS: INSERT query completed successfully - {} transactions inserted in {}ms", insertedCount, stepDuration);
            log.info("Step 1 completed in {}ms: {} transactions inserted", stepDuration, insertedCount);
            
            // Step 2: Count errors (SELECT only, no UPDATE on STG)
            // NOTE: We count errors but don't mark them with process_status to avoid writing on 20M+ record table
            log.info("Step 2: Counting errors (duplicates and missing merchants) - SELECT only, no UPDATE...");
            long errorStart = System.currentTimeMillis();
            
            // Count duplicates within batch (same file) - SELECT only, no UPDATE
            int batchDuplicates = 0;
            try {
                Number count = (Number) entityManager.createNativeQuery("""
                    SELECT COUNT(*)
                    FROM STG_TRANSACTION stg
                    INNER JOIN STG_MERCHANT m_stg 
                        ON stg.id_esercente = m_stg.id_esercente 
                        AND stg.id_intermediario = m_stg.id_intermediario
                        AND m_stg.fk_submission = stg.fk_submission
                    INNER JOIN (
                        SELECT id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag,
                               MIN(pk_stg_transaction) as first_pk
                        FROM STG_TRANSACTION
                        WHERE fk_submission = :submissionId AND process_status IS NULL
                        GROUP BY id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag
                        HAVING COUNT(*) > 1
                    ) dups 
                        ON stg.id_esercente = dups.id_esercente 
                        AND stg.chiave_banca = dups.chiave_banca
                        AND stg.id_pos = dups.id_pos
                        AND stg.tipo_ope = dups.tipo_ope
                        AND stg.dt_ope = dups.dt_ope
                        AND stg.divisa_ope = dups.divisa_ope
                        AND stg.tipo_pag = dups.tipo_pag
                        AND stg.pk_stg_transaction > dups.first_pk
                    WHERE stg.fk_submission = :submissionId
                      AND stg.process_status IS NULL
                    """)
                    .setParameter("submissionId", submissionId)
                    .getSingleResult();
                batchDuplicates = count != null ? count.intValue() : 0;
                log.info("SINGLE-PASS: Found {} duplicate transactions within batch", batchDuplicates);
            } catch (Exception e) {
                log.error("Error counting duplicate transactions for submission {}: {}", submissionId, e.getMessage(), e);
            }
            
            // Count duplicates already inserted in TRANSACTION - SELECT only, no UPDATE
            int existingDuplicates = 0;
            try {
                Number count = (Number) entityManager.createNativeQuery("""
                    SELECT COUNT(*)
                    FROM STG_TRANSACTION stg
                    INNER JOIN STG_MERCHANT m_stg 
                        ON stg.id_esercente = m_stg.id_esercente 
                        AND stg.id_intermediario = m_stg.id_intermediario
                        AND m_stg.fk_submission = stg.fk_submission
                    INNER JOIN TRANSACTION t 
                        ON stg.id_esercente = t.id_esercente 
                        AND stg.chiave_banca = t.chiave_banca
                        AND stg.id_pos = t.id_pos
                        AND stg.tipo_ope = t.tipo_ope
                        AND stg.dt_ope = t.dt_ope
                        AND stg.divisa_ope = t.divisa_ope
                        AND stg.tipo_pag = t.tipo_pag
                        AND stg.fk_submission = t.fk_submission
                    WHERE stg.fk_submission = :submissionId
                      AND stg.process_status IS NULL
                    """)
                    .setParameter("submissionId", submissionId)
                    .getSingleResult();
                existingDuplicates = count != null ? count.intValue() : 0;
                log.info("SINGLE-PASS: Found {} duplicate transactions already in TRANSACTION", existingDuplicates);
            } catch (Exception e) {
                log.error("Error counting existing duplicate transactions for submission {}: {}", submissionId, e.getMessage(), e);
            }
            
            int totalDuplicates = batchDuplicates + existingDuplicates;
            
            // Count missing merchants (SELECT only)
            int missingMerchants = 0;
            try {
                Number count = (Number) entityManager.createNativeQuery("""
                    SELECT COUNT(*)
                    FROM STG_TRANSACTION stg
                    LEFT JOIN STG_MERCHANT m_stg 
                        ON stg.id_esercente = m_stg.id_esercente 
                        AND stg.id_intermediario = m_stg.id_intermediario
                        AND m_stg.fk_submission = stg.fk_submission
                    WHERE stg.fk_submission = :submissionId
                      AND stg.process_status IS NULL
                      AND m_stg.pk_stg_merchant IS NULL
                    """)
                    .setParameter("submissionId", submissionId)
                    .getSingleResult();
                missingMerchants = count != null ? count.intValue() : 0;
                log.info("SINGLE-PASS: Found {} transactions with missing merchants", missingMerchants);
            } catch (Exception e) {
                log.error("Error counting missing merchant transactions for submission {}: {}", submissionId, e.getMessage(), e);
            }
            
            long errorDuration = System.currentTimeMillis() - errorStart;
            log.info("Step 2 completed in {}ms: {} duplicates ({} within batch + {} already in TRANSACTION), {} missing merchants", 
                    errorDuration, totalDuplicates, batchDuplicates, existingDuplicates, missingMerchants);
            
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("==========================================");
            log.info("SINGLE-PASS PROCESSING COMPLETED - VERSION WITHOUT temp_inserted_transactions");
            log.info("Single-pass processing completed in {}ms: inserted={}, duplicates={}, missingMerchants={}", 
                    elapsed, insertedCount, totalDuplicates, missingMerchants);
            log.info("==========================================");
            
            // NOTE: For single-pass processing, we don't create temp_inserted_transactions,
            // so no need to drop it here
            
            return new StagingResult(insertedCount, totalDuplicates, missingMerchants, 0);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getDuplicateMerchantRawRows(Long submissionId) {
        @SuppressWarnings("unchecked")
        List<String> results = entityManager.createNativeQuery("""
                SELECT raw_row FROM STG_MERCHANT 
                WHERE fk_submission = :submissionId AND process_status = 2
                """)
                .setParameter("submissionId", submissionId)
                .getResultList();
        return results != null ? results : new ArrayList<>();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getDuplicateTransactionRawRows(Long submissionId) {
        @SuppressWarnings("unchecked")
        List<String> results = entityManager.createNativeQuery("""
                SELECT raw_row FROM STG_TRANSACTION 
                WHERE fk_submission = :submissionId AND process_status = 2
                """)
                .setParameter("submissionId", submissionId)
                .getResultList();
        return results != null ? results : new ArrayList<>();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getMissingMerchantTransactionRawRows(Long submissionId) {
        @SuppressWarnings("unchecked")
        List<String> results = entityManager.createNativeQuery("""
                SELECT raw_row FROM STG_TRANSACTION 
                WHERE fk_submission = :submissionId AND process_status = 3
                """)
                .setParameter("submissionId", submissionId)
                .getResultList();
        return results != null ? results : new ArrayList<>();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Object[]> getDuplicateMerchantDetails(Long submissionId) {
        @SuppressWarnings("unchecked")
        List<Object[]> results = entityManager.createNativeQuery("""
                SELECT raw_row, error_message FROM STG_MERCHANT 
                WHERE fk_submission = :submissionId AND process_status = 2
                """)
                .setParameter("submissionId", submissionId)
                .getResultList();
        return results != null ? results : new ArrayList<>();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Object[]> getDuplicateTransactionDetails(Long submissionId) {
        @SuppressWarnings("unchecked")
        List<Object[]> results = entityManager.createNativeQuery("""
                SELECT raw_row, error_message FROM STG_TRANSACTION 
                WHERE fk_submission = :submissionId AND process_status = 2
                """)
                .setParameter("submissionId", submissionId)
                .getResultList();
        return results != null ? results : new ArrayList<>();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Object[]> getMissingMerchantTransactionDetails(Long submissionId) {
        @SuppressWarnings("unchecked")
        List<Object[]> results = entityManager.createNativeQuery("""
                SELECT raw_row, error_message FROM STG_TRANSACTION 
                WHERE fk_submission = :submissionId AND process_status = 3
                """)
                .setParameter("submissionId", submissionId)
                .getResultList();
        return results != null ? results : new ArrayList<>();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Object[]> getMissingMerchantTransactionDetailsChunk(Long submissionId, Long minPk, Long maxPk) {
        @SuppressWarnings("unchecked")
        List<Object[]> results = entityManager.createNativeQuery("""
                SELECT stg.raw_row, 
                       CONCAT('Merchant not found: ', stg.id_esercente, '/', stg.id_intermediario) as error_message,
                       stg.pk_stg_transaction
                FROM STG_TRANSACTION stg
                LEFT JOIN STG_MERCHANT m_stg 
                    ON stg.id_esercente = m_stg.id_esercente 
                    AND stg.id_intermediario = m_stg.id_intermediario
                    AND m_stg.fk_submission = stg.fk_submission
                LEFT JOIN TRANSACTION t
                    ON stg.id_esercente = t.id_esercente 
                    AND stg.chiave_banca = t.chiave_banca
                    AND stg.id_pos = t.id_pos
                    AND stg.tipo_ope = t.tipo_ope
                    AND stg.dt_ope = t.dt_ope
                    AND stg.divisa_ope = t.divisa_ope
                    AND stg.tipo_pag = t.tipo_pag
                    AND stg.fk_submission = t.fk_submission
                WHERE stg.fk_submission = :submissionId
                  AND stg.pk_stg_transaction >= :minPk
                  AND stg.pk_stg_transaction < :maxPk
                  AND m_stg.pk_stg_merchant IS NULL
                  AND t.pk_transaction IS NULL
                ORDER BY stg.pk_stg_transaction ASC
                """)
                .setParameter("submissionId", submissionId)
                .setParameter("minPk", minPk)
                .setParameter("maxPk", maxPk)
                .getResultList();
        return results != null ? results : new ArrayList<>();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Object[]> getDuplicateTransactionDetailsChunk(Long submissionId, Long minPk, Long maxPk) {
        @SuppressWarnings("unchecked")
        List<Object[]> results = entityManager.createNativeQuery("""
                SELECT stg.raw_row, 
                       'Duplicate: transaction appears multiple times in the same file' as error_message,
                       stg.pk_stg_transaction
                FROM STG_TRANSACTION stg
                INNER JOIN STG_MERCHANT m_stg 
                    ON stg.id_esercente = m_stg.id_esercente 
                    AND stg.id_intermediario = m_stg.id_intermediario
                    AND m_stg.fk_submission = stg.fk_submission
                LEFT JOIN TRANSACTION t
                    ON stg.id_esercente = t.id_esercente 
                    AND stg.chiave_banca = t.chiave_banca
                    AND stg.id_pos = t.id_pos
                    AND stg.tipo_ope = t.tipo_ope
                    AND stg.dt_ope = t.dt_ope
                    AND stg.divisa_ope = t.divisa_ope
                    AND stg.tipo_pag = t.tipo_pag
                    AND t.fk_submission = stg.fk_submission
                WHERE stg.fk_submission = :submissionId
                  AND stg.pk_stg_transaction >= :minPk
                  AND stg.pk_stg_transaction < :maxPk
                  AND t.pk_transaction IS NULL
                ORDER BY stg.pk_stg_transaction ASC
                """)
                .setParameter("submissionId", submissionId)
                .setParameter("minPk", minPk)
                .setParameter("maxPk", maxPk)
                .getResultList();
        return results != null ? results : new ArrayList<>();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Object[]> getBatchDuplicateTransactionDetailsChunk(Long submissionId, Long minPk, Long maxPk) {
        @SuppressWarnings("unchecked")
        List<Object[]> results = entityManager.createNativeQuery("""
                SELECT stg.raw_row, 
                       'Duplicate within batch: same transaction appears multiple times in the file' as error_message,
                       stg.pk_stg_transaction
                FROM STG_TRANSACTION stg
                INNER JOIN STG_MERCHANT m_stg 
                    ON stg.id_esercente = m_stg.id_esercente 
                    AND stg.id_intermediario = m_stg.id_intermediario
                    AND m_stg.fk_submission = stg.fk_submission
                INNER JOIN (
                    SELECT id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag,
                           MIN(pk_stg_transaction) as first_pk
                    FROM STG_TRANSACTION
                    WHERE fk_submission = :submissionId 
                      AND pk_stg_transaction >= :minPk
                      AND pk_stg_transaction < :maxPk
                    GROUP BY id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag
                    HAVING COUNT(*) > 1
                ) dups 
                    ON stg.id_esercente = dups.id_esercente 
                    AND stg.chiave_banca = dups.chiave_banca
                    AND stg.id_pos = dups.id_pos
                    AND stg.tipo_ope = dups.tipo_ope
                    AND stg.dt_ope = dups.dt_ope
                    AND stg.divisa_ope = dups.divisa_ope
                    AND stg.tipo_pag = dups.tipo_pag
                    AND stg.pk_stg_transaction > dups.first_pk
                WHERE stg.fk_submission = :submissionId
                  AND stg.pk_stg_transaction >= :minPk
                  AND stg.pk_stg_transaction < :maxPk
                ORDER BY stg.pk_stg_transaction ASC
                """)
                .setParameter("submissionId", submissionId)
                .setParameter("minPk", minPk)
                .setParameter("maxPk", maxPk)
                .getResultList();
        return results != null ? results : new ArrayList<>();
    }

    /**
     * Count duplicate transactions within the same submission (same file).
     * Duplicates are transactions that:
     * - Have a valid merchant (INNER JOIN with STG_MERCHANT succeeds)
     * - Are NOT inserted in TRANSACTION (LEFT JOIN with temp table returns NULL)
     * - Appear multiple times in STG (GROUP BY with COUNT > 1)
     * 
     * @param submissionId the submission ID
     * @return number of duplicate transactions
     */
    private int countDuplicateTransactions(Long submissionId) {
        return transactionTemplate.execute(status -> {
            try {
                Number count = (Number) entityManager.createNativeQuery("""
                    SELECT COUNT(*)
                    FROM STG_TRANSACTION stg
                    INNER JOIN STG_MERCHANT m_stg 
                        ON stg.id_esercente = m_stg.id_esercente 
                        AND stg.id_intermediario = m_stg.id_intermediario
                        AND m_stg.fk_submission = stg.fk_submission
                    INNER JOIN (
                        SELECT id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag,
                               MIN(pk_stg_transaction) as first_pk
                        FROM STG_TRANSACTION
                        WHERE fk_submission = :submissionId
                        GROUP BY id_esercente, chiave_banca, id_pos, tipo_ope, dt_ope, divisa_ope, tipo_pag
                        HAVING COUNT(*) > 1
                    ) dups 
                        ON stg.id_esercente = dups.id_esercente 
                        AND stg.chiave_banca = dups.chiave_banca
                        AND stg.id_pos = dups.id_pos
                        AND stg.tipo_ope = dups.tipo_ope
                        AND stg.dt_ope = dups.dt_ope
                        AND stg.divisa_ope = dups.divisa_ope
                        AND stg.tipo_pag = dups.tipo_pag
                        AND stg.pk_stg_transaction > dups.first_pk
                    WHERE stg.fk_submission = :submissionId
                    """)
                    .setParameter("submissionId", submissionId)
                    .getSingleResult();
                return count != null ? count.intValue() : 0;
            } catch (Exception e) {
                log.error("Error counting duplicate transactions for submission {}: {}", submissionId, e.getMessage(), e);
                return 0;
            }
        });
    }

    /**
     * Count transactions with missing merchants.
     * Missing merchants are transactions that:
     * - Do NOT have a valid merchant (LEFT JOIN with STG_MERCHANT returns NULL)
     * - Are NOT inserted in TRANSACTION (LEFT JOIN with temp table returns NULL)
     * 
     * @param submissionId the submission ID
     * @return number of transactions with missing merchants
     */
    private int countMissingMerchantTransactions(Long submissionId) {
        return transactionTemplate.execute(status -> {
            try {
                Number count = (Number) entityManager.createNativeQuery("""
                    SELECT COUNT(*)
                    FROM STG_TRANSACTION stg
                    LEFT JOIN STG_MERCHANT m_stg 
                        ON stg.id_esercente = m_stg.id_esercente 
                        AND stg.id_intermediario = m_stg.id_intermediario
                        AND m_stg.fk_submission = stg.fk_submission
                    WHERE stg.fk_submission = :submissionId
                      AND m_stg.pk_stg_merchant IS NULL
                    """)
                    .setParameter("submissionId", submissionId)
                    .getSingleResult();
                return count != null ? count.intValue() : 0;
            } catch (Exception e) {
                log.error("Error counting missing merchant transactions for submission {}: {}", submissionId, e.getMessage(), e);
                return 0;
            }
        });
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("'", "''").replace("\\", "\\\\");
    }

    private String truncate(String s, int maxLength) {
        if (s == null) return "";
        return s.length() > maxLength ? s.substring(0, maxLength) : s;
    }

    @Override
    @Transactional(readOnly = true)
    public long[] countPendingRecords(Long submissionId) {
        Long pendingTransactions = ((Number) entityManager.createNativeQuery("""
                SELECT COUNT(*) 
                FROM STG_TRANSACTION 
                WHERE fk_submission = :submissionId AND process_status IS NULL
                """)
                .setParameter("submissionId", submissionId)
                .getSingleResult()).longValue();
        
        Long pendingMerchants = ((Number) entityManager.createNativeQuery("""
                SELECT COUNT(*) 
                FROM STG_MERCHANT 
                WHERE fk_submission = :submissionId AND process_status IS NULL
                """)
                .setParameter("submissionId", submissionId)
                .getSingleResult()).longValue();
        
        return new long[]{pendingTransactions, pendingMerchants};
    }

    @Override
    @Transactional
    public long[] resetInsertedRecordsToPending(Long submissionId) {
        log.info("Resetting inserted records (process_status = 1) to pending (NULL) for submission: {}", submissionId);
        
        // Reset STG_TRANSACTION: status 1 -> NULL (erano inseriti, poi cancellati dal cleanup)
        int resetTransactions = entityManager.createNativeQuery("""
                UPDATE STG_TRANSACTION 
                SET process_status = NULL, error_message = NULL
                WHERE fk_submission = :submissionId AND process_status = 1
                """)
                .setParameter("submissionId", submissionId)
                .executeUpdate();
        
        // Reset STG_MERCHANT: status 1 -> NULL (erano inseriti, poi cancellati dal cleanup)
        int resetMerchants = entityManager.createNativeQuery("""
                UPDATE STG_MERCHANT 
                SET process_status = NULL, error_message = NULL
                WHERE fk_submission = :submissionId AND process_status = 1
                """)
                .setParameter("submissionId", submissionId)
                .executeUpdate();
        
        log.info("Reset {} transactions and {} merchants from status=1 to pending for submission {}", 
                resetTransactions, resetMerchants, submissionId);
        
        return new long[]{resetTransactions, resetMerchants};
    }
}
