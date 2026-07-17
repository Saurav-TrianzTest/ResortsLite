package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
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
 * ReportService handles report generation and storage using Amazon S3 for
 * cloud-native, durable object storage. All file path dependencies have been
 * replaced with S3 operations. Port and URL configuration is externalized to
 * AWS Systems Manager Parameter Store. Time operations use java.time (UTC).
 */
@Service
public class ReportService {

    // Blocker-1,2,3,4,5,6,7 (cr-java-0061, cr-java-0062, cr-java-0063):
    // Hard-coded file paths and local file write/read operations replaced with
    // Amazon S3 using AWS SDK for Java v2. Bucket name and key prefix are
    // injected from environment variables / application properties.
    @Value("${cloud.aws.s3.report-bucket:resorts-lite-reports}")
    private String reportBucket;

    @Value("${cloud.aws.s3.report-prefix:reports/}")
    private String reportPrefix;

    // Blocker-12 (cr-java-0077): Hard-coded port replaced with environment variable injection.
    // Value is read from the environment variable SERVER_PORT, defaulting to 8080.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    /**
     * Generates a monthly report CSV and stores it durably in Amazon S3.
     * Replaces all local java.io.File / FileWriter operations (blockers 1-7).
     *
     * @param month the month for the report
     * @param year  the year for the report
     * @return result map containing status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // Blocker-1,2,3 (cr-java-0061): S3 key replaces absolute local file path
        String s3Key = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Blocker-4,5,6,7 (cr-java-0062, cr-java-0063):
            // Replace File/FileWriter local write with S3 PutObject for durable cloud storage
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            // Return S3 URI instead of local path
            result.put("s3Uri", "s3://" + reportBucket + "/" + s3Key);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using the base URL retrieved from
     * AWS Systems Manager Parameter Store (blocker-11, cr-java-0071).
     *
     * @param reportName the name of the report file
     * @return the fully qualified download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // Blocker-11 (cr-java-0071): Hard-coded environment URL replaced with
        // value retrieved from AWS Systems Manager Parameter Store at runtime.
        String baseUrl = getParameterValue("/resortslite/report/download-base-url",
                "https://reports.resorts-internal.com/download");
        return baseUrl + "/" + reportName;
    }

    /**
     * Returns system information using externalized configuration values.
     * Blocker-19 (cr-java-0111): java.util.Date replaced with java.time Instant (UTC).
     *
     * @return map of system information
     */
    public Map<String, Object> getSystemInfo() {
        // Blocker-19 (cr-java-0111): Replace java.util.Date / SimpleDateFormat with
        // java.time.Instant formatted in UTC to eliminate timezone inconsistencies
        // across cloud regions and container instances.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // Blocker-1,2,3 (cr-java-0061): Report location is now an S3 bucket reference
        info.put("reportBucket", reportBucket);
        info.put("reportPrefix", reportPrefix);
        // Blocker-12 (cr-java-0077): Port sourced from environment variable
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     * Falls back to the provided default value if the parameter is not found.
     *
     * @param parameterName the SSM parameter name/path
     * @param defaultValue  fallback value if parameter is unavailable
     * @return the resolved parameter value
     */
    private String getParameterValue(String parameterName, String defaultValue) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
