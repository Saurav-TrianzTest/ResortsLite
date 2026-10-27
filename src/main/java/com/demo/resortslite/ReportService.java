package com.demo.resortslite;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // S3 bucket name from environment variable (cr-java-0061, cr-java-0062, cr-java-0063)
    private String s3BucketName;

    // S3 client, lazily initialised on first use (cr-java-0061, cr-java-0062, cr-java-0063)
    private S3Client s3Client;

    // SSM client for Parameter Store lookups (cr-java-0071)
    private SsmClient ssmClient;

    // Parameter Store base path for environment configuration (cr-java-0071)
    private String parameterPath;

    // Server port from environment variable (cr-java-0077)
    private static final int SERVER_PORT;
    static {
        String portEnv = System.getenv("PORT");
        SERVER_PORT = (portEnv != null) ? Integer.parseInt(portEnv) : 8080;
    }

    private S3Client getS3Client() {
        if (s3Client == null) {
            s3Client = S3Client.builder().build();
        }
        return s3Client;
    }

    private SsmClient getSsmClient() {
        if (ssmClient == null) {
            ssmClient = SsmClient.builder().build();
        }
        return ssmClient;
    }

    private String getS3BucketName() {
        if (s3BucketName == null) {
            s3BucketName = System.getenv("APP_S3_BUCKET");
        }
        return s3BucketName;
    }

    private String getParameterPath() {
        if (parameterPath == null) {
            parameterPath = System.getenv("APP_PARAMETER_PATH");
            if (parameterPath == null) {
                parameterPath = "/app/config/";
            }
        }
        return parameterPath;
    }

    private String getParameterFromSsm(String parameterKey) {
        String fullPath = getParameterPath() + parameterKey;
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(fullPath)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = getSsmClient().getParameter(request);
            return response.parameter().value();
        } catch (SsmException e) {
            return null;
        }
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            String content = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(getS3BucketName())
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            getS3Client().putObject(putRequest, RequestBody.fromString(content));

            result.put("status", "generated");
            result.put("path", s3Key);
            result.put("serverPort", SERVER_PORT);

        } catch (SdkException e) {
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
        String baseUrl = getParameterFromSsm("report-download-url");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://reports.resorts-internal.com/download/";
        }
        return baseUrl + reportName;
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        String timestamp = Instant.now().atZone(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", getS3BucketName());
        info.put("backupPath", getS3BucketName());
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
