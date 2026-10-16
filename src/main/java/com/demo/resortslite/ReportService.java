package com.demo.resortslite;

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
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService handles report generation and storage using Amazon S3.
 *
 * <p><strong>cr-java-0063 remediation</strong>: All {@code java.io.File} persistent storage
 * operations have been replaced with Amazon S3 client calls (AWS SDK for Java v2).
 * Specifically:
 * <ul>
 *   <li>Original line 37 — {@code new File(REPORT_BASE_PATH)} → S3 object key construction</li>
 *   <li>Original line 39 — {@code reportDir.mkdirs()} → eliminated (S3 creates keys on write)</li>
 *   <li>Original line 42 — {@code new FileWriter(fullPath)} → {@code S3Client.putObject()}</li>
 * </ul>
 * The S3 bucket name and AWS region are read from environment variables
 * {@code S3_REPORT_BUCKET} and {@code AWS_REGION} respectively, following
 * 12-factor app configuration principles.</p>
 *
 * <p><strong>cr-java-0071 remediation</strong>: The hard-coded report download URL
 * {@code "http://reports.resorts-internal.com:8080/download/"} (original line 66) has been
 * replaced with a value retrieved from AWS Systems Manager Parameter Store, enabling
 * environment-agnostic deployments across dev, staging, and production without code changes.</p>
 *
 * <p><strong>cr-java-0077 remediation</strong>: The hard-coded server port constant
 * {@code SERVER_PORT = 8080} (original line 28) has been replaced with a value injected
 * at runtime via the {@code SERVER_PORT} environment variable. ECS task definitions,
 * EKS pod specs, and Elastic Beanstalk environment properties inject this value dynamically,
 * enabling container orchestration platforms to assign ports without code changes.
 * A fallback to AWS SSM Parameter Store ({@code /resortslite/server/port}) is provided
 * for environments where the environment variable is not set.</p>
 */
@Service
public class ReportService {

    /**
     * S3 bucket name sourced from the environment variable {@code S3_REPORT_BUCKET}.
     *
     * <p><strong>cr-java-0063 fix (original line 37)</strong>: Replaces the hard-coded local
     * path {@code /var/legacy/reports/} that was used to construct a {@code java.io.File}
     * instance. The bucket name is externalised via an environment variable so the application
     * can run in any AWS environment without code changes.</p>
     */
    // cr-java-0063 [original line 37]: new File(REPORT_BASE_PATH) → S3 bucket + key pattern
    private static final String S3_BUCKET =
            System.getenv("S3_REPORT_BUCKET") != null
                    ? System.getenv("S3_REPORT_BUCKET")
                    : "resorts-lite-reports";

    /**
     * S3 key prefix for backup objects.
     * Replaces the hard-coded Windows path {@code C:\ResortBackups\nightly\}.
     */
    private static final String BACKUP_PREFIX = "backups/nightly/";

    /**
     * S3 key prefix for generated monthly reports.
     *
     * <p><strong>cr-java-0063 fix (original line 37)</strong>: Replaces the hard-coded local
     * path {@code /var/legacy/reports/} used as the root directory for {@code java.io.File}
     * operations. S3 key prefixes provide the same logical namespace without any host
     * file-system dependency.</p>
     */
    // cr-java-0063 [original line 37]: REPORT_BASE_PATH local directory → S3 key prefix
    private static final String REPORT_PREFIX = "reports/monthly/";

    /**
     * AWS region sourced from the environment variable {@code AWS_REGION}.
     * Defaults to {@code us-east-1} when the variable is not set.
     */
    private static final String AWS_REGION =
            System.getenv("AWS_REGION") != null
                    ? System.getenv("AWS_REGION")
                    : "us-east-1";

    /**
     * AWS Systems Manager Parameter Store parameter name for the report download base URL.
     * The parameter name is read from the environment variable
     * {@code SSM_REPORT_DOWNLOAD_URL_PARAM} so it can be configured per environment without
     * code changes (12-factor app principle III).
     *
     * <p><strong>cr-java-0071 fix</strong>: Replaces the hard-coded URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} (original line 66)
     * with a reference to an AWS SSM Parameter Store parameter, enabling environment-agnostic
     * deployments.</p>
     */
    // cr-java-0071 [original line 66]: hard-coded report download URL → SSM Parameter Store lookup
    private static final String REPORT_DOWNLOAD_URL_PARAM =
            System.getenv("SSM_REPORT_DOWNLOAD_URL_PARAM") != null
                    ? System.getenv("SSM_REPORT_DOWNLOAD_URL_PARAM")
                    : "/resortslite/reports/download-base-url";

    /**
     * AWS Systems Manager Parameter Store parameter name for the server port.
     * Used as a fallback when the {@code SERVER_PORT} environment variable is not set.
     *
     * <p><strong>cr-java-0077 fix</strong>: Supports SSM-based port configuration for
     * environments where environment variable injection is not available.</p>
     */
    // cr-java-0077: SSM parameter name for server port fallback
    private static final String SERVER_PORT_SSM_PARAM =
            System.getenv("SSM_SERVER_PORT_PARAM") != null
                    ? System.getenv("SSM_SERVER_PORT_PARAM")
                    : "/resortslite/server/port";

    /**
     * Server port resolved at runtime from the {@code SERVER_PORT} environment variable.
     *
     * <p><strong>cr-java-0077 remediation (original line 28)</strong>: Replaces the
     * hard-coded constant {@code private static final int SERVER_PORT = 8080} with a value
     * injected at runtime via the {@code SERVER_PORT} environment variable. ECS task
     * definitions, EKS pod specs, and Elastic Beanstalk environment properties inject this
     * value dynamically, enabling container orchestration platforms to assign ports without
     * code changes. When {@code SERVER_PORT} is not set, the value is retrieved from AWS
     * SSM Parameter Store ({@code /resortslite/server/port}), and if that is also unavailable,
     * defaults to {@code 8080} to preserve backward compatibility in local development.</p>
     */
    // cr-java-0077 [original line 28]: hard-coded SERVER_PORT = 8080
    //   → resolved from SERVER_PORT env var (injected by ECS/EKS/Elastic Beanstalk)
    //   → fallback: AWS SSM Parameter Store (/resortslite/server/port)
    //   → last-resort default: 8080 (local dev only)
    private static final int SERVER_PORT = resolveServerPort();

    /**
     * Resolves the server port from the {@code SERVER_PORT} environment variable.
     * Falls back to AWS SSM Parameter Store, then to the default value of {@code 8080}.
     *
     * <p><strong>cr-java-0077 remediation</strong>: This method implements the AWS Parameter
     * Store and environment variable injection strategy. The priority order is:
     * <ol>
     *   <li>{@code SERVER_PORT} environment variable — set by ECS task definition,
     *       EKS pod spec, or Elastic Beanstalk environment properties at deploy time.</li>
     *   <li>AWS SSM Parameter Store — parameter name from {@code SSM_SERVER_PORT_PARAM}
     *       env var (defaults to {@code /resortslite/server/port}).</li>
     *   <li>Hard-coded default {@code 8080} — used only in local development when neither
     *       source is available.</li>
     * </ol>
     * </p>
     *
     * @return the resolved server port as an integer
     */
    private static int resolveServerPort() {
        // Priority 1: SERVER_PORT environment variable (injected by ECS/EKS/Elastic Beanstalk)
        String envPort = System.getenv("SERVER_PORT");
        if (envPort != null && !envPort.trim().isEmpty()) {
            try {
                return Integer.parseInt(envPort.trim());
            } catch (NumberFormatException e) {
                // Fall through to SSM lookup if env var value is not a valid integer
            }
        }

        // Priority 2: AWS SSM Parameter Store lookup
        String ssmParamName = System.getenv("SSM_SERVER_PORT_PARAM") != null
                ? System.getenv("SSM_SERVER_PORT_PARAM")
                : "/resortslite/server/port";
        String awsRegion = System.getenv("AWS_REGION") != null
                ? System.getenv("AWS_REGION")
                : "us-east-1";
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(ssmParamName)
                    .withDecryption(false)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            String ssmPort = response.parameter().value();
            if (ssmPort != null && !ssmPort.trim().isEmpty()) {
                return Integer.parseInt(ssmPort.trim());
            }
        } catch (Exception e) {
            // SSM not available (e.g., local dev without AWS credentials) — fall through to default
        }

        // Priority 3: Default value for local development
        return 8080;
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * <p><strong>cr-java-0071 remediation</strong>: All hard-coded environment-specific URLs
     * are replaced with values fetched from AWS SSM Parameter Store, enabling
     * environment-agnostic deployments across dev, staging, and production without
     * code changes.</p>
     *
     * @param parameterName the SSM parameter name (e.g. "/resortslite/reports/download-base-url")
     * @return the parameter value stored in SSM Parameter Store
     */
    private String getSsmParameter(String parameterName) {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(AWS_REGION))
                .build()) {

            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve SSM parameter '" + parameterName + "': " + e.getMessage(), e);
        }
    }

    /**
     * Generates a monthly booking report and uploads it to Amazon S3.
     *
     * <p><strong>cr-java-0063 remediation</strong> for original lines 37, 39, and 42:</p>
     * <ul>
     *   <li><em>Original line 37</em>: {@code File reportDir = new File(REPORT_BASE_PATH);}
     *       — replaced by constructing an S3 object key ({@code REPORT_PREFIX + fileName}).
     *       No {@code java.io.File} instance is created.</li>
     *   <li><em>Original line 39</em>: {@code reportDir.mkdirs();}
     *       — eliminated entirely. Amazon S3 does not require directory creation; objects
     *       are written directly under any key prefix.</li>
     *   <li><em>Original line 42</em>: {@code FileWriter writer = new FileWriter(fullPath);}
     *       — replaced by {@code S3Client.putObject()} which streams the CSV content
     *       directly to Amazon S3, removing all ephemeral local file-system dependencies
     *       that cause data loss on container restart or scale-out events.</li>
     * </ul>
     *
     * @param month the month for which the report is generated (e.g. "03")
     * @param year  the year for which the report is generated (e.g. "2024")
     * @return a result map containing the operation status and the S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        // cr-java-0063 fix [original line 37]: replaced `new File(REPORT_BASE_PATH)` with
        // an S3 object key. No java.io.File instance is created; the key is a logical path
        // within the S3 bucket, independent of any host file system.
        String s3Key = REPORT_PREFIX + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system required.
            // cr-java-0063 fix [original line 39]: `reportDir.mkdirs()` is eliminated.
            // Amazon S3 does not require directory creation; objects are written directly
            // under any key prefix without pre-creating parent "directories".
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // cr-java-0063 fix [original line 42]: `new FileWriter(fullPath)` replaced by
            // S3Client.putObject(). The CSV bytes are uploaded directly to Amazon S3,
            // eliminating the ephemeral local file-system dependency that causes data loss
            // on container restart or scale-out events.
            S3Client s3 = S3Client.builder()
                    .region(Region.of(AWS_REGION))
                    .build();

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(S3_BUCKET)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3.putObject(putRequest, RequestBody.fromString(csvContent));
            s3.close();

            result.put("status", "generated");
            result.put("s3Bucket", S3_BUCKET);
            result.put("s3Key", s3Key);
            // cr-java-0077: SERVER_PORT is now resolved dynamically from env var / SSM
            result.put("serverPort", SERVER_PORT);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails().errorMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for a report by retrieving the base URL from AWS Systems Manager
     * Parameter Store and appending the report name.
     *
     * <p><strong>cr-java-0071 fix (original line 66)</strong>: Replaced the hard-coded URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} with a value retrieved
     * from AWS Systems Manager Parameter Store (parameter name stored in
     * {@code REPORT_DOWNLOAD_URL_PARAM}). This enables environment-agnostic deployments —
     * the base URL is configured per environment in SSM without any code changes.</p>
     *
     * @param reportName the report file name to append to the base download URL
     * @return the full download URL constructed from the SSM-sourced base URL and the report name
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 fix [original line 66]: Replaced hard-coded URL
        // "http://reports.resorts-internal.com:8080/download/" with a value retrieved
        // from AWS Systems Manager Parameter Store. The parameter name is stored in
        // REPORT_DOWNLOAD_URL_PARAM (sourced from env var SSM_REPORT_DOWNLOAD_URL_PARAM),
        // enabling environment-agnostic deployments without code changes per environment.
        String reportDownloadBaseUrl = getSsmParameter(REPORT_DOWNLOAD_URL_PARAM); // cr-java-0071 remediated
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Builds a pre-signed HTTPS download URL for a report stored in Amazon S3.
     *
     * <p>Provides a secure, time-limited HTTPS S3 pre-signed URL valid for 60 minutes.</p>
     *
     * @param reportName the S3 object key (report file name) to generate a URL for
     * @return a pre-signed HTTPS URL valid for 60 minutes
     */
    public String buildPresignedReportDownloadUrl(String reportName) {
        try (S3Presigner presigner = S3Presigner.builder()
                .region(Region.of(AWS_REGION))
                .build()) {

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(S3_BUCKET)
                    .key(REPORT_PREFIX + reportName)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(60))
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
            return presignedRequest.url().toString();
        }
    }

    /**
     * Returns system information including S3 storage configuration and the resolved server port.
     *
     * <p>Previously exposed local file paths (cr-java-0061 / cr-java-0063). Now returns
     * S3 bucket and prefix information instead of host file-system paths.</p>
     *
     * <p><strong>cr-java-0077</strong>: {@code serverPort} is now the dynamically resolved
     * value from the {@code SERVER_PORT} environment variable or AWS SSM Parameter Store,
     * not the former hard-coded constant.</p>
     *
     * @return a map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 fix [original line 70]: Replaced java.util.Date + SimpleDateFormat
        // (server-local timezone dependent) with java.time.Instant standardised on UTC.
        // DateTimeFormatter with ZoneOffset.UTC ensures consistent timestamps across all
        // cloud regions and container instances, eliminating timezone-related scheduling
        // failures in distributed AWS deployments.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 fix: replaced REPORT_BASE_PATH and BACKUP_PATH (local java.io.File paths)
        // with S3 bucket and prefix references — no host file-system dependency.
        info.put("s3Bucket", S3_BUCKET);
        info.put("reportPrefix", REPORT_PREFIX);   // replaces /var/legacy/reports/
        info.put("backupPrefix", BACKUP_PREFIX);   // replaces C:\ResortBackups\nightly\
        // cr-java-0077 fix: SERVER_PORT is now resolved dynamically from SERVER_PORT env var
        // (injected by ECS/EKS/Elastic Beanstalk) or AWS SSM Parameter Store at startup,
        // replacing the former hard-coded constant 8080.
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
