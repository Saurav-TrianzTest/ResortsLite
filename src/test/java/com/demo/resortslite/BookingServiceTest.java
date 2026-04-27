package com.demo.resortslite;

import com.demo.resortslite.entity.Booking;
import com.demo.resortslite.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Comprehensive test suite for BookingService.
 * Tests all business logic, database operations, and edge cases.
 */
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;

    @InjectMocks
    private BookingService bookingService;

    private static final String TEST_DB_URL = "jdbc:postgresql://localhost:5432/testdb";
    private static final String TEST_PAYMENT_API = "http://localhost:8080/payment";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(bookingService, "dbUrl", TEST_DB_URL);
        ReflectionTestUtils.setField(bookingService, "paymentApi", TEST_PAYMENT_API);
    }

    @Test
    void testCreateBooking_withValidData_savesBookingAndReturnsDetails() {
        // Arrange
        String guestName = "Jane Smith";
        String roomType = "DELUXE";
        String checkIn = "2024-03-15";
        String checkOut = "2024-03-20";

        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Map<String, Object> result = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("bookingId"));
        assertTrue(result.get("bookingId").toString().startsWith("BK-"));
        assertEquals(guestName, result.get("guestName"));
        assertEquals(roomType, result.get("roomType"));
        assertEquals(checkIn, result.get("checkIn"));
        assertEquals(checkOut, result.get("checkOut"));
        assertNotNull(result.get("confirmationCode"));
        assertEquals(TEST_DB_URL, result.get("dbUrl"));
        verify(bookingRepository, times(1)).save(any(Booking.class));
    }

    @Test
    void testCreateBooking_withDifferentDates_generatesUniqueBookingId() {
        // Arrange
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Map<String, Object> result1 = bookingService.createBooking("Guest1", "STANDARD", "2024-01-01", "2024-01-05");
        Map<String, Object> result2 = bookingService.createBooking("Guest2", "SUITE", "2024-02-01", "2024-02-10");

        // Assert
        assertNotEquals(result1.get("bookingId"), result2.get("bookingId"));
        verify(bookingRepository, times(2)).save(any(Booking.class));
    }

    @Test
    void testCreateBooking_generatesConfirmationCode() {
        // Arrange
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Map<String, Object> result = bookingService.createBooking("Test Guest", "VILLA", "2024-06-01", "2024-06-07");

        // Assert
        assertNotNull(result.get("confirmationCode"));
        assertTrue(result.get("confirmationCode").toString().length() > 0);
        // SHA-256 hash should be 64 characters (hex)
        assertEquals(64, result.get("confirmationCode").toString().length());
    }

    @Test
    void testGetBookingById_withExistingBooking_returnsBookingDetails() {
        // Arrange
        String bookingId = "BK-TEST123";
        Booking mockBooking = new Booking();
        mockBooking.setId(bookingId);
        mockBooking.setGuest("John Doe");
        mockBooking.setRoom("SUITE");
        mockBooking.setCheckin(LocalDate.of(2024, 4, 10));
        mockBooking.setCheckout(LocalDate.of(2024, 4, 15));
        mockBooking.setConfirmationCode("abc123def456");
        mockBooking.setCreatedAt(LocalDateTime.now());

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(mockBooking));

        // Act
        Map<String, Object> result = bookingService.getBookingById(bookingId);

        // Assert
        assertNotNull(result);
        assertEquals(bookingId, result.get("bookingId"));
        assertEquals("John Doe", result.get("guestName"));
        assertEquals("SUITE", result.get("roomType"));
        assertEquals("2024-04-10", result.get("checkIn"));
        assertEquals("2024-04-15", result.get("checkOut"));
        assertEquals("abc123def456", result.get("confirmationCode"));
        assertNotNull(result.get("createdAt"));
        verify(bookingRepository, times(1)).findById(bookingId);
    }

    @Test
    void testGetBookingById_withNonExistentBooking_returnsError() {
        // Arrange
        String bookingId = "BK-NOTFOUND";
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.empty());

        // Act
        Map<String, Object> result = bookingService.getBookingById(bookingId);

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("not found"));
        assertTrue(result.get("error").toString().contains(bookingId));
        verify(bookingRepository, times(1)).findById(bookingId);
    }

    @Test
    void testGetBookingById_withNullId_callsRepository() {
        // Arrange
        when(bookingRepository.findById(null)).thenReturn(Optional.empty());

        // Act
        Map<String, Object> result = bookingService.getBookingById(null);

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        verify(bookingRepository, times(1)).findById(null);
    }

    @Test
    void testCalculateRoomPrice_standardRoom_regularSeason_noLoyalty() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 3, "REGULAR", "NONE");

        // Assert
        assertEquals("360.00", price); // 120 * 3 nights
    }

    @Test
    void testCalculateRoomPrice_deluxeRoom_peakSeason_goldLoyalty() {
        // Act
        String price = bookingService.calculateRoomPrice("DELUXE", 5, "PEAK", "GOLD");

        // Assert
        // Base: 200, Peak: 200*1.5=300, Gold: 300*0.9=270, Total: 270*5=1350
        assertEquals("1350.00", price);
    }

    @Test
    void testCalculateRoomPrice_suiteRoom_offSeason_platinumLoyalty() {
        // Act
        String price = bookingService.calculateRoomPrice("SUITE", 7, "OFF", "PLATINUM");

        // Assert
        // Base: 350, Off: 350*0.8=280, Platinum: 280*0.8=224, 7nights: 224*0.95=212.8, Total: 212.8*7=1489.60
        assertEquals("1489.60", price);
    }

    @Test
    void testCalculateRoomPrice_villaRoom_regularSeason_diamondLoyalty() {
        // Act
        String price = bookingService.calculateRoomPrice("VILLA", 10, "REGULAR", "DIAMOND");

        // Assert
        // Base: 600, Regular: 600, Diamond: 600*0.7=420, 10nights: 420*0.95=399, Total: 399*10=3990
        assertEquals("3990.00", price);
    }

    @Test
    void testCalculateRoomPrice_with14NightsStay_appliesLengthDiscount() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "REGULAR", "NONE");

        // Assert
        // Base: 120, 14nights: 120*0.90=108, Total: 108*14=1512
        assertEquals("1512.00", price);
    }

    @Test
    void testCalculateRoomPrice_with7NightsStay_appliesLengthDiscount() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "REGULAR", "NONE");

        // Assert
        // Base: 120, 7nights: 120*0.95=114, Total: 114*7=798
        assertEquals("798.00", price);
    }

    @Test
    void testCalculateRoomPrice_withUnknownRoomType_usesDefaultPrice() {
        // Act
        String price = bookingService.calculateRoomPrice("UNKNOWN", 2, "REGULAR", "NONE");

        // Assert
        assertEquals("240.00", price); // Default 120 * 2 nights
    }

    @Test
    void testCalculateRoomPrice_withZeroNights_returnsZero() {
        // Act
        String price = bookingService.calculateRoomPrice("DELUXE", 0, "REGULAR", "NONE");

        // Assert
        assertEquals("0.00", price);
    }

    @Test
    void testCalculateRoomPrice_withOneNight_noLengthDiscount() {
        // Act
        String price = bookingService.calculateRoomPrice("SUITE", 1, "REGULAR", "NONE");

        // Assert
        assertEquals("350.00", price); // 350 * 1 night, no discount
    }

    @Test
    void testCalculateRoomPrice_combinedDiscounts_peakSeasonWith14Nights() {
        // Act
        String price = bookingService.calculateRoomPrice("VILLA", 14, "PEAK", "DIAMOND");

        // Assert
        // Base: 600, Peak: 600*1.5=900, Diamond: 900*0.7=630, 14nights: 630*0.90=567, Total: 567*14=7938
        assertEquals("7938.00", price);
    }

    @Test
    void testIsRoomAvailable_withStandardRoom_returnsTrue() {
        // Act
        boolean available = bookingService.isRoomAvailable("STANDARD");

        // Assert
        assertTrue(available);
    }

    @Test
    void testIsRoomAvailable_withDeluxeRoom_returnsTrue() {
        // Act
        boolean available = bookingService.isRoomAvailable("DELUXE");

        // Assert
        assertTrue(available);
    }

    @Test
    void testIsRoomAvailable_withSuiteRoom_returnsTrue() {
        // Act
        boolean available = bookingService.isRoomAvailable("SUITE");

        // Assert
        assertTrue(available);
    }

    @Test
    void testIsRoomAvailable_withVillaRoom_returnsTrue() {
        // Act
        boolean available = bookingService.isRoomAvailable("VILLA");

        // Assert
        assertTrue(available);
    }

    @Test
    void testIsRoomAvailable_withInvalidRoomType_returnsFalse() {
        // Act
        boolean available = bookingService.isRoomAvailable("PENTHOUSE");

        // Assert
        assertFalse(available);
    }

    @Test
    void testIsRoomAvailable_withNullRoomType_returnsFalse() {
        // Act
        boolean available = bookingService.isRoomAvailable(null);

        // Assert
        assertFalse(available);
    }

    @Test
    void testIsRoomAvailable_withEmptyRoomType_returnsFalse() {
        // Act
        boolean available = bookingService.isRoomAvailable("");

        // Assert
        assertFalse(available);
    }

    @Test
    void testGenerateReport_withValidMonth_returnsMessage() {
        // Act
        String result = bookingService.generateReport("January");

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("January"));
        assertTrue(result.contains(TEST_PAYMENT_API));
    }

    @Test
    void testGenerateReport_withNullMonth_returnsMessage() {
        // Act
        String result = bookingService.generateReport(null);

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("null"));
    }

    @Test
    void testGenerateReport_withEmptyMonth_returnsMessage() {
        // Act
        String result = bookingService.generateReport("");

        // Assert
        assertNotNull(result);
        assertTrue(result.length() > 0);
    }

    @Test
    void testCreateBooking_verifyBookingEntityFields() {
        // Arrange
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> {
            Booking savedBooking = invocation.getArgument(0);
            
            // Verify entity fields are set correctly
            assertNotNull(savedBooking.getId());
            assertEquals("Test Guest", savedBooking.getGuest());
            assertEquals("STANDARD", savedBooking.getRoom());
            assertEquals(LocalDate.of(2024, 5, 1), savedBooking.getCheckin());
            assertEquals(LocalDate.of(2024, 5, 5), savedBooking.getCheckout());
            assertNotNull(savedBooking.getConfirmationCode());
            
            return savedBooking;
        });

        // Act
        bookingService.createBooking("Test Guest", "STANDARD", "2024-05-01", "2024-05-05");

        // Assert
        verify(bookingRepository, times(1)).save(any(Booking.class));
    }

    @Test
    void testCalculateRoomPrice_allRoomTypes_verifyBasePrices() {
        // Act & Assert
        assertEquals("120.00", bookingService.calculateRoomPrice("STANDARD", 1, "REGULAR", "NONE"));
        assertEquals("200.00", bookingService.calculateRoomPrice("DELUXE", 1, "REGULAR", "NONE"));
        assertEquals("350.00", bookingService.calculateRoomPrice("SUITE", 1, "REGULAR", "NONE"));
        assertEquals("600.00", bookingService.calculateRoomPrice("VILLA", 1, "REGULAR", "NONE"));
    }

    @Test
    void testCalculateRoomPrice_allSeasons_verifyAdjustments() {
        // Act & Assert
        // Regular season: no adjustment
        assertEquals("120.00", bookingService.calculateRoomPrice("STANDARD", 1, "REGULAR", "NONE"));
        // Peak season: 1.5x
        assertEquals("180.00", bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE"));
        // Off season: 0.8x
        assertEquals("96.00", bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE"));
    }

    @Test
    void testCalculateRoomPrice_allLoyaltyLevels_verifyDiscounts() {
        // Act & Assert
        // No loyalty
        assertEquals("200.00", bookingService.calculateRoomPrice("DELUXE", 1, "REGULAR", "NONE"));
        // Gold: 10% off
        assertEquals("180.00", bookingService.calculateRoomPrice("DELUXE", 1, "REGULAR", "GOLD"));
        // Platinum: 20% off
        assertEquals("160.00", bookingService.calculateRoomPrice("DELUXE", 1, "REGULAR", "PLATINUM"));
        // Diamond: 30% off
        assertEquals("140.00", bookingService.calculateRoomPrice("DELUXE", 1, "REGULAR", "DIAMOND"));
    }
}
