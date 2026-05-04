package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    @Autowired(required = false)
    private S3Client s3Client;

    @Autowired(required = false)
    private SsmClient ssmClient;

    @Value("${aws.s3.bucket.name:resortslite-reports}")
    private String s3BucketName;

    @Value("${aws.ssm.parameter.prefix:/resortslite}")
    private String ssmParameterPrefix;

    @Value("${server.port:8080}")
    private String serverPort;

    /**
     * Generates a monthly report and stores it in Amazon S3.
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
            // Generate report content in memory
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            outputStream.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n".getBytes());
            outputStream.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n".getBytes());
            outputStream.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n".getBytes());

            // Upload to S3 instead of local file system
            if (s3Client != null) {
                PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                        .bucket(s3BucketName)
                        .key(s3Key)
                        .contentType("text/csv")
                        .build();

                s3Client.putObject(putObjectRequest, RequestBody.fromBytes(outputStream.toByteArray()));

                result.put("status", "generated");
                result.put("s3Bucket", s3BucketName);
                result.put("s3Key", s3Key);
                result.put("location", "s3://" + s3BucketName + "/" + s3Key);
            } else {
                result.put("status", "error");
                result.put("message", "S3 client not configured");
            }

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using externalized configuration from AWS Parameter Store.
     * 
     * @param reportName The name of the report to download
     * @return The download URL retrieved from Parameter Store or a default value
     */
    public String buildReportDownloadUrl(String reportName) {
        // Retrieve report service URL from AWS Systems Manager Parameter Store
        String reportServiceUrl = getParameterFromStore("report-service-url");
        
        if (reportServiceUrl == null || reportServiceUrl.isEmpty()) {
            reportServiceUrl = "https://reports.resorts-internal.com";
        }
        
        return reportServiceUrl + "/download/" + reportName;
    }

    /**
     * Retrieves system information with cloud-native configuration.
     * 
     * @return Map containing system configuration from environment variables and Parameter Store
     */
    public Map<String, Object> getSystemInfo() {
        // Use UTC time with java.time API instead of java.util.Date
        String timestamp = Instant.now()
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        
        Map<String, Object> info = new HashMap<>();
        info.put("s3Bucket", s3BucketName);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        return info;
    }

    private String getParameterFromStore(String parameterName) {
        if (ssmClient == null) {
            return null;
        }

        try {
            String fullParameterPath = ssmParameterPrefix + "/" + parameterName;
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(fullParameterPath)
                    .withDecryption(true)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            System.err.println("Failed to retrieve parameter from SSM: " + e.getMessage());
            return null;
        }
    }
}
