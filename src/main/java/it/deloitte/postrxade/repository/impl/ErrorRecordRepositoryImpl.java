package it.deloitte.postrxade.repository.impl;

import it.deloitte.postrxade.entity.ErrorCause;
import it.deloitte.postrxade.entity.ErrorRecord;
import it.deloitte.postrxade.repository.ErrorCauseRepository;
import it.deloitte.postrxade.repository.ErrorRecordRepositoryCustom;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Transactional(timeout = 120) // 2 minutes timeout to prevent lock wait timeout
public class ErrorRecordRepositoryImpl implements ErrorRecordRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;
    @Autowired
    private ErrorCauseRepository errorCauseRepository;
    
    // Counter for unique temporary table names
    private static final AtomicLong tempTableCounter = new AtomicLong(0);

    @Override
    public void bulkInsert(List<ErrorRecord> errorRecords) {
        if (errorRecords == null || errorRecords.isEmpty()) {
            return;
        }

        String sql = buildInsertSql(errorRecords.size());
        Query query = entityManager.createNativeQuery(sql);

        for (int i = 0; i < errorRecords.size(); i++) {
            setParams(query, i, errorRecords.get(i));
        }

        query.executeUpdate();
    }

    private String buildInsertSql(int batchSize) {
        StringJoiner values = new StringJoiner(", ");
        for (int i = 0; i < batchSize; i++) {
            values.add("( :fk_ingestion_" + i
                    + ", :fk_submission_" + i
                    + ", :raw_row_" + i
                    + ", CURRENT_TIMESTAMP)");
        }

        return String.format(
                "INSERT INTO ERROR_RECORD (fk_ingestion, fk_submission, raw_row, created_at) VALUES %s",
                values);
    }

    private void setParams(Query query, int index, ErrorRecord errorRecord) {
        query.setParameter("fk_ingestion_" + index,
                errorRecord.getIngestion() != null ? errorRecord.getIngestion().getId() : null);
        query.setParameter("fk_submission_" + index,
                errorRecord.getSubmission() != null ? errorRecord.getSubmission().getId() : null);
        query.setParameter("raw_row_" + index, errorRecord.getRawRow());
    }

    @Override
    @Transactional
    public void bulkInsertRecordsWithCauses(List<ErrorRecord> records, Long ingestionId) {
        if (records == null || records.isEmpty()) {
            return;
        }
        
        // OPTIMIZED: Process in smaller batches for memory efficiency
        // Uses temporary table with JOIN instead of IN clause for much better performance
        // Batch size of 1000 balances memory usage and performance
        final int BATCH_SIZE = 1000;
        
        for (int i = 0; i < records.size(); i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, records.size());
            List<ErrorRecord> batch = records.subList(i, endIndex);
            
            // Insert ErrorRecords for this batch
            bulkInsert(batch);
            
            // Fetch IDs for this batch (smaller IN clause = faster)
            Map<String, ErrorRecord> recordIds = fetchInsertedErrorRecords(ingestionId, batch);
            
            // Collect ErrorCauses for this batch
            List<ErrorCause> causes = new ArrayList<>();
            for (ErrorRecord record : batch) {
                ErrorRecord errorRecord = recordIds.get(record.getRawRow());
                if (errorRecord != null && record.getErrorCauses() != null) {
                    for (ErrorCause cause : record.getErrorCauses()) {
                        cause.setErrorRecord(errorRecord);
                        causes.add(cause);
                    }
                }
            }
            
            // Insert ErrorCauses for this batch
            if (!causes.isEmpty()) {
                errorCauseRepository.bulkInsert(causes);
            }
            
            // Flush to ensure data is persisted before next batch
            entityManager.flush();
            entityManager.clear();
        }
    }

    /**
     * Fetch inserted ErrorRecord IDs using a temporary table for better performance.
     * Uses a temporary table with JOIN instead of IN clause for much faster queries
     * when dealing with large batches (1000+ records).
     * 
     * @param ingestionId the ingestion ID
     * @param records the list of ErrorRecords to fetch IDs for
     * @return map of raw_row -> ErrorRecord entity reference
     */
    public Map<String, ErrorRecord> fetchInsertedErrorRecords(
            Long ingestionId,
            List<ErrorRecord> records) {

        if (records == null || records.isEmpty()) {
            return new HashMap<>();
        }

        // Generate unique temporary table name to avoid conflicts
        String tempTableName = "temp_error_records_" + System.currentTimeMillis() + "_" + tempTableCounter.incrementAndGet();
        
        try {
            // Step 1: Create temporary table in memory (fast)
            entityManager.createNativeQuery(String.format("""
                CREATE TEMPORARY TABLE %s (
                    raw_row VARCHAR(250) PRIMARY KEY
                ) ENGINE=MEMORY
                """, tempTableName)).executeUpdate();

            // Step 2: Insert raw_row values into temporary table in batches
            // This avoids parameter limit issues with very large batches
            List<String> rawRows = records.stream()
                    .map(ErrorRecord::getRawRow)
                    .filter(rawRow -> rawRow != null && !rawRow.isEmpty())
                    .toList();

            if (rawRows.isEmpty()) {
                return new HashMap<>();
            }

            // Insert in smaller sub-batches to avoid parameter limits
            final int TEMP_INSERT_BATCH_SIZE = 500;
            for (int i = 0; i < rawRows.size(); i += TEMP_INSERT_BATCH_SIZE) {
                int endIndex = Math.min(i + TEMP_INSERT_BATCH_SIZE, rawRows.size());
                List<String> batch = rawRows.subList(i, endIndex);

                StringJoiner values = new StringJoiner(", ");
                for (int j = 0; j < batch.size(); j++) {
                    values.add("(:raw_row_" + j + ")");
                }

                Query insertQuery = entityManager.createNativeQuery(String.format(
                    "INSERT INTO %s (raw_row) VALUES %s", tempTableName, values));

                for (int j = 0; j < batch.size(); j++) {
                    insertQuery.setParameter("raw_row_" + j, batch.get(j));
                }
                insertQuery.executeUpdate();
            }

            // Step 3: Use JOIN instead of IN clause - much faster!
            // The index on (fk_ingestion, raw_row) will be used for the JOIN
            Query query = entityManager.createNativeQuery(String.format("""
                SELECT er.pk_error_record, er.raw_row
                FROM ERROR_RECORD er
                INNER JOIN %s temp ON er.raw_row = temp.raw_row
                WHERE er.fk_ingestion = :ingestionId
                """, tempTableName));

            query.setParameter("ingestionId", ingestionId);

            @SuppressWarnings("unchecked")
            List<Object[]> result = query.getResultList();

            Map<String, ErrorRecord> map = new HashMap<>(result.size());

            for (Object[] row : result) {
                // pk_error_record is BIGINT (primary key), but be defensive with JDBC return types
                Long id = (row[0] instanceof Number) ? ((Number) row[0]).longValue() : null;
                if (id == null) {
                    throw new IllegalStateException("Expected Number for pk_error_record, got: " + 
                        (row[0] != null ? row[0].getClass().getName() : "null"));
                }
                // Be defensive: depending on column type/driver this can be String, Clob, etc.
                String rawRow = String.valueOf(row[1]);

                ErrorRecord recordRef =
                        entityManager.getReference(ErrorRecord.class, id);

                map.put(rawRow, recordRef);
            }

            return map;

        } catch (Exception e) {
            // Log error but don't fail silently - rethrow
            throw new RuntimeException("Failed to fetch inserted ErrorRecord IDs using temporary table", e);
        } finally {
            // Step 4: Clean up temporary table
            // Note: TEMPORARY tables are automatically dropped when the connection closes,
            // but we explicitly drop it to free memory immediately
            try {
                entityManager.createNativeQuery("DROP TEMPORARY TABLE IF EXISTS " + tempTableName).executeUpdate();
            } catch (Exception e) {
                // Ignore cleanup errors - table will be dropped automatically on connection close
            }
        }
    }


}

