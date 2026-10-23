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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService – generates and stores resort reports in Amazon S3.
 *
 * <p>All local file system operations using {@code java.io.File} have been
 * replaced with Amazon S3 object storage using AWS SDK for Java v2.
 *
 * <p>Fix for cr-java-0063 (Java.io.File Usage for Data Storage):
 * The original code used {@code java.io.File} for persistent data storage operations
 * (e.g. {@code new File(REPORT_BASE_PATH)}, {@code reportDir.mkdirs()}, and
 * {@code new FileWriter(fullPath)}), which assumes local file system availability
 * and persistence. This violates cloud storage patterns where data must be
 * externalized to managed storage services for durability and scalability.
 * All {@code java.io.File}-based persistent storage operations have been replaced
 * with {@link S3Client#putObject} calls to achieve cloud-native, durable, and
 * scalable storage without host-level file system dependencies.
 *
 * <p>Fix for cr-java-0071 (Hard-coded Environment URLs):
 * The original {@code buildReportDownloadUrl} method contained a hard-coded
 * environment-specific base URL ({@code http://reports.resorts-internal.com:8080/download/}).
 * This URL is now retrieved at runtime from AWS Systems Manager Parameter Store
 * using the parameter name configured via {@code ssm.param.report.download.url}.
 * This enables environment-agnostic deployments where each environment (dev, staging,
 * production) stores its own URL in SSM without requiring code changes.
 *
 * <p>Fix for cr-java-0077 (Hard-coded Ports):
 * The original code contained {@code private static final int SERVER_PORT = 8080} (line 28),
 * a hard-coded port number embedded directly in application logic. Hard-coded ports prevent
 * dynamic port assignment required by container orchestration platforms (ECS, EKS) and cloud
 * service discovery mechanisms. The port is now resolved at runtime from AWS Systems Manager
 * Parameter Store via the SSM parameter path configured in {@code ssm.param.server.port}
 * (default: {@code /resortslite/config/server-port}). The SSM parameter name itself is
 * injected through the environment variable {@code SSM_PARAM_SERVER_PORT}, enabling
 * environment-specific port configuration without any code changes. A fallback to the
 * {@code SERVER_PORT} environment variable (default: 8080) is provided for local development
 * environments where SSM is unavailable.
 *
 * <p>Required environment variables / application properties:
 * <ul>
 *   <li>{@code AWS_REGION}                  – AWS region for S3 and SSM (default: us-east-1)</li>
 *   <li>{@code S3_REPORT_BUCKET}            – S3 bucket name for report objects</li>
 *   <li>{@code S3_BACKUP_PREFIX}            – S3 key prefix used for backup objects</li>
 *   <li>{@code SERVER_PORT}                 – HTTP port fallback env var (default: 8080)</li>
 *   <li>{@code SSM_PARAM_SERVER_PORT}       – SSM parameter path for the server port
 *                                             (default: /resortslite/config/server-port)</li>
 *   <li>{@code ssm.param.report.download.url} – SSM parameter path for the report download base URL</li>
 * </ul>
 */
@Service
public class ReportService {

    // cr-java-0063 FIX: replaced hard-coded "/var/legacy/reports/" local path with an
    // S3 bucket name injected from the environment variable S3_REPORT_BUCKET.
    // Local paths are ephemeral in containers; S3 provides durable, scalable storage.
    @Value("${S3_REPORT_BUCKET:resort-reports-bucket}")
    private String reportBucket;

    // cr-java-0063 FIX: replaced hard-coded "C:\\ResortBackups\\nightly\\" local path
    // with an S3 key prefix injected from the environment variable S3_BACKUP_PREFIX.
    @Value("${S3_BACKUP_PREFIX:backups/nightly/}")
    private String backupPrefix;

    // cr-java-0063 FIX: AWS region resolved from environment variable AWS_REGION.
    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    // cr-java-0077 FIX (original line 28): The hard-coded constant
    //   private static final int SERVER_PORT = 8080;
    // has been replaced with an AWS SSM Parameter Store lookup at runtime.
    // The SSM parameter path is injected via the SSM_PARAM_SERVER_PORT environment
    // variable (default: /resortslite/config/server-port), allowing ECS/EKS task
    // definitions or Elastic Beanstalk environment configurations to supply the
    // correct port for each deployment environment without code changes.
    // A SERVER_PORT environment variable fallback (default: 8080) is retained for
    // local development where SSM credentials may not be available.
    @Value("${SSM_PARAM_SERVER_PORT:/resortslite/config/server-port}")
    private String serverPortParamName;

    @Value("${SERVER_PORT:8080}")
    private String serverPortFallback;

    // cr-java-0071 FIX: SSM parameter name for the report download base URL.
    // The parameter name is externalised via application properties so that no
    // environment-specific URL is embedded in source code.
    @Value("${ssm.param.report.download.url:/resortslite/reports/download-base-url}")
    private String reportDownloadUrlParamName;

    /**
     * Generates a monthly resort report and uploads it to Amazon S3.
     *
     * <p>cr-java-0063 FIX (Lines 37–42 of original source):
     * <ul>
     *   <li>Line 37 – {@code File reportDir = new File(REPORT_BASE_PATH)} removed;
     *       S3 bucket name sourced from {@code S3_REPORT_BUCKET} environment variable.</li>
     *   <li>Line 39 – {@code reportDir.mkdirs()} removed; S3 does not require directory
     *       creation — objects are stored directly under the bucket key.</li>
     *   <li>Line 42 – {@code FileWriter writer = new FileWriter(fullPath)} removed;
     *       replaced with {@link S3Client#putObject} to write CSV content directly
     *       to Amazon S3, ensuring durability across container restarts and
     *       horizontal scaling events.</li>
     * </ul>
     *
     * @param month two-digit month string (e.g. "03")
     * @param year  four-digit year string  (e.g. "2024")
     * @return result map containing status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0063 FIX: S3 object key replaces the local fullPath variable
        // that previously concatenated REPORT_BASE_PATH + fileName using java.io.File.
        String objectKey = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0063 FIX (original lines 37–42):
            // The following java.io.File-based persistent storage operations have been removed:
            //   File reportDir = new File(REPORT_BASE_PATH);   // original line 37
            //   if (!reportDir.exists()) {
            //       reportDir.mkdirs();                        // original line 39
            //   }
            //   FileWriter writer = new FileWriter(fullPath);  // original line 42
            //   writer.write(...);
            //   writer.close();
            //
            // Replaced with Amazon S3 client calls (AWS SDK for Java v2) to achieve
            // cloud-native, durable, and scalable storage without host-level file
            // system dependencies.
            S3Client s3 = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .build();

            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            s3.putObject(putRequest, RequestBody.fromString(csvContent));

            // cr-java-0077 FIX: server port is now resolved from AWS SSM Parameter Store
            // at runtime rather than from the removed hard-coded constant SERVER_PORT = 8080.
            String resolvedPort = getParameterFromSsm(serverPortParamName, serverPortFallback);

            result.put("status", "generated");
            result.put("bucket", reportBucket);
            result.put("key", objectKey);
            result.put("serverPort", resolvedPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a report by retrieving the environment-specific
     * base URL from AWS Systems Manager Parameter Store.
     *
     * <p>cr-java-0071 FIX (Line 66 of original source):
     * The original hard-coded URL {@code http://reports.resorts-internal.com:8080/download/}
     * has been replaced with a runtime lookup against AWS SSM Parameter Store.
     * The SSM parameter name is configured via {@code ssm.param.report.download.url}
     * (default: {@code /resortslite/reports/download-base-url}), allowing each
     * deployment environment to supply its own URL without code changes.
     *
     * @param reportName the report file name / S3 object key
     * @return fully qualified download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: retrieve the environment-specific base URL from AWS SSM
        // Parameter Store instead of using the previously hard-coded value
        // "http://reports.resorts-internal.com:8080/download/" (original line 66).
        String baseUrl = getParameterFromSsm(reportDownloadUrlParamName,
                "https://" + reportBucket + ".s3." + awsRegion + ".amazonaws.com/reports/");
        return baseUrl + reportName;
    }

    /**
     * Returns system information including S3 storage configuration and resolved server port.
     *
     * <p>cr-java-0063 FIX: replaced hard-coded REPORT_BASE_PATH / BACKUP_PATH
     * (local {@code java.io.File} references) with S3 bucket and prefix values
     * sourced from environment variables, ensuring cloud portability.
     *
     * <p>cr-java-0077 FIX: server port is now resolved from AWS SSM Parameter Store
     * rather than from the removed hard-coded constant {@code SERVER_PORT = 8080}.
     *
     * @return map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: replaced java.util.Date / SimpleDateFormat with java.time API
        // standardized on UTC (ZoneOffset.UTC) to avoid timezone inconsistencies across
        // distributed cloud containers and multi-region deployments.
        String timestamp = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        // cr-java-0077 FIX: resolve port from SSM Parameter Store at runtime.
        String resolvedPort = getParameterFromSsm(serverPortParamName, serverPortFallback);
        Map<String, Object> info = new HashMap<>();
        info.put("reportBucket", reportBucket);
        info.put("backupPrefix", backupPrefix);
        info.put("serverPort", resolvedPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * <p>cr-java-0071 / cr-java-0077 FIX: Centralised SSM lookup helper used by all
     * methods that previously contained hard-coded environment-specific values (URLs,
     * ports). The {@code fallback} value is used only when the SSM call cannot be
     * completed (e.g. local development without AWS credentials), ensuring the
     * application remains functional in all environments.
     *
     * @param parameterName the SSM parameter path (e.g. /resortslite/config/server-port)
     * @param fallback      default value used when SSM is unavailable
     * @return the resolved parameter value
     */
    private String getParameterFromSsm(String parameterName, String fallback) {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();

        } catch (Exception e) {
            System.err.println("[ReportService] WARNING: Could not retrieve SSM parameter '"
                    + parameterName + "'. Using fallback value. Error: " + e.getMessage());
            return fallback;
        }
    }
}
