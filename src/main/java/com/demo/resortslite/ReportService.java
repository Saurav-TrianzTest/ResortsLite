package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for generating and managing resort booking reports.
 * Handles monthly report generation and system information retrieval.
 */
@Service
public class ReportService {

    // FIXED: Externalized report path to environment variable for container compatibility
    // Use volume mounts or cloud object storage (S3/Azure Blob) for production
    @Value("${REPORT_BASE_PATH:/tmp/reports/}")
    private String reportBasePath;

    // FIXED: Externalized backup path to environment variable for cross-platform compatibility
    @Value("${BACKUP_PATH:/tmp/backups/}")
    private String backupPath;

    // FIXED: Externalized server port to environment variable for dynamic port binding
    @Value("${server.port:8080}")
    private int serverPort;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Generates a monthly booking report in CSV format.
     * 
     * @param month Month for the report (e.g., "January", "01")
     * @param year Year for the report (e.g., "2024")
     * @return Map containing report generation status and file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = reportBasePath + fileName;

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

    /**
     * Builds a secure HTTPS URL for downloading a report.
     * 
     * @param reportName Name of the report file to download
     * @return HTTPS URL for report download
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIXED: Using HTTPS instead of HTTP for cloud security compliance
        // Use environment variable for domain configuration
        String domain = System.getenv().getOrDefault("REPORT_DOMAIN", "reports.resorts-internal.com");
        return "https://" + domain + "/download/" + reportName;
    }

    /**
     * Retrieves system configuration information including paths and ports.
     * 
     * @return Map containing system configuration details
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = LocalDateTime.now().format(DATE_TIME_FORMATTER);
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
