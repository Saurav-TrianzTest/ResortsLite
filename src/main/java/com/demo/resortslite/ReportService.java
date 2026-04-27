package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Cloud-ready report service that uses Amazon S3 for durable storage
 * and AWS Systems Manager Parameter Store for configuration management.
 * 
 * Fixes applied:
 * - cr-java-0061: Replaced hard-coded file paths with S3 object storage
 * - cr-java-0062: Replaced local file writes with S3 for durable storage
 * - cr-java-0063: Migrated java.io.File operations to AWS SDK for Java v2
 * - cr-java-0071: Externalized environment URLs using AWS Parameter Store
 * - cr-java-0077: Replaced hard-coded ports with environment variables
 * - cr-java-0111: Replaced java.util.Date with java.time API and UTC standardization
 */
@Service
public class ReportService {

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${aws.s3.bucket:resorts-reports-bucket}")
    private String s3BucketName;

    @Value("${SERVER_PORT:8080}")
    private int serverPort;

    private S3Client s3Client;
    private SsmClient ssmClient;

    /**
     * Generates a monthly report and stores it in Amazon S3.
     * 
     * @param month The month for the report
     * @param year The year for the report
     * @return Map containing report generation status and S3 location
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        
        Map<String, Object> result = new HashMap<>();

        try {
            // Initialize S3 client if not already initialized
            if (s3Client == null) {
                s3Client = S3Client.builder()
                        .region(Region.of(awsRegion))
                        .build();
            }

            // Create CSV content in memory
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(baos);
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.flush();
            writer.close();

            // Upload to S3
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key("reports/" + fileName)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(baos.toByteArray()));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", "reports/" + fileName);
            result.put("s3Uri", "s3://" + s3BucketName + "/reports/" + fileName);
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using configuration from AWS Parameter Store.
     * 
     * @param reportName The name of the report to download
     * @return The download URL retrieved from Parameter Store
     */
    public String buildReportDownloadUrl(String reportName) {
        try {
            // Initialize SSM client if not already initialized
            if (ssmClient == null) {
                ssmClient = SsmClient.builder()
                        .region(Region.of(awsRegion))
                        .build();
            }

            // Retrieve report service URL from Parameter Store
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name("/resorts/report-service/base-url")
                    .withDecryption(false)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(parameterRequest);
            String baseUrl = response.parameter().value();
            
            return baseUrl + "/download/" + reportName;
        } catch (Exception e) {
            // Fallback to environment variable if Parameter Store is not available
            String baseUrl = System.getenv().getOrDefault("REPORT_SERVICE_URL", 
                    "https://reports.resorts-internal.com:" + serverPort);
            return baseUrl + "/download/" + reportName;
        }
    }

    /**
     * Retrieves system information including cloud-native configuration.
     * Uses UTC timezone for all timestamps to ensure consistency across regions.
     * 
     * @return Map containing system configuration information
     */
    public Map<String, Object> getSystemInfo() {
        // Use java.time API with UTC timezone for cloud-native time handling
        Instant now = Instant.now();
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(now);
        
        Map<String, Object> info = new HashMap<>();
        info.put("s3Bucket", s3BucketName);
        info.put("awsRegion", awsRegion);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        return info;
    }
}
