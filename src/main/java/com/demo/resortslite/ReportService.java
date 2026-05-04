package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // FIXED: blocker-2, blocker-3 (cz-java-0057) - Using S3 storage instead of absolute paths
    @Autowired
    private S3StorageService s3StorageService;

    // FIXED: blocker-11 (cz-java-0061) - Externalized port configuration
    @Value("${server.port}")
    private int serverPort;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // FIXED: blocker-2 (cz-java-0057) - Using S3 key instead of absolute path
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Generate report content
            StringBuilder reportContent = new StringBuilder();
            reportContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            reportContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            reportContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // FIXED: blocker-2 (cz-java-0057) - Upload to S3 instead of local file system
            s3StorageService.uploadFile(s3Key, reportContent.toString());

            result.put("status", "generated");
            result.put("path", s3StorageService.getFileUrl(s3Key));
            // FIXED: blocker-11 (cz-java-0061) - Using externalized port configuration
            result.put("serverPort", serverPort);

        } catch (Exception e) {
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
        // FIXED: blocker-11 (cz-java-0061) - Using externalized port configuration
        return "http://reports.resorts-internal.com:" + serverPort + "/download/" + reportName; // cr-java-0088
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        // FIXED: blocker-3 (cz-java-0057) - Using S3 bucket reference instead of absolute paths
        info.put("reportPath", "S3 Bucket: " + s3StorageService.getFileUrl("reports/"));
        info.put("backupPath", "S3 Bucket: " + s3StorageService.getFileUrl("backups/"));
        // FIXED: blocker-11 (cz-java-0061) - Using externalized port configuration
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
