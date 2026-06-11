package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController bookingController;

    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidParams_returnsConfirmedStatus() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Alice");
        mockBooking.put("roomType", "SUITE");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_responseContainsBookingObject() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Bob");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03", session);

        // Assert
        assertTrue(response.containsKey("booking"), "Response should contain 'booking' key");
        assertNotNull(response.get("booking"));
    }

    @Test
    void createBooking_storesLastBookingInSession() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Carol");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Carol", "STANDARD", "2024-08-01", "2024-08-03", session);

        // Assert – session attribute set by controller
        assertNotNull(session.getAttribute("lastBooking"), "Session should store lastBooking");
    }

    @Test
    void createBooking_storesGuestNameInSession() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Dave");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Dave", "VILLA", "2024-09-01", "2024-09-05", session);

        // Assert
        assertEquals("Dave", session.getAttribute("guestName"));
    }

    @Test
    void createBooking_delegatesToBookingService() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-05"))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-05", session);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Eve", "SUITE", "2024-10-01", "2024-10-05");
    }

    @Test
    void createBooking_bookingAddedToCache() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-CACHE001");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Frank", "DELUXE", "2024-11-01", "2024-11-03", session);

        // Assert – booking is returned in response (cache is internal, verify via response)
        assertNotNull(response.get("booking"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingStatus tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingStatus_returnsMapWithBookingId() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-XYZ");
        when(bookingService.getBookingById("BK-XYZ")).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-XYZ", session);

        // Assert
        assertNotNull(result);
        assertEquals("BK-XYZ", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_returnsDetailsFromService() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("guest", "Grace");
        when(bookingService.getBookingById("BK-001")).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-001", session);

        // Assert
        assertNotNull(result.get("details"));
        assertEquals(mockDetails, result.get("details"));
    }

    @Test
    void getBookingStatus_sessionGuestIsNullWhenNotSet() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-002", session);

        // Assert – no guestName in session → sessionGuest should be null
        assertNull(result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_sessionGuestReturnsValueWhenSet() {
        // Arrange
        session.setAttribute("guestName", "Henry");
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-003", session);

        // Assert
        assertEquals("Henry", result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_delegatesToBookingService() {
        // Arrange
        when(bookingService.getBookingById("BK-004")).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-004", session);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-004");
    }

    @Test
    void getBookingStatus_resultContainsDetailsKey() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-005", session);

        // Assert
        assertTrue(result.containsKey("details"), "Result should contain 'details' key");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // checkAvailability tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void checkAvailability_returnsRoomType() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        // Assert
        assertEquals("SUITE", result.get("roomType"));
    }

    @Test
    void checkAvailability_returnsAvailableTrue_whenRoomAvailable() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals(true, result.get("available"));
    }

    @Test
    void checkAvailability_returnsAvailableFalse_whenRoomUnavailable() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertEquals(false, result.get("available"));
    }

    @Test
    void checkAvailability_containsInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("STANDARD");

        // Assert
        assertTrue(result.containsKey("inventoryEndpoint"),
                "Response should contain 'inventoryEndpoint'");
        assertNotNull(result.get("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_delegatesToBookingService() {
        // Arrange
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        // Act
        bookingController.checkAvailability("VILLA");

        // Assert
        verify(bookingService, times(1)).isRoomAvailable("VILLA");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // downloadReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void downloadReport_returnsReportPath() {
        // Arrange
        when(bookingService.generateReport("2024-03")).thenReturn("Report generated");

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-03");

        // Assert
        assertNotNull(result.get("reportPath"));
        assertTrue(((String) result.get("reportPath")).contains("2024-03"));
    }

    @Test
    void downloadReport_reportPathEndsWithPdf() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("Report generated");

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-06");

        // Assert
        String path = (String) result.get("reportPath");
        assertTrue(path.endsWith(".pdf"), "Report path should end with .pdf");
    }

    @Test
    void downloadReport_containsMessageFromService() {
        // Arrange
        when(bookingService.generateReport("2024-09")).thenReturn("Report for September");

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-09");

        // Assert
        assertEquals("Report for September", result.get("message"));
    }

    @Test
    void downloadReport_delegatesToBookingService() {
        // Arrange
        when(bookingService.generateReport("2024-12")).thenReturn("December report");

        // Act
        bookingController.downloadReport("2024-12");

        // Assert
        verify(bookingService, times(1)).generateReport("2024-12");
    }

    @Test
    void downloadReport_resultContainsBothKeys() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("ok");

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-01");

        // Assert
        assertTrue(result.containsKey("reportPath"), "Result should contain 'reportPath'");
        assertTrue(result.containsKey("message"), "Result should contain 'message'");
    }
}
