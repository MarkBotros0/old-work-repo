package it.deloitte.postrxade.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import it.deloitte.postrxade.entity.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.deloitte.postrxade.dto.PeriodSubmissionData;
import it.deloitte.postrxade.dto.SubmissionCustomDTO;
import it.deloitte.postrxade.dto.ValidationDTO;
import it.deloitte.postrxade.enums.ErrorTypeCode;
import it.deloitte.postrxade.enums.IngestionStatusEnum;
import it.deloitte.postrxade.enums.IngestionTypeEnum;
import it.deloitte.postrxade.enums.SeverityEnum;
import it.deloitte.postrxade.enums.SubmissionStatusEnum;
import it.deloitte.postrxade.exception.NotFoundRecordException;
import it.deloitte.postrxade.parser.transaction.FileProcessingService;
import it.deloitte.postrxade.parser.transaction.RemoteFile;
import it.deloitte.postrxade.records.FileDescriptor;
import it.deloitte.postrxade.records.ProcessedFailedRecordBatch;
import it.deloitte.postrxade.records.ProcessedRecordBatch;
import it.deloitte.postrxade.repository.ErrorCauseRepository;
import it.deloitte.postrxade.repository.ErrorRecordRepository;
import it.deloitte.postrxade.repository.IngestionErrorRepository;
import it.deloitte.postrxade.repository.IngestionRepository;
import it.deloitte.postrxade.repository.IngestionStatusRepository;
import it.deloitte.postrxade.repository.IngestionTypeRepository;
import it.deloitte.postrxade.repository.LogRepository;
import it.deloitte.postrxade.repository.MerchantRepository;
import it.deloitte.postrxade.repository.ObligationRepository;
import it.deloitte.postrxade.repository.OutputRepository;
import it.deloitte.postrxade.repository.PeriodRepository;
import it.deloitte.postrxade.repository.ResolvedTransactionRepository;
import it.deloitte.postrxade.repository.StagingRepository;
import it.deloitte.postrxade.repository.SubmissionRepository;
import it.deloitte.postrxade.repository.TransactionRepository;
import it.deloitte.postrxade.service.IngestionService;
import it.deloitte.postrxade.service.ObligationService;
import it.deloitte.postrxade.service.S3Service;
import it.deloitte.postrxade.service.StagingIngestionService;
import it.deloitte.postrxade.service.SubmissionService;
import it.deloitte.postrxade.records.StagingResult;
import lombok.extern.slf4j.Slf4j;
import ma.glasnost.orika.MapperFacade;
import software.amazon.awssdk.services.s3.model.S3Object;


/**
 * Implementation of the ObligationService.
 * <p>
 * This service manages the lifecycle and retrieval of Obligations and their Submissions.
 * It enforces strict data integrity rules (e.g., ensuring only one active submission exists per obligation)
 * and provides helper methods for historical data retrieval.
 */
@Service
@Slf4j
public class ObligationServiceImpl implements ObligationService {

    private static final String PERIOD_NOT_FOUND_MSG = "Period with name %s not found";
    private static final String OBLIGATION_NOT_FOUND_MSG = "Obligation not found for period %s and fiscal year %d";
    private static final String OBLIGATION_NOT_VALID_MSG = "Obligation with period %s and fiscal year %s has invalid number of active submission.";
    private static final String ERROR_TAG = "error";
    private static final String WARNING_TAG = "warning";

    @Autowired
    private ObligationRepository obligationRepository;

    @Autowired
    private PeriodRepository periodRepository;

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private IngestionTypeRepository ingestionTypeRepository;

    @Autowired
    private IngestionErrorRepository ingestionErrorRepository;

    @Autowired
    private IngestionStatusRepository ingestionStatusRepository;

    @Autowired
    private LogRepository logRepository;

    @Autowired
    private FileProcessingService transactionFileProcessingService;

    @Autowired
    private @Qualifier("alternativeMapperFacade") MapperFacade alternativeMapperFacade;

    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private IngestionRepository ingestionRepository;

    @Autowired
    private OutputRepository outputRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private ResolvedTransactionRepository resolvedTransactionRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private ErrorRecordRepository errorRecordRepository;

    @Autowired
    private ErrorCauseRepository errorCauseRepository;

    @Autowired
    @Qualifier("mapperFacade")
    private MapperFacade mapperFacade;

    @Autowired
    private S3Service s3Service;

    @Autowired
    private StagingIngestionService stagingIngestionService;

    @Autowired
    private StagingRepository stagingRepository;

    @Value("${aws.s3.input-folder}")
    private String inputFolder;

    // Flag to enable staging-based ingestion (high-performance ETL approach)
    // Set to true for production with large files (250k+ merchants, 800k+ transactions)
    @Value("${application.ingestion.use-staging:true}")
    private boolean useStagingIngestion;

    /**
     * Retrieves all submissions for a given Fiscal Year and Period.
     * <p>
     * Performs a strict integrity check:
     * If more than one active submission exists, it throws a {@link NotFoundRecordException}.
     *
     * @param fy     The Fiscal Year.
     * @param period The Period name.
     * @return A list of all Submissions (Active + Cancelled/Rejected).
     * @throws NotFoundRecordException if the period/obligation is missing or data integrity is violated.
     */
    @Override
    @Transactional(readOnly = true)
    public List<Submission> getAllSubmissionByFyAndPeriod(Integer fy, String period) throws NotFoundRecordException {

        Optional<Period> optionalPeriod = periodRepository.findByName(period);
        if (optionalPeriod.isEmpty()) {
            throw new NotFoundRecordException(String.format(PERIOD_NOT_FOUND_MSG, period));
        }
        Period existingPeriod = optionalPeriod.get();

        Optional<Obbligation> optionalObligation = obligationRepository.findByFiscalYearAndPeriod(fy, existingPeriod);
        if (optionalObligation.isEmpty()) {
            throw new NotFoundRecordException(String.format(OBLIGATION_NOT_FOUND_MSG, period, fy));
        }

        Obbligation obligation = optionalObligation.get();

        // Integrity Check: Throws 404 if >1 active submission
        checkIfValid(obligation);

        return submissionRepository.findByObbligationId(obligation.getId());
    }

    /**
     * Calculates the target past period based on the offset and retrieves its submissions.
     *
     * @param fiscalYear      The starting Fiscal Year.
     * @param periodName      The starting Period name.
     * @param periodsToGoBack Number of periods to regress.
     * @return List of submissions for the calculated past period, or empty list if not found.
     */
    @Override
    @Transactional(readOnly = true)
    public List<Submission> getSubmissionsFromPastPeriod(Integer fiscalYear, String periodName, int periodsToGoBack) {
        if (periodsToGoBack <= 0) return Collections.emptyList();

        Optional<Period> optionalCurrentPeriod = periodRepository.findByName(periodName);
        if (optionalCurrentPeriod.isEmpty()) return Collections.emptyList();

        int currentOrder = optionalCurrentPeriod.get().getOrder();
        int yearsToSubtract = periodsToGoBack / 12;
        int monthsToSubtract = periodsToGoBack % 12;
        int targetFiscalYear = fiscalYear - yearsToSubtract;
        int targetPeriodOrder = currentOrder - monthsToSubtract;

        if (targetPeriodOrder <= 0) {
            targetFiscalYear -= 1;
            targetPeriodOrder += 12;
        }

        Optional<Period> optionalTargetPeriod = periodRepository.findByOrder(targetPeriodOrder);
        if (optionalTargetPeriod.isEmpty()) return Collections.emptyList();
        Period targetPeriod = optionalTargetPeriod.get();

        try {
            // We re-use the Strict method here, but CATCH the error
            return getAllSubmissionByFyAndPeriod(targetFiscalYear, targetPeriod.getName());
        } catch (NotFoundRecordException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Validates the integrity of an Obligation.
     * <p>
     * Rule: An obligation can have at most ONE active submission.
     * 0 active is Valid. 1 active is Valid. >1 is Invalid.
     *
     * @param obligation The entity to check.
     * @throws NotFoundRecordException if validation fails.
     */
    @Override
    public Submission checkIfValid(Obbligation obligation) throws NotFoundRecordException {
        Submission submission = submissionService.getActivSubmission(obligation);

        if (submission == null) {
            throw new NotFoundRecordException(String.format(OBLIGATION_NOT_VALID_MSG,
                    obligation.getPeriod().getName(),
                    obligation.getFiscalYear()));

        }

        return submission;
    }

    /**
     * Retrieves the single active submission for statistics purposes.
     * <p>
     * Returns {@link Optional#empty()} if validation fails or no active submission exists,
     * rather than throwing an exception (except for strict validity checks).
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Submission> getActiveSubmissionForStats(Integer fy, String period) throws NotFoundRecordException {

        // 1. Check Period & Obligation (Return Empty if missing)
        Optional<Period> optionalPeriod = periodRepository.findByName(period);
        if (optionalPeriod.isEmpty()) return Optional.empty();

        Optional<Obbligation> optionalObbligation = obligationRepository.findByFiscalYearAndPeriod(fy, optionalPeriod.get());
        if (optionalObbligation.isEmpty()) return Optional.empty();

        Obbligation obligation = optionalObbligation.get();

        // 2. STRICT INTEGRITY CHECK (The only time we throw Error)
        checkIfValid(obligation);

        // 3. Filter for the single Active Submission
        if (obligation.getSubmissions() == null) return Optional.empty();

        return obligation.getSubmissions().stream()
                .filter(this::isActiveSubmission)
                .findFirst();
    }

    /**
     * Retrieves PeriodSubmissionData DTO for a specific Fiscal Year and Period.
     * <p>
     * Used mainly for charts or summary widgets. It returns a safe empty object
     * if the record is missing or invalid, preventing UI crashes.
     *
     * @param fy         The Fiscal Year.
     * @param periodName The Period name.
     * @return A {@link PeriodSubmissionData} object (never null).
     */
    @Override
    @Transactional(readOnly = true)
    public PeriodSubmissionData getDataByFyAndPeriod(Integer fy, String periodName) {
        Optional<Period> optionalPeriod = periodRepository.findByName(periodName);
        if (optionalPeriod.isEmpty()) return new PeriodSubmissionData(fy, periodName);

        Optional<Obbligation> optionalObligation = obligationRepository.findByFiscalYearAndPeriod(fy, optionalPeriod.get());

        if (optionalObligation.isPresent()) {
            try {
                checkIfValid(optionalObligation.get());
                return new PeriodSubmissionData(optionalObligation.get());
            } catch (NotFoundRecordException e) {
                return new PeriodSubmissionData(fy, periodName); // Return 0s on error
            }
        }
        return new PeriodSubmissionData(fy, periodName);
    }

    /**
     * Retrieves PeriodSubmissionData for a past period calculated by offset.
     * <p>
     * Logic handles fiscal year rollovers (e.g., going back from Jan 2024 to Dec 2023).
     *
     * @param fy              The current Fiscal Year.
     * @param period          The current Period name.
     * @param periodsToGoBack The number of months/periods to subtract.
     * @return A {@link PeriodSubmissionData} object for the past target.
     */
    @Override
    @Transactional(readOnly = true)
    public PeriodSubmissionData getDataFromPastPeriod(Integer fy, String period, int periodsToGoBack) {
        if (periodsToGoBack <= 0) return getDataByFyAndPeriod(fy, period);

        Optional<Period> optionalCurrentPeriod = periodRepository.findByName(period);
        if (optionalCurrentPeriod.isEmpty()) return new PeriodSubmissionData(fy, period);

        int currentOrder = optionalCurrentPeriod.get().getOrder();
        int yearsToSubtract = periodsToGoBack / 12;
        int monthsToSubtract = periodsToGoBack % 12;
        int targetFiscalYear = fy - yearsToSubtract;
        int targetPeriodOrder = currentOrder - monthsToSubtract;

        if (targetPeriodOrder <= 0) {
            targetFiscalYear -= 1;
            targetPeriodOrder += 12;
        }

        Optional<Period> optionalTargetPeriod = periodRepository.findByOrder(targetPeriodOrder);
        if (optionalTargetPeriod.isEmpty()) {
            return new PeriodSubmissionData(targetFiscalYear, "PeriodOrder(" + targetPeriodOrder + ")");
        }

        return getDataByFyAndPeriod(targetFiscalYear, optionalTargetPeriod.get().getName());
    }

    /**
     * Retrieves and maps the active submission to a {@link SubmissionCustomDTO}.
     * This is the main entry point for the UI grid.
     */
    @Override
    @Transactional(readOnly = true)
    public List<SubmissionCustomDTO> getAllObligationsByFyAndPeriod(Integer fy, String period) throws NotFoundRecordException {
        Optional<Period> optionalPeriod = periodRepository.findByName(period);
        if (optionalPeriod.isEmpty()) return Collections.emptyList();

        Optional<Obbligation> optionalObbligation = obligationRepository.findByFiscalYearAndPeriod(fy, optionalPeriod.get());
        if (optionalObbligation.isEmpty()) return Collections.emptyList();

        Obbligation obligation = optionalObbligation.get();
        List<Submission> submissions = submissionRepository.findByObbligationId(obligation.getId());
        List<Submission> filteredSubmissions = submissions.stream()
                .filter(submission -> !submission.getCurrentSubmissionStatus().getName().equals(SubmissionStatusEnum.ERROR.getDbName()))
                .toList();

        List<SubmissionCustomDTO> customSubmissions = new ArrayList<>();
        for (Submission s : filteredSubmissions) {
            SubmissionCustomDTO dto = alternativeMapperFacade.map(s, SubmissionCustomDTO.class);
            dto.setValidations(getValidations(s));
            dto.setIngestionsCount(s.getIngestions() != null ? s.getIngestions().size() : 0);
            dto.setOutputFilesCount((int) outputRepository.countBySubmissionId(s.getId()));
            customSubmissions.add(dto);
        }

        return customSubmissions;
    }

    /**
     * Helper wrapper to return a List<Submission> instead of Optional.
     * Useful for batch processing or legacy compatibility.
     *
     * @param fy         The Fiscal Year.
     * @param periodName The Period Name.
     * @return List containing the single active submission, or empty list.
     * @throws NotFoundRecordException if validation fails.
     */
    @Override
    @Transactional(readOnly = true)
    public List<Submission> getSubmissionsForStats(Integer fy, String periodName) throws NotFoundRecordException {

        Optional<Obbligation> obbligationOpt = this.obligationRepository.findByFiscalYearAndPeriod_Name(fy, periodName);
        if (obbligationOpt.isEmpty()) return Collections.emptyList();

        Obbligation obbligation = obbligationOpt.get();

        List<Submission> submissions = this.submissionRepository.findByObbligationId(obbligation.getId());

        return submissions.stream()
                .filter(this::isActiveSubmission)
                .toList();
    }

    /**
     * Filters the submissions of an Obligation to find only the "Active" ones.
     * "Active" is defined as not Cancelled and not Rejected.
     *
     * @param obligation The obligation to filter.
     * @return List of active submissions.
     */
    @Override
    public List<Submission> getActiveSubmissions(Obbligation obligation) {
        List<Submission> submissions = submissionRepository.findByObbligationId(obligation.getId());

        return submissions.stream()
                .filter(this::isActiveSubmission)
                .toList();
    }

    private boolean isActiveSubmission(Submission submission) {
        // 1. Safety checks for nulls
        if (submission.getCurrentSubmissionStatus() == null ||
                submission.getCurrentSubmissionStatus().getOrder() == null) {
            return false;
        }

        Integer order = submission.getCurrentSubmissionStatus().getOrder();

        // 2. Use the Enum IDs for comparison
        // We check if the order is NOT Cancelled AND NOT Rejected and not ERROR
        return !order.equals(SubmissionStatusEnum.CANCELLED.getOrder()) &&
                !order.equals(SubmissionStatusEnum.REJECTED.getOrder()) &&
                !order.equals(SubmissionStatusEnum.ERROR.getOrder());
    }

    /**
     * Aggregates validation counts (Errors and Warnings) for a submission.
     * Counts error records from both transaction file (transato) and anagrafe file, aligned with other dashboards.
     *
     * @param submission The submission to analyze.
     * @return List of ValidationDTO (one for errors, one for warnings).
     */
    private List<ValidationDTO> getValidations(Submission submission) {
        long errorCount = errorCauseRepository.countDistinctErrorRecordsBySubmissionIdAndSeverity(
                submission.getId(), SeverityEnum.ERROR.getLevel());
        long warningCount = errorCauseRepository.countDistinctErrorRecordsBySubmissionIdAndSeverity(
                submission.getId(), SeverityEnum.WARNING.getLevel());

        return Arrays.asList(
                new ValidationDTO(ERROR_TAG, errorCount),
                new ValidationDTO(WARNING_TAG, warningCount)
        );
    }

    @Override
    @Async
    public CompletableFuture<Void> ingestObligationFilesAsync() throws NotFoundRecordException, IOException {
        log.debug("Starting async ingestion of obligation files");
        try {
            // Verifica che S3Service sia disponibile nel thread asincrono
            if (s3Service == null) {
                log.error("S3Service is null in async thread");
                throw new IllegalStateException("S3Service is not available in async thread");
            }
            log.debug("S3Service is available in async thread, bucket: {}", s3Service.getBucketName());

            ingestObligationFiles();
            log.debug("Async ingestion of obligation files completed successfully");
        } catch (Exception e) {
            log.error("Error during async ingestion of obligation files", e);
            throw e;
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void ingestObligationFiles() throws NotFoundRecordException {
        log.debug("Starting ingestion of obligation files");
        List<String> fileNames = s3Service.fetchFileKeysFromBucket();
        log.info("Fetched {} file(s) from bucket: {}", fileNames.size(), fileNames);

        FileDescriptor fileProps = extractEotFileProps(fileNames);
        log.debug("Extracted file properties: year={}, month={}",
                fileProps.year(), fileProps.month());

        List<String> transatoFiles = filterFiles(fileNames, "transato");
        List<String> anagrafeFiles = filterFiles(fileNames, "anagrafe");
        log.debug("Transato files: {}, Anagrafe files: {}", transatoFiles.size(), anagrafeFiles.size());

        Period period = periodRepository.findByName(fileProps.month()).orElse(null);
        Obbligation obbligation = findOrCreateObbligation(fileProps.year(), period);
        log.debug("Using obligation: id={}, fiscalYear={}, period={}",
                obbligation.getId(), obbligation.getFiscalYear(),
                period != null ? period.getName() : "null");

        // =====================================================================
        // CHECK FOR RESUME: Controlla se l'ULTIMA submission ERROR ha record pending in STG
        // IMPORTANTE: Considera SOLO l'ultima submission ERROR, le altre restano in ERROR
        // =====================================================================
        Submission submissionToResume = findSubmissionWithPendingStagingRecords(obbligation);
        if (submissionToResume != null) {
            log.info("==========================================================");
            log.info("   RESUMING INGESTION FROM STAGING");
            log.info("==========================================================");
            log.info("Found last ERROR submission {} with pending records in STG", submissionToResume.getId());
            log.info("Resuming processing from staging tables (no file re-processing needed)");
            
            try {
                // NOTE: In the new approach, we don't update process_status during processing,
                // so resetInsertedRecordsToPending is no longer needed. All records remain with
                // process_status = NULL and are processed based on PK range only.
                // 
                // If there are old records with process_status = 1 from previous runs with old approach,
                // they will be skipped (but this should not happen in normal flow).
                // 
                // Commented out for new approach:
                // long[] resetCounts = stagingRepository.resetInsertedRecordsToPending(submissionToResume.getId());
                // log.info("Reset {} transactions and {} merchants from status=1 to pending for re-processing", 
                //         resetCounts[0], resetCounts[1]);
                
                // Riporta lo status a DATA_VALIDATION per permettere riprocessamento
                submissionService.updateStatusInternal(submissionToResume, 2); // DATA_VALIDATION
                log.info("Updated submission {} status to DATA_VALIDATION for resume", submissionToResume.getId());
                
                // Riprendi il processamento da STG
                resumeFromStaging(submissionToResume, obbligation, period);
                
                // Se completato con successo, sposta i file rimasti in input (se non già spostati)
                // Questo evita che i file vengano riprocessati alla prossima esecuzione
                moveRemainingFilesAfterResume(transatoFiles, anagrafeFiles, fileProps.eotFileName());
                
                // Se completato con successo, aggiorna status
                submissionService.updateStatusInternal(submissionToResume, 3); // VALIDATION_COMPLETED
                log.info("Resume completed successfully for submission {}", submissionToResume.getId());
                
                // Processa resolved transactions SOLO se il resume è completato con successo
                retryFailedTransactions(obbligation, period, submissionToResume);
                
            } catch (Exception exception) {
                log.error("Error during resume from staging: {}", exception.getMessage(), exception);
                cleanUpFailedSubmission(submissionToResume, null, exception);
                // NON processare resolved transactions se il resume fallisce
                // Lancia l'eccezione per far fallire il task ECS
                throw new RuntimeException("Resume from staging failed - aborting ingestion", exception);
            }
            
            return; // Exit - resume completed successfully
        }

        boolean hasActiveSubmission = hasActiveSubmission(obbligation);
        log.info("hasActiveSubmission check result: {} for obligation id={}, fiscalYear={}, period={}", 
                hasActiveSubmission, obbligation.getId(), obbligation.getFiscalYear(),
                period != null ? period.getName() : "null");
        
        if (hasActiveSubmission) {
            log.warn("Found active submission for obligation id={}, fiscalYear={}, period={}. " +
                    "Cannot start new ingestion. Please cancel/close existing submissions first.",
                    obbligation.getId(), obbligation.getFiscalYear(),
                    period != null ? period.getName() : "null");
        }

        Submission submission = this.submissionService.createSubmissionByObligation(obbligation);
        log.info("Created submission: id={} with status: {}", 
                submission.getId(), 
                submission.getCurrentSubmissionStatus() != null ? submission.getCurrentSubmissionStatus().getName() : "null");

        if (hasActiveSubmission) {
            log.error("Aborting ingestion - active submission exists for obligation id={}. Marking new submission as ERROR.", 
                    obbligation.getId());
            submissionService.markAsError(submission);
            return;
        }
        
        log.info("No active submissions found. Proceeding with ingestion for submission id={}", submission.getId());

        // Update status to DATA_VALIDATION when starting data processing
        try {
            submissionService.updateStatusInternal(submission, 2); // DATA_VALIDATION
            log.info("Updated submission {} status to DATA_VALIDATION - starting data processing", submission.getId());
        } catch (NotFoundRecordException e) {
            log.error("Failed to update submission status to DATA_VALIDATION", e);
            submissionService.markAsError(submission);
            return;
        }

        Ingestion currentIngestion = null;

        try {
            if (useStagingIngestion) {
                // =====================================================
                // HIGH-PERFORMANCE STAGING APPROACH (recommended for large files)
                // Uses bulk load + set-based operations for 10-20x speedup
                // =====================================================
                log.info("Using STAGING-BASED ingestion (high-performance ETL approach)");
                processFilesWithStagingApproach(anagrafeFiles, transatoFiles, submission, obbligation);
            } else {
                // =====================================================
                // LEGACY ROW-BY-ROW APPROACH (kept for compatibility)
                // =====================================================
                log.info("Using LEGACY row-by-row ingestion approach");
                processFilesWithLegacyApproach(anagrafeFiles, transatoFiles, submission, obbligation);
            }

            // All files processed successfully - update status to VALIDATION_COMPLETED
            try {
                submissionService.updateStatusInternal(submission, 3); // VALIDATION_COMPLETED
                log.info("Updated submission {} status to VALIDATION_COMPLETED - all data processed successfully", submission.getId());
            } catch (NotFoundRecordException e) {
                log.error("Failed to update submission status to VALIDATION_COMPLETED", e);
                // Don't mark as error, just log - the data is already processed
            }
            
            // Final steps after successful ingestion:
            // 1. Clear staging tables (TRUNCATE via stored procedure)
            // 2. Move .eot file to input-loaded
            // These steps happen ONLY on success - in case of error, STG tables remain for resume
            log.info("Clearing staging tables after successful ingestion for submission: {}", submission.getId());
            stagingIngestionService.cleanupStaging(submission.getId());
            
            s3Service.moveFileFromInputToInputLoaded(fileProps.eotFileName());
            log.info("Moved .eot file {} to input-loaded folder after successful ingestion", fileProps.eotFileName());
        } catch (Exception exception) {
            log.error("An error occured during ingestion: {}", exception.getMessage(), exception);
            cleanUpFailedSubmission(submission, currentIngestion, exception);
            // NOTE: .eot file is NOT moved in case of error - allows for resume from staging tables
            // Re-throw exception to fail the batch process correctly
            throw new RuntimeException("Ingestion failed for submission " + submission.getId(), exception);
        }
        retryFailedTransactions(obbligation, period, submission);
    }

    private boolean hasActiveSubmission(Obbligation obbligation) {
        List<Submission> submissions = submissionRepository.findByObbligationId(obbligation.getId());
        log.debug("Found {} total submission(s) for obligation id={}", submissions.size(), obbligation.getId());

        List<Submission> activeSubmissions = submissions.stream().filter(s -> {
            String statusName = s.getCurrentSubmissionStatus().getName();
            boolean isActive = !statusName.equals(SubmissionStatusEnum.CANCELLED.getDbName())
                    && !statusName.equals(SubmissionStatusEnum.ERROR.getDbName())
                    && !statusName.equals(SubmissionStatusEnum.REJECTED.getDbName());
            if (isActive) {
                log.debug("Active submission found: id={}, status={}", s.getId(), statusName);
            }
            return isActive;
        }).toList();

        log.info("hasActiveSubmission for obligation id={}: {} active submission(s) found", 
                obbligation.getId(), activeSubmissions.size());
        return !activeSubmissions.isEmpty();
    }

    private String getEotFileName(List<String> files) throws NotFoundRecordException {
        log.debug("Searching for EOT file in {} file(s)", files.size());
        for (String file : files) {
            if (file != null) {
                String name = file.toLowerCase();
                log.debug("Checking file: {}", file);
                if (name.endsWith(".eot")) {
                    log.debug("Found EOT file: {}", file);
                    return file;
                }
            }
        }
        log.error("EOT file not found in the provided files list");
        throw new NotFoundRecordException(".eot file is not found");
    }

    private List<String> filterFiles(List<String> files, String substring) {
        log.debug("Filtering .txt files containing {} in {} file(s)", substring, files.size());
        List<String> filteredFiles = files.stream()
                .filter(Objects::nonNull)
                .filter(file -> file.toLowerCase().endsWith(".txt"))
                .filter(file -> file.toLowerCase().contains(substring))
                .toList();
        log.debug("Found {} {} file(s)", filteredFiles.size(), substring);
        return filteredFiles;
    }

    private List<RemoteFile> fetchFilesFromBucket() throws IOException, NotFoundRecordException {
        // uncomment for local testing
//        List<RemoteFile> files = new ArrayList<>();
//        List<String> resourceNames = new ArrayList<>(
//                List.of("TRXPOSADE_32875_TRANSATOPOS_202510_20251127162800.txt",
//                        "TRXPOSADE_32875_TRANSATOPOS_202510_20251127162800.eot",
//                        "TRXPOSADE_32875_ANAGRAFEPOS_202510_20251127162800.txt"
//                ));
//
//        log.debug("Fetching files from bucket");
//        for (String resourceName : resourceNames) {
//            Resource resource = new ClassPathResource(resourceName);
//            if (!resource.exists()) {
//                log.error("Resource '{}' not found in classpath resources", resourceName);
//                throw new NotFoundRecordException(resourceName + " not found in classpath resources");
//            }
//            log.debug("Successfully loaded resource: {}", resourceName);
//            files.add(new RemoteFile(resourceName, resource.getInputStream()));
//        }
//        log.debug("Created {} RemoteFile(s) from resource", files.size());
//        return files;


        // NUOVO CODICE: Lettura file da S3
        log.debug("Fetching files from S3 bucket, input folder: {}", inputFolder);
        List<RemoteFile> files = new ArrayList<>();

        // Lista tutti gli oggetti nella cartella input-folder
        List<S3Object> s3Objects = s3Service.listObjects(inputFolder);

        if (s3Objects.isEmpty()) {
            log.warn("No files found in S3 input folder: {}", inputFolder);
            throw new NotFoundRecordException("No files found in S3 input folder: " + inputFolder);
        }

        log.debug("Found {} object(s) in S3 input folder", s3Objects.size());

        // Per ogni oggetto S3, scarica il file e crea un RemoteFile
        for (S3Object s3Object : s3Objects) {
            String key = s3Object.key();
            // Estrae solo il nome del file dalla chiave (rimuove il path della cartella)
            String fileName = key.substring(key.lastIndexOf('/') + 1);

            log.debug("Downloading file from S3: {}", key);
            InputStream inputStream = s3Service.downloadFileAsStream(key);
            files.add(new RemoteFile(fileName, inputStream));
            log.debug("Successfully created RemoteFile for: {}", fileName);
        }

        log.debug("Created {} RemoteFile(s) from S3", files.size());
        return files;
    }

    // Retry configuration for lock timeout
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 100; // Start with 100ms

    public void saveProcessedBatches(ProcessedRecordBatch batch, Ingestion ingestion, Submission submission) {
        log.info("Starting to save processed batch in main thread for ingestionId: {}",
                ingestion.getId());

        if (!batch.merchants().isEmpty()) {
            executeWithRetry(() -> {
                merchantRepository.bulkInsert(batch.merchants(), submission);
                log.info("Saved {} merchants", batch.merchants().size());
            }, "merchants");
        }
        if (!batch.transactions().isEmpty()) {
            executeWithRetry(() -> {
                transactionRepository.bulkInsert(batch.transactions(), submission);
                log.info("Saved {} transactions", batch.transactions().size());
            }, "transactions");
        }
        if (!batch.errorRecords().isEmpty()) {
            executeWithRetry(() -> {
                errorRecordRepository.bulkInsertRecordsWithCauses(batch.errorRecords(), ingestion.getId());
                log.info("Saved {} error records", batch.errorRecords().size());
            }, "error records");
        }
    }

    /**
     * Execute database operation with retry logic for lock timeout errors.
     * Uses exponential backoff: 100ms, 200ms, 400ms
     */
    private void executeWithRetry(Runnable operation, String operationName) {
        int attempt = 0;
        long delay = INITIAL_RETRY_DELAY_MS;

        while (attempt < MAX_RETRIES) {
            try {
                operation.run();
                return; // Success, exit retry loop
            } catch (Exception e) {
                attempt++;

                // Check if it's a lock timeout error
                boolean isLockTimeout = e.getMessage() != null &&
                        (e.getMessage().contains("Lock wait timeout") ||
                                e.getMessage().contains("try restarting transaction"));

                if (isLockTimeout && attempt < MAX_RETRIES) {
                    log.warn("Lock timeout on {} (attempt {}/{}), retrying after {}ms",
                            operationName, attempt, MAX_RETRIES, delay);
                    try {
                        Thread.sleep(delay);
                        delay *= 2; // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    }
                } else {
                    // Not a lock timeout or max retries reached, rethrow
                    log.error("Failed to save {} after {} attempts", operationName, attempt, e);
                    throw new RuntimeException("Failed to save " + operationName + " after " + attempt + " attempts", e);
                }
            }
        }
    }

    private FileDescriptor extractEotFileProps(List<String> files) throws NotFoundRecordException {
        String eotFileName = getEotFileName(files);
        log.debug("Found EOT file: {}", eotFileName);

        log.debug("Extracting file properties from filename: {}", eotFileName);
        String[] parts = eotFileName.split("_");
        if (parts.length < 5) {
            throw new IllegalArgumentException("Invalid filename structure: " + eotFileName);
        }

        String typeName = parts[2].toLowerCase();
        log.debug("Extracted type name: {}", typeName);

        String datePart = parts[3];
        log.debug("Extracted date part: {}", datePart);

        if (!datePart.matches("^[0-9]{6}$")) {
            throw new IllegalArgumentException("Invalid date segment (expected CCYYMM): " + datePart);
        }

        int year = Integer.parseInt(datePart.substring(0, 4));
        int monthNum = Integer.parseInt(datePart.substring(4, 6));
        log.debug("Parsed year: {}, month number: {}", year, monthNum);

        String month = Month.of(monthNum).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        log.debug("Converted month number to name: {}", month);

        FileDescriptor descriptor = new FileDescriptor(year, month, eotFileName);
        log.debug(
                "Successfully extracted file properties: year={}, month={}",
                year, month
        );

        return descriptor;
    }

    private Obbligation findOrCreateObbligation(int year, Period period) {
        log.debug("Finding or creating obligation for year={}, period={}",
                year, period != null ? period.getName() : "null");
        Obbligation oldObligation = obligationRepository.findByFiscalYearAndPeriod(year, period)
                .orElseGet(() -> {
                    log.debug("Creating new obligation for year={}, period={}",
                            year, period != null ? period.getName() : "null");
                    Obbligation newObbligation = new Obbligation();
                    newObbligation.setFiscalYear(year);
                    newObbligation.setPeriod(period);

                    Obbligation savedObligation = obligationRepository.save(newObbligation);
                    savedObligation.setPeriod(period);
                    return savedObligation;
                });
        oldObligation.setPeriod(period);
        return oldObligation;
    }

    /**
     * HIGH-PERFORMANCE STAGING APPROACH
     * Uses bulk load into staging tables + set-based operations for 10-20x speedup.
     * Ideal for large files (250k+ merchants, 800k+ transactions).
     */
    private void processFilesWithStagingApproach(
            List<String> anagrafeFiles,
            List<String> transatoFiles,
            Submission submission,
            Obbligation obbligation) throws NotFoundRecordException, IOException {

        long totalStartTime = System.currentTimeMillis();

        // Phase 1: Process merchant files (anagrafe)
        log.info("=== STAGING Phase 1: Processing {} merchant file(s) ===", anagrafeFiles.size());
        IngestionType anagrafeType = ingestionTypeRepository.findByNameIgnoreCase("anagrafe")
                .orElseThrow(() -> new NotFoundRecordException("Ingestion type 'anagrafe' is not found"));

        int totalMerchantsInserted = 0;
        int totalMerchantsDuplicate = 0;

        for (String keyName : anagrafeFiles) {
            String fileName = keyName.substring(keyName.lastIndexOf('/') + 1);
            try (InputStream inputStream = s3Service.downloadFileAsStream(keyName)) {
                RemoteFile remoteFile = new RemoteFile(fileName, inputStream);

                log.info("Processing merchant file: {}", fileName);
                Ingestion ingestion = this.ingestionService.createIngestionBySubmission(submission, anagrafeType);

                long fileStartTime = System.currentTimeMillis();
                StagingResult result = stagingIngestionService.processMerchantFile(remoteFile, ingestion, submission);
                long fileElapsed = System.currentTimeMillis() - fileStartTime;

                totalMerchantsInserted += result.insertedCount();
                totalMerchantsDuplicate += result.duplicateCount();

                log.info("Completed merchant file {} in {}ms: inserted={}, duplicates={}",
                        fileName, fileElapsed, result.insertedCount(), result.duplicateCount());

                ingestionService.markAsSuccess(ingestion);
                s3Service.moveFileFromInputToInputLoaded(remoteFile.name());
            }
        }

        log.info("=== STAGING Phase 1 Complete: {} merchants inserted, {} duplicates ===",
                totalMerchantsInserted, totalMerchantsDuplicate);

        // Phase 2: Process transaction files (transato)
        log.info("=== STAGING Phase 2: Processing {} transaction file(s) ===", transatoFiles.size());
        IngestionType transatoType = ingestionTypeRepository.findByNameIgnoreCase("transato")
                .orElseThrow(() -> new NotFoundRecordException("Ingestion type 'transato' is not found"));

        int totalTransactionsInserted = 0;
        int totalTransactionsDuplicate = 0;
        int totalMissingMerchants = 0;
        int totalTransactionValidationErrors = 0;
        Ingestion ingestionForFinalization = null;

        for (String keyName : transatoFiles) {
            String fileName = keyName.substring(keyName.lastIndexOf('/') + 1);
            Ingestion ingestion = null;
            try (InputStream inputStream = s3Service.downloadFileAsStream(keyName)) {
                RemoteFile remoteFile = new RemoteFile(fileName, inputStream);

                log.info("Processing transaction file: {}", fileName);
                ingestion = this.ingestionService.createIngestionBySubmission(submission, transatoType);
                // Use the first ingestion as the "finalization" context for STG -> TRANSACTION processing and error records.
                // This keeps behavior deterministic when multiple files belong to the same submission.
                if (ingestionForFinalization == null) {
                    ingestionForFinalization = ingestion;
                }

                long fileStartTime = System.currentTimeMillis();
                StagingResult result = stagingIngestionService.loadTransactionFileToStaging(remoteFile, ingestion, submission, obbligation);
                long fileElapsed = System.currentTimeMillis() - fileStartTime;

                totalTransactionValidationErrors += result.errorCount();

                log.info("Completed transaction file {} load-to-staging in {}ms: validationErrors={}",
                        fileName, fileElapsed, result.errorCount());

                ingestionService.markAsSuccess(ingestion);
                s3Service.moveFileFromInputToInputLoaded(remoteFile.name());
            } catch (Exception e) {
                log.error("Error processing transaction file {}: {}", fileName, e.getMessage(), e);
                if (ingestion != null) {
                    try {
                        ingestionService.markAsError(ingestion);
                    } catch (Exception ex) {
                        log.error("Failed to mark ingestion as error: {}", ex.getMessage());
                    }
                }
                // Re-throw to be caught by outer catch block
                throw new RuntimeException("Failed to process transaction file: " + fileName, e);
            }
        }

        // Finalize once for the whole submission after ALL transaction files have been loaded into STG
        if (ingestionForFinalization != null) {
            log.info("Finalizing transactions from staging for submission {} ({} transaction file(s))...",
                    submission.getId(), transatoFiles.size());
            long finalizeStart = System.currentTimeMillis();
            StagingResult finalizeResult = stagingIngestionService.finalizeTransactionsFromStaging(ingestionForFinalization, submission);
            long finalizeElapsed = System.currentTimeMillis() - finalizeStart;

            totalTransactionsInserted += finalizeResult.insertedCount();
            totalTransactionsDuplicate += finalizeResult.duplicateCount();
            totalMissingMerchants += finalizeResult.missingMerchantCount();

            log.info("Finalize from staging completed in {}ms: inserted={}, duplicates={}, missingMerchants={}",
                    finalizeElapsed, finalizeResult.insertedCount(), finalizeResult.duplicateCount(), finalizeResult.missingMerchantCount());
        } else {
            log.warn("No transaction ingestions were created for submission {} - skipping finalize from staging", submission.getId());
        }

        log.info("=== STAGING Phase 2 Complete: {} transactions inserted, {} duplicates, {} missing merchants, {} validation errors ===",
                totalTransactionsInserted, totalTransactionsDuplicate, totalMissingMerchants, totalTransactionValidationErrors);

        // NOTE: Pulizia STG viene eseguita insieme allo spostamento del file .eot
        // dopo il completamento con successo dell'intera ingestion (vedi riga 615)

        long totalElapsed = System.currentTimeMillis() - totalStartTime;
        log.info("=== STAGING INGESTION COMPLETE in {}ms ({} seconds) ===", totalElapsed, totalElapsed / 1000);
        log.info("Summary: merchants={}/{} inserted/dup, transactions={}/{}/{} inserted/dup/missing",
                totalMerchantsInserted, totalMerchantsDuplicate,
                totalTransactionsInserted, totalTransactionsDuplicate, totalMissingMerchants);
    }

    /**
     * LEGACY ROW-BY-ROW APPROACH
     * Original implementation with per-batch duplicate checks.
     * Kept for compatibility and rollback capability.
     */
    private void processFilesWithLegacyApproach(
            List<String> anagrafeFiles,
            List<String> transatoFiles,
            Submission submission,
            Obbligation obbligation) throws NotFoundRecordException, IOException {

        Ingestion currentIngestion = null;

        log.info("Phase 1: Processing {} anagrafe file(s) (merchants)", anagrafeFiles.size());
        IngestionType anagrafeType = ingestionTypeRepository.findByNameIgnoreCase("anagrafe")
                .orElseThrow(() -> new NotFoundRecordException("Ingestion type 'anagrafe' is not found"));

        for (String keyName : anagrafeFiles) {
            String fileName = keyName.substring(keyName.lastIndexOf('/') + 1);
            try (InputStream inputStream = s3Service.downloadFileAsStream(keyName)) {
                RemoteFile remoteFile = new RemoteFile(fileName, inputStream);

                log.debug("Processing anagrafe file: {}", fileName);
                currentIngestion = this.ingestionService.createIngestionBySubmission(submission, anagrafeType);
                log.debug("Created ingestion: id={} for file: {}", currentIngestion.getId(), fileName);

                long anagrafeProcessStart = System.nanoTime();

                transactionFileProcessingService.processWithImmediateSave(
                        remoteFile, currentIngestion, submission, obbligation, this);

                long anagrafeProcessFinish = System.nanoTime();

                long processingTime = Duration.ofNanos(anagrafeProcessFinish - anagrafeProcessStart).toSeconds();
                log.debug("Completed processing anagrafe file: {}, processing time: {} seconds",
                        remoteFile.name(), processingTime);
                ingestionService.markAsSuccess(currentIngestion);
                s3Service.moveFileFromInputToInputLoaded(remoteFile.name());
            }
        }

        log.info("Phase 2: Processing {} transato file(s) (transactions)", transatoFiles.size());

        IngestionType transatoType = ingestionTypeRepository.findByNameIgnoreCase("transato")
                .orElseThrow(() -> new NotFoundRecordException("Ingestion type 'transato' is not found"));

        for (String keyName : transatoFiles) {
            String fileName = keyName.substring(keyName.lastIndexOf('/') + 1);
            try (InputStream inputStream = s3Service.downloadFileAsStream(keyName)) {
                RemoteFile remoteFile = new RemoteFile(fileName, inputStream);

                log.debug("Processing transato file: {}", remoteFile.name());
                currentIngestion = this.ingestionService.createIngestionBySubmission(submission, transatoType);
                log.debug("Created ingestion: id={} for file: {}", currentIngestion.getId(), remoteFile.name());

                long transatoProcessStart = System.nanoTime();

                // Process file with immediate save to avoid OutOfMemory
                transactionFileProcessingService.processWithImmediateSave(
                        remoteFile, currentIngestion, submission, obbligation, this);

                long transatoProcessFinish = System.nanoTime();

                long transatoProcessingTime = Duration.ofNanos(transatoProcessFinish - transatoProcessStart).toSeconds();
                log.debug("Completed processing transato file: {}, processing time: {} seconds",
                        remoteFile.name(), transatoProcessingTime);
                ingestionService.markAsSuccess(currentIngestion);
                s3Service.moveFileFromInputToInputLoaded(remoteFile.name());
            }
        }
    }

    private void cleanUpFailedSubmission(Submission submission, Ingestion ingestion, Exception exception) throws NotFoundRecordException {
        // Build error message - handle case where ingestion is null (error occurred before ingestion was created)
        String errorMsg;
        if (ingestion != null) {
            errorMsg = "Ingestion ID: " + ingestion.getId() + " failed with error message: " + exception.getMessage();
        } else {
            errorMsg = "Ingestion failed before creation (error occurred during file processing setup) with error message: " + exception.getMessage();
        }
        
        // NOTE: LOG.message has a limited length in DB (seen "Data too long for column 'message'").
        // Truncate defensively to avoid masking the original failure with a secondary DB error.
        String safeLogMsg = errorMsg;
        if (safeLogMsg != null && safeLogMsg.length() > 250) {
            safeLogMsg = safeLogMsg.substring(0, 250);
        }

        Log logEntry = new Log();
        logEntry.setSubmission(submission);
        logEntry.setBeforeSubmissionStatus(submission.getCurrentSubmissionStatus());
        logEntry.setMessage(safeLogMsg);

        submissionService.markAsError(submission);

        logEntry.setAfterSubmissionStatus(submission.getCurrentSubmissionStatus());
        logRepository.save(logEntry);

        // CRITICAL: Check if there are pending records in staging before deleting merchants/transactions
        // If there are pending records, we might resume the process later, so we need to keep the merchants
        long[] pendingCounts = stagingRepository.countPendingRecords(submission.getId());
        long pendingTransactions = pendingCounts[0];
        long pendingMerchants = pendingCounts[1];
        
        if (pendingTransactions > 0 || pendingMerchants > 0) {
            log.info("Submission {} has pending records in staging ({} transactions, {} merchants). " +
                    "Keeping merchants in MERCHANT table to allow resume.", 
                    submission.getId(), pendingTransactions, pendingMerchants);
            // Only delete transactions that were already inserted (they will be re-processed from staging)
            transactionRepository.deleteBySubmissionId(submission.getId());
            // DO NOT delete merchants - they are needed for resume
        } else {
            log.info("Submission {} has no pending records in staging. Cleaning up all data.", submission.getId());
            transactionRepository.deleteBySubmissionId(submission.getId());
            merchantRepository.deleteBySubmissionId(submission.getId());
        }
        // CRITICAL: Delete ERROR_CAUSE BEFORE ERROR_RECORD (FK constraint)
        // ERROR_CAUSE has FK to ERROR_RECORD, so must delete children first
        try {
            errorCauseRepository.deleteBySubmissionId(submission.getId());
        } catch (Exception e) {
            log.warn("Failed to delete ERROR_CAUSE records for submission {}: {}", submission.getId(), e.getMessage());
        }
        errorRecordRepository.deleteBySubmissionId(submission.getId());

        // Only update ingestion status if ingestion was created
        if (ingestion != null) {
            IngestionStatus errorStatus = ingestionStatusRepository.findOneByName(IngestionStatusEnum.FAILED.name())
                    .orElseThrow(() -> new NotFoundRecordException("Ingestion status with name failed is not found"));

            IngestionError ingestionError = new IngestionError();
            // Same defensive truncation (in case DB column is limited)
            String safeIngestionErrorMsg = errorMsg;
            if (safeIngestionErrorMsg != null && safeIngestionErrorMsg.length() > 1000) {
                safeIngestionErrorMsg = safeIngestionErrorMsg.substring(0, 1000);
            }
            ingestionError.setDescription(safeIngestionErrorMsg);
            ingestionErrorRepository.save(ingestionError);

            ingestion.setIngestionStatus(errorStatus);
            ingestion.setIngestionError(ingestionError);
            ingestionRepository.save(ingestion);
        } else {
            log.warn("Cannot update ingestion status - ingestion was not created before error occurred");
        }
    }

    /**
     * Trova SOLO l'ULTIMA submission in stato ERROR con record pending in STG per la stessa obligation.
     * IMPORTANTE: Considera SOLO l'ultima submission ERROR (per data), non tutte le submission ERROR.
     * Le altre submission ERROR vengono ignorate e restano in stato ERROR.
     * 
     * @param obbligation l'obbligation per cui cercare
     * @return l'ultima submission ERROR con record pending, o null se non ce ne sono
     */
    private Submission findSubmissionWithPendingStagingRecords(Obbligation obbligation) {
        // Trova SOLO l'ULTIMA submission in ERROR (ordinata per lastUpdatedAt DESC)
        Optional<Submission> lastErrorSubmission = submissionRepository.findByObbligationId(obbligation.getId())
                .stream()
                .filter(s -> s.getCurrentSubmissionStatus() != null 
                        && s.getCurrentSubmissionStatus().getName().equals(SubmissionStatusEnum.ERROR.getDbName()))
                .max(Comparator.comparing(Submission::getLastUpdatedAt));
        
        if (lastErrorSubmission.isEmpty()) {
            log.debug("No ERROR submissions found for obligation {}", obbligation.getId());
            return null;
        }
        
        Submission submission = lastErrorSubmission.get();
        log.info("Found last ERROR submission {} for obligation {} (last updated: {})", 
                submission.getId(), obbligation.getId(), submission.getLastUpdatedAt());
        
        // Controlla se questa submission ha record pending in STG
        long[] pendingCounts = stagingRepository.countPendingRecords(submission.getId());
        long pendingTransactions = pendingCounts[0];
        long pendingMerchants = pendingCounts[1];
        
        if (pendingTransactions > 0 || pendingMerchants > 0) {
            log.info("Last ERROR submission {} has pending records: {} transactions, {} merchants", 
                    submission.getId(), pendingTransactions, pendingMerchants);
            return submission;
        } else {
            log.info("Last ERROR submission {} has no pending records in STG - will not resume", submission.getId());
            return null;
        }
    }
    
    /**
     * Riprende il processamento da STG per una submission che era fallita.
     * Processa solo i record con process_status IS NULL (non riprocessa i file).
     */
    private void resumeFromStaging(Submission submission, Obbligation obbligation, Period period) {
        log.info("Resuming ingestion from staging for submission: {}", submission.getId());
        
        // Conta record pending
        long[] pendingCounts = stagingRepository.countPendingRecords(submission.getId());
        long pendingTransactions = pendingCounts[0];
        long pendingMerchants = pendingCounts[1];
        
        log.info("Pending records in STG: {} transactions, {} merchants", pendingTransactions, pendingMerchants);
        
        // Processa merchant se ci sono record pending
        if (pendingMerchants > 0) {
            log.info("Resuming merchant processing from STG...");
            StagingResult merchantResult = stagingRepository.processMerchantsFromStaging(submission.getId());
            log.info("Merchant resume completed: inserted={}, duplicates={}", 
                    merchantResult.insertedCount(), merchantResult.duplicateCount());
            
            // Crea ErrorRecords per duplicati se necessario
            if (merchantResult.duplicateCount() > 0) {
                Ingestion merchantIngestion = ingestionRepository.findFirstBySubmission_IdAndIngestionType_Name(
                        submission.getId(), IngestionTypeEnum.MERCHANT.getLabel()).orElse(null);
                if (merchantIngestion != null) {
                    stagingIngestionService.createErrorRecordsForDuplicateMerchants(merchantIngestion, submission);
                }
            }
        }
        
        // Processa transactions se ci sono record pending
        if (pendingTransactions > 0) {
            log.info("Resuming transaction processing from STG...");
            StagingResult transactionResult = stagingRepository.processTransactionsFromStaging(submission.getId());
            log.info("Transaction resume completed: inserted={}, duplicates={}, missingMerchants={}", 
                    transactionResult.insertedCount(), transactionResult.duplicateCount(), 
                    transactionResult.missingMerchantCount());
            
            // Crea ErrorRecords per duplicati e missing merchants se necessario
            Ingestion transactionIngestion = ingestionRepository.findFirstBySubmission_IdAndIngestionType_Name(
                    submission.getId(), IngestionTypeEnum.TRANSACTIONS.getLabel()).orElse(null);
            if (transactionIngestion != null) {
                if (transactionResult.duplicateCount() > 0) {
                    stagingIngestionService.createErrorRecordsForDuplicateTransactions(transactionIngestion, submission);
                }
                if (transactionResult.missingMerchantCount() > 0) {
                    stagingIngestionService.createErrorRecordsForMissingMerchantTransactions(transactionIngestion, submission);
                }
            }
        }
        
        // Cleanup finale delle staging tables
        stagingIngestionService.cleanupStaging(submission.getId());
        log.info("Resume from staging completed for submission: {}", submission.getId());
    }

    /**
     * Sposta i file rimasti in input dopo un resume completato con successo.
     * Questo evita che i file vengano riprocessati alla prossima esecuzione.
     * I file vengono spostati solo se sono ancora presenti in input (non già spostati).
     * 
     * @param transatoFiles lista dei file transazioni trovati in input
     * @param anagrafeFiles lista dei file anagrafe trovati in input
     * @param eotFileName nome del file .eot
     */
    private void moveRemainingFilesAfterResume(List<String> transatoFiles, List<String> anagrafeFiles, String eotFileName) {
        log.info("Moving remaining files to input_loaded after successful resume...");
        
        // Sposta file transazioni (se ancora in input)
        for (String transatoFile : transatoFiles) {
            try {
                String fileName = transatoFile.substring(transatoFile.lastIndexOf('/') + 1);
                log.info("Moving transaction file to input_loaded: {}", fileName);
                s3Service.moveFileFromInputToInputLoaded(fileName);
            } catch (Exception e) {
                // Se il file è già stato spostato o non esiste, logga un warning ma continua
                log.warn("Could not move transaction file {} (may already be moved): {}", transatoFile, e.getMessage());
            }
        }
        
        // Sposta file anagrafe (se ancora in input - probabilmente già spostato)
        for (String anagrafeFile : anagrafeFiles) {
            try {
                String fileName = anagrafeFile.substring(anagrafeFile.lastIndexOf('/') + 1);
                log.info("Moving merchant file to input_loaded: {}", fileName);
                s3Service.moveFileFromInputToInputLoaded(fileName);
            } catch (Exception e) {
                // Se il file è già stato spostato o non esiste, logga un warning ma continua
                log.warn("Could not move merchant file {} (may already be moved): {}", anagrafeFile, e.getMessage());
            }
        }
        
        // Sposta file .eot (se ancora in input)
        if (eotFileName != null) {
            try {
                log.info("Moving EOT file to input_loaded: {}", eotFileName);
                s3Service.moveFileFromInputToInputLoaded(eotFileName);
            } catch (Exception e) {
                // Se il file è già stato spostato o non esiste, logga un warning ma continua
                log.warn("Could not move EOT file {} (may already be moved): {}", eotFileName, e.getMessage());
            }
        }
        
        log.info("File movement after resume completed");
    }

    private void retryFailedTransactions(Obbligation obbligation, Period period, Submission currentSubmission) {
        int periodOrder = period.getOrder();
        int year = obbligation.getFiscalYear();
        if (periodOrder == 1) {
            year--;
            periodOrder = 12;
        } else {
            periodOrder--;
        }
        Period lastPeriod = periodRepository.findByOrder(periodOrder).orElse(null);

        if (lastPeriod == null) return;

        Obbligation oldObligation = obligationRepository.findByFiscalYearAndPeriod(year, lastPeriod).orElse(null);
        if (oldObligation == null) return;
        oldObligation.setPeriod(lastPeriod);

        List<Submission> submissions = submissionRepository.findByObbligationId(oldObligation.getId());
        Submission activeSubmission = submissions.stream()
                .filter(s ->
                        !s.getCurrentSubmissionStatus().getName().equals(SubmissionStatusEnum.CANCELLED.getDbName())
                                && !s.getCurrentSubmissionStatus().getName().equals(SubmissionStatusEnum.REJECTED.getDbName())
                )
                .max(Comparator.comparing(Submission::getLastUpdatedAt))
                .orElse(null);

        if (activeSubmission == null) return;

        Ingestion ingestion = ingestionRepository.findFirstBySubmission_IdAndIngestionType_Name(
                activeSubmission.getId(), IngestionTypeEnum.TRANSACTIONS.getLabel()).orElse(null);

        if (ingestion == null) return;

        List<ErrorRecord> failedTransactions = errorRecordRepository.findRecordsWithExactlyOneCauseOfType(
                ingestion.getId(), ErrorTypeCode.FOREIGN_KEY_ERROR.getErrorCode());

        List<String> rawRows = failedTransactions.stream()
                .map(ErrorRecord::getRawRow)
                .toList();

        if (rawRows.isEmpty()) return;

        List<ProcessedFailedRecordBatch> processedBatches = transactionFileProcessingService.processFailedTransactions(
                rawRows, ingestion, activeSubmission, oldObligation);

        for (ProcessedFailedRecordBatch batch : processedBatches) {
            if (!batch.transactions().isEmpty()) {
                resolvedTransactionRepository.bulkInsert(batch.transactions(), currentSubmission, activeSubmission);

                Set<String> resolvedRawRows = batch.transactions().stream()
                        .map(ResolvedTransaction::getRawRow)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

                if (!resolvedRawRows.isEmpty()) {
                    List<ErrorRecord> toDelete = failedTransactions.stream()
                            .filter(er -> er.getRawRow() != null && resolvedRawRows.contains(er.getRawRow()))
                            .toList();

                    if (!toDelete.isEmpty()) {
                        log.info("Deleting {} resolved error record(s) from ingestion {}", toDelete.size(), ingestion.getId());
                        errorRecordRepository.deleteAll(toDelete);
                    }
                }
            }
        }
    }
}