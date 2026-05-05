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

    @Autowired
    private S3Service s3Service;

    // FIXED blocker-2 & blocker-3 (cz-java-0057): Replaced absolute file paths with S3 storage
    @Value("${aws.s3.bucket.name}")
    private String s3BucketName;

    // FIXED blocker-11 (cz-java-0061): Externalized port configuration using environment variable
    @Value("${server.port}")
    private int serverPort;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        
        Map<String, Object> result = new HashMap<>();

        try {
            // FIXED blocker-2 & blocker-3 (cz-java-0057): Using S3 instead of local file system
            StringBuilder reportContent = new StringBuilder();
            reportContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            reportContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            reportContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            String s3Key = s3Service.generateS3Key(fileName);
            String s3Path = s3Service.uploadFile(s3Key, reportContent.toString());

            result.put("status", "generated");
            result.put("path", s3Path);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL
     * @param reportName Name of the report
     * @return Download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIXED blocker-11 (cz-java-0061): Using externalized port configuration
        String host = System.getenv().getOrDefault("REPORT_HOST", "reports.resorts-internal.com");
        return "http://" + host + ":" + serverPort + "/download/" + reportName;
    }

    /**
     * Gets system information
     * @return System information map
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        // FIXED blocker-2 & blocker-3 (cz-java-0057): Using S3 bucket instead of file paths
        info.put("reportStorage", "s3://" + s3BucketName);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
