package com.demo.resortslite;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService handles monthly report generation and system information retrieval.
 * All file operations are delegated to Amazon S3 for cloud-native durable storage.
 * Environment-specific URLs and port configuration are retrieved from AWS Systems Manager
 * Parameter Store to enable environment-agnostic deployments.
 */
@Service
public class ReportService {

    // S3 bucket name is externalised via environment variable — no hard-coded paths.
    // Set REPORTS_S3_BUCKET in ECS task definition / EKS pod spec / Elastic Beanstalk env.
    private static final String S3_BUCKET = System.getenv().getOrDefault("REPORTS_S3_BUCKET", "resorts-reports-bucket");

    // S3 key prefix replaces the former hard-coded /var/legacy/reports/ path (blocker-1/2/3).
    private static final String S3_KEY_PREFIX = "reports/";

    // Server port is externalised via environment variable — no hard-coded port (blocker-12).
    // Set SERVER_PORT in ECS task definition / EKS pod spec / Elastic Beanstalk env.
    private static final int SERVER_PORT = Integer.parseInt(
            System.getenv().getOrDefault("SERVER_PORT", "8080"));

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService() {
        this.s3Client = S3Client.create();
        this.ssmClient = SsmClient.create();
    }

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     * Replaces all local java.io.File / FileWriter operations (blockers 4, 5, 6, 7).
     *
     * @param month the month for which the report is generated
     * @param year  the year for which the report is generated
     * @return a map containing the operation status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // S3 object key replaces the former absolute file path (blocker-1, blocker-2, blocker-3)
        String s3Key = S3_KEY_PREFIX + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency (blocker-4, 5, 6, 7)
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // Upload directly to Amazon S3 — durable, scalable, cloud-native storage
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(S3_BUCKET)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest,
                    RequestBody.fromString(csvContent.toString()));

            result.put("status", "generated");
            result.put("s3Bucket", S3_BUCKET);
            result.put("s3Key", s3Key);
            result.put("serverPort", SERVER_PORT);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the report download URL by retrieving the base URL from AWS Systems Manager
     * Parameter Store (blocker-11). Replaces the former hard-coded environment URL.
     *
     * @param reportName the name of the report file
     * @return the fully qualified HTTPS download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // Retrieve the report base URL from AWS SSM Parameter Store (blocker-11)
        // Parameter name: /resortslite/report/base-url
        String reportBaseUrl;
        try {
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name("/resortslite/report/base-url")
                            .withDecryption(false)
                            .build());
            reportBaseUrl = response.parameter().value();
        } catch (Exception e) {
            // Fall back to environment variable if SSM is unavailable
            reportBaseUrl = System.getenv().getOrDefault(
                    "REPORT_BASE_URL", "https://reports.resorts-internal.com/download");
        }
        return reportBaseUrl + "/" + reportName;
    }

    /**
     * Returns current system information using UTC timestamps (blocker-19).
     * Replaces java.util.Date / SimpleDateFormat with java.time API standardised on UTC.
     *
     * @return a map containing system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // Use java.time.Instant with UTC zone — eliminates server-local timezone dependency (blocker-19)
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        info.put("reportStorage", "s3://" + S3_BUCKET + "/" + S3_KEY_PREFIX);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        return info;
    }
}
