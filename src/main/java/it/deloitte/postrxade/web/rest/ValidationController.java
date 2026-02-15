package it.deloitte.postrxade.web.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.deloitte.postrxade.dto.DataQualityIssueDTO;
import it.deloitte.postrxade.dto.ValidationPageDTO;
import it.deloitte.postrxade.service.S3Service;
import org.springframework.core.io.Resource;
import it.deloitte.postrxade.enums.AuthIdProfilo;
import it.deloitte.postrxade.exception.NotFoundRecordException;
import it.deloitte.postrxade.security.RequireAuthorities;
import it.deloitte.postrxade.service.UserService;
import it.deloitte.postrxade.service.ValidationService;
import it.deloitte.postrxade.utils.AuditLogger;
import ma.glasnost.orika.MapperFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;


/**
 * REST controller for managing validation data, error retrieval, and asynchronous report generation.
 * <p>
 * This controller provides endpoints to:
 * <ul>
 * <li>Retrieve validation summaries based on Fiscal Year (FY) and Period.</li>
 * <li>Fetch specific data quality issues/errors.</li>
 * <li>Handle the asynchronous generation of Excel reports (Start Job, Check Status, Get Result).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/validations")
@Tag(name = "Validation Reporting Service", description = "The main endpoint for Validation and Reporting")
public class ValidationController {

    private final ValidationService validationService;

    @Autowired
    private @Qualifier("alternativeMapperFacade") MapperFacade alternativeMapperFacade;

    @Autowired
    private S3Service s3Service;

    public ValidationController(AuditLogger appLogger, ValidationService validationService, UserService userService) {
        this.validationService = validationService;
    }


    /**
     * Retrieves the validation summary for submissions based on the Fiscal Year and Period.
     * <p>
     * Endpoint: GET /api/validations/submissions/validation
     *
     * @param fy     The Fiscal Year (e.g., 2024).
     * @param period The specific month name (e.g. "January" ).
     * @return ResponseEntity containing the {@link ValidationPageDTO} with summary data.
     * @throws NotFoundRecordException if the obligation state is invalid (e.g., multiple active submissions).
     */
    @GetMapping("/submissions/validation")
    @Operation(summary = "Get submission validation summary", description = "Request to get validation summary by given FY and Period")
    @RequireAuthorities({
            AuthIdProfilo.MANAGER,
            AuthIdProfilo.REVIEWER,
            AuthIdProfilo.APPROVER
    })
    public ResponseEntity<ValidationPageDTO> getObligationValidation(
            @RequestParam("fy") Integer fy,
            @RequestParam("period") String period) throws NotFoundRecordException {
        ValidationPageDTO validationData = validationService.getValidationSummary(fy, period);
        return new ResponseEntity<>(validationData, HttpStatus.OK);
    }

    /**
     * Retrieves specific data quality issues (errors) based on the criteria provided.
     * <p>
     * Endpoint: GET /api/validations/errors
     *
     * @param fy     The Fiscal Year.
     * @param period The specific month name (e.g. "January" ).
     * @param type   The type of error/issue to filter by.
     * @return A list of {@link DataQualityIssueDTO} objects representing the errors found.
     * @throws NotFoundRecordException if the obligation state is invalid (e.g., multiple active submissions).
     */
    @GetMapping("/issues")
    @Operation(summary = "Get obligation validation summary", description = "Request to get obligation error rows by given FY and Period")
    @RequireAuthorities({
            AuthIdProfilo.MANAGER,
            AuthIdProfilo.REVIEWER,
            AuthIdProfilo.APPROVER
    })
    public List<DataQualityIssueDTO> getErrors(
            @RequestParam("fiscalYear") Integer fy,
            @RequestParam("period") String period,
            @RequestParam("category") String category,
            @RequestParam(value = "type", defaultValue = "ALL") String type
    ) throws NotFoundRecordException {
        return validationService.getDataQualityIssueDTOlIST(fy, period, category, type);
    }

    /**
     * Initiates the asynchronous report generation process.
     * <p>
     * Endpoint: POST/GET /api/validations/download/start
     * <p>
     * Logic moved to Service: {@link ValidationService#startReportJob(Integer, String)} handles
     * ID generation, job creation, and starting the async process.
     * <p>
     * Supports both GET and POST methods for compatibility with frontend implementations.
     *
     * @param fy     The Fiscal Year for the report.
     * @param period The specific month name for the report (e.g. "January").
     * @return A Map containing the generated "jobId".
     */
    @RequestMapping(value = "/download/start", method = {RequestMethod.GET, RequestMethod.POST})
    @Operation(summary = "Start report generation", description = "Returns a Job ID to track progress")
    @RequireAuthorities({
            AuthIdProfilo.MANAGER,
            AuthIdProfilo.REVIEWER,
            AuthIdProfilo.APPROVER
    })
    public ResponseEntity<Map<String, String>> startReportGeneration(
            @RequestParam("fy") Integer fy,
            @RequestParam("period") String period) {

        String jobId = validationService.startReportJob(fy, period);

        return ResponseEntity.ok(Map.of("jobId", jobId));
    }

    /**
     * Polls the status of a specific report generation job.
     * <p>
     * Endpoint: GET /api/validations/download/status/{jobId}
     *
     * @param jobId The UUID string of the job returned by the start endpoint.
     * @return A Map containing the current "status" of the job (e.g., ACCEPTED, PROCESSING, COMPLETED).
     * Returns 404 NOT_FOUND if the jobId does not exist.
     */
    @GetMapping("/download/status/{jobId}")
    @RequireAuthorities({
            AuthIdProfilo.MANAGER,
            AuthIdProfilo.REVIEWER,
            AuthIdProfilo.APPROVER
    })
    public ResponseEntity<Map<String, String>> getJobStatus(@PathVariable String jobId) {

        String status = validationService.getJobStatus(jobId);

        if ("NOT_FOUND".equals(status)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", "NOT_FOUND"));
        }

        return ResponseEntity.ok(Map.of("status", status));
    }

    /**
     * Retrieves the final result of the report generation job.
     * <p>
     * Returns the generated CSV file directly as a download.
     * This maintains compatibility with the frontend - it just needs to handle file download
     * instead of processing JSON data.
     * <p>
     * The Service layer now handles:
     * 1. Checking if the job is COMPLETED.
     * 2. Removing the job from memory.
     * 3. Logging the audit event.
     * 
     * @param jobId The UUID string of the job.
     * @return The CSV file as a downloadable resource, or BAD_REQUEST if job not found/completed.
     */
    /**
     * Retrieves the final result of the report generation job.
     * <p>
     * Returns JSON with file information for compatibility with existing frontend.
     * The frontend can then call the download endpoint to get the actual file.
     * <p>
     * The Service layer now handles:
     * 1. Checking if the job is COMPLETED.
     * 2. Removing the job from memory.
     * 3. Logging the audit event.
     * 
     * @param jobId The UUID string of the job.
     * @return JSON with fileUrl and downloadUrl for the generated Excel file.
     */
    @GetMapping("/download/result/{jobId}")
    @RequireAuthorities({
            AuthIdProfilo.MANAGER,
            AuthIdProfilo.REVIEWER,
            AuthIdProfilo.APPROVER
    })
    public ResponseEntity<Map<String, String>> getJobResult(@PathVariable String jobId) {
        try {
            String fileUrl = validationService.getJobResult(jobId);
            String fileName = fileUrl.substring(fileUrl.lastIndexOf('/') + 1);
            
            // Return JSON with file information for compatibility
            // Frontend can then call /download/file endpoint to download
            return ResponseEntity.ok(Map.of(
                "fileUrl", fileUrl,
                "downloadUrl", "/api/validations/download/file?key=" + java.net.URLEncoder.encode(fileUrl, java.nio.charset.StandardCharsets.UTF_8),
                "fileName", fileName
            ));
        } catch (IllegalStateException e) {
            // Service throws IllegalStateException if job is not found or not completed
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Downloads the generated Excel file from S3.
     * <p>
     * Endpoint: GET /api/validations/download/file?key={s3Key}
     * <p>
     * This endpoint streams the file from S3 through the backend.
     * The backend has IAM Role permissions to access S3.
     *
     * @param key The S3 key/path of the file to download.
     * @return The Excel file as a downloadable resource.
     */
    @GetMapping("/download/file")
    @RequireAuthorities({
            AuthIdProfilo.MANAGER,
            AuthIdProfilo.REVIEWER,
            AuthIdProfilo.APPROVER
    })
    public ResponseEntity<Resource> downloadFile(@RequestParam("key") String key) {
        try {
            // Decode URL-encoded key (use as-is: must match the key used at upload time)
            String decodedKey = java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8);

            // Usa download con Content-Length per evitare 502 con proxy (Envoy) quando si streama in chunked
            S3Service.DownloadWithLength download = s3Service.downloadFileWithLength(decodedKey);
            String fileName = decodedKey.substring(decodedKey.lastIndexOf('/') + 1);

            // Determine content type based on file extension
            String contentType = fileName.endsWith(".xlsx")
                    ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    : "text/csv; charset=utf-8";

            var responseBuilder = ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + fileName + "\"")
                    .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, contentType);
            if (download.contentLength() > 0) {
                responseBuilder.header(org.springframework.http.HttpHeaders.CONTENT_LENGTH, String.valueOf(download.contentLength()));
            }
            return responseBuilder.body(download.resource());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }
}
