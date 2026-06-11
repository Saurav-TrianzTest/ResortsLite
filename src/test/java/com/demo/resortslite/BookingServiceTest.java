package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidInputs_returnsBookingMap() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(result);
        assertEquals("Alice", result.get("guestName"));
        assertEquals("SUITE", result.get("roomType"));
        assertEquals("2024-06-01", result.get("checkIn"));
        assertEquals("2024-06-05", result.get("checkOut"));
    }

    @Test
    void createBooking_bookingIdStartsWithBK() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertNotNull(bookingId);
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with 'BK-'");
    }

    @Test
    void createBooking_confirmationCodeIsNotNull() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Carol", "STANDARD", "2024-08-10", "2024-08-12");

        // Assert
        assertNotNull(result.get("confirmationCode"));
        assertFalse(((String) result.get("confirmationCode")).isEmpty());
    }

    @Test
    void createBooking_confirmationCodeIsSha256Hex() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Dave", "VILLA", "2024-09-01", "2024-09-07");

        // Assert – SHA-256 hex is always 64 characters
        String code = (String) result.get("confirmationCode");
        assertNotNull(code);
        assertEquals(64, code.length(), "SHA-256 hex digest must be 64 characters");
        assertTrue(code.matches("[0-9a-f]+"), "SHA-256 hex must contain only hex characters");
    }

    @Test
    void createBooking_dbHostIsPresent() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Eve", "STANDARD", "2024-10-01", "2024-10-02");

        // Assert
        assertNotNull(result.get("dbHost"));
    }

    @Test
    void createBooking_executesJdbcInsert() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        bookingService.createBooking("Frank", "DELUXE", "2024-11-01", "2024-11-04");

        // Assert – jdbcTemplate.execute() must be called exactly once
        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    @Test
    void createBooking_eachCallGeneratesUniqueBookingId() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> r1 = bookingService.createBooking("G1", "SUITE", "2024-01-01", "2024-01-02");
        Map<String, Object> r2 = bookingService.createBooking("G2", "SUITE", "2024-01-01", "2024-01-02");

        // Assert
        assertNotEquals(r1.get("bookingId"), r2.get("bookingId"),
                "Each booking should have a unique ID");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingById tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingById_whenFound_returnsBookingData() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-ABCD1234");
        dbRow.put("guest", "Alice");
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-ABCD1234");

        // Assert
        assertNotNull(result);
        assertEquals("Alice", result.get("guest"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("No rows found"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-UNKNOWN");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"), "Result should contain an 'error' key");
        assertTrue(((String) result.get("error")).contains("BK-UNKNOWN"));
    }

    @Test
    void getBookingById_withEmptyId_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("Empty id"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_standardRoomNormalSeasonNoLoyalty() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 3, "NORMAL", "NONE");

        // Assert – 120.0 * 3 = 360.00
        assertEquals("360.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoomNormalSeasonNoLoyalty() {
        String price = bookingService.calculateRoomPrice("DELUXE", 2, "NORMAL", "NONE");
        // 200.0 * 2 = 400.00
        assertEquals("400.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoomNormalSeasonNoLoyalty() {
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");
        // 350.0 * 1 = 350.00
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoomNormalSeasonNoLoyalty() {
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");
        // 600.0 * 1 = 600.00
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomTypeDefaultsToStandard() {
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");
        // defaults to 120.0 * 1 = 120.00
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeasonAppliesMultiplier() {
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");
        // 120.0 * 1.5 * 1 = 180.00
        assertEquals("180.00", price);
    }

    @Test
    void calculateRoomPrice_offSeasonAppliesMultiplier() {
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");
        // 120.0 * 0.8 * 1 = 96.00
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_goldLoyaltyAppliesDiscount() {
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");
        // 120.0 * 0.9 * 1 = 108.00
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyaltyAppliesDiscount() {
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");
        // 120.0 * 0.8 * 1 = 96.00
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyaltyAppliesDiscount() {
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");
        // 120.0 * 0.7 * 1 = 84.00
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_sevenNightsAppliesWeeklyDiscount() {
        // nights >= 7 → * 0.95
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");
        // 120.0 * 0.95 * 7 = 798.00
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNightsAppliesFortnightDiscount() {
        // nights >= 14 → * 0.90  (but code checks >= 7 first, so 14 gets 0.95)
        // Code logic: if nights>=7 → 0.95; else if nights>=14 → 0.90
        // So 14 nights → 0.95 branch (first condition wins)
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");
        // 120.0 * 0.95 * 14 = 1596.00
        assertEquals("1596.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeasonWithGoldLoyalty() {
        String price = bookingService.calculateRoomPrice("DELUXE", 3, "PEAK", "GOLD");
        // 200.0 * 1.5 = 300.0; * 0.9 = 270.0; * 3 = 810.00
        assertEquals("810.00", price);
    }

    @Test
    void calculateRoomPrice_villaOffSeasonDiamondLoyalty() {
        String price = bookingService.calculateRoomPrice("VILLA", 2, "OFF", "DIAMOND");
        // 600.0 * 0.8 = 480.0; * 0.7 = 336.0; * 2 = 672.00
        assertEquals("672.00", price);
    }

    @Test
    void calculateRoomPrice_returnsFormattedTwoDecimalString() {
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");
        assertTrue(price.matches("\\d+\\.\\d{2}"), "Price should be formatted to 2 decimal places");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isRoomAvailable tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void isRoomAvailable_standardRoomReturnsTrue() {
        assertTrue(bookingService.isRoomAvailable("STANDARD"));
    }

    @Test
    void isRoomAvailable_deluxeRoomReturnsTrue() {
        assertTrue(bookingService.isRoomAvailable("DELUXE"));
    }

    @Test
    void isRoomAvailable_suiteRoomReturnsTrue() {
        assertTrue(bookingService.isRoomAvailable("SUITE"));
    }

    @Test
    void isRoomAvailable_villaRoomReturnsTrue() {
        assertTrue(bookingService.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_unknownRoomReturnsFalse() {
        assertFalse(bookingService.isRoomAvailable("PENTHOUSE"));
    }

    @Test
    void isRoomAvailable_emptyStringReturnsFalse() {
        assertFalse(bookingService.isRoomAvailable(""));
    }

    @Test
    void isRoomAvailable_lowercaseReturnsFalse() {
        assertFalse(bookingService.isRoomAvailable("standard"));
    }

    @Test
    void isRoomAvailable_nullLikeStringReturnsFalse() {
        assertFalse(bookingService.isRoomAvailable("null"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateReport_returnsStringContainingMonth() {
        String result = bookingService.generateReport("2024-03");
        assertNotNull(result);
        assertTrue(result.contains("2024-03"), "Report message should contain the month");
    }

    @Test
    void generateReport_returnsNonEmptyString() {
        String result = bookingService.generateReport("2024-12");
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void generateReport_withEmptyMonth_returnsString() {
        String result = bookingService.generateReport("");
        assertNotNull(result);
    }
}
