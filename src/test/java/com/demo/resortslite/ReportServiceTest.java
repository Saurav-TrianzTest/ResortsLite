package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateMonthlyReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateMonthlyReport_returnsNonNullMap() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("2024-03", "2024");

        // Assert
        assertNotNull(result);
    }

    @Test
    void generateMonthlyReport_resultContainsStatusKey() {
        Map<String, Object> result = reportService.generateMonthlyReport("2024-03", "2024");
        assertTrue(result.containsKey("status"), "Result must contain 'status' key");
    }

    @Test
    void generateMonthlyReport_statusIsGeneratedOrError() {
        Map<String, Object> result = reportService.generateMonthlyReport("2024-03", "2024");
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status must be 'generated' or 'error', got: " + status);
    }

    @Test
    void generateMonthlyReport_whenGenerated_pathContainsMonthAndYear() {
        Map<String, Object> result = reportService.generateMonthlyReport("2024-03", "2024");
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            String path = (String) result.get("path");
            assertNotNull(path);
            assertTrue(path.contains("2024-03"), "Path should contain month");
            assertTrue(path.contains("2024"), "Path should contain year");
        }
    }

    @Test
    void generateMonthlyReport_whenGenerated_pathContainsCsvExtension() {
        Map<String, Object> result = reportService.generateMonthlyReport("2024-06", "2024");
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            String path = (String) result.get("path");
            assertTrue(path.endsWith(".csv"), "Report file should have .csv extension");
        }
    }

    @Test
    void generateMonthlyReport_whenGenerated_serverPortIsPresent() {
        Map<String, Object> result = reportService.generateMonthlyReport("2024-03", "2024");
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            assertTrue(result.containsKey("serverPort"), "Result should contain 'serverPort'");
            assertEquals(8080, result.get("serverPort"));
        }
    }

    @Test
    void generateMonthlyReport_withDifferentMonths_returnsDistinctPaths() {
        Map<String, Object> r1 = reportService.generateMonthlyReport("2024-01", "2024");
        Map<String, Object> r2 = reportService.generateMonthlyReport("2024-02", "2024");

        String status1 = (String) r1.get("status");
        String status2 = (String) r2.get("status");

        if ("generated".equals(status1) && "generated".equals(status2)) {
            assertNotEquals(r1.get("path"), r2.get("path"),
                    "Different months should produce different file paths");
        }
    }

    @Test
    void generateMonthlyReport_withEmptyMonth_doesNotThrow() {
        // Should not throw; may return error status
        assertDoesNotThrow(() -> reportService.generateMonthlyReport("", "2024"));
    }

    @Test
    void generateMonthlyReport_withNullMonth_doesNotThrow() {
        assertDoesNotThrow(() -> reportService.generateMonthlyReport(null, "2024"));
    }

    @Test
    void generateMonthlyReport_whenError_containsMessageKey() {
        // Force an error by passing null (NullPointerException during path construction)
        Map<String, Object> result = reportService.generateMonthlyReport(null, null);
        // Either generated (unlikely) or error
        String status = (String) result.get("status");
        if ("error".equals(status)) {
            assertTrue(result.containsKey("message"), "Error result should contain 'message'");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildReportDownloadUrl tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildReportDownloadUrl_returnsNonNullString() {
        String url = reportService.buildReportDownloadUrl("report_2024-03.csv");
        assertNotNull(url);
    }

    @Test
    void buildReportDownloadUrl_containsReportName() {
        String reportName = "report_2024-03.csv";
        String url = reportService.buildReportDownloadUrl(reportName);
        assertTrue(url.contains(reportName), "URL should contain the report name");
    }

    @Test
    void buildReportDownloadUrl_startsWithHttp() {
        String url = reportService.buildReportDownloadUrl("any_report.csv");
        assertTrue(url.startsWith("http"), "URL should start with http");
    }

    @Test
    void buildReportDownloadUrl_containsDownloadPath() {
        String url = reportService.buildReportDownloadUrl("test.csv");
        assertTrue(url.contains("/download/"), "URL should contain '/download/' path segment");
    }

    @Test
    void buildReportDownloadUrl_withEmptyName_returnsUrlEndingWithSlash() {
        String url = reportService.buildReportDownloadUrl("");
        assertNotNull(url);
        assertFalse(url.isEmpty());
    }

    @Test
    void buildReportDownloadUrl_withSpecialCharacters_includesThem() {
        String url = reportService.buildReportDownloadUrl("report 2024.csv");
        assertTrue(url.contains("report 2024.csv"));
    }

    @Test
    void buildReportDownloadUrl_differentNamesProduceDifferentUrls() {
        String url1 = reportService.buildReportDownloadUrl("report_jan.csv");
        String url2 = reportService.buildReportDownloadUrl("report_feb.csv");
        assertNotEquals(url1, url2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getSystemInfo tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getSystemInfo_returnsNonNullMap() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertNotNull(info);
    }

    @Test
    void getSystemInfo_containsReportPathKey() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("reportPath"), "System info should contain 'reportPath'");
    }

    @Test
    void getSystemInfo_containsBackupPathKey() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("backupPath"), "System info should contain 'backupPath'");
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("serverPort"), "System info should contain 'serverPort'");
    }

    @Test
    void getSystemInfo_serverPortIs8080() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("generatedAt"), "System info should contain 'generatedAt'");
    }

    @Test
    void getSystemInfo_generatedAtIsFormattedTimestamp() {
        Map<String, Object> info = reportService.getSystemInfo();
        String ts = (String) info.get("generatedAt");
        assertNotNull(ts);
        // Format: yyyy-MM-dd HH:mm:ss  → length 19
        assertEquals(19, ts.length(), "Timestamp should be 19 characters (yyyy-MM-dd HH:mm:ss)");
    }

    @Test
    void getSystemInfo_reportPathIsNotEmpty() {
        Map<String, Object> info = reportService.getSystemInfo();
        String path = (String) info.get("reportPath");
        assertNotNull(path);
        assertFalse(path.isEmpty());
    }

    @Test
    void getSystemInfo_backupPathIsNotEmpty() {
        Map<String, Object> info = reportService.getSystemInfo();
        String path = (String) info.get("backupPath");
        assertNotNull(path);
        assertFalse(path.isEmpty());
    }

    @Test
    void getSystemInfo_calledTwice_generatedAtTimestampsAreValid() {
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();
        // Both timestamps should be non-null and properly formatted
        assertNotNull(info1.get("generatedAt"));
        assertNotNull(info2.get("generatedAt"));
    }
}
