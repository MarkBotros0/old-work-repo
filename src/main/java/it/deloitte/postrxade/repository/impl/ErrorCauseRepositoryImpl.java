package it.deloitte.postrxade.repository.impl;

import it.deloitte.postrxade.dto.ErrorTypeCountDTO;
import it.deloitte.postrxade.entity.ErrorCause;
import it.deloitte.postrxade.repository.ErrorCauseRepositoryCustom;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Transactional(timeout = 120) // 2 minutes timeout to prevent lock wait timeout
// Nota: questa classe NON deve essere annotata con @Repository o @Component
// Spring Data JPA la userà automaticamente come implementazione di ErrorCauseRepositoryCustom
// seguendo la convenzione di naming (ErrorCauseRepository + Impl)
public class ErrorCauseRepositoryImpl implements ErrorCauseRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void bulkInsert(List<ErrorCause> errorCauses) {
        if (errorCauses == null || errorCauses.isEmpty()) {
            return;
        }

        String sql = buildInsertSql(errorCauses.size());
        Query query = entityManager.createNativeQuery(sql);

        for (int i = 0; i < errorCauses.size(); i++) {
            setParams(query, i, errorCauses.get(i));
        }

        query.executeUpdate();
    }

    private String buildInsertSql(int batchSize) {
        StringJoiner values = new StringJoiner(", ");

        for (int i = 0; i < batchSize; i++) {
            values.add("(" +
                    ":fk_error_record_" + i + ", " +
                    ":fk_error_type_" + i + ", " +
                    ":fk_submission_" + i + ", " +
                    ":error_message_" + i +
                    ")");
        }

        return """
        INSERT INTO ERROR_CAUSE
        (fk_error_record, fk_error_type, fk_submission, error_message)
        VALUES %s
        """.formatted(values.toString());
    }


    private void setParams(Query query, int index, ErrorCause cause) {
        query.setParameter(
                "fk_error_record_" + index,
                cause.getErrorRecord().getId()
        );
        query.setParameter(
                "fk_error_type_" + index,
                cause.getErrorType().getId()
        );
        query.setParameter(
                "fk_submission_" + index,
                cause.getSubmission().getId()
        );
        query.setParameter(
                "error_message_" + index,
                cause.getErrorMessage()
        );
    }

    @Override
    @Transactional(readOnly = true, timeout = 180) // 3 minutes for read-only aggregation queries
    public long countDistinctErrorRecordsBySubmissionIdAndSeverityNative(Long submissionId, Integer severity) {
        long startTime = System.currentTimeMillis();
        log.debug("Starting countDistinctErrorRecordsBySubmissionIdAndSeverityNative for submissionId={}, severity={}", submissionId, severity);
        
        // OPTIMIZED: Count directly on ERROR_CAUSE using fk_submission and fk_error_record.
        // This avoids joining the (potentially huge) ERROR_RECORD table for submission-level counts.
        // With an index on ERROR_CAUSE(fk_submission, fk_error_type, fk_error_record) MySQL can
        // filter by submission quickly and compute DISTINCT on the indexed fk_error_record.
        String nativeSql = """
            SELECT COUNT(DISTINCT ec.fk_error_record)
            FROM ERROR_CAUSE ec
            INNER JOIN ERROR_TYPE et ON et.pk_error_type = ec.fk_error_type
            WHERE ec.fk_submission = :submissionId
              AND et.serverity_level = :severity
            """;
        
        Query query = entityManager.createNativeQuery(nativeSql);
        query.setParameter("submissionId", submissionId);
        query.setParameter("severity", severity);
        
        Object result = query.getSingleResult();
        long count = result instanceof Number ? ((Number) result).longValue() : 0L;
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("countDistinctErrorRecordsBySubmissionIdAndSeverityNative completed in {}ms: submissionId={}, severity={}, count={}", 
                duration, submissionId, severity, count);
        
        return count;
    }

    @Override
    @Transactional(readOnly = true, timeout = 180) // 3 minutes for read-only aggregation queries
    public List<ErrorTypeCountDTO> findErrorTypeCountsBySubmissionIdAndSeverityNative(Long submissionId, Integer severity) {
        long startTime = System.currentTimeMillis();
        log.debug("Starting findErrorTypeCountsBySubmissionIdAndSeverityNative for submissionId={}, severity={}", submissionId, severity);
        
        // OPTIMIZED: Group directly from ERROR_CAUSE filtered by submission and severity.
        // We count distinct fk_error_record per error type (not number of causes) which matches the UI semantics.
        String nativeSql = """
            SELECT 
                et.pk_error_type AS errorTypeId,
                et.name AS errorTypeName,
                et.error_code AS errorCode,
                COUNT(DISTINCT ec.fk_error_record) AS count
            FROM ERROR_CAUSE ec
            INNER JOIN ERROR_TYPE et ON et.pk_error_type = ec.fk_error_type
            WHERE ec.fk_submission = :submissionId
              AND et.serverity_level = :severity
            GROUP BY et.pk_error_type, et.name, et.error_code
            ORDER BY COUNT(DISTINCT ec.fk_error_record) DESC
            """;
        
        Query query = entityManager.createNativeQuery(nativeSql);
        query.setParameter("submissionId", submissionId);
        query.setParameter("severity", severity);
        
        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();
        
        List<ErrorTypeCountDTO> dtos = new ArrayList<>(results.size());
        for (Object[] row : results) {
            Long errorTypeId = ((Number) row[0]).longValue();
            String errorTypeName = (String) row[1];
            String errorCode = (String) row[2];
            Long count = ((Number) row[3]).longValue();
            
            dtos.add(new ErrorTypeCountDTO(errorTypeId, errorTypeName, errorCode, count));
        }
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("findErrorTypeCountsBySubmissionIdAndSeverityNative completed in {}ms: submissionId={}, severity={}, resultCount={}", 
                duration, submissionId, severity, dtos.size());
        
        return dtos;
    }

}

