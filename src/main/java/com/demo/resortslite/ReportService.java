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

    // blocker-2 (cz-java-0057): Replaced hardcoded absolute path "/var/legacy/reports/"
    // with environment variable REPORT_BASE_PATH injected via Kubernetes ConfigMap on AKS
    @Value("${REPORT_BASE_PATH:/reports}")
    private String reportBasePath;

    // blocker-3 (cz-java-0057): Replaced hardcoded Windows absolute path "C:\\ResortBackups\\nightly\\"
    // with environment variable BACKUP_PATH injected via Kubernetes ConfigMap on AKS
    @Value("${BACKUP_PATH:/backups/nightly}")
    private String backupPath;

    // blocker-11 (cz-java-0061): Replaced hardcoded port 8080 with environment variable SERVER_PORT
    // injected via Kubernetes ConfigMap on AKS for flexible container port binding
    @Value("${SERVER_PORT:8080}")
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

    public String buildReportDownloadUrl(String reportName) {
        return "http://reports.resorts-internal.com:" + serverPort + "/download/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
