package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive test suite for BookingController.
 * Tests all endpoints, session handling, and service integration.
 */
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @Mock
    private HttpSession httpSession;

    @InjectMocks
    private BookingController bookingController;

    private Map<String, Object> mockBooking;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Set externalized properties
        ReflectionTestUtils.setField(bookingController, "inventoryUrl", "https://inventory.example.com");
        ReflectionTestUtils.setField(bookingController, "reportPath", "/tmp/reports/");
        
        // Setup mock booking data
        mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-12345678");
        mockBooking.put("guestName", "John Doe");
        mockBooking.put("roomType", "DELUXE");
        mockBooking.put("checkIn", "2024-03-01");
        mockBooking.put("checkOut", "2024-03-05");
        mockBooking.put("confirmationCode", "abc123def456");
    }

    @Test
    @DisplayName("Test createBooking with valid parameters returns confirmed booking")
    void testCreateBooking_withValidParameters_returnsConfirmedBooking() {
        // Arrange
        String guestName = "John Doe";
        String roomType = "DELUXE";
        String checkIn = "2024-03-01";
        String checkOut = "2024-03-05";
        
        when(bookingService.createBooking(guestName, roomType, checkIn, checkOut))
            .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
            guestName, roomType, checkIn, checkOut, httpSession);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
        assertNotNull(response.get("booking"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> booking = (Map<String, Object>) response.get("booking");
        assertEquals("BK-12345678", booking.get("bookingId"));
        assertEquals(guestName, booking.get("guestName"));
        
        verify(bookingService, times(1)).createBooking(guestName, roomType, checkIn, checkOut);
        verify(httpSession, times(1)).setAttribute("lastBooking", mockBooking);
        verify(httpSession, times(1)).setAttribute("guestName", guestName);
    }

    @Test
    @DisplayName("Test createBooking stores booking in session")
    void testCreateBooking_storesBookingInSession() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Jane Smith", "SUITE", "2024-04-01", "2024-04-10", httpSession);

        // Assert
        verify(httpSession).setAttribute("lastBooking", mockBooking);
        verify(httpSession).setAttribute("guestName", "Jane Smith");
    }

    @Test
    @DisplayName("Test createBooking with different room types")
    void testCreateBooking_withDifferentRoomTypes() {
        // Arrange
        String[] roomTypes = {"STANDARD", "DELUXE", "SUITE", "VILLA"};
        
        for (String roomType : roomTypes) {
            Map<String, Object> booking = new HashMap<>(mockBooking);
            booking.put("roomType", roomType);
            when(bookingService.createBooking(anyString(), eq(roomType), anyString(), anyString()))
                .thenReturn(booking);

            // Act
            Map<String, Object> response = bookingController.createBooking(
                "Test Guest", roomType, "2024-05-01", "2024-05-05", httpSession);

            // Assert
            assertNotNull(response);
            assertEquals("confirmed", response.get("status"));
        }
    }

    @Test
    @DisplayName("Test getBookingStatus returns booking details with session guest")
    void testGetBookingStatus_returnsBookingDetailsWithSessionGuest() {
        // Arrange
        String bookingId = "BK-12345678";
        String sessionGuest = "John Doe";
        
        when(httpSession.getAttribute("guestName")).thenReturn(sessionGuest);
        when(bookingService.getBookingById(bookingId)).thenReturn(mockBooking);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus(bookingId, httpSession);

        // Assert
        assertNotNull(result);
        assertEquals(bookingId, result.get("bookingId"));
        assertEquals(sessionGuest, result.get("sessionGuest"));
        assertEquals(mockBooking, result.get("details"));
        
        verify(httpSession, times(1)).getAttribute("guestName");
        verify(bookingService, times(1)).getBookingById(bookingId);
    }

    @Test
    @DisplayName("Test getBookingStatus with null session guest")
    void testGetBookingStatus_withNullSessionGuest() {
        // Arrange
        String bookingId = "BK-99999999";
        when(httpSession.getAttribute("guestName")).thenReturn(null);
        when(bookingService.getBookingById(bookingId)).thenReturn(mockBooking);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus(bookingId, httpSession);

        // Assert
        assertNotNull(result);
        assertNull(result.get("sessionGuest"));
        assertEquals(bookingId, result.get("bookingId"));
    }

    @Test
    @DisplayName("Test getBookingStatus with non-existent booking")
    void testGetBookingStatus_withNonExistentBooking() {
        // Arrange
        String bookingId = "BK-INVALID";
        Map<String, Object> errorResult = new HashMap<>();
        errorResult.put("error", "Booking not found: " + bookingId);
        
        when(httpSession.getAttribute("guestName")).thenReturn("Test User");
        when(bookingService.getBookingById(bookingId)).thenReturn(errorResult);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus(bookingId, httpSession);

        // Assert
        assertNotNull(result);
        assertEquals(bookingId, result.get("bookingId"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.get("details");
        assertTrue(details.containsKey("error"));
    }

    @Test
    @DisplayName("Test checkAvailability returns availability status")
    void testCheckAvailability_returnsAvailabilityStatus() {
        // Arrange
        String roomType = "DELUXE";
        when(bookingService.isRoomAvailable(roomType)).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability(roomType);

        // Assert
        assertNotNull(response);
        assertEquals(roomType, response.get("roomType"));
        assertEquals("https://inventory.example.com", response.get("inventoryEndpoint"));
        assertEquals(true, response.get("available"));
        
        verify(bookingService, times(1)).isRoomAvailable(roomType);
    }

    @Test
    @DisplayName("Test checkAvailability with unavailable room")
    void testCheckAvailability_withUnavailableRoom() {
        // Arrange
        String roomType = "PENTHOUSE";
        when(bookingService.isRoomAvailable(roomType)).thenReturn(false);

        // Act
        Map<String, Object> response = bookingController.checkAvailability(roomType);

        // Assert
        assertNotNull(response);
        assertEquals(roomType, response.get("roomType"));
        assertEquals(false, response.get("available"));
    }

    @Test
    @DisplayName("Test checkAvailability for all room types")
    void testCheckAvailability_forAllRoomTypes() {
        // Arrange
        String[] roomTypes = {"STANDARD", "DELUXE", "SUITE", "VILLA"};
        
        for (String roomType : roomTypes) {
            when(bookingService.isRoomAvailable(roomType)).thenReturn(true);

            // Act
            Map<String, Object> response = bookingController.checkAvailability(roomType);

            // Assert
            assertNotNull(response);
            assertEquals(roomType, response.get("roomType"));
            assertTrue((Boolean) response.get("available"));
        }
    }

    @Test
    @DisplayName("Test downloadReport returns report path and message")
    void testDownloadReport_returnsReportPathAndMessage() {
        // Arrange
        String month = "March";
        String expectedMessage = "Report generated successfully";
        when(bookingService.generateReport(month)).thenReturn(expectedMessage);

        // Act
        Map<String, Object> response = bookingController.downloadReport(month);

        // Assert
        assertNotNull(response);
        assertEquals("/tmp/reports/March_bookings.pdf", response.get("reportPath"));
        assertEquals(expectedMessage, response.get("message"));
        
        verify(bookingService, times(1)).generateReport(month);
    }

    @Test
    @DisplayName("Test downloadReport with different months")
    void testDownloadReport_withDifferentMonths() {
        // Arrange
        String[] months = {"January", "February", "March", "December"};
        
        for (String month : months) {
            when(bookingService.generateReport(month)).thenReturn("Report for " + month);

            // Act
            Map<String, Object> response = bookingController.downloadReport(month);

            // Assert
            assertNotNull(response);
            assertTrue(((String) response.get("reportPath")).contains(month));
            assertEquals("Report for " + month, response.get("message"));
        }
    }

    @Test
    @DisplayName("Test downloadReport constructs correct file path")
    void testDownloadReport_constructsCorrectFilePath() {
        // Arrange
        String month = "April";
        when(bookingService.generateReport(month)).thenReturn("Success");

        // Act
        Map<String, Object> response = bookingController.downloadReport(month);

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.startsWith("/tmp/reports/"));
        assertTrue(reportPath.endsWith("_bookings.pdf"));
        assertTrue(reportPath.contains(month));
    }

    @Test
    @DisplayName("Test createBooking with empty guest name")
    void testCreateBooking_withEmptyGuestName() {
        // Arrange
        String emptyName = "";
        Map<String, Object> booking = new HashMap<>(mockBooking);
        booking.put("guestName", emptyName);
        
        when(bookingService.createBooking(emptyName, "STANDARD", "2024-06-01", "2024-06-05"))
            .thenReturn(booking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
            emptyName, "STANDARD", "2024-06-01", "2024-06-05", httpSession);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    @DisplayName("Test createBooking with special characters in guest name")
    void testCreateBooking_withSpecialCharactersInGuestName() {
        // Arrange
        String specialName = "O'Brien-Smith";
        Map<String, Object> booking = new HashMap<>(mockBooking);
        booking.put("guestName", specialName);
        
        when(bookingService.createBooking(specialName, "SUITE", "2024-07-01", "2024-07-10"))
            .thenReturn(booking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
            specialName, "SUITE", "2024-07-01", "2024-07-10", httpSession);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
        verify(httpSession).setAttribute("guestName", specialName);
    }

    @Test
    @DisplayName("Test checkAvailability uses externalized inventory URL")
    void testCheckAvailability_usesExternalizedInventoryUrl() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals("https://inventory.example.com", response.get("inventoryEndpoint"));
    }

    @Test
    @DisplayName("Test downloadReport uses externalized report path")
    void testDownloadReport_usesExternalizedReportPath() {
        // Arrange
        when(bookingService.generateReport("May")).thenReturn("Success");

        // Act
        Map<String, Object> response = bookingController.downloadReport("May");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.startsWith("/tmp/reports/"));
    }
}
