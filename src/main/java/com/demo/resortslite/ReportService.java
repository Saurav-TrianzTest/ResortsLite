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
 * Service for generating and managing reports.
 * FIXED: Externalized file paths and configuration for cloud/container compatibility.
 */
@Service
public class ReportService {

    // FIXED: Externalized report path to environment variable for container compatibility
    // Use volume mounts or cloud object storage (S3/Azure Blob) in production
    @Value("${app.report.path:/tmp/reports/}")
    private String reportBasePath;

    // FIXED: Externalized backup path to environment variable
    @Value("${app.backup.path:/tmp/backups/}")
    private String backupPath;

    // FIXED: Externalized server port to environment variable for dynamic port binding
    @Value("${server.port:8080}")
    private int serverPort;

    // FIXED: Externalized report service URL to environment variable
    @Value("${app.report.service.url:https://reports.resorts-internal.com}")
    private String reportServiceUrl;

    // Thread-safe DateTimeFormatter for Java 17
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Generates a monthly report in CSV format.
     * FIXED: Uses externalized report path from environment variable.
     * RECOMMENDATION: Use cloud object storage (S3/Azure Blob) instead of local file system.
     * 
     * @param month the month for the report
     * @param year the year for the report
     * @return report generation result map
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = reportBasePath + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // FIXED: Uses externalized reportBasePath from environment variable
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
            result.put("recommendation", "Consider using S3 or Azure Blob Storage for production");

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL.
     * FIXED: Uses HTTPS and externalized service URL for cloud security compliance.
     * 
     * @param reportName the report name
     * @return the download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIXED: Uses HTTPS and externalized reportServiceUrl from environment variable
        return reportServiceUrl + "/download/" + reportName;
    }

    /**
     * Gets system information including configuration paths.
     * FIXED: Returns externalized configuration values from environment variables.
     * 
     * @return system information map
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = LocalDateTime.now().format(DATE_TIME_FORMATTER);
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        info.put("serverPort", serverPort);
        info.put("reportServiceUrl", reportServiceUrl);
        info.put("generatedAt", timestamp);
        info.put("note", "All paths are externalized via environment variables for cloud compatibility");
        return info;
    }
}
