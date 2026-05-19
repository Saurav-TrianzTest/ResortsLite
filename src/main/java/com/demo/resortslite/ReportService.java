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

import javax.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${aws.s3.bucket.name:resorts-lite-reports}")
    private String s3BucketName;

    @Value("${SERVER_PORT:8080}")
    private String serverPort;

    private S3Client s3Client;
    private SsmClient ssmClient;
    private String reportDownloadBaseUrl;

    @PostConstruct
    public void init() {
        Region region = Region.of(awsRegion);
        this.s3Client = S3Client.builder()
                .region(region)
                .build();
        this.ssmClient = SsmClient.builder()
                .region(region)
                .build();
        
        // Load report download URL from AWS Parameter Store
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name("/resorts-lite/report-download-url")
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            this.reportDownloadBaseUrl = response.parameter().value();
        } catch (Exception e) {
            // Fallback to environment variable if Parameter Store is not available
            this.reportDownloadBaseUrl = System.getenv().getOrDefault(
                    "REPORT_DOWNLOAD_URL", 
                    "https://reports.resorts-internal.com/download");
        }
    }

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
            OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.flush();
            writer.close();

            byte[] reportData = outputStream.toByteArray();

            // Upload to S3
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(reportData));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            result.put("fileName", fileName);
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", "Failed to generate report: " + e.getMessage());
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", "Failed to upload to S3: " + e.getMessage());
        }

        return result;
    }

    /**
     * Builds a secure HTTPS URL for report download from S3.
     * 
     * @param reportName The name of the report file
     * @return HTTPS URL for downloading the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // Use HTTPS and externalized base URL from Parameter Store
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Retrieves system information with cloud-native configuration.
     * 
     * @return Map containing system configuration details
     */
    public Map<String, Object> getSystemInfo() {
        // Use java.time API with UTC timezone for cloud consistency
        String timestamp = DateTimeFormatter.ISO_INSTANT
                .format(Instant.now().atOffset(ZoneOffset.UTC));
        
        Map<String, Object> info = new HashMap<>();
        info.put("s3Bucket", s3BucketName);
        info.put("awsRegion", awsRegion);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("storageType", "Amazon S3");
        return info;
    }
}
