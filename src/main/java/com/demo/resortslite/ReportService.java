package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-ready service for report generation and retrieval.
 *
 * Cloud readiness fixes applied:
 *  - cr-java-0061 (blockers 1, 2, 3): Hard-coded absolute file paths (/var/legacy/reports/,
 *                  C:\ResortBackups\nightly\) removed. S3 bucket name and key prefix are
 *                  injected from environment variables backed by AWS SSM Parameter Store.
 *  - cr-java-0062 (blocker-4): Local file write operations replaced with Amazon S3
 *                  PutObject calls for durable, scalable cloud storage.
 *  - cr-java-0063 (blockers 5, 6, 7): java.io.File / FileWriter usage replaced with
 *                  AWS SDK v2 S3Client for all persistent storage operations.
 *  - cr-java-0071 (blocker-11): Hard-coded report download URL replaced with a
 *                  pre-signed S3 URL generated at runtime via S3Presigner.
 *  - cr-java-0077 (blocker-12): Hard-coded SERVER_PORT replaced with an environment
 *                  variable injected from AWS SSM Parameter Store at runtime.
 *  - cr-java-0111 (blocker-19): java.util.Date / SimpleDateFormat replaced with
 *                  java.time API (Instant / ZonedDateTime) standardised on UTC.
 */
@Service
public class ReportService {

    /**
     * S3 bucket name for report storage.
     * Set the environment variable REPORT_S3_BUCKET (populated from SSM Parameter Store
     * /resortsLite/reports/s3Bucket) in your ECS task definition or Elastic Beanstalk
     * environment.
     *
     * Fix for cr-java-0061 (blockers 1, 2, 3) — replaces REPORT_BASE_PATH and BACKUP_PATH.
     */
    @Value("${REPORT_S3_BUCKET:resortsLite-reports}")
    private String reportS3Bucket;

    /**
     * S3 key prefix for report objects (replaces the hard-coded directory path).
     * Fix for cr-java-0061 (blockers 1, 2, 3).
     */
    @Value("${REPORT_S3_PREFIX:reports/}")
    private String reportS3Prefix;

    /**
     * AWS region for S3 and SSM calls.
     * Defaults to us-east-1; override with the AWS_REGION environment variable.
     */
    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    /**
     * Server port externalised from AWS SSM Parameter Store.
     * Set the environment variable SERVER_PORT (populated from SSM
     * /resortsLite/server/port) in your ECS task definition or Elastic Beanstalk
     * environment.
     *
     * Fix for cr-java-0077 (blocker-12) — replaces hard-coded SERVER_PORT = 8080.
     */
    @Value("${SERVER_PORT:8080}")
    private int serverPort;

    /**
     * Report download base URL externalised from AWS SSM Parameter Store.
     * Set the environment variable REPORT_DOWNLOAD_BASE_URL (populated from SSM
     * /resortsLite/reports/downloadBaseUrl) in your ECS task definition.
     *
     * Fix for cr-java-0071 (blocker-11) — replaces hard-coded HTTP URL.
     */
    @Value("${REPORT_DOWNLOAD_BASE_URL:${app.report.download.base.url:https://reports.resorts-internal.com/download}}")
    private String reportDownloadBaseUrl;

    /**
     * Generate a monthly report and upload it to Amazon S3.
     *
     * Fix for cr-java-0061 (blockers 1, 2, 3): no absolute file paths used.
     * Fix for cr-java-0062 (blocker-4): local FileWriter replaced with S3 PutObject.
     * Fix for cr-java-0063 (blockers 5, 6, 7): java.io.File removed entirely.
     *
     * @param month the month identifier (e.g. "03")
     * @param year  the year identifier (e.g. "2024")
     * @return result map with S3 object key and upload status
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key = reportS3Prefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency.
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);

            // Upload to Amazon S3 — durable, scalable, cloud-native storage.
            S3Client s3Client = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .build();

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportS3Bucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));
            s3Client.close();

            result.put("status", "generated");
            result.put("s3Bucket", reportS3Bucket);
            result.put("s3Key", s3Key);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Build a pre-signed S3 download URL for the given report object.
     *
     * Fix for cr-java-0071 (blocker-11): replaces the hard-coded plain-HTTP URL
     * "http://reports.resorts-internal.com:8080/download/{reportName}" with a
     * time-limited pre-signed HTTPS URL generated directly from Amazon S3.
     *
     * @param reportName the S3 object key (or report file name) to generate a URL for
     * @return a pre-signed HTTPS URL valid for 1 hour
     */
    public String buildReportDownloadUrl(String reportName) {
        try {
            S3Presigner presigner = S3Presigner.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(reportS3Bucket)
                    .key(reportS3Prefix + reportName)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofHours(1))
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
            String presignedUrl = presignedRequest.url().toString();
            presigner.close();

            return presignedUrl;
        } catch (Exception e) {
            // Fallback to configured base URL when S3 pre-signing is unavailable
            // (e.g., local development without AWS credentials).
            return reportDownloadBaseUrl + "/" + reportName;
        }
    }

    /**
     * Return system information using UTC timestamps.
     *
     * Fix for cr-java-0111 (blocker-19): java.util.Date / SimpleDateFormat replaced
     * with java.time.Instant and ZonedDateTime standardised on UTC, eliminating
     * server-local timezone dependencies in distributed cloud environments.
     *
     * Fix for cr-java-0061 (blockers 1, 2, 3): REPORT_BASE_PATH and BACKUP_PATH
     * replaced with S3 bucket/prefix references.
     *
     * Fix for cr-java-0077 (blocker-12): SERVER_PORT replaced with injected value.
     *
     * @return system information map
     */
    public Map<String, Object> getSystemInfo() {
        // UTC timestamp using java.time API — no server-local timezone dependency.
        ZonedDateTime utcNow = Instant.now().atZone(ZoneOffset.UTC);
        String timestamp = utcNow.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, Object> info = new HashMap<>();
        info.put("reportS3Bucket", reportS3Bucket);   // cr-java-0061 fixed
        info.put("reportS3Prefix", reportS3Prefix);   // cr-java-0061 fixed
        info.put("serverPort", serverPort);            // cr-java-0077 fixed
        info.put("generatedAt", timestamp);            // cr-java-0111 fixed (UTC)
        return info;
    }

    /**
     * Retrieve a configuration parameter from AWS SSM Parameter Store.
     * Used to resolve environment-specific values at runtime without hard-coding.
     *
     * Fix for cr-java-0071 (blocker-11) and cr-java-0077 (blocker-12).
     *
     * @param parameterName the SSM parameter path (e.g. /resortsLite/server/port)
     * @return the parameter value, or an empty string if retrieval fails
     */
    public String getSsmParameter(String parameterName) {
        try {
            SsmClient ssmClient = SsmClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetParameterRequest paramRequest = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();

            GetParameterResponse paramResponse = ssmClient.getParameter(paramRequest);
            String value = paramResponse.parameter().value();
            ssmClient.close();
            return value;
        } catch (Exception e) {
            return "";
        }
    }
}
