package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import javax.annotation.PostConstruct;
import java.io.InputStream;

/**
 * Service for managing file operations with Amazon S3.
 * Replaces local file system operations for container portability.
 */
@Service
public class S3Service {

    @Value("${aws.s3.bucket.name}")
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
     * Upload content to S3 bucket
     * @param key S3 object key (file path)
     * @param content Content to upload
     * @return S3 object URL
     */
    public String uploadFile(String key, String content) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromString(content));
        
        return String.format("s3://%s/%s", bucketName, key);
    }

    /**
     * Get file from S3 bucket
     * @param key S3 object key
     * @return InputStream of the file
     */
    public InputStream getFile(String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        return s3Client.getObject(getObjectRequest);
    }

    /**
     * Generate S3 object key from file path
     * @param fileName File name
     * @return S3 object key
     */
    public String generateS3Key(String fileName) {
        return "reports/" + fileName;
    }
}
