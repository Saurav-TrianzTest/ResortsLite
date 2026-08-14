package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
@Service
public class ReportService {

    // FIXED cr-java-0061: Replaced hard-coded file paths with S3 bucket configuration
    // Using environment variables for cloud-native configuration
    @Value("${aws.s3.bucket.reports:resort-reports-bucket}")
    private String reportsBucketName;

    @Value("${aws.s3.region:us-east-1}")
    private String awsRegion;

    // FIXED cr-java-0077: Hard-coded port replaced with environment variable injection
    // Port is now configurable via SERVER_PORT environment variable for AWS ECS/EKS/Elastic Beanstalk
    // Falls back to server.port property, which defaults to 0 (random port assignment)
    // This enables dynamic port assignment required by container orchestration platforms
    @Value("${SERVER_PORT:${server.port:0}}")
    private int serverPort;

    private S3Client s3Client;

    @PostConstruct
    public void initializeS3Client() {
        // Initialize S3 client with default credentials provider (uses IAM roles in AWS)
        this.s3Client = S3Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @PreDestroy
    public void closeS3Client() {
        if (s3Client != null) {
            s3Client.close();
        }
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // FIXED cr-java-0061 Line 23: Using S3 key path instead of local file path
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.flush();
            writer.close();

            byte[] csvData = outputStream.toByteArray();

            // FIXED cr-java-0061 Line 37 & 42: Upload to S3 instead of writing to local file system
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(reportsBucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(csvData));

            result.put("status", "generated");
            result.put("s3Bucket", reportsBucketName);
            result.put("s3Key", s3Key);
            result.put("s3Uri", "s3://" + reportsBucketName + "/" + s3Key);
            result.put("serverPort", serverPort);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", "S3 Error: " + e.awsErrorDetails().errorMessage());
        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", "IO Error: " + e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // FIXED cr-java-0077: Replaced hard-coded port 8080 with dynamic serverPort variable
        // Port is now injected from environment variable SERVER_PORT or server.port property
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        return "http://reports.resorts-internal.com:" + serverPort + "/download/" + reportName; // cr-java-0088
        // FIXED cr-java-0111: Replaced java.util.Date/SimpleDateFormat with java.time API (Instant) and standardized on UTC
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC).format(Instant.now());
        info.put("awsRegion", awsRegion);
        info.put("storageType", "Amazon S3");
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
