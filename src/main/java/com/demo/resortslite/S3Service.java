package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * S3Service provides file storage operations using Amazon S3.
 * Replaces local file system operations for container portability.
 */
@Service
public class S3Service {

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    @Value("${aws.s3.region}")
    private String region;

    private S3Client s3Client;

    @PostConstruct
    public void init() {
        this.s3Client = S3Client.builder()
                .region(Region.of(region))
                .build();
    }

    /**
     * Upload file content to S3
     * @param key S3 object key (file path)
     * @param content File content as bytes
     */
    public void uploadFile(String key, byte[] content) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        
        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(content));
    }

    /**
     * Upload file content to S3
     * @param key S3 object key (file path)
     * @param content File content as string
     */
    public void uploadFile(String key, String content) {
        uploadFile(key, content.getBytes());
    }

    /**
     * Download file content from S3
     * @param key S3 object key (file path)
     * @return File content as bytes
     */
    public byte[] downloadFile(String key) throws IOException {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        
        try (InputStream inputStream = s3Client.getObject(getObjectRequest);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, length);
            }
            return outputStream.toByteArray();
        }
    }

    /**
     * Generate S3 object key from file path
     * @param filePath Original file path
     * @return S3 object key
     */
    public String generateS3Key(String filePath) {
        // Remove leading slashes and backslashes, replace path separators
        return filePath.replaceAll("^[/\\\\]+", "")
                .replace("\\", "/")
                .replace(":", "_");
    }

    /**
     * Get S3 bucket name
     * @return Configured S3 bucket name
     */
    public String getBucketName() {
        return bucketName;
    }
}
