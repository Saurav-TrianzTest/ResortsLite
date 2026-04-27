package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive test suite for BookingService.
 * Tests all business logic, database operations, and pricing calculations.
 */
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Set externalized properties
        ReflectionTestUtils.setField(bookingService, "dbHost", "localhost");
        ReflectionTestUtils.setField(bookingService, "dbUser", "postgres");
        ReflectionTestUtils.setField(bookingService, "paymentApi", "https://payment.example.com");
    }

    @Test
    @DisplayName("Test createBooking inserts booking into database")
    void testCreateBooking_insertsBookingIntoDatabase() {
        // Arrange
        String guestName = "John Doe";
        String roomType = "DELUXE";
        String checkIn = "2024-03-01";
        String checkOut = "2024-03-05";
        
        when(jdbcTemplate.update(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Assert
        assertNotNull(result);
        assertTrue(((String) result.get("bookingId")).startsWith("BK-"));
        assertEquals(guestName, result.get("guestName"));
        assertEquals(roomType, result.get("roomType"));
        assertEquals(checkIn, result.get("checkIn"));
        assertEquals(checkOut, result.get("checkOut"));
        assertNotNull(result.get("confirmationCode"));
        assertEquals("localhost", result.get("dbHost"));
        
        verify(jdbcTemplate, times(1)).update(
            eq("INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)"),
            anyString(), eq(guestName), eq(roomType), eq(checkIn), eq(checkOut)
        );
    }

    @Test
    @DisplayName("Test createBooking generates unique booking IDs")
    void testCreateBooking_generatesUniqueBookingIds() {
        // Arrange
        when(jdbcTemplate.update(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(1);

        // Act
        Map<String, Object> booking1 = bookingService.createBooking("Guest1", "STANDARD", "2024-01-01", "2024-01-05");
        Map<String, Object> booking2 = bookingService.createBooking("Guest2", "DELUXE", "2024-02-01", "2024-02-05");

        // Assert
        assertNotEquals(booking1.get("bookingId"), booking2.get("bookingId"));
    }

    @Test
    @DisplayName("Test createBooking generates SHA-256 confirmation code")
    void testCreateBooking_generatesSha256ConfirmationCode() {
        // Arrange
        when(jdbcTemplate.update(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking("Jane Smith", "SUITE", "2024-04-01", "2024-04-10");

        // Assert
        String confirmationCode = (String) result.get("confirmationCode");
        assertNotNull(confirmationCode);
        assertEquals(64, confirmationCode.length()); // SHA-256 produces 64 hex characters
    }

    @Test
    @DisplayName("Test createBooking with all room types")
    void testCreateBooking_withAllRoomTypes() {
        // Arrange
        String[] roomTypes = {"STANDARD", "DELUXE", "SUITE", "VILLA"};
        when(jdbcTemplate.update(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(1);

        for (String roomType : roomTypes) {
            // Act
            Map<String, Object> result = bookingService.createBooking("Test Guest", roomType, "2024-05-01", "2024-05-05");

            // Assert
            assertNotNull(result);
            assertEquals(roomType, result.get("roomType"));
        }
    }

    @Test
    @DisplayName("Test getBookingById returns booking details")
    void testGetBookingById_returnsBookingDetails() {
        // Arrange
        String bookingId = "BK-12345678";
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("id", bookingId);
        mockBooking.put("guest", "John Doe");
        mockBooking.put("room", "DELUXE");
        
        when(jdbcTemplate.queryForMap(anyString(), eq(bookingId))).thenReturn(mockBooking);

        // Act
        Map<String, Object> result = bookingService.getBookingById(bookingId);

        // Assert
        assertNotNull(result);
        assertEquals(bookingId, result.get("id"));
        assertEquals("John Doe", result.get("guest"));
        assertEquals("DELUXE", result.get("room"));
        
        verify(jdbcTemplate, times(1)).queryForMap(
            eq("SELECT * FROM bookings WHERE id = ?"), eq(bookingId)
        );
    }

    @Test
    @DisplayName("Test getBookingById with non-existent booking returns error")
    void testGetBookingById_withNonExistentBooking_returnsError() {
        // Arrange
        String bookingId = "BK-INVALID";
        when(jdbcTemplate.queryForMap(anyString(), eq(bookingId)))
            .thenThrow(new EmptyResultDataAccessException(1));

        // Act
        Map<String, Object> result = bookingService.getBookingById(bookingId);

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(((String) result.get("error")).contains(bookingId));
    }

    @Test
    @DisplayName("Test calculateRoomPrice for STANDARD room")
    void testCalculateRoomPrice_forStandardRoom() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 3, "REGULAR", "NONE");

        // Assert
        assertEquals("360.00", price); // 120 * 3 nights
    }

    @Test
    @DisplayName("Test calculateRoomPrice for DELUXE room")
    void testCalculateRoomPrice_forDeluxeRoom() {
        // Act
        String price = bookingService.calculateRoomPrice("DELUXE", 2, "REGULAR", "NONE");

        // Assert
        assertEquals("400.00", price); // 200 * 2 nights
    }

    @Test
    @DisplayName("Test calculateRoomPrice for SUITE room")
    void testCalculateRoomPrice_forSuiteRoom() {
        // Act
        String price = bookingService.calculateRoomPrice("SUITE", 4, "REGULAR", "NONE");

        // Assert
        assertEquals("1400.00", price); // 350 * 4 nights
    }

    @Test
    @DisplayName("Test calculateRoomPrice for VILLA room")
    void testCalculateRoomPrice_forVillaRoom() {
        // Act
        String price = bookingService.calculateRoomPrice("VILLA", 5, "REGULAR", "NONE");

        // Assert
        assertEquals("3000.00", price); // 600 * 5 nights
    }

    @Test
    @DisplayName("Test calculateRoomPrice with PEAK season pricing")
    void testCalculateRoomPrice_withPeakSeasonPricing() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 2, "PEAK", "NONE");

        // Assert
        assertEquals("360.00", price); // 120 * 1.5 (peak) * 2 nights = 360
    }

    @Test
    @DisplayName("Test calculateRoomPrice with OFF season pricing")
    void testCalculateRoomPrice_withOffSeasonPricing() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 2, "OFF", "NONE");

        // Assert
        assertEquals("192.00", price); // 120 * 0.8 (off) * 2 nights = 192
    }

    @Test
    @DisplayName("Test calculateRoomPrice with GOLD loyalty discount")
    void testCalculateRoomPrice_withGoldLoyaltyDiscount() {
        // Act
        String price = bookingService.calculateRoomPrice("DELUXE", 3, "REGULAR", "GOLD");

        // Assert
        assertEquals("540.00", price); // 200 * 0.9 (gold) * 3 nights = 540
    }

    @Test
    @DisplayName("Test calculateRoomPrice with PLATINUM loyalty discount")
    void testCalculateRoomPrice_withPlatinumLoyaltyDiscount() {
        // Act
        String price = bookingService.calculateRoomPrice("DELUXE", 3, "REGULAR", "PLATINUM");

        // Assert
        assertEquals("480.00", price); // 200 * 0.8 (platinum) * 3 nights = 480
    }

    @Test
    @DisplayName("Test calculateRoomPrice with DIAMOND loyalty discount")
    void testCalculateRoomPrice_withDiamondLoyaltyDiscount() {
        // Act
        String price = bookingService.calculateRoomPrice("DELUXE", 3, "REGULAR", "DIAMOND");

        // Assert
        assertEquals("420.00", price); // 200 * 0.7 (diamond) * 3 nights = 420
    }

    @Test
    @DisplayName("Test calculateRoomPrice with 7+ nights discount")
    void testCalculateRoomPrice_with7PlusNightsDiscount() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "REGULAR", "NONE");

        // Assert
        assertEquals("798.00", price); // 120 * 0.95 (7+ nights) * 7 = 798
    }

    @Test
    @DisplayName("Test calculateRoomPrice with 14+ nights discount")
    void testCalculateRoomPrice_with14PlusNightsDiscount() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "REGULAR", "NONE");

        // Assert
        assertEquals("1512.00", price); // 120 * 0.90 (14+ nights) * 14 = 1512
    }

    @Test
    @DisplayName("Test calculateRoomPrice with combined discounts")
    void testCalculateRoomPrice_withCombinedDiscounts() {
        // Act - PEAK season, PLATINUM loyalty, 14+ nights
        String price = bookingService.calculateRoomPrice("SUITE", 14, "PEAK", "PLATINUM");

        // Assert
        // 350 * 1.5 (peak) * 0.8 (platinum) * 0.9 (14+ nights) * 14 nights = 5292.00
        assertEquals("5292.00", price);
    }

    @Test
    @DisplayName("Test calculateRoomPrice with unknown room type defaults to STANDARD")
    void testCalculateRoomPrice_withUnknownRoomType_defaultsToStandard() {
        // Act
        String price = bookingService.calculateRoomPrice("UNKNOWN", 2, "REGULAR", "NONE");

        // Assert
        assertEquals("240.00", price); // Defaults to 120 (STANDARD) * 2 nights
    }

    @Test
    @DisplayName("Test calculateRoomPrice with 1 night stay")
    void testCalculateRoomPrice_with1NightStay() {
        // Act
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "REGULAR", "NONE");

        // Assert
        assertEquals("200.00", price);
    }

    @Test
    @DisplayName("Test calculateRoomPrice with 6 nights stay no length discount")
    void testCalculateRoomPrice_with6NightsStay_noLengthDiscount() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 6, "REGULAR", "NONE");

        // Assert
        assertEquals("720.00", price); // 120 * 6, no length discount
    }

    @Test
    @DisplayName("Test isRoomAvailable returns true for valid room types")
    void testIsRoomAvailable_returnsTrueForValidRoomTypes() {
        // Arrange
        String[] validRoomTypes = {"STANDARD", "DELUXE", "SUITE", "VILLA"};

        for (String roomType : validRoomTypes) {
            // Act
            boolean result = bookingService.isRoomAvailable(roomType);

            // Assert
            assertTrue(result, "Room type " + roomType + " should be available");
        }
    }

    @Test
    @DisplayName("Test isRoomAvailable returns false for invalid room types")
    void testIsRoomAvailable_returnsFalseForInvalidRoomTypes() {
        // Arrange
        String[] invalidRoomTypes = {"PENTHOUSE", "BUNGALOW", "CABIN", "UNKNOWN", ""};

        for (String roomType : invalidRoomTypes) {
            // Act
            boolean result = bookingService.isRoomAvailable(roomType);

            // Assert
            assertFalse(result, "Room type " + roomType + " should not be available");
        }
    }

    @Test
    @DisplayName("Test isRoomAvailable is case-sensitive")
    void testIsRoomAvailable_isCaseSensitive() {
        // Act
        boolean upperCase = bookingService.isRoomAvailable("DELUXE");
        boolean lowerCase = bookingService.isRoomAvailable("deluxe");
        boolean mixedCase = bookingService.isRoomAvailable("Deluxe");

        // Assert
        assertTrue(upperCase);
        assertFalse(lowerCase);
        assertFalse(mixedCase);
    }

    @Test
    @DisplayName("Test generateReport returns message with month and payment API")
    void testGenerateReport_returnsMessageWithMonthAndPaymentApi() {
        // Act
        String result = bookingService.generateReport("March");

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("March"));
        assertTrue(result.contains("https://payment.example.com"));
    }

    @Test
    @DisplayName("Test generateReport with different months")
    void testGenerateReport_withDifferentMonths() {
        // Arrange
        String[] months = {"January", "February", "March", "December"};

        for (String month : months) {
            // Act
            String result = bookingService.generateReport(month);

            // Assert
            assertTrue(result.contains(month));
        }
    }

    @Test
    @DisplayName("Test createBooking uses parameterized query to prevent SQL injection")
    void testCreateBooking_usesParameterizedQuery() {
        // Arrange
        String maliciousInput = "'; DROP TABLE bookings; --";
        when(jdbcTemplate.update(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(maliciousInput, "STANDARD", "2024-01-01", "2024-01-05");

        // Assert
        assertNotNull(result);
        verify(jdbcTemplate).update(
            eq("INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)"),
            anyString(), eq(maliciousInput), anyString(), anyString(), anyString()
        );
    }

    @Test
    @DisplayName("Test getBookingById uses parameterized query to prevent SQL injection")
    void testGetBookingById_usesParameterizedQuery() {
        // Arrange
        String maliciousId = "BK-123' OR '1'='1";
        when(jdbcTemplate.queryForMap(anyString(), eq(maliciousId)))
            .thenThrow(new EmptyResultDataAccessException(1));

        // Act
        Map<String, Object> result = bookingService.getBookingById(maliciousId);

        // Assert
        assertNotNull(result);
        verify(jdbcTemplate).queryForMap(
            eq("SELECT * FROM bookings WHERE id = ?"), eq(maliciousId)
        );
    }

    @Test
    @DisplayName("Test createBooking with null values")
    void testCreateBooking_withNullValues() {
        // Arrange
        when(jdbcTemplate.update(anyString(), anyString(), isNull(), anyString(), anyString(), anyString()))
            .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(null, "STANDARD", "2024-01-01", "2024-01-05");

        // Assert
        assertNotNull(result);
        assertNull(result.get("guestName"));
    }

    @Test
    @DisplayName("Test calculateRoomPrice with zero nights")
    void testCalculateRoomPrice_withZeroNights() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 0, "REGULAR", "NONE");

        // Assert
        assertEquals("0.00", price);
    }

    @Test
    @DisplayName("Test calculateRoomPrice with negative nights")
    void testCalculateRoomPrice_withNegativeNights() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", -5, "REGULAR", "NONE");

        // Assert
        assertEquals("-600.00", price); // Should handle negative values
    }

    @Test
    @DisplayName("Test calculateRoomPrice formats price with two decimal places")
    void testCalculateRoomPrice_formatsWithTwoDecimalPlaces() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "REGULAR", "GOLD");

        // Assert
        assertTrue(price.matches("\\d+\\.\\d{2}"));
    }
}
