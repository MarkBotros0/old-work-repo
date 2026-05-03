package it.deloitte.postrxade.service.impl;

import it.deloitte.postrxade.dto.SubmissionStatusChangeDTO;
import it.deloitte.postrxade.dto.SubmissionStatusGroupDTO;
import it.deloitte.postrxade.dto.UserDTO;
import it.deloitte.postrxade.entity.*;
import it.deloitte.postrxade.enums.SubmissionStatusEnum;
import it.deloitte.postrxade.exception.InvalidStatusTransitionException;
import it.deloitte.postrxade.exception.NotFoundRecordException;
import it.deloitte.postrxade.exception.ResourceNotFoundException;
import it.deloitte.postrxade.exception.UserNotValidException;
import it.deloitte.postrxade.repository.SubmissionRepository;
import it.deloitte.postrxade.repository.SubmissionStatusGroupRepository;
import it.deloitte.postrxade.repository.SubmissionStatusRepository;
import it.deloitte.postrxade.service.OutputService;
import it.deloitte.postrxade.tenant.TenantConfiguration;
import it.deloitte.postrxade.tenant.TenantContext;
import it.deloitte.postrxade.service.SubmissionService;
import it.deloitte.postrxade.service.UserService;
import it.deloitte.postrxade.utils.AuditLogger;
import it.deloitte.postrxade.utils.PermissionUtil;
import lombok.extern.slf4j.Slf4j;
import ma.glasnost.orika.MapperFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class SubmissionServiceImpl implements SubmissionService {


    private final AuditLogger appLogger;

    private final UserService userService;

    private final OutputService outputService;

    private final PermissionUtil permissionUtil;

    private SubmissionRepository submissionRepository;

    private SubmissionStatusRepository submissionStatusRepository;

    private final SubmissionStatusGroupRepository submissionStatusGroupRepository;

    @Autowired
    private @Qualifier("alternativeMapperFacade") MapperFacade alternativeMapperFacade;


    public SubmissionServiceImpl(UserService userService, PermissionUtil permissionUtil, SubmissionRepository submissionRepository,
                                 SubmissionStatusGroupRepository submissionStatusGroupRepository, SubmissionRepository submissionTypeRepository, AuditLogger appLogger, OutputService outputService) {
        this.userService = userService;
        this.permissionUtil = permissionUtil;
        this.submissionRepository = submissionRepository;
        this.submissionStatusGroupRepository = submissionStatusGroupRepository;
        this.appLogger = appLogger;
        this.outputService = outputService;
    }

    @Autowired
    public void setSubmissionRepository(SubmissionRepository submissionRepository, SubmissionStatusRepository submissionStatusRepository) {
        this.submissionStatusRepository = submissionStatusRepository;
        this.submissionRepository = submissionRepository;
    }

    public void changeStatus(Submission submission, SubmissionStatus submissionStatusDestination, UserDTO userDTO) throws IOException, NotFoundRecordException {

        SubmissionStatus beforeSubmissionStatus = submission.getCurrentSubmissionStatus();

        if (!(Objects.equals(submissionStatusDestination.getOrder(), SubmissionStatusEnum.CANCELLED.getOrder())) && !(Objects.equals(submissionStatusDestination.getOrder(), SubmissionStatusEnum.REJECTED.getOrder()))) { // if we want to cancel the submission don't validate the step
            validateSubmissionOrder(submission, submissionStatusDestination);
        } else {
            submission.setLastSubmissionStatus(submission.getCurrentSubmissionStatus());
            submission.setCancelledAt(LocalDateTime.now());

        }
        submission.setCurrentSubmissionStatus(submissionStatusDestination);
        submission.setLastUpdateBy(alternativeMapperFacade.map(userDTO, User.class));
        submissionRepository.save(submission);

        // log it
        appLogger.save(Log.builder()
                .message(String.format(AuditLogger.STATUS_CHANGE,
                        userDTO.getFirstName(),
                        userDTO.getLastName(),
                        submission.getId(),
                        submission.getObbligation().getFiscalYear(),
                        submission.getObbligation().getPeriod().getName(),
                        beforeSubmissionStatus.getName(),
                        submissionStatusDestination.getName()))
                .timestamp(Instant.now())
                .submission(submission)
                .updater(alternativeMapperFacade.map(userDTO, User.class))
                .beforeSubmissionStatus(beforeSubmissionStatus)
                .afterSubmissionStatus(submissionStatusDestination)
                .build());
    }

    private void validateSubmissionOrder(Submission submission, SubmissionStatus submissionStatusDestination) {
        int diff = Math.abs(submission.getCurrentSubmissionStatus().getOrder()
                - submissionStatusDestination.getOrder());

        if (diff > 1) {
            throw new InvalidStatusTransitionException(
                    "Cannot skip statuses: current=" + submission.getCurrentSubmissionStatus().getName() +
                            ", attempted=" + submissionStatusDestination.getName()
            );
        }

    }

    @Override
    @Transactional
    public void performChangeStatusAction(SubmissionStatusChangeDTO submissionStatusChangeDTO)
            throws UserNotValidException, NotFoundRecordException, IOException {

        String funcIdentifier = "[UPD_SUB_STATUS-" + submissionStatusChangeDTO.getSubmissionId() + "]";
        log.info("{} Starting performChangeStatusAction for destinationStatusOrder={}", funcIdentifier, submissionStatusChangeDTO.getDestinationStatusOrder());

        UserDTO userDetails = userService.getCurrentUser();
        log.debug("{} Retrieved current user: id={}", funcIdentifier, userDetails.getId());

        Submission submission = submissionRepository.findOneById(submissionStatusChangeDTO.getSubmissionId())
                .orElseThrow(() -> {
                    log.warn("{} Submission not found for id={}", funcIdentifier, submissionStatusChangeDTO.getSubmissionId());
                    return new NotFoundRecordException("Submission not found with id: " + submissionStatusChangeDTO.getSubmissionId());
                });

        log.debug("{} Found submission with currentStatusOrder={}", funcIdentifier, submission.getCurrentSubmissionStatus().getOrder());

        SubmissionStatus destinationSubmissionStatus = submissionStatusRepository.findOneByOrder(submissionStatusChangeDTO.getDestinationStatusOrder())
                .orElseThrow(() -> {
                    log.warn("{} Destination status not found for order={}", funcIdentifier, submissionStatusChangeDTO.getDestinationStatusOrder());
                    return new NotFoundRecordException("Submission Status not found with order: " + submissionStatusChangeDTO.getDestinationStatusOrder());
                });

        log.debug("{} Destination status resolved successfully: {}", funcIdentifier, destinationSubmissionStatus.getName());

        Integer currentStatusOrder = submission.getCurrentSubmissionStatus().getOrder();

        if (!permissionUtil.isPermitted(userDetails, submissionStatusChangeDTO.getDestinationStatusOrder(), currentStatusOrder)) {
            log.warn("{} Permission denied for userId={} to change status from {} -> {}",
                    funcIdentifier, userDetails.getId(), currentStatusOrder, submissionStatusChangeDTO.getDestinationStatusOrder());
            throw new UserNotValidException("User does not have permission for this status change.");
        }

        log.info("{} Attempting status change: from {} to {}", funcIdentifier, currentStatusOrder, submissionStatusChangeDTO.getDestinationStatusOrder());
        changeStatus(submission, destinationSubmissionStatus, userDetails);
        log.info("{} Status change completed successfully for submissionId={}", funcIdentifier, submissionStatusChangeDTO.getSubmissionId());

        if (SubmissionStatusEnum.isOutputRequired(submission.getCurrentSubmissionStatus().getOrder())) {
            outputService.generateSubmissionOutputTxtAsync(submission.getId());
        }
    }


    /**
     * Gets ALL groups and their statuses.
     */
    @Override
    @Transactional(readOnly = true)
    public List<SubmissionStatusGroupDTO> getStatusOptions() {

        List<SubmissionStatusGroup> groups = submissionStatusGroupRepository.findAll();

        return alternativeMapperFacade.mapAsList(groups, SubmissionStatusGroupDTO.class);
    }

    @Override
    public Submission createSubmissionByObligation(Obbligation obbligation) throws NotFoundRecordException {
        Submission submission = new Submission();
        submission.setObbligation(obbligation);
        submission.setIsManual(Boolean.TRUE);
        submission.setLastUpdatedAt(LocalDateTime.now());

        // Submission deadline = last day of the month following the obligation's period.
        // Es.: obligation DICEMBRE 2025 → deadline 31/01/2026; obligation gennaio 2026 → deadline 28/02/2026.
        submission.setDeadlineDate(computeSubmissionDeadline(obbligation));

        // Usa INGESTION_FINISHED (order 1) come status iniziale per le nuove submission
        // La submission passerà a DATA_VALIDATION durante il processamento e poi a VALIDATION_COMPLETED alla fine
        SubmissionStatus submissionStatus = submissionStatusRepository.findOneByOrder(1)
                .orElseThrow(() -> new NotFoundRecordException(
                        "SubmissionStatus with order 1 (INGESTION_FINISHED) not found. " +
                                "Please ensure the database is properly initialized with submission statuses."));

        submission.setCurrentSubmissionStatus(submissionStatus);
        Submission saved = submissionRepository.save(submission);

        // batch_id = TENANT_SUBMISSIONID_YYYYMM (es. NEXI_123_202512, AMEX_456_202601)
        String batchId = buildSubmissionBatchId(saved, obbligation);
        if (batchId != null) {
            saved.setBatchId(batchId);
            submissionRepository.save(saved);
        }
        return saved;
    }

    /**
     * Builds the submission batch_id: TENANT_SUBMISSIONID_OBBLIGATIONYEAROBBLIGATIONMONTH.
     * Tenant = NEXI o AMEX; year = YYYY; month = MM (01-12).
     */
    private String buildSubmissionBatchId(Submission submission, Obbligation obbligation) {
        if (submission == null || submission.getId() == null || obbligation == null) {
            return null;
        }
        String tenant = TenantContext.getTenantId();
        if (tenant != null && !tenant.isBlank()) {
            tenant = TenantConfiguration.resolveTenantAlias(tenant.trim());
        }
        if (tenant == null || tenant.isBlank()) {
            return null;
        }
        String tenantUpper = tenant.toUpperCase();
        Integer year = obbligation.getFiscalYear();
        if (year == null) return null;
        if (obbligation.getPeriod() == null) return null;
        int monthOrder = obbligation.getPeriod().getOrder();
        String monthStr = monthOrder >= 1 && monthOrder <= 12
                ? String.format("%02d", monthOrder)
                : String.valueOf(monthOrder);
        return tenantUpper + "_" + submission.getId() + "_" + year + monthStr;
    }

    /**
     * Computes the submission deadline as the last day of the month following the obligation's period.
     * Obligation "December 2025" (period order 12, fiscalYear 2025) → 31 January 2026.
     * Obligation "January 2026" (period order 1, fiscalYear 2026) → 28 February 2026.
     *
     * @param obbligation The obligation the submission belongs to.
     * @return The deadline date, or null if obligation/period/fiscalYear are missing.
     */
    private LocalDate computeSubmissionDeadline(Obbligation obbligation) {
        if (obbligation == null || obbligation.getPeriod() == null || obbligation.getFiscalYear() == null) {
            return null;
        }
        int monthOrder = obbligation.getPeriod().getOrder();
        int year = obbligation.getFiscalYear();
        int nextMonth;
        int nextYear;
        if (monthOrder == 12) {
            nextMonth = 1;
            nextYear = year + 1;
        } else {
            nextMonth = monthOrder + 1;
            nextYear = year;
        }
        return YearMonth.of(nextYear, nextMonth).atEndOfMonth();
    }

    @Override
    @Transactional
    public void markAsError(Submission submission) {
//        error status
        SubmissionStatus submissionStatus = submissionStatusRepository.findOneByOrder(13)
                .orElseThrow(() -> new ResourceNotFoundException(" Status with order 13 is not found"));
        submissionRepository.updateStatus(submission.getId(), submissionStatus);
    }

    /**
     * Updates submission status without user context (for internal/system use).
     * Used during automated ingestion process.
     *
     * @param submission  The submission to update
     * @param statusOrder The order of the target status (1=INGESTION_FINISHED, 2=DATA_VALIDATION, 3=VALIDATION_COMPLETED, etc.)
     * @throws NotFoundRecordException If the status is not found
     */
    @Transactional
    public void updateStatusInternal(Submission submission, Integer statusOrder) throws NotFoundRecordException {
        SubmissionStatus submissionStatus = submissionStatusRepository.findOneByOrder(statusOrder)
                .orElseThrow(() -> new NotFoundRecordException(
                        "SubmissionStatus with order " + statusOrder + " not found. " +
                                "Please ensure the database is properly initialized with submission statuses."));
        submissionRepository.updateStatus(submission.getId(), submissionStatus);
        log.info("Updated submission {} status to order {} ({})",
                submission.getId(), statusOrder, submissionStatus.getName());
    }

    @Override
    public Submission getActivSubmission(Obbligation obbligation) {
        return submissionRepository
                .findByObbligationId(obbligation.getId())
                .stream()
                .filter(s -> {
                    return !s.getCurrentSubmissionStatus().getName().equals(SubmissionStatusEnum.ERROR.getDbName())
                            && !s.getCurrentSubmissionStatus().getName().equals(SubmissionStatusEnum.REJECTED.getDbName())
                            && !s.getCurrentSubmissionStatus().getName().equals(SubmissionStatusEnum.CANCELLED.getDbName());
                }).findFirst()
                .orElse(null);
    }
}
