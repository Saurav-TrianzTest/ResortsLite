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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Fixed: cr-java-0061, cr-java-0062, cr-java-0063 - Replace hard-coded file paths with Amazon S3
    @Value("${aws.s3.bucket:resorts-lite-reports}")
    private String s3BucketName;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    // Fixed: cr-java-0077 - Replace hard-coded ports with environment variable injection
    @Value("${SERVER_PORT:8080}")
    private int serverPort;

    private S3Client s3Client;
    private SsmClient ssmClient;

    public ReportService(@Value("${aws.region:us-east-1}") String region) {
        this.awsRegion = region;
        this.s3Client = S3Client.builder()
                .region(Region.of(region))
                .build();
        this.ssmClient = SsmClient.builder()
                .region(Region.of(region))
                .build();
    }

    /**
     * Generates a monthly report and stores it in Amazon S3.
     * Fixed: cr-java-0061, cr-java-0062, cr-java-0063 - Migrated from local file system to S3
     * 
     * @param month The month for the report
     * @param year The year for the report
     * @return Map containing report generation status and S3 location
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key = "reports/" + year + "/" + month + "/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Generate report content in memory instead of writing to local file system
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(outputStream);
            
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.flush();
            
            byte[] reportContent = outputStream.toByteArray();
            writer.close();

            // Upload to S3 instead of writing to local file system
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(reportContent));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            result.put("s3Url", "s3://" + s3BucketName + "/" + s3Key);
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using externalized configuration.
     * Fixed: cr-java-0071 - Externalize environment URLs using AWS Systems Manager Parameter Store
     * 
     * @param reportName The name of the report
     * @return The download URL retrieved from Parameter Store
     */
    public String buildReportDownloadUrl(String reportName) {
        try {
            // Retrieve the base URL from AWS Systems Manager Parameter Store
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name("/resorts/config/report-base-url")
                    .withDecryption(false)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(parameterRequest);
            String baseUrl = response.parameter().value();
            
            return baseUrl + "/download/" + reportName;
        } catch (Exception e) {
            // Fallback to environment variable if Parameter Store is not available
            String baseUrl = System.getenv().getOrDefault("REPORT_BASE_URL", 
                    "https://reports.resorts-internal.com:" + serverPort);
            return baseUrl + "/download/" + reportName;
        }
    }

    /**
     * Retrieves system information with cloud-native configuration.
     * Fixed: cr-java-0111 - Replace java.util.Date with java.time API and standardize on UTC
     * 
     * @return Map containing system information
     */
    public Map<String, Object> getSystemInfo() {
        // Fixed: cr-java-0111 - Use java.time API with UTC timezone
        Instant now = Instant.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.of("UTC"));
        String timestamp = formatter.format(now);
        
        Map<String, Object> info = new HashMap<>();
        info.put("s3Bucket", s3BucketName);
        info.put("awsRegion", awsRegion);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        return info;
    }
}
