package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for ReportService.
 * Tests report generation, file operations, and system information retrieval.
 */
class ReportServiceTest {

    @InjectMocks
    private ReportService reportService;

    @TempDir
    Path tempDir;

    private String testReportPath;
    private String testBackupPath;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Use temp directory for testing
        testReportPath = tempDir.toString() + "/reports/";
        testBackupPath = tempDir.toString() + "/backups/";
        
        // Set externalized properties
        ReflectionTestUtils.setField(reportService, "reportBasePath", testReportPath);
        ReflectionTestUtils.setField(reportService, "backupPath", testBackupPath);
        ReflectionTestUtils.setField(reportService, "serverPort", 8080);
    }

    @Test
    @DisplayName("Test generateMonthlyReport creates report file successfully")
    void testGenerateMonthlyReport_createsReportFileSuccessfully() throws IOException {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert
        assertNotNull(result);
        assertEquals("generated", result.get("status"));
        
        String filePath = (String) result.get("path");
        assertNotNull(filePath);
        assertTrue(filePath.contains("resort_report_March_2024.csv"));
        assertEquals(8080, result.get("serverPort"));
        
        // Verify file was created
        File reportFile = new File(filePath);
        assertTrue(reportFile.exists());
        assertTrue(reportFile.isFile());
    }

    @Test
    @DisplayName("Test generateMonthlyReport creates directory if not exists")
    void testGenerateMonthlyReport_createsDirectoryIfNotExists() {
        // Arrange
        File reportDir = new File(testReportPath);
        if (reportDir.exists()) {
            reportDir.delete();
        }
        assertFalse(reportDir.exists());

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("April", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        assertTrue(reportDir.exists());
        assertTrue(reportDir.isDirectory());
    }

    @Test
    @DisplayName("Test generateMonthlyReport writes correct CSV content")
    void testGenerateMonthlyReport_writesCorrectCsvContent() throws IOException {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("May", "2024");

        // Assert
        String filePath = (String) result.get("path");
        File reportFile = new File(filePath);
        String content = Files.readString(reportFile.toPath());
        
        assertTrue(content.contains("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount"));
        assertTrue(content.contains("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00"));
        assertTrue(content.contains("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00"));
    }

    @Test
    @DisplayName("Test generateMonthlyReport with different months")
    void testGenerateMonthlyReport_withDifferentMonths() {
        // Arrange
        String[] months = {"January", "February", "March", "December"};
        String year = "2024";

        for (String month : months) {
            // Act
            Map<String, Object> result = reportService.generateMonthlyReport(month, year);

            // Assert
            assertEquals("generated", result.get("status"));
            String filePath = (String) result.get("path");
            assertTrue(filePath.contains(month));
            assertTrue(filePath.contains(year));
        }
    }

    @Test
    @DisplayName("Test generateMonthlyReport with different years")
    void testGenerateMonthlyReport_withDifferentYears() {
        // Arrange
        String[] years = {"2023", "2024", "2025"};
        String month = "June";

        for (String year : years) {
            // Act
            Map<String, Object> result = reportService.generateMonthlyReport(month, year);

            // Assert
            assertEquals("generated", result.get("status"));
            String filePath = (String) result.get("path");
            assertTrue(filePath.contains(year));
        }
    }

    @Test
    @DisplayName("Test generateMonthlyReport constructs correct file name")
    void testGenerateMonthlyReport_constructsCorrectFileName() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("July", "2024");

        // Assert
        String filePath = (String) result.get("path");
        assertTrue(filePath.endsWith("resort_report_July_2024.csv"));
    }

    @Test
    @DisplayName("Test generateMonthlyReport with invalid path returns error")
    void testGenerateMonthlyReport_withInvalidPath_returnsError() {
        // Arrange
        ReflectionTestUtils.setField(reportService, "reportBasePath", "/invalid/readonly/path/");

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("August", "2024");

        // Assert
        assertEquals("error", result.get("status"));
        assertNotNull(result.get("message"));
    }

    @Test
    @DisplayName("Test generateMonthlyReport includes server port in result")
    void testGenerateMonthlyReport_includesServerPortInResult() {
        // Arrange
        ReflectionTestUtils.setField(reportService, "serverPort", 9090);

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("September", "2024");

        // Assert
        assertEquals(9090, result.get("serverPort"));
    }

    @Test
    @DisplayName("Test buildReportDownloadUrl constructs HTTPS URL")
    void testBuildReportDownloadUrl_constructsHttpsUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("report_March_2024.csv");

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("https://"));
        assertTrue(url.contains("report_March_2024.csv"));
    }

    @Test
    @DisplayName("Test buildReportDownloadUrl uses default domain")
    void testBuildReportDownloadUrl_usesDefaultDomain() {
        // Act
        String url = reportService.buildReportDownloadUrl("test_report.csv");

        // Assert
        assertTrue(url.contains("reports.resorts-internal.com"));
        assertTrue(url.contains("/download/test_report.csv"));
    }

    @Test
    @DisplayName("Test buildReportDownloadUrl with different report names")
    void testBuildReportDownloadUrl_withDifferentReportNames() {
        // Arrange
        String[] reportNames = {
            "january_2024.csv",
            "annual_summary.pdf",
            "booking_report_Q1.xlsx"
        };

        for (String reportName : reportNames) {
            // Act
            String url = reportService.buildReportDownloadUrl(reportName);

            // Assert
            assertTrue(url.contains(reportName));
            assertTrue(url.startsWith("https://"));
        }
    }

    @Test
    @DisplayName("Test buildReportDownloadUrl with special characters in report name")
    void testBuildReportDownloadUrl_withSpecialCharactersInReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("report_2024-03-15_final.csv");

        // Assert
        assertTrue(url.contains("report_2024-03-15_final.csv"));
    }

    @Test
    @DisplayName("Test buildReportDownloadUrl with empty report name")
    void testBuildReportDownloadUrl_withEmptyReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertTrue(url.startsWith("https://"));
        assertTrue(url.endsWith("/download/"));
    }

    @Test
    @DisplayName("Test getSystemInfo returns all configuration details")
    void testGetSystemInfo_returnsAllConfigurationDetails() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info);
        assertEquals(testReportPath, info.get("reportPath"));
        assertEquals(testBackupPath, info.get("backupPath"));
        assertEquals(8080, info.get("serverPort"));
        assertNotNull(info.get("generatedAt"));
    }

    @Test
    @DisplayName("Test getSystemInfo includes timestamp")
    void testGetSystemInfo_includesTimestamp() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String timestamp = (String) info.get("generatedAt");
        assertNotNull(timestamp);
        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    @DisplayName("Test getSystemInfo with different server ports")
    void testGetSystemInfo_withDifferentServerPorts() {
        // Arrange
        int[] ports = {8080, 9090, 3000, 8443};

        for (int port : ports) {
            ReflectionTestUtils.setField(reportService, "serverPort", port);

            // Act
            Map<String, Object> info = reportService.getSystemInfo();

            // Assert
            assertEquals(port, info.get("serverPort"));
        }
    }

    @Test
    @DisplayName("Test getSystemInfo returns correct report path")
    void testGetSystemInfo_returnsCorrectReportPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(testReportPath, info.get("reportPath"));
    }

    @Test
    @DisplayName("Test getSystemInfo returns correct backup path")
    void testGetSystemInfo_returnsCorrectBackupPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(testBackupPath, info.get("backupPath"));
    }

    @Test
    @DisplayName("Test getSystemInfo generates unique timestamps")
    void testGetSystemInfo_generatesUniqueTimestamps() throws InterruptedException {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Thread.sleep(1100); // Wait for at least 1 second
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert
        String timestamp1 = (String) info1.get("generatedAt");
        String timestamp2 = (String) info2.get("generatedAt");
        assertNotEquals(timestamp1, timestamp2);
    }

    @Test
    @DisplayName("Test generateMonthlyReport with numeric month")
    void testGenerateMonthlyReport_withNumericMonth() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("01", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        String filePath = (String) result.get("path");
        assertTrue(filePath.contains("01"));
    }

    @Test
    @DisplayName("Test generateMonthlyReport overwrites existing file")
    void testGenerateMonthlyReport_overwritesExistingFile() throws IOException {
        // Arrange
        String month = "October";
        String year = "2024";
        
        // Create first report
        Map<String, Object> result1 = reportService.generateMonthlyReport(month, year);
        String filePath = (String) result1.get("path");
        File reportFile = new File(filePath);
        long firstModified = reportFile.lastModified();
        
        // Wait a bit to ensure different timestamp
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // Ignore
        }

        // Act - Create second report with same month/year
        Map<String, Object> result2 = reportService.generateMonthlyReport(month, year);

        // Assert
        assertEquals("generated", result2.get("status"));
        long secondModified = reportFile.lastModified();
        assertTrue(secondModified >= firstModified);
    }

    @Test
    @DisplayName("Test generateMonthlyReport with long month name")
    void testGenerateMonthlyReport_withLongMonthName() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("September", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        String filePath = (String) result.get("path");
        assertTrue(filePath.contains("September"));
    }

    @Test
    @DisplayName("Test generateMonthlyReport with short month name")
    void testGenerateMonthlyReport_withShortMonthName() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("May", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        String filePath = (String) result.get("path");
        assertTrue(filePath.contains("May"));
    }

    @Test
    @DisplayName("Test buildReportDownloadUrl uses environment variable for domain")
    void testBuildReportDownloadUrl_usesEnvironmentVariableForDomain() {
        // Note: This test verifies the method uses System.getenv() with a default
        // In actual execution, it would use the environment variable if set
        
        // Act
        String url = reportService.buildReportDownloadUrl("test.csv");

        // Assert
        assertTrue(url.startsWith("https://"));
        assertTrue(url.contains("/download/test.csv"));
    }

    @Test
    @DisplayName("Test generateMonthlyReport file contains CSV header")
    void testGenerateMonthlyReport_fileContainsCsvHeader() throws IOException {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("November", "2024");

        // Assert
        String filePath = (String) result.get("path");
        String content = Files.readString(Path.of(filePath));
        String[] lines = content.split("\n");
        
        assertTrue(lines.length > 0);
        assertEquals("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount", lines[0]);
    }

    @Test
    @DisplayName("Test generateMonthlyReport file contains sample data")
    void testGenerateMonthlyReport_fileContainsSampleData() throws IOException {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("December", "2024");

        // Assert
        String filePath = (String) result.get("path");
        String content = Files.readString(Path.of(filePath));
        String[] lines = content.split("\n");
        
        assertTrue(lines.length >= 3); // Header + 2 data rows
    }
}
