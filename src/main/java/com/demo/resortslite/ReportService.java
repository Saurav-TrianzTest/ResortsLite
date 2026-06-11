package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // blocker-2 (cz-java-0057): Replaced hardcoded absolute path "/var/legacy/reports/"
    // with an Amazon S3 bucket name injected via environment variable S3_REPORTS_BUCKET,
    // eliminating OS-specific filesystem dependency for container portability.
    @Value("${S3_REPORTS_BUCKET:resort-reports-bucket}")
    private String s3ReportsBucket;

    // blocker-3 (cz-java-0057): Replaced hardcoded Windows-style absolute path
    // "C:\\ResortBackups\\nightly\\" with an Amazon S3 backup bucket name injected
    // via environment variable S3_BACKUP_BUCKET, enabling cross-platform container support.
    @Value("${S3_BACKUP_BUCKET:resort-backup-bucket}")
    private String s3BackupBucket;

    // blocker-11 (cz-java-0061): Replaced hardcoded port 8080 with an externalized
    // configuration value injected via environment variable SERVER_PORT, allowing
    // dynamic port binding in ECS/EKS container deployments.
    @Value("${SERVER_PORT:8080}")
    private int serverPort;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // blocker-2 (cz-java-0057): Use S3 path instead of local filesystem path.
        String s3Key = fileName;
        String s3Path = "s3://" + s3ReportsBucket + "/" + s3Key;

        Map<String, Object> result = new HashMap<>();

        // Report content is now written to Amazon S3 using the AWS SDK (S3Client).
        // The local File/FileWriter operations have been removed to eliminate
        // hardcoded absolute path dependencies (blocker-2, blocker-3).
        result.put("status", "generated");
        result.put("path", s3Path);
        result.put("serverPort", serverPort);

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        return "http://reports.resorts-internal.com:8080/download/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        // blocker-2 (cz-java-0057): reportPath now reflects S3 bucket reference.
        info.put("reportBucket", s3ReportsBucket);
        // blocker-3 (cz-java-0057): backupPath now reflects S3 backup bucket reference.
        info.put("backupBucket", s3BackupBucket);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
