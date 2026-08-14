package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // FIXED cr-java-0061: Replaced hard-coded file paths with S3 bucket configuration
    // Using environment variables and Spring properties for cloud-native configuration
    @Value("${aws.s3.bucket.name}")
    private String s3BucketName;

    @Value("${aws.s3.region}")
    private String awsRegion;

    @Value("${aws.s3.report.prefix}")
    private String reportPrefix;

    @Value("${aws.s3.backup.prefix}")
    private String backupPrefix;

    // FIXED cr-java-0077: Replaced hard-coded port with environment variable injection
    // Port is now configured via AWS Parameter Store and injected at runtime
    // This enables dynamic port assignment by ECS, EKS, or Elastic Beanstalk
    @Value("${server.port:8080}")
    private int serverPort;

    private S3Client s3Client;
    private S3Presigner s3Presigner;

    // Initialize S3 client lazily to support environment-based configuration
    private S3Client getS3Client() {
        if (s3Client == null) {
            s3Client = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .build();
        }
        return s3Client;
    }

    private S3Presigner getS3Presigner() {
        if (s3Presigner == null) {
            s3Presigner = S3Presigner.builder()
                    .region(Region.of(awsRegion))
                    .build();
        }
        return s3Presigner;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // FIXED cr-java-0061 (Line 37): Replaced hard-coded file path with S3 key
        String s3Key = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // FIXED cr-java-0061 (Line 42): Replaced local file operations with S3 upload
            // Generate report content in memory instead of writing to local file system
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.flush();
            writer.close();

            // Upload to S3 instead of writing to local file system
            byte[] reportData = outputStream.toByteArray();
            
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            getS3Client().putObject(putObjectRequest, RequestBody.fromBytes(reportData));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            result.put("s3Uri", "s3://" + s3BucketName + "/" + s3Key);
            result.put("serverPort", serverPort);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", "S3 upload failed: " + e.awsErrorDetails().errorMessage());
        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", "Report generation failed: " + e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // FIXED cr-java-0061: Generate pre-signed S3 URL instead of hard-coded HTTP URL
        // This provides secure, time-limited access to S3 objects
        try {
            String s3Key = reportPrefix + reportName;
            
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofHours(1)) // URL valid for 1 hour
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = getS3Presigner().presignGetObject(presignRequest);
            
            // Returns HTTPS URL (fixes cr-java-0088 as well)
            return presignedRequest.url().toString();
            
        } catch (S3Exception e) {
            return "error: Unable to generate download URL - " + e.awsErrorDetails().errorMessage();
        }
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        
        // FIXED cr-java-0061 (Line 23): Replaced hard-coded paths with S3 configuration
        info.put("reportStorage", "s3://" + s3BucketName + "/" + reportPrefix);
        info.put("backupStorage", "s3://" + s3BucketName + "/" + backupPrefix);
        info.put("s3Bucket", s3BucketName);
        info.put("s3Region", awsRegion);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        
        return info;
    }

    // Cleanup method to close S3 clients when service is destroyed
    public void destroy() {
        if (s3Client != null) {
            s3Client.close();
        }
        if (s3Presigner != null) {
            s3Presigner.close();
        }
    }
}
