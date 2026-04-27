package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Tests report generation, file operations, and configuration management.
 */
class ReportServiceTest {

    @InjectMocks
    private ReportService reportService;

    @TempDir
    Path tempDir;

    private String testReportPath;
    private String testBackupPath;
    private static final int TEST_SERVER_PORT = 9090;
    private static final String TEST_REPORT_SERVICE_URL = "https://test-reports.example.com";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        testReportPath = tempDir.toString() + "/reports/";
        testBackupPath = tempDir.toString() + "/backups/";
        
        ReflectionTestUtils.setField(reportService, "reportBasePath", testReportPath);
        ReflectionTestUtils.setField(reportService, "backupPath", testBackupPath);
        ReflectionTestUtils.setField(reportService, "serverPort", TEST_SERVER_PORT);
        ReflectionTestUtils.setField(reportService, "reportServiceUrl", TEST_REPORT_SERVICE_URL);
    }

    @Test
    void testGenerateMonthlyReport_withValidData_createsReportFile() throws IOException {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("January", "2024");

        // Assert
        assertNotNull(result);
        assertEquals("generated", result.get("status"));
        assertNotNull(result.get("path"));
        assertEquals(TEST_SERVER_PORT, result.get("serverPort"));
        assertTrue(result.get("recommendation").toString().contains("S3"));

        // Verify file was created
        String filePath = result.get("path").toString();
        File reportFile = new File(filePath);
        assertTrue(reportFile.exists());
        assertTrue(reportFile.isFile());
    }

    @Test
    void testGenerateMonthlyReport_verifyFileContent() throws IOException {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("February", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        
        // Read and verify file content
        String filePath = result.get("path").toString();
        String content = Files.readString(Path.of(filePath));
        
        assertTrue(content.contains("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount"));
        assertTrue(content.contains("BK-001,John Smith,SUITE"));
        assertTrue(content.contains("BK-002,Jane Doe,DELUXE"));
    }

    @Test
    void testGenerateMonthlyReport_createsDirectoryIfNotExists() {
        // Arrange
        String newReportPath = tempDir.toString() + "/new_reports/";
        ReflectionTestUtils.setField(reportService, "reportBasePath", newReportPath);

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        File reportDir = new File(newReportPath);
        assertTrue(reportDir.exists());
        assertTrue(reportDir.isDirectory());
    }

    @Test
    void testGenerateMonthlyReport_withDifferentMonths_createsUniqueFiles() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("April", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("May", "2024");

        // Assert
        assertEquals("generated", result1.get("status"));
        assertEquals("generated", result2.get("status"));
        assertNotEquals(result1.get("path"), result2.get("path"));
        
        assertTrue(result1.get("path").toString().contains("April"));
        assertTrue(result2.get("path").toString().contains("May"));
    }

    @Test
    void testGenerateMonthlyReport_withDifferentYears_createsUniqueFiles() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("June", "2023");
        Map<String, Object> result2 = reportService.generateMonthlyReport("June", "2024");

        // Assert
        assertEquals("generated", result1.get("status"));
        assertEquals("generated", result2.get("status"));
        assertNotEquals(result1.get("path"), result2.get("path"));
        
        assertTrue(result1.get("path").toString().contains("2023"));
        assertTrue(result2.get("path").toString().contains("2024"));
    }

    @Test
    void testGenerateMonthlyReport_withEmptyMonth_createsFile() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        assertNotNull(result.get("path"));
    }

    @Test
    void testGenerateMonthlyReport_withNullMonth_createsFile() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport(null, "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        assertNotNull(result.get("path"));
        assertTrue(result.get("path").toString().contains("null"));
    }

    @Test
    void testGenerateMonthlyReport_withSpecialCharactersInMonth_createsFile() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("Jan-2024", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        assertNotNull(result.get("path"));
    }

    @Test
    void testGenerateMonthlyReport_overwritesExistingFile() throws IOException {
        // Arrange
        String month = "July";
        String year = "2024";
        
        // Create first report
        Map<String, Object> result1 = reportService.generateMonthlyReport(month, year);
        String filePath = result1.get("path").toString();
        long firstModified = new File(filePath).lastModified();
        
        // Wait a bit to ensure different timestamp
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Act - Create second report with same month/year
        Map<String, Object> result2 = reportService.generateMonthlyReport(month, year);

        // Assert
        assertEquals("generated", result2.get("status"));
        assertEquals(result1.get("path"), result2.get("path"));
        
        long secondModified = new File(filePath).lastModified();
        assertTrue(secondModified >= firstModified);
    }

    @Test
    void testBuildReportDownloadUrl_withValidReportName_returnsCorrectUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("report_jan_2024.csv");

        // Assert
        assertNotNull(url);
        assertEquals(TEST_REPORT_SERVICE_URL + "/download/report_jan_2024.csv", url);
        assertTrue(url.startsWith("https://"));
    }

    @Test
    void testBuildReportDownloadUrl_withEmptyReportName_returnsUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertEquals(TEST_REPORT_SERVICE_URL + "/download/", url);
    }

    @Test
    void testBuildReportDownloadUrl_withNullReportName_returnsUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl(null);

        // Assert
        assertNotNull(url);
        assertTrue(url.contains("/download/"));
    }

    @Test
    void testBuildReportDownloadUrl_withSpecialCharacters_returnsUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("report@2024#jan.csv");

        // Assert
        assertNotNull(url);
        assertTrue(url.contains("report@2024#jan.csv"));
    }

    @Test
    void testBuildReportDownloadUrl_withPathTraversal_returnsUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("../../../etc/passwd");

        // Assert
        assertNotNull(url);
        assertTrue(url.contains("../../../etc/passwd"));
    }

    @Test
    void testGetSystemInfo_returnsAllConfigurationValues() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info);
        assertEquals(testReportPath, info.get("reportPath"));
        assertEquals(testBackupPath, info.get("backupPath"));
        assertEquals(TEST_SERVER_PORT, info.get("serverPort"));
        assertEquals(TEST_REPORT_SERVICE_URL, info.get("reportServiceUrl"));
        assertNotNull(info.get("generatedAt"));
        assertNotNull(info.get("note"));
    }

    @Test
    void testGetSystemInfo_generatedAtHasCorrectFormat() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String generatedAt = info.get("generatedAt").toString();
        assertNotNull(generatedAt);
        // Format should be: yyyy-MM-dd HH:mm:ss
        assertTrue(generatedAt.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    void testGetSystemInfo_includesCloudCompatibilityNote() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String note = info.get("note").toString();
        assertTrue(note.contains("environment variables"));
        assertTrue(note.contains("cloud compatibility"));
    }

    @Test
    void testGetSystemInfo_calledMultipleTimes_returnsDifferentTimestamps() throws InterruptedException {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Thread.sleep(1100); // Wait more than 1 second to ensure different timestamp
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert
        assertNotEquals(info1.get("generatedAt"), info2.get("generatedAt"));
    }

    @Test
    void testGenerateMonthlyReport_withLongMonthName_createsFile() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("VeryLongMonthNameThatExceedsNormalLength", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        assertNotNull(result.get("path"));
    }

    @Test
    void testGenerateMonthlyReport_withNumericMonth_createsFile() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("01", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        assertTrue(result.get("path").toString().contains("01"));
    }

    @Test
    void testGenerateMonthlyReport_verifyRecommendationMessage() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("August", "2024");

        // Assert
        String recommendation = result.get("recommendation").toString();
        assertTrue(recommendation.contains("S3") || recommendation.contains("Azure Blob"));
        assertTrue(recommendation.toLowerCase().contains("production"));
    }

    @Test
    void testBuildReportDownloadUrl_usesHttps() {
        // Act
        String url = reportService.buildReportDownloadUrl("secure_report.csv");

        // Assert
        assertTrue(url.startsWith("https://"));
        assertFalse(url.startsWith("http://"));
    }

    @Test
    void testGetSystemInfo_allFieldsAreNonNull() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("reportPath"));
        assertNotNull(info.get("backupPath"));
        assertNotNull(info.get("serverPort"));
        assertNotNull(info.get("reportServiceUrl"));
        assertNotNull(info.get("generatedAt"));
        assertNotNull(info.get("note"));
    }

    @Test
    void testGenerateMonthlyReport_fileNameFormat() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("September", "2024");

        // Assert
        String path = result.get("path").toString();
        assertTrue(path.contains("resort_report_September_2024.csv"));
    }
}
