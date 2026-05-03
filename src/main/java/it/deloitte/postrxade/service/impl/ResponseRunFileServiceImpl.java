package it.deloitte.postrxade.service.impl;

import it.deloitte.postrxade.entity.Output;
import it.deloitte.postrxade.repository.OutputRepository;
import it.deloitte.postrxade.service.ResponseRunFileService;
import it.deloitte.postrxade.service.S3Service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class ResponseRunFileServiceImpl implements ResponseRunFileService {

    private static final Pattern RESPONSE_RUN_FILE_PATTERN =
            Pattern.compile("ATDLTQ2\\.S0070092\\.D\\d+\\.T(\\d+)\\.run$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTPUT_PART_PATTERN =
            Pattern.compile("_part(\\d+)\\.[^.]+$", Pattern.CASE_INSENSITIVE);

    @Autowired
    private S3Service s3Service;

    @Autowired
    private OutputRepository outputRepository;

    @Value("${aws.s3.input-folder}")
    private String inputFolder;

    @Override
    public void processSubmissionResponseRunFiles(Long submissionId) {
        String folderPrefix = inputFolder.replaceAll("/$", "") + "/" + submissionId + "/";
        List<S3Object> objects = s3Service.listObjects(folderPrefix);

        List<String> responseRunKeys = objects.stream()
                .map(S3Object::key)
                .filter(key -> !key.endsWith("/"))
                .filter(this::isResponseRunFile)
                .sorted(Comparator.comparingLong(this::extractRunSequence))
                .toList();

        List<Output> submissionOutputs = outputRepository.findBySubmissionId(submissionId).stream()
                .filter(output -> output.getFullPath() != null)
                .filter(output -> !output.getFullPath().contains("OUTPUT_RESOLVED_"))
                .filter(output -> extractOutputPart(output.getFullPath()) >= 0)
                .sorted(Comparator.comparingInt(o -> extractOutputPart(o.getFullPath())))
                .toList();

        log.info("Response run processing for submission {}: {} run file(s), {} output file(s)",
                submissionId, responseRunKeys.size(), submissionOutputs.size());

        if (responseRunKeys.size() != submissionOutputs.size()) {
            log.warn("Mismatch between response .run files ({}) and output files ({}) for submission {}. " +
                            "Continuing with min cardinality mapping as requested.",
                    responseRunKeys.size(), submissionOutputs.size(), submissionId);
        }

        int mappingCount = Math.min(responseRunKeys.size(), submissionOutputs.size());
        for (int i = 0; i < mappingCount; i++) {
            String runKey = responseRunKeys.get(i);
            Output output = submissionOutputs.get(i);
            long runSeq = extractRunSequence(runKey);
            int outputPart = extractOutputPart(output.getFullPath());

            log.info("Mapped response file [{}] (T={}) to output file [{}] (outputId={}, part={}) for submission {}",
                    runKey, runSeq, output.getFullPath(), output.getId(), outputPart, submissionId);
        }
    }

    private boolean isResponseRunFile(String key) {
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        return RESPONSE_RUN_FILE_PATTERN.matcher(fileName).matches();
    }

    private long extractRunSequence(String key) {
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        Matcher matcher = RESPONSE_RUN_FILE_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return Long.MAX_VALUE;
        }
        return Long.parseLong(matcher.group(1));
    }

    private int extractOutputPart(String outputPath) {
        String fileName = outputPath.substring(outputPath.lastIndexOf('/') + 1);
        Matcher matcher = OUTPUT_PART_PATTERN.matcher(fileName);
        if (!matcher.find()) {
            return -1;
        }
        return Integer.parseInt(matcher.group(1));
    }
}
