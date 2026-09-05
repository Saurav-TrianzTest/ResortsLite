package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // cz-java-0057 FIX (Line 19): Replaced hardcoded absolute path "/var/legacy/reports/"
    // with EFS-backed mount path injected via environment variable APP_REPORT_BASE_PATH.
    // In ECS Fargate, configure an EFS volume mounted at the path set in APP_REPORT_BASE_PATH
    // (default: /mnt/efs/reports) within the task definition.
    @Value("${app.report.base-path:/mnt/efs/reports}")
    private String reportBasePath;

    // cz-java-0057 FIX (Line 23): Replaced hardcoded Windows absolute path
    // "C:\\ResortBackups\\nightly\\" with EFS-backed mount path injected via
    // environment variable APP_BACKUP_PATH. In ECS Fargate, configure an EFS volume
    // mounted at the path set in APP_BACKUP_PATH (default: /mnt/efs/backups).
    @Value("${app.backup.path:/mnt/efs/backups}")
    private String backupPath;

    // cz-java-0061 FIX (Line 28): Replaced hardcoded port 8080 with a value injected from
    // AWS Secrets Manager via the SERVER_PORT environment variable in the ECS Fargate task
    // definition. Container orchestration (ECS/EKS) dynamically assigns ports; hardcoded
    // values prevent flexible deployment and service discovery.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = reportBasePath + "/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(reportBasePath);
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            FileWriter writer = new FileWriter(fullPath);
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.close();

            result.put("status", "generated");
            result.put("path", fullPath);
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
