package com.demo.resortslite;

import com.demo.resortslite.entity.Booking;
import com.demo.resortslite.repository.BookingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private BookingRepository bookingRepository;

    // FIXED: Externalized database credentials to environment variables
    // Use AWS Secrets Manager or Parameter Store in production
    @Value("${spring.datasource.url}")
    private String dbUrl;

    // FIXED: Externalized payment API endpoint to environment variable
    @Value("${app.payment.endpoint}")
    private String paymentApi;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Creates a new booking in PostgreSQL database using JPA.
     * Uses parameterized queries via JPA Repository to prevent SQL injection.
     * 
     * @param guestName the guest name
     * @param roomType the room type
     * @param checkIn check-in date (yyyy-MM-dd format)
     * @param checkOut check-out date (yyyy-MM-dd format)
     * @return booking details map
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // FIXED: Using JPA Repository with parameterized queries (SQL injection safe)
        Booking booking = new Booking();
        booking.setId(bookingId);
        booking.setGuest(guestName);
        booking.setRoom(roomType);
        booking.setCheckin(LocalDate.parse(checkIn, DATE_FORMATTER));
        booking.setCheckout(LocalDate.parse(checkOut, DATE_FORMATTER));

        // Generate confirmation code
        String confirmCode = sha256Hash(bookingId + guestName);
        booking.setConfirmationCode(confirmCode);

        // Save to PostgreSQL database
        bookingRepository.save(booking);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("guestName", guestName);
        result.put("roomType", roomType);
        result.put("checkIn", checkIn);
        result.put("checkOut", checkOut);
        result.put("confirmationCode", confirmCode);
        result.put("dbUrl", dbUrl);
        return result;
    }

    /**
     * Retrieves booking by ID from PostgreSQL database.
     * Uses JPA Repository with parameterized query (SQL injection safe).
     * 
     * @param bookingId the booking ID
     * @return booking details map
     */
    public Map<String, Object> getBookingById(String bookingId) {
        Map<String, Object> result = new HashMap<>();
        
        // FIXED: Using JPA Repository findById (parameterized query, SQL injection safe)
        Optional<Booking> bookingOpt = bookingRepository.findById(bookingId);
        
        if (bookingOpt.isPresent()) {
            Booking booking = bookingOpt.get();
            result.put("bookingId", booking.getId());
            result.put("guestName", booking.getGuest());
            result.put("roomType", booking.getRoom());
            result.put("checkIn", booking.getCheckin().toString());
            result.put("checkOut", booking.getCheckout().toString());
            result.put("confirmationCode", booking.getConfirmationCode());
            result.put("createdAt", booking.getCreatedAt().toString());
        } else {
            result.put("error", "Booking not found: " + bookingId);
        }
        
        return result;
    }

    /**
     * Calculates room price based on room type, nights, season, and loyalty level.
     * REFACTORED: Reduced cyclomatic complexity by extracting logic to helper methods.
     * 
     * @param roomType the room type
     * @param nights number of nights
     * @param season the season (PEAK, OFF, REGULAR)
     * @param loyalty loyalty level (GOLD, PLATINUM, DIAMOND)
     * @return formatted price string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = getBasePrice(roomType);
        basePrice = applySeasonalAdjustment(basePrice, season);
        basePrice = applyLoyaltyDiscount(basePrice, loyalty);
        basePrice = applyLengthOfStayDiscount(basePrice, nights);
        
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Gets base price for room type.
     * Extracted from calculateRoomPrice to reduce complexity.
     */
    private double getBasePrice(String roomType) {
        return switch (roomType) {
            case "STANDARD" -> 120.0;
            case "DELUXE" -> 200.0;
            case "SUITE" -> 350.0;
            case "VILLA" -> 600.0;
            default -> 120.0;
        };
    }

    /**
     * Applies seasonal price adjustment.
     * Extracted from calculateRoomPrice to reduce complexity.
     */
    private double applySeasonalAdjustment(double basePrice, String season) {
        return switch (season) {
            case "PEAK" -> basePrice * 1.5;
            case "OFF" -> basePrice * 0.8;
            default -> basePrice;
        };
    }

    /**
     * Applies loyalty discount.
     * Extracted from calculateRoomPrice to reduce complexity.
     */
    private double applyLoyaltyDiscount(double basePrice, String loyalty) {
        return switch (loyalty) {
            case "GOLD" -> basePrice * 0.9;
            case "PLATINUM" -> basePrice * 0.8;
            case "DIAMOND" -> basePrice * 0.7;
            default -> basePrice;
        };
    }

    /**
     * Applies length of stay discount.
     * Extracted from calculateRoomPrice to reduce complexity.
     */
    private double applyLengthOfStayDiscount(double basePrice, int nights) {
        if (nights >= 14) {
            return basePrice * 0.90;
        } else if (nights >= 7) {
            return basePrice * 0.95;
        }
        return basePrice;
    }

    /**
     * Checks if room type is available.
     * REFACTORED: Uses shared validation method to eliminate code duplication.
     * 
     * @param roomType the room type
     * @return true if room type is valid
     */
    public boolean isRoomAvailable(String roomType) {
        return isValidRoomType(roomType);
    }

    /**
     * Validates room type.
     * Shared validation method to eliminate duplication.
     */
    private boolean isValidRoomType(String roomType) {
        return roomType.equals("STANDARD") || roomType.equals("DELUXE") 
            || roomType.equals("SUITE") || roomType.equals("VILLA");
    }

    /**
     * Generates report for the specified month.
     * 
     * @param month the month for report generation
     * @return report generation message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Generates SHA-256 hash for the given input string.
     * SHA-256 is a secure cryptographic hash function suitable for security-related operations.
     * 
     * @param input the string to hash
     * @return hexadecimal representation of the SHA-256 hash
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { 
                sb.append(String.format("%02x", b)); 
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 should always be available in Java 17
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
