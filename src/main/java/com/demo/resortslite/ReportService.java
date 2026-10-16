package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService – generates resort reports and stores them in Amazon S3.
 *
 * <p>All file-system dependencies (previously hard-coded absolute paths such as
 * {@code /var/legacy/reports/} and {@code C:\ResortBackups\nightly\}) have been
 * replaced with Amazon S3 object storage using AWS SDK for Java v2 (cr-java-0061).
 * The S3 bucket name and key prefix are externalised via environment-backed
 * Spring {@code @Value} properties so that no path is hard-coded in the source.</p>
 *
 * <p>All hard-coded environment-specific URLs (cr-java-0071) have been replaced
 * with values resolved from AWS Systems Manager Parameter Store at runtime via
 * Spring {@code @Value} properties backed by environment variables.</p>
 */
@Service
public class ReportService {

    /**
     * S3 bucket that stores generated reports.
     * Resolved from the environment variable {@code AWS_S3_REPORT_BUCKET}
     * or the Spring property {@code aws.s3.report-bucket}.
     */
    @Value("${aws.s3.report-bucket:${AWS_S3_REPORT_BUCKET:resorts-lite-reports}}")
    private String reportBucket; // cr-java-0061: replaces hard-coded REPORT_BASE_PATH

    /**
     * Optional S3 key prefix (folder) under which reports are stored.
     * Resolved from the environment variable {@code AWS_S3_REPORT_PREFIX}
     * or the Spring property {@code aws.s3.report-prefix}.
     */
    @Value("${aws.s3.report-prefix:${AWS_S3_REPORT_PREFIX:reports/}}")
    private String reportKeyPrefix; // cr-java-0061: replaces hard-coded REPORT_BASE_PATH

    /**
     * S3 key prefix used for backup objects.
     * Resolved from the environment variable {@code AWS_S3_BACKUP_PREFIX}
     * or the Spring property {@code aws.s3.backup-prefix}.
     */
    @Value("${aws.s3.backup-prefix:${AWS_S3_BACKUP_PREFIX:backups/nightly/}}")
    private String backupKeyPrefix; // cr-java-0061: replaces hard-coded BACKUP_PATH (C:\ResortBackups\nightly\)

    /**
     * cr-java-0077 FIX: Server port externalised to AWS Systems Manager Parameter Store.
     * The value is resolved at runtime from the Spring property {@code app.server.port},
     * which is backed by the environment variable {@code SERVER_PORT}.
     * In AWS deployments the environment variable is populated from SSM Parameter Store
     * (e.g. SSM key: {@code /resortslite/server/port}) via ECS task-definition environment
     * injection or a startup script: {@code aws ssm get-parameter --name /resortslite/server/port}.
     */
    @Value("${app.server.port:${SERVER_PORT:8080}}")
    private int serverPort; // cr-java-0077: replaces hard-coded SERVER_PORT = 8080

    /**
     * cr-java-0071: Base URL for the report download service externalised from
     * AWS Systems Manager Parameter Store.  The value is resolved at startup from
     * the Spring property {@code app.report.download-base-url}, which is backed by
     * the environment variable {@code REPORT_DOWNLOAD_BASE_URL}.
     * In AWS deployments the environment variable is populated by SSM Parameter Store
     * (e.g. via ECS task-definition secretsFrom or a startup script that calls
     * {@code aws ssm get-parameter --name /resortslite/report/download-base-url}).
     */
    @Value("${app.report.download-base-url:${REPORT_DOWNLOAD_BASE_URL:https://reports.resorts-internal.com/download}}")
    private String reportDownloadBaseUrl; // cr-java-0071: replaces hard-coded "http://reports.resorts-internal.com:8080/download/"

    /** AWS S3 client – injected by {@link S3Config}. */
    private final S3Client s3Client;

    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     *
     * <p>Previously this method wrote the report to the hard-coded local path
     * {@code /var/legacy/reports/resort_report_<month>_<year>.csv} which does not
     * exist in cloud or container environments.  The file is now stored as an S3
     * object under the configurable key prefix (cr-java-0061).</p>
     *
     * @param month numeric or named month string (e.g. {@code "03"})
     * @param year  four-digit year string (e.g. {@code "2024"})
     * @return result map containing {@code status}, {@code s3Key}, and {@code s3Bucket}
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // cr-java-0061: S3 object key replaces the former local fullPath variable
        String s3Key = reportKeyPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory – no local File or FileWriter needed
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

            // cr-java-0061: Upload to S3 instead of writing to local file system
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .contentLength((long) contentBytes.length)
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));

            result.put("status", "generated");
            result.put("s3Bucket", reportBucket);
            result.put("s3Key", s3Key);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for a report by name.
     *
     * <p>cr-java-0071 FIX: The previously hard-coded environment-specific URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} has been replaced
     * with a value externalised via AWS Systems Manager Parameter Store.  The base URL
     * is injected through the Spring property {@code app.report.download-base-url}
     * (environment variable: {@code REPORT_DOWNLOAD_BASE_URL}), enabling
     * environment-agnostic deployments without any source-code changes.</p>
     *
     * @param reportName the name (file name) of the report to download
     * @return fully-qualified download URL for the given report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071: replaced hard-coded "http://reports.resorts-internal.com:8080/download/"
        // with externalised value from AWS SSM Parameter Store via reportDownloadBaseUrl field.
        return reportDownloadBaseUrl + "/" + reportName; // cr-java-0071
    }

    /**
     * Returns system information for diagnostics.
     *
     * <p>Hard-coded local paths ({@code REPORT_BASE_PATH}, {@code BACKUP_PATH})
     * have been replaced with the S3 bucket and key-prefix values that are
     * resolved from environment variables at runtime (cr-java-0061).</p>
     *
     * @return map of diagnostic key/value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111: Replaced java.util.Date / SimpleDateFormat with java.time API.
        // Instant.now() reads from the JVM Clock (UTC by default) ensuring consistent
        // timestamps across all cloud regions and container instances.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0061: report S3 coordinates replace the former hard-coded REPORT_BASE_PATH
        info.put("reportBucket", reportBucket);
        info.put("reportKeyPrefix", reportKeyPrefix);
        // cr-java-0061: backup S3 prefix replaces the former hard-coded BACKUP_PATH
        info.put("backupKeyPrefix", backupKeyPrefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
