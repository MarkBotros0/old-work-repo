package it.deloitte.postrxade.batch;

import it.deloitte.postrxade.service.ResponseRunFileService;
import it.deloitte.postrxade.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@Profile("response")
@Slf4j
public class ResponseProcessingRunner implements CommandLineRunner {

    private static final String DEFAULT_TENANT = "nexi";

    @Autowired
    private ResponseRunFileService responseRunFileService;

    @Value("${TENANT_ID:nexi}")
    private String tenantId;

    @Value("${SUBMISSION_ID:}")
    private String submissionIdEnv;

    @Override
    public void run(String... args) {
        Instant startTime = Instant.now();
        String effectiveTenant = tenantId != null && !tenantId.isBlank() ? tenantId.trim() : DEFAULT_TENANT;
        TenantContext.setTenantId(effectiveTenant);
        log.info("Response run tenant: {}", effectiveTenant);

        log.info("==========================================================");
        log.info("   ECS RESPONSE PROCESSING STARTING");
        log.info("==========================================================");
        log.info("Start time: {}", startTime);

        try {
            Long submissionId = parseSubmissionId(submissionIdEnv);
            log.info("Starting response .run processing from S3 for submissionId={}...", submissionId);
            responseRunFileService.processSubmissionResponseRunFiles(submissionId);

            Duration duration = Duration.between(startTime, Instant.now());
            log.info("==========================================================");
            log.info("   ECS RESPONSE PROCESSING COMPLETED SUCCESSFULLY");
            log.info("==========================================================");
            log.info("Submission ID: {}", submissionId);
            log.info("Total duration: {} minutes {} seconds", duration.toMinutes(), duration.toSecondsPart());
            log.info("End time: {}", Instant.now());
            System.exit(0);
        } catch (Exception e) {
            Duration duration = Duration.between(startTime, Instant.now());
            log.error("==========================================================");
            log.error("   ECS RESPONSE PROCESSING FAILED");
            log.error("==========================================================");
            log.error("Error: {}", e.getMessage(), e);
            log.error("Duration before failure: {} minutes {} seconds", duration.toMinutes(), duration.toSecondsPart());
            System.exit(1);
        } finally {
            TenantContext.clear();
        }
    }

    private Long parseSubmissionId(String submissionIdRaw) {
        if (submissionIdRaw == null || submissionIdRaw.isBlank()) {
            throw new IllegalArgumentException("SUBMISSION_ID environment variable is required for response profile");
        }
        try {
            return Long.parseLong(submissionIdRaw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid SUBMISSION_ID format: " + submissionIdRaw, e);
        }
    }
}
