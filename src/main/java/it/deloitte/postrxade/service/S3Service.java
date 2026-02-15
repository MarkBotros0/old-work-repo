package it.deloitte.postrxade.service;

import it.deloitte.postrxade.exception.NotFoundRecordException;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.util.List;

/**
 * Service interface for AWS S3 operations.
 */
public interface S3Service {

    /**
     * Uploads a file to S3.
     *
     * @param key the S3 object key, which might simply be the fullpath of the object
     * @param inputStream the file input stream
     * @param contentType the content type
     * @return the S3 object key
     */
    String uploadFile(String key, InputStream inputStream, String contentType);

    /**
     * Downloads a file from S3.
     *
     * @param key the S3 object key
     * @return the file as Resource
     */
    Resource downloadFile(String key);

    /**
     * Downloads a file from S3 with content length for correct HTTP response headers.
     * Prefer this for streaming through proxies (e.g. Envoy) to avoid 502 when using chunked encoding.
     *
     * @param key the S3 object key
     * @return the file as Resource and its content length (or -1 if unknown)
     */
    DownloadWithLength downloadFileWithLength(String key);

    /** Result of download with metadata (content length). */
    record DownloadWithLength(Resource resource, long contentLength) {}

    /**
     * Downloads a file from S3 as InputStream.
     *
     * @param key the S3 object key
     * @return the file as InputStream
     */
    InputStream downloadFileAsStream(String key);

    /**
     * Lists all objects in the bucket with the given prefix.
     *
     * @param prefix the prefix to filter objects
     * @return list of S3 objects
     */
    List<S3Object> listObjects(String prefix);

    /**
     * Deletes a file from S3.
     *
     * @param key the S3 object key
     */
    void deleteFile(String key);

    /**
     * Checks if a file exists in S3.
     *
     * @param key the S3 object key
     * @return true if file exists, false otherwise
     */
    boolean fileExists(String key);

    /**
     * Gets the S3 bucket name.
     *
     * @return the bucket name
     */
    String getBucketName();

    void moveFileFromInputToInputLoaded(String fileName);

    List<String> fetchFileKeysFromBucket() throws NotFoundRecordException;

    /**
     * Generates a presigned URL for downloading a file from S3.
     * Useful for large files to allow direct download from S3 without going through the backend.
     *
     * @param key the S3 object key
     * @param expirationMinutes expiration time in minutes (default: 60)
     * @return the presigned URL
     */
    String generatePresignedDownloadUrl(String key, int expirationMinutes);
}
