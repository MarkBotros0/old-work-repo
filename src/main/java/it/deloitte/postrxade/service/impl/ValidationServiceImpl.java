package it.deloitte.postrxade.service.impl;

import it.deloitte.postrxade.dto.*;
import it.deloitte.postrxade.entity.*;
import it.deloitte.postrxade.enums.IngestionTypeEnum;
import it.deloitte.postrxade.enums.SeverityEnum;
import it.deloitte.postrxade.enums.SubmissionStatusEnum;
import it.deloitte.postrxade.exception.NotFoundRecordException;
import it.deloitte.postrxade.repository.*;
import it.deloitte.postrxade.service.*;
import it.deloitte.postrxade.tenant.TenantConfiguration;
import it.deloitte.postrxade.tenant.TenantContext;
import it.deloitte.postrxade.utils.AuditLogger;
import it.deloitte.postrxade.utils.ReportJob;
import lombok.extern.slf4j.Slf4j;
import ma.glasnost.orika.MapperFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;


/**
 * Service Implementation for managing validation logic, data aggregation, and report generation.
 * <p>
 * This service acts as the bridge between the Controller and the repositories. It handles:
 * <ul>
 * <li>Retrieving and validating Fiscal Years and Periods.</li>
 * <li>Aggregating error and warning statistics for the dashboard.</li>
 * <li>Filtering specific error rows for detail views.</li>
 * <li>Asynchronously generating Excel exports to avoid HTTP timeouts.</li>
 * </ul>
 */
@Service
@Slf4j
public class ValidationServiceImpl implements ValidationService {
    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private ErrorRecordRepository errorRecordRepository;

    @Autowired
    private ErrorCauseRepository errorCauseRepository;

    @Autowired
    private ErrorTypeRepository errorTypeRepository;

    @Autowired
    private ErrorTypeService errorTypeService;

    @Autowired
    private PeriodRepository periodRepository;

    @Autowired
    private ObligationRepository obligationRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private IngestionRepository ingestionRepository;

    @Autowired
    private ObligationService obligationService;

    @Autowired
    private AuditLogger appLogger;

    @Autowired
    private UserService userService;

    @Autowired
    @Qualifier("alternativeMapperFacade")
    private MapperFacade alternativeMapperFacade;

    @Autowired
    private S3Service s3Service;

    @org.springframework.beans.factory.annotation.Value("${aws.s3.output-folder:OUTPUT}")
    private String s3OutputFolder;

    private static final String NEXI_INTERMEDIARIO_MARKER = "32875";

    /**
     * Self-injection per permettere a Spring di intercettare le chiamate @Async.
     * Questo è necessario perché @Async funziona solo quando il metodo viene chiamato
     * da un proxy Spring, non quando viene chiamato direttamente sullo stesso oggetto.
     * Usiamo ApplicationContext per ottenere il proxy corretto.
     */
    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;
    
    private ValidationService getSelf() {
        return applicationContext.getBean(ValidationService.class);
    }

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * In-memory storage for tracking asynchronous report jobs.
     * Uses ConcurrentHashMap to ensure thread safety during async access.
     */
    public static final Map<String, ReportJob> jobStorage = new ConcurrentHashMap<>();

    /**
     * Retrieves the validation summary (counts of errors/warnings) for a specific FY and Period.
     * <p>
     * Logic Flow:
     * 1. Validate existence of Period and Obligation (Return empty summary if missing).
     * 2. Delegate validity checks to {@link ObligationService} (Throws exception if multiple active submissions exist).
     * 3. Fetch the active submission.
     * 4. Aggregate statistics.
     *
     * @param fy         The Fiscal Year.
     * @param periodName The month name (e.g., "January").
     * @return A {@link ValidationPageDTO} containing the calculated statistics.
     * @throws NotFoundRecordException if the obligation state is invalid (e.g., multiple active submissions).
     */
    @Override
    @Transactional(readOnly = true)
    public ValidationPageDTO getValidationSummary(Integer fy, String periodName) throws NotFoundRecordException {

        Obbligation obligation;

        Period period = periodRepository.findByName(periodName).orElse(null);

        obligation = obligationRepository.findByFiscalYearAndPeriod(fy, period).orElseThrow(
                () -> new NotFoundRecordException("No Obligation found for this period"));

        return buildValidationFromSubmissions(obligation);
    }


    /**
     * Retrieves a detailed list of specific data quality issues (Errors or Warnings).
     *
     * @param fy       The Fiscal Year.
     * @param period   The month name.
     * @param category The filter type ("errors" or other).
     * @return A list of {@link DataQualityIssueDTO}.
     * @throws NotFoundRecordException if underlying data retrieval fails.
     */
    @Override
    @Transactional(readOnly = true)
    public List<DataQualityIssueDTO> getDataQualityIssueDTOlIST(
            Integer fy, String period, String category, String type) throws NotFoundRecordException {

        List<Submission> submissions = obligationService.getAllSubmissionByFyAndPeriod(fy, period);
        Submission activeSubmission = submissions.stream()
                .filter(s -> {
                            return !s.getCurrentSubmissionStatus().getName().equals(SubmissionStatusEnum.ERROR.getDbName())
                                    && !s.getCurrentSubmissionStatus().getName().equals(SubmissionStatusEnum.CANCELLED.getDbName())
                                    && !s.getCurrentSubmissionStatus().getName().equals(SubmissionStatusEnum.REJECTED.getDbName());
                        }
                ).findFirst().orElse(null);

        List<DataQualityIssueDTO> resultList = new ArrayList<>();

        if (activeSubmission == null) return resultList;

        List<ErrorType> errorTypes;
        if (type.equals("ALL")) {
            errorTypes = errorTypeRepository.findBySubmissionIdAndSeverity(
                    activeSubmission.getId(), Integer.parseInt(category));
        } else {
            ErrorType errorType = errorTypeRepository.findByName(type).orElse(null);
            if (errorType == null) return Collections.emptyList();
            errorTypes = List.of(errorType);
        }

        for (ErrorType errorType : errorTypes) {
            List<ErrorRecord> errors = errorRecordRepository.findRecordsByErrorCodeAndSubmissionId(
                    activeSubmission.getId(), errorType.getErrorCode());

            int errorCauses = errorCauseRepository.findBySubmissionIdAndErrorCode(
                    activeSubmission.getId(), errorType.getErrorCode());

            DataQualityIssueDTO dto = DataQualityIssueDTO.builder()
                    .errorLevel(errorType.getSeverityLevel() == 1 ? "Warning" : "Error")
                    .errorCode(errorType.getErrorCode())
                    .errorName(errorType.getName())
                    .description(errorType.getDescription())
                    .errorsCount(errorCauses)
                    .examples(errors.stream().map(ErrorRecord::getRawRow).limit(5).toList())
                    .build();

            resultList.add(dto);
        }
        return resultList;
    }

// ==================================================================================
// ASYNC REPORT MANAGEMENT
// ==================================================================================

    /**
     * Initializes the report job and starts the async process.
     *
     * @return The Job ID.
     */
    public String startReportJob(Integer fy, String period) {
        String jobId = UUID.randomUUID().toString();

        ReportJob job = ReportJob.builder()
                .jobId(jobId)
                .status("ACCEPTED")
                .build();

        jobStorage.put(jobId, job);

        log.info("Starting async report generation job: jobId={}, fy={}, period={}", jobId, fy, period);

        // Trigger the actual async processing using self-injection
        // Questo permette a Spring di intercettare la chiamata e eseguirla in modo asincrono
        getSelf().generateExcelDataAsync(jobId, fy, period);

        log.info("Async report generation job started: jobId={}", jobId);

        return jobId;
    }

    /**
     * Checks the status of a job.
     */
    public String getJobStatus(String jobId) {
        ReportJob job = jobStorage.get(jobId);
        if (job == null) {
            log.debug("Job not found in storage: jobId={}", jobId);
            return "NOT_FOUND";
        }
        String status = job.getStatus();
        log.debug("Job status retrieved: jobId={}, status={}, hasFileUrl={}", 
                jobId, status, job.getFileUrl() != null);
        return status;
    }

    /**
     * Retrieves the result (S3 file URL), logs the action, and cleans up the job.
     */
    public String getJobResult(String jobId) {
        ReportJob job = jobStorage.get(jobId);

        if (job == null || !"COMPLETED".equals(job.getStatus())) {
            throw new IllegalStateException("Job not found or not completed");
        }

        String fileUrl = job.getFileUrl();
        
        // Clean up memory
        jobStorage.remove(jobId);

        // Perform Audit Logging
        logReportDownload();

        return fileUrl;
    }

    private void logReportDownload() {
        try {
            appLogger.save(Log.builder()
                    .message(AuditLogger.DOWNLOAD_REPORT) // Assuming static constant access
                    .timestamp(Instant.now())
                    .updater(alternativeMapperFacade.map(userService.getCurrentUser(), User.class))
                    .build());
        } catch (Exception e) {
            // Log without user details if user validation fails
            appLogger.save(Log.builder()
                    .message(AuditLogger.DOWNLOAD_REPORT)
                    .timestamp(Instant.now())
                    .build());
        }
    }

    /**
     * Asynchronously generates the Excel report data.
     * <p>
     * This method is annotated with {@link Async} to run on a separate thread.
     * It updates the {@link ReportJob} status in {@link #jobStorage} as it progresses.
     * <p>
     * OPTIMIZED: Uses native SQL query to fetch data in batches instead of loading
     * all ErrorCause entities into memory. This prevents OOM errors with large datasets.
     *
     * @param jobId  The unique ID of the job to update.
     * @param fy     The Fiscal Year.
     * @param period The month name.
     */
    @Async
    public void generateExcelDataAsync(String jobId, Integer fy, String period) {
        long asyncStartTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        log.info("Async method invoked: jobId={}, fy={}, period={}, thread={}, timestamp={}", 
                jobId, fy, period, threadName, asyncStartTime);
        
        long startTime = System.currentTimeMillis();
        log.info("Starting async Excel generation: jobId={}, fy={}, period={}, thread={}", 
                jobId, fy, period, threadName);
        
        try {
            // 1. Update status to processing
            ReportJob job = jobStorage.get(jobId);
            if (job == null) {
                log.warn("Job not found in storage: jobId={}", jobId);
                return; // Safety check
            }
            job.setStatus("PROCESSING");
            // Re-save to ensure thread safety
            jobStorage.put(jobId, job);
            log.info("Job status updated to PROCESSING: jobId={}, thread={}, delayFromInvocation={}ms", 
                    jobId, threadName, System.currentTimeMillis() - asyncStartTime);

            // 2. Fetch Data and generate CSV file
            Optional<Submission> submission = obligationService.getActiveSubmissionForStats(fy, period);
            String fileUrl = null;

            if (submission.isPresent()) {
                Long submissionId = submission.get().getId();
                // batch_id in Excel = Submission.batchId (identificativo del batch di ingestion)
                String batchId = submission.get().getBatchId();
                log.info("Processing submission: submissionId={}, batchId={}", submissionId, batchId);

                // OPTIMIZED: Single query with all necessary JOINs
                // error_description = ec.error_message (messaggio specifico della causa), non et.description (descrizione generica del tipo)
                // batch_id (usato in Excel) = Submission.batchId della submission attiva per il periodo
                String nativeSql = """
                    SELECT 
                        er.pk_error_record,
                        er.raw_row,
                        et.serverity_level,
                        et.name AS error_name,
                        ec.error_message AS error_description,
                        i.ingested_at,
                        it.name AS ingestion_type_name
                    FROM ERROR_CAUSE ec
                    INNER JOIN ERROR_RECORD er ON ec.fk_error_record = er.pk_error_record
                    INNER JOIN ERROR_TYPE et ON ec.fk_error_type = et.pk_error_type
                    INNER JOIN INGESTION i ON er.fk_ingestion = i.pk_ingestion
                    INNER JOIN INGESTION_TYPE it ON i.fk_ingestion_type = it.pk_ingestion_type
                    WHERE er.fk_submission = :submissionId
                        AND (:lastErrorRecordId IS NULL OR er.pk_error_record > :lastErrorRecordId)
                    ORDER BY er.pk_error_record ASC, et.serverity_level DESC
                    LIMIT :batchSize
                    """;

                // Generate Excel file using streaming to avoid memory issues
                // SXSSFWorkbook writes to temporary file and streams data
                String fileName = String.format("validation_report_%d_%s_%s.xlsx", 
                        fy, period, jobId.substring(0, 8));
                // Multi-tenant: path S3 per tenant (AMEX/OUTPUT o NEXI/OUTPUT), allineato a EcsTaskServiceImpl
                String resolvedTenant = TenantConfiguration.resolveTenantAlias(TenantContext.getTenantId());
                boolean isNexi = "nexi".equalsIgnoreCase(resolvedTenant);
                String outputFolderNorm = (resolvedTenant != null && !resolvedTenant.isBlank())
                        ? resolvedTenant.toUpperCase() + "/OUTPUT"
                        : (s3OutputFolder == null ? "OUTPUT" : s3OutputFolder.replaceAll("/+$", ""));
                String s3Key = String.format("%s/VALIDATION_REPORTS/%s/%s", 
                        outputFolderNorm, jobId, fileName);
                
                log.info("Generating Excel file: s3Key={}, jobId={}, tenant={}", s3Key, jobId, resolvedTenant);
                
                // Process in larger batches to reduce database round-trips
                final int BATCH_SIZE = 150000; // Increased to 150k to further reduce query overhead
                Long lastErrorRecordId = null;
                int totalProcessed = 0;
                int totalErrors = 0;
                int totalWarnings = 0;
                boolean hasMore = true;
                
                // Create Excel workbook with streaming support for large files
                // SXSSFWorkbook keeps only a limited number of rows in memory (windowSize)
                // and writes the rest to a temporary file, preventing memory issues
                final int WINDOW_SIZE = 1000; // Keep only last 1000 rows in memory
                File tempFile = File.createTempFile("validation_report_" + jobId, ".xlsx");
                tempFile.deleteOnExit();
                
                try (SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW_SIZE);
                     FileOutputStream fos = new FileOutputStream(tempFile)) {
                    
                    log.info("Using SXSSFWorkbook with windowSize={} for jobId={}, tempFile={}", 
                            WINDOW_SIZE, jobId, tempFile.getAbsolutePath());
                    
                    // Excel has a limit of 1,048,576 rows per sheet (including header)
                    // We use 1,048,575 as max data rows (excluding header row 0)
                    final int MAX_EXCEL_ROWS_PER_SHEET = 1048575;
                    
                    // Create header style (reuse for performance)
                    CellStyle headerStyle = workbook.createCellStyle();
                    Font headerFont = workbook.createFont();
                    headerFont.setBold(true);
                    headerStyle.setFont(headerFont);

                    String[] headers = isNexi
                            ? new String[]{"raw_record", "error_level", "error_name", "error_description", "batch_id", "timestamp", "file_type", "country"}
                            : new String[]{"raw_record", "error_level", "error_name", "error_description", "batch_id", "timestamp", "file_type"};

                    // Helper method to create header row
                    java.util.function.Consumer<Sheet> createHeader = (sheet) -> {
                        Row headerRow = sheet.createRow(0);
                        for (int i = 0; i < headers.length; i++) {
                            Cell cell = headerRow.createCell(i);
                            cell.setCellValue(headers[i]);
                            cell.setCellStyle(headerStyle);
                        }
                    };

                    // Create initial sheets
                    Sheet errorsSheet = workbook.createSheet("Errors");
                    Sheet warningsSheet = workbook.createSheet("Warnings");
                    createHeader.accept(errorsSheet);
                    createHeader.accept(warningsSheet);
                    
                    // Track row numbers and sheet indices for each type
                    int errorsRowNum = 1;
                    int warningsRowNum = 1;
                    int errorsSheetIndex = 0;
                    int warningsSheetIndex = 0;
                    
                    while (hasMore) {
                        long batchStartTime = System.currentTimeMillis();
                        Query query = entityManager.createNativeQuery(nativeSql);
                        query.setParameter("submissionId", submissionId);
                        query.setParameter("lastErrorRecordId", lastErrorRecordId);
                        query.setParameter("batchSize", BATCH_SIZE);

                        @SuppressWarnings("unchecked")
                        List<Object[]> batch = query.getResultList();
                        
                        if (batch.isEmpty()) {
                            hasMore = false;
                            break;
                        }
                        
                        long queryTime = System.currentTimeMillis() - batchStartTime;
                        log.debug("Fetched batch: jobId={}, lastErrorRecordId={}, batchSize={}, queryTime={}ms", 
                                jobId, lastErrorRecordId, batch.size(), queryTime);
                        
                        long processStartTime = System.currentTimeMillis();
                        
                        // Write batch directly to Excel - no memory accumulation
                        for (Object[] row : batch) {
                            Long errorRecordId = ((Number) row[0]).longValue();
                            String rawRow = (String) row[1];
                            Integer severityLevel = ((Number) row[2]).intValue();
                            String errorName = (String) row[3];
                            String errorDescription = (String) row[4];
                            java.sql.Timestamp ingestedAt = (java.sql.Timestamp) row[5];
                            String ingestionTypeName = (String) row[6];
                            
                            // Update cursor for next iteration
                            lastErrorRecordId = errorRecordId;
                            
                            String type = severityLevel == 1 ? "warning" : "error";
                            String timestamp = ingestedAt != null ? ingestedAt.toString() : "";
                            
                            // Handle Excel row limit by creating new sheets when needed
                            // Excel limit: 1,048,576 rows total (0-1048575), so max data rows = 1048575 (excluding header at row 0)
                            Sheet targetSheet;
                            int currentRowNum;
                            
                            if ("error".equals(type)) {
                                // Check BEFORE incrementing if we need a new sheet
                                // When errorsRowNum reaches MAX_EXCEL_ROWS_PER_SHEET, next row would exceed limit
                                if (errorsRowNum >= MAX_EXCEL_ROWS_PER_SHEET) {
                                    errorsSheetIndex++;
                                    errorsRowNum = 1; // Reset to 1 (header is row 0)
                                    String sheetName = errorsSheetIndex == 0 ? "Errors" : "Errors_" + (errorsSheetIndex + 1);
                                    errorsSheet = workbook.createSheet(sheetName);
                                    createHeader.accept(errorsSheet);
                                    log.info("Created new Errors sheet: {} (total sheets: {}) for jobId={}", 
                                            sheetName, errorsSheetIndex + 1, jobId);
                                }
                                targetSheet = errorsSheet;
                                currentRowNum = errorsRowNum++;
                            } else {
                                // Check BEFORE incrementing if we need a new sheet
                                if (warningsRowNum >= MAX_EXCEL_ROWS_PER_SHEET) {
                                    warningsSheetIndex++;
                                    warningsRowNum = 1; // Reset to 1 (header is row 0)
                                    String sheetName = warningsSheetIndex == 0 ? "Warnings" : "Warnings_" + (warningsSheetIndex + 1);
                                    warningsSheet = workbook.createSheet(sheetName);
                                    createHeader.accept(warningsSheet);
                                    log.info("Created new Warnings sheet: {} (total sheets: {}) for jobId={}", 
                                            sheetName, warningsSheetIndex + 1, jobId);
                                }
                                targetSheet = warningsSheet;
                                currentRowNum = warningsRowNum++;
                            }
                            
                            Row excelRow = targetSheet.createRow(currentRowNum);
                            
                            // Write cells
                            excelRow.createCell(0).setCellValue(rawRow != null ? rawRow : "");
                            excelRow.createCell(1).setCellValue(type);
                            excelRow.createCell(2).setCellValue(errorName != null ? errorName : "");
                            excelRow.createCell(3).setCellValue(errorDescription != null ? errorDescription : "");
                            excelRow.createCell(4).setCellValue(batchId != null ? batchId : "");
                            excelRow.createCell(5).setCellValue(timestamp);
                            excelRow.createCell(6).setCellValue(ingestionTypeName != null ? ingestionTypeName : "");
                            if (isNexi){
                                excelRow.createCell(7).setCellValue(resolveCountryForNexi(rawRow));
                            }

                            if ("error".equals(type)) {
                                totalErrors++;
                            } else {
                                totalWarnings++;
                            }
                        }
                        
                        long processTime = System.currentTimeMillis() - processStartTime;
                        totalProcessed += batch.size();
                        hasMore = batch.size() == BATCH_SIZE;
                        
                        // Log progress every 300k records or at end of batch
                        if (totalProcessed % 300000 == 0 || !hasMore) {
                            log.info("Progress: jobId={}, processed={}, errors={}, warnings={}, lastErrorRecordId={}, batchTime={}ms (query={}ms, process={}ms)", 
                                    jobId, totalProcessed, totalErrors, totalWarnings, lastErrorRecordId, 
                                    queryTime + processTime, queryTime, processTime);
                        }
                    }
                    
                    // Write workbook to file (SXSSF streams data, so this is efficient)
                    long writeStartTime = System.currentTimeMillis();
                    workbook.write(fos);
                    fos.flush();
                    long writeTime = System.currentTimeMillis() - writeStartTime;
                    
                    // SXSSF temporary files are cleaned up automatically when workbook is closed (try-with-resources)
                    
                    long fileSize = tempFile.length();
                    log.info("Completed Excel generation: jobId={}, totalProcessed={}, totalErrors={}, totalWarnings={}, fileSize={} bytes, writeTime={}ms", 
                            jobId, totalProcessed, totalErrors, totalWarnings, fileSize, writeTime);
                    
                    // Upload to S3 from temp file
                    log.info("Uploading Excel file to S3: s3Key={}, size={} bytes", s3Key, fileSize);
                    try (FileInputStream fileInputStream = new FileInputStream(tempFile)) {
                        s3Service.uploadFile(s3Key, fileInputStream, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                        fileUrl = s3Key;
                        log.info("Excel file successfully uploaded to S3: s3Key={}", s3Key);
                    } catch (Exception e) {
                        log.error("Failed to upload Excel file to S3: s3Key={}, error={}", s3Key, e.getMessage(), e);
                        throw new IOException("Failed to upload file to S3: " + s3Key, e);
                    } finally {
                        // Clean up temporary file immediately after upload
                        if (tempFile.exists() && !tempFile.delete()) {
                            log.warn("Failed to delete temporary file: {}", tempFile.getAbsolutePath());
                        }
                    }
                } catch (IOException e) {
                    log.error("Error generating Excel file: jobId={}, error={}", jobId, e.getMessage(), e);
                    // Clean up temp file on error
                    if (tempFile.exists() && !tempFile.delete()) {
                        log.warn("Failed to delete temporary file after error: {}", tempFile.getAbsolutePath());
                    }
                    throw e;
                }
            } else {
                log.warn("No active submission found: fy={}, period={}", fy, period);
            }

            // Get fresh reference to job and update
            job = jobStorage.get(jobId);
            if (job != null) {
                job.setFileUrl(fileUrl);
                job.setStatus("COMPLETED");
                // Re-save to ensure thread safety
                jobStorage.put(jobId, job);
                
                long duration = System.currentTimeMillis() - startTime;
                log.info("CSV generation completed successfully: jobId={}, duration={}ms, fileUrl={}, status={}", 
                        jobId, duration, fileUrl, job.getStatus());
            } else {
                log.error("Job not found when trying to set result: jobId={}", jobId);
            }

        } catch (Exception e) {
            log.error("Error generating Excel report: jobId={}, error={}", jobId, e.getMessage(), e);
            ReportJob job = jobStorage.get(jobId);
            if (job != null) {
                job.setStatus("FAILED");
                job.setErrorMessage(e.getMessage());
                // Re-save to ensure thread safety
                jobStorage.put(jobId, job);
                log.error("Job status set to FAILED: jobId={}, error={}", jobId, e.getMessage());
            }
        }
    }

// ==================================================================================
// PRIVATE HELPER METHODS
// ==================================================================================

    /**
     * Maps an ErrorCause to an ExcelRowDTO for reporting.
     * error_description = error_message from ErrorCause (messaggio specifico), non la description dell'ErrorType.
     * batch_id = Submission.batchId (identificativo del batch di ingestion della submission).
     */
    private ExcelRowDTO mapToExcelRow(ErrorCause errorCause, Submission submission, Ingestion ingestion) {
        String type = errorCause.getErrorType().getSeverityLevel() == 1 ? "warning" : "error";
        String description = (errorCause.getErrorMessage() != null && !errorCause.getErrorMessage().isBlank())
                ? errorCause.getErrorMessage()
                : errorCause.getErrorType().getDescription();
        return ExcelRowDTO.builder()
                .rawRecord(errorCause.getErrorRecord().getRawRow())
                .type(type)
                .name(errorCause.getErrorType().getName())
                .description(description)
                .batchId(submission.getBatchId())
                .timestamp(String.valueOf(ingestion.getIngestedAt()))
                .fileType(ingestion.getIngestionType().getName())
                .build();
    }

    /**
     * Resolves the country marker for the Nexi-only "country" column in the validation report.
     * Returns DENMARK when idEsercente trimmed length is 7, GERMANY when 9, "" otherwise.
     * Applies only when the row's idIntermediario (positions 1-12 of raw_row, optional single
     * leading zero) equals "32875". Both anagrafe and transato share these slice offsets.
     */
    private String resolveCountryForNexi(String rawRow) {
        if (rawRow == null || rawRow.length() < 12) {
            return "";
        }
        String intermediario = rawRow.substring(1, Math.min(rawRow.length(), 12)).trim();
        if (!intermediario.contains(NEXI_INTERMEDIARIO_MARKER)) {
            return "";
        }
        String idEsercente = rawRow.length() > 12
                ? rawRow.substring(12, Math.min(rawRow.length(), 42)).trim()
                : "";
        return switch (idEsercente.length()) {
            case 7 -> "DENMARK";
            case 9 -> "GERMANY";
            default -> "";
        };
    }

    /**
     * Aggregates validation statistics from a list of submissions.
     * OPTIMIZED: Uses native SQL aggregation queries instead of loading all ErrorCause entities into memory.
     * This prevents OOM and timeout issues with millions of records.
     */
    private ValidationPageDTO buildValidationFromSubmissions(Obbligation obbligation) throws NotFoundRecordException {
        long methodStartTime = System.currentTimeMillis();
        Submission submission = obligationService.checkIfValid(obbligation);
        Long submissionId = submission.getId();
        
        log.info("Starting buildValidationFromSubmissions for submissionId={}", submissionId);

        // Use optimized native SQL COUNT queries instead of loading all entities
        long queryStart = System.currentTimeMillis();
        long totalErrors = errorCauseRepository.countDistinctErrorRecordsBySubmissionIdAndSeverityNative(
                        submissionId,
                        SeverityEnum.ERROR.getLevel()
                );
        log.info("Total errors query took {}ms, result={}", System.currentTimeMillis() - queryStart, totalErrors);

        queryStart = System.currentTimeMillis();
        long totalWarnings = errorCauseRepository.countDistinctErrorRecordsBySubmissionIdAndSeverityNative(
                        submissionId,
                        SeverityEnum.WARNING.getLevel()
                );
        log.info("Total warnings query took {}ms, result={}", System.currentTimeMillis() - queryStart, totalWarnings);

        // Get transaction counts (already optimized)
        queryStart = System.currentTimeMillis();
        long totalTransactions = transactionRepository.countTransactionsBySubmission(submissionId);
        log.info("Total transactions query took {}ms, result={}", System.currentTimeMillis() - queryStart, totalTransactions);
        
        queryStart = System.currentTimeMillis();
        long totalTransactionsWithErrorCount = errorRecordRepository.countErrorRecordsBySubmissionAndIngestionType(
                submissionId,
                IngestionTypeEnum.TRANSACTIONS.getLabel());
        log.info("Total transactions with error query took {}ms, result={}", System.currentTimeMillis() - queryStart, totalTransactionsWithErrorCount);

        // Use optimized native SQL GROUP BY queries instead of loading all ErrorCause entities and grouping in memory
        queryStart = System.currentTimeMillis();
        List<ErrorTypeCountDTO> errorTypeCounts = errorCauseRepository.findErrorTypeCountsBySubmissionIdAndSeverityNative(
                        submissionId,
                        SeverityEnum.ERROR.getLevel()
                );
        log.info("Error type counts query took {}ms, resultCount={}", System.currentTimeMillis() - queryStart, errorTypeCounts.size());

        queryStart = System.currentTimeMillis();
        List<ErrorTypeCountDTO> warningTypeCounts = errorCauseRepository.findErrorTypeCountsBySubmissionIdAndSeverityNative(
                        submissionId,
                        SeverityEnum.WARNING.getLevel()
                );
        log.info("Warning type counts query took {}ms, resultCount={}", System.currentTimeMillis() - queryStart, warningTypeCounts.size());

        // Convert DTOs to ValidationReason (lightweight transformation)
        List<ValidationReason> errorReasons = errorTypeCounts.stream()
                .map(dto -> new ValidationReason(dto.errorTypeName(), dto.errorCode(), dto.count()))
                .collect(Collectors.toList());

        List<ValidationReason> warningReasons = warningTypeCounts.stream()
                .map(dto -> new ValidationReason(dto.errorTypeName(), dto.errorCode(), dto.count()))
                .collect(Collectors.toList());

        ValidationGroup errorGroup = new ValidationGroup(totalErrors, errorReasons);
        ValidationGroup warningGroup = new ValidationGroup(totalWarnings, warningReasons);

        long totalDuration = System.currentTimeMillis() - methodStartTime;
        log.info("buildValidationFromSubmissions completed in {}ms for submissionId={}", totalDuration, submissionId);

        return new ValidationPageDTO(
                totalTransactions,
                totalTransactionsWithErrorCount,
                errorGroup,
                warningGroup);
    }

}
