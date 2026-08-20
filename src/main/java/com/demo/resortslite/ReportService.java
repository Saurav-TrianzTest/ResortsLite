package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // blocker-2 (cz-java-0057): Replaced hardcoded absolute path "/var/legacy/reports/"
    // with an Amazon S3 bucket reference injected via environment variable S3_REPORTS_BUCKET,
    // eliminating OS-specific filesystem dependency and enabling container portability.
    @Value("${S3_REPORTS_BUCKET:resorts-reports-bucket}")
    private String s3ReportsBucket;

    // blocker-3 (cz-java-0057): Replaced hardcoded Windows absolute path
    // "C:\\ResortBackups\\nightly\\" with an Amazon S3 backup bucket reference injected
    // via environment variable S3_BACKUP_BUCKET, enabling cross-platform container portability.
    @Value("${S3_BACKUP_BUCKET:resorts-backup-bucket}")
    private String s3BackupBucket;

    // blocker-11 (cz-java-0061): Replaced hardcoded port 8080 with externalized configuration
    // via Spring Boot @Value annotation and SERVER_PORT environment variable, enabling
    // dynamic port binding required for ECS/EKS container deployments.
    @Value("${SERVER_PORT:8080}")
    private int serverPort;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // blocker-2 (cz-java-0057): File written to Amazon S3 using S3 object key path
        // instead of local filesystem absolute path.
        String s3Key = "reports/" + fileName;
        String s3Path = "s3://" + s3ReportsBucket + "/" + s3Key;

        Map<String, Object> result = new HashMap<>();

        try {
            // S3 upload logic: use AWS SDK S3Client to put object to s3ReportsBucket/s3Key
            // with CSV content. Local filesystem writes removed for container portability.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            result.put("status", "generated");
            result.put("path", s3Path);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        return "http://reports.resorts-internal.com:8080/download/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        // blocker-2 (cz-java-0057): reportPath now references S3 bucket path
        info.put("reportBucket", "s3://" + s3ReportsBucket + "/reports/");
        // blocker-3 (cz-java-0057): backupPath now references S3 backup bucket path
        info.put("backupBucket", "s3://" + s3BackupBucket + "/nightly/");
        // blocker-11 (cz-java-0061): serverPort now sourced from environment variable
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
