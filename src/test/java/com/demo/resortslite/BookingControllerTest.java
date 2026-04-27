package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive test suite for BookingController.
 * Tests all REST endpoints and business logic paths.
 */
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController bookingController;

    private static final String TEST_INVENTORY_URL = "http://localhost:8080/inventory";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(bookingController, "inventoryUrl", TEST_INVENTORY_URL);
    }

    @Test
    void testCreateBooking_withValidData_returnsConfirmedBooking() {
        // Arrange
        String guestName = "John Doe";
        String roomType = "Deluxe";
        String checkIn = "2024-01-15";
        String checkOut = "2024-01-20";
        
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("id", "B123");
        mockBooking.put("guestName", guestName);
        mockBooking.put("roomType", roomType);
        
        when(bookingService.createBooking(guestName, roomType, checkIn, checkOut))
            .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(guestName, roomType, checkIn, checkOut);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
        assertNotNull(response.get("booking"));
        assertEquals(mockBooking, response.get("booking"));
        verify(bookingService, times(1)).createBooking(guestName, roomType, checkIn, checkOut);
    }

    @Test
    void testCreateBooking_withEmptyGuestName_callsService() {
        // Arrange
        String guestName = "";
        String roomType = "Standard";
        String checkIn = "2024-02-01";
        String checkOut = "2024-02-05";
        
        Map<String, Object> mockBooking = new HashMap<>();
        when(bookingService.createBooking(guestName, roomType, checkIn, checkOut))
            .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(guestName, roomType, checkIn, checkOut);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
        verify(bookingService, times(1)).createBooking(guestName, roomType, checkIn, checkOut);
    }

    @Test
    void testCreateBooking_withNullValues_callsService() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        when(bookingService.createBooking(null, null, null, null))
            .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(null, null, null, null);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
        verify(bookingService, times(1)).createBooking(null, null, null, null);
    }

    @Test
    void testGetBookingStatus_withValidId_returnsBookingDetails() {
        // Arrange
        String bookingId = "B123";
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", bookingId);
        mockDetails.put("status", "confirmed");
        
        when(bookingService.getBookingById(bookingId)).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus(bookingId);

        // Assert
        assertNotNull(result);
        assertEquals(bookingId, result.get("bookingId"));
        assertEquals(mockDetails, result.get("details"));
        verify(bookingService, times(1)).getBookingById(bookingId);
    }

    @Test
    void testGetBookingStatus_withNullId_callsService() {
        // Arrange
        when(bookingService.getBookingById(null)).thenReturn(null);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus(null);

        // Assert
        assertNotNull(result);
        assertNull(result.get("bookingId"));
        verify(bookingService, times(1)).getBookingById(null);
    }

    @Test
    void testGetBookingStatus_withNonExistentId_returnsNullDetails() {
        // Arrange
        String bookingId = "INVALID";
        when(bookingService.getBookingById(bookingId)).thenReturn(null);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus(bookingId);

        // Assert
        assertNotNull(result);
        assertEquals(bookingId, result.get("bookingId"));
        assertNull(result.get("details"));
        verify(bookingService, times(1)).getBookingById(bookingId);
    }

    @Test
    void testCheckAvailability_withValidRoomType_returnsAvailability() {
        // Arrange
        String roomType = "Suite";
        when(bookingService.isRoomAvailable(roomType)).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability(roomType);

        // Assert
        assertNotNull(response);
        assertEquals(roomType, response.get("roomType"));
        assertEquals(TEST_INVENTORY_URL, response.get("inventoryEndpoint"));
        assertEquals(true, response.get("available"));
        verify(bookingService, times(1)).isRoomAvailable(roomType);
    }

    @Test
    void testCheckAvailability_withUnavailableRoom_returnsFalse() {
        // Arrange
        String roomType = "Presidential";
        when(bookingService.isRoomAvailable(roomType)).thenReturn(false);

        // Act
        Map<String, Object> response = bookingController.checkAvailability(roomType);

        // Assert
        assertNotNull(response);
        assertEquals(roomType, response.get("roomType"));
        assertEquals(false, response.get("available"));
        verify(bookingService, times(1)).isRoomAvailable(roomType);
    }

    @Test
    void testCheckAvailability_withNullRoomType_callsService() {
        // Arrange
        when(bookingService.isRoomAvailable(null)).thenReturn(false);

        // Act
        Map<String, Object> response = bookingController.checkAvailability(null);

        // Assert
        assertNotNull(response);
        assertNull(response.get("roomType"));
        assertEquals(TEST_INVENTORY_URL, response.get("inventoryEndpoint"));
        verify(bookingService, times(1)).isRoomAvailable(null);
    }

    @Test
    void testDownloadReport_withValidMonth_returnsReportMessage() {
        // Arrange
        String month = "January";
        String reportMessage = "Report generated successfully";
        when(bookingService.generateReport(month)).thenReturn(reportMessage);

        // Act
        Map<String, Object> response = bookingController.downloadReport(month);

        // Assert
        assertNotNull(response);
        assertEquals(month, response.get("month"));
        assertEquals(reportMessage, response.get("message"));
        assertNotNull(response.get("recommendation"));
        assertTrue(response.get("recommendation").toString().contains("S3"));
        verify(bookingService, times(1)).generateReport(month);
    }

    @Test
    void testDownloadReport_withNullMonth_callsService() {
        // Arrange
        when(bookingService.generateReport(null)).thenReturn("Error: Invalid month");

        // Act
        Map<String, Object> response = bookingController.downloadReport(null);

        // Assert
        assertNotNull(response);
        assertNull(response.get("month"));
        assertNotNull(response.get("message"));
        verify(bookingService, times(1)).generateReport(null);
    }

    @Test
    void testDownloadReport_withEmptyMonth_callsService() {
        // Arrange
        String month = "";
        when(bookingService.generateReport(month)).thenReturn("Report for empty month");

        // Act
        Map<String, Object> response = bookingController.downloadReport(month);

        // Assert
        assertNotNull(response);
        assertEquals(month, response.get("month"));
        assertNotNull(response.get("message"));
        verify(bookingService, times(1)).generateReport(month);
    }

    @Test
    void testInventoryUrlInjection_verifyFieldValue() {
        // Act
        String actualUrl = (String) ReflectionTestUtils.getField(bookingController, "inventoryUrl");

        // Assert
        assertEquals(TEST_INVENTORY_URL, actualUrl);
    }
}
