package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // FIXED: Externalized database credentials to environment variables
    // Use AWS Secrets Manager or Parameter Store for production deployment
    @Value("${DB_HOST:localhost}")
    private String dbHost;

    @Value("${DB_USER:postgres}")
    private String dbUser;

    // FIXED: Externalized infrastructure hostname to environment variable
    @Value("${app.payment.endpoint}")
    private String paymentApi;

    /**
     * Creates a new booking in the database using parameterized queries to prevent SQL injection.
     * 
     * @param guestName Guest name for the booking
     * @param roomType Type of room (STANDARD, DELUXE, SUITE, VILLA)
     * @param checkIn Check-in date
     * @param checkOut Check-out date
     * @return Map containing booking details
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // FIXED: Using parameterized query to prevent SQL injection
        // PostgreSQL uses standard ANSI SQL syntax for INSERT statements
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Updated to use SHA-256 instead of MD5 for security
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", dbHost);
        return booking;
    }

    /**
     * Retrieves booking details by booking ID using parameterized query.
     * 
     * @param bookingId The booking ID to retrieve
     * @return Map containing booking details or error message
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // FIXED: Using parameterized query to prevent SQL injection
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    /**
     * Calculates room price based on room type, nights, season, and loyalty status.
     * 
     * @param roomType Type of room
     * @param nights Number of nights
     * @param season Season (PEAK, OFF, REGULAR)
     * @param loyalty Loyalty status (GOLD, PLATINUM, DIAMOND)
     * @return Formatted price string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = getBasePrice(roomType);
        basePrice = applySeasonalPricing(basePrice, season);
        basePrice = applyLoyaltyDiscount(basePrice, loyalty);
        basePrice = applyLengthOfStayDiscount(basePrice, nights);
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Gets base price for room type.
     * 
     * @param roomType Type of room
     * @return Base price for the room type
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
     * Applies seasonal pricing adjustments.
     * 
     * @param basePrice Base price before adjustment
     * @param season Season type
     * @return Adjusted price
     */
    private double applySeasonalPricing(double basePrice, String season) {
        return switch (season) {
            case "PEAK" -> basePrice * 1.5;
            case "OFF" -> basePrice * 0.8;
            default -> basePrice;
        };
    }

    /**
     * Applies loyalty discount based on membership tier.
     * 
     * @param basePrice Base price before discount
     * @param loyalty Loyalty tier
     * @return Discounted price
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
     * 
     * @param basePrice Base price before discount
     * @param nights Number of nights
     * @return Discounted price
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
     * Checks if a room type is available.
     * 
     * @param roomType Type of room to check
     * @return true if room type is valid and available
     */
    public boolean isRoomAvailable(String roomType) {
        return isValidRoomType(roomType);
    }

    /**
     * Validates room type against supported types.
     * 
     * @param roomType Type of room to validate
     * @return true if room type is valid
     */
    private boolean isValidRoomType(String roomType) {
        return roomType.equals("STANDARD") || roomType.equals("DELUXE") 
                || roomType.equals("SUITE") || roomType.equals("VILLA");
    }

    /**
     * Generates a report for the specified month.
     * 
     * @param month Month for report generation
     * @return Report generation message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Generates SHA-256 hash of input string.
     * 
     * @param input String to hash
     * @return Hexadecimal hash string
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
            return input;
        }
    }
}
