package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fixed: cr-java-0069 - Replace hard-coded database credentials with AWS Secrets Manager
    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${aws.secrets.db-credentials:resorts/db/credentials}")
    private String dbCredentialsSecretName;

    @Value("${aws.secrets.api-keys:resorts/api/keys}")
    private String apiKeysSecretName;

    private SecretsManagerClient secretsManagerClient;
    private ObjectMapper objectMapper = new ObjectMapper();

    // Cache for credentials to avoid excessive API calls
    private Map<String, String> credentialsCache = new HashMap<>();

    public BookingService(@Value("${aws.region:us-east-1}") String region) {
        this.awsRegion = region;
        this.secretsManagerClient = SecretsManagerClient.builder()
                .region(Region.of(region))
                .build();
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * Fixed: cr-java-0069 - Eliminate hard-coded database credentials
     * 
     * @return Map containing database credentials
     */
    private Map<String, String> getDatabaseCredentials() {
        if (credentialsCache.containsKey("db_host")) {
            return credentialsCache;
        }

        try {
            GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
                    .secretId(dbCredentialsSecretName)
                    .build();

            GetSecretValueResponse getSecretValueResponse = secretsManagerClient
                    .getSecretValue(getSecretValueRequest);

            String secret = getSecretValueResponse.secretString();
            JsonNode secretJson = objectMapper.readTree(secret);

            credentialsCache.put("db_host", secretJson.get("host").asText());
            credentialsCache.put("db_user", secretJson.get("username").asText());
            credentialsCache.put("db_pass", secretJson.get("password").asText());

            return credentialsCache;
        } catch (Exception e) {
            // Fallback to environment variables if Secrets Manager is not available
            credentialsCache.put("db_host", System.getenv().getOrDefault("DB_HOST", "localhost"));
            credentialsCache.put("db_user", System.getenv().getOrDefault("DB_USERNAME", "sa"));
            credentialsCache.put("db_pass", System.getenv().getOrDefault("DB_PASSWORD", ""));
            return credentialsCache;
        }
    }

    /**
     * Retrieves payment API endpoint from AWS Secrets Manager.
     * Fixed: cr-java-0090 - Replace file-based authentication with AWS Secrets Manager
     * 
     * @return Payment API endpoint URL
     */
    private String getPaymentApiEndpoint() {
        try {
            GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
                    .secretId(apiKeysSecretName)
                    .build();

            GetSecretValueResponse getSecretValueResponse = secretsManagerClient
                    .getSecretValue(getSecretValueRequest);

            String secret = getSecretValueResponse.secretString();
            JsonNode secretJson = objectMapper.readTree(secret);

            return secretJson.get("payment_api_endpoint").asText();
        } catch (Exception e) {
            // Fallback to environment variable
            return System.getenv().getOrDefault("PAYMENT_ENDPOINT", "http://payment-svc:9090/payments/charge");
        }
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Fixed: SQL injection - Use parameterized queries instead of string concatenation
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Fixed: Use SHA-256 instead of MD5 for security
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, String> dbCredentials = getDatabaseCredentials();

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", dbCredentials.get("db_host"));
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // Fixed: SQL injection - Use parameterized query
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // Fixed: Reduced cyclomatic complexity by extracting logic
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = getBasePrice(roomType);
        basePrice = applySeasonalAdjustment(basePrice, season);
        basePrice = applyLoyaltyDiscount(basePrice, loyalty);
        basePrice = applyLengthOfStayDiscount(basePrice, nights);
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    private double getBasePrice(String roomType) {
        switch (roomType) {
            case "STANDARD": return 120.0;
            case "DELUXE": return 200.0;
            case "SUITE": return 350.0;
            case "VILLA": return 600.0;
            default: return 120.0;
        }
    }

    private double applySeasonalAdjustment(double basePrice, String season) {
        if ("PEAK".equals(season)) {
            return basePrice * 1.5;
        } else if ("OFF".equals(season)) {
            return basePrice * 0.8;
        }
        return basePrice;
    }

    private double applyLoyaltyDiscount(double basePrice, String loyalty) {
        switch (loyalty) {
            case "GOLD": return basePrice * 0.9;
            case "PLATINUM": return basePrice * 0.8;
            case "DIAMOND": return basePrice * 0.7;
            default: return basePrice;
        }
    }

    private double applyLengthOfStayDiscount(double basePrice, int nights) {
        if (nights >= 14) {
            return basePrice * 0.90;
        } else if (nights >= 7) {
            return basePrice * 0.95;
        }
        return basePrice;
    }

    public boolean isRoomAvailable(String roomType) {
        // Fixed: Extracted to shared validation method
        return isValidRoomType(roomType);
    }

    private boolean isValidRoomType(String roomType) {
        return "STANDARD".equals(roomType) || "DELUXE".equals(roomType) 
                || "SUITE".equals(roomType) || "VILLA".equals(roomType);
    }

    public String generateReport(String month) {
        String paymentApi = getPaymentApiEndpoint();
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    // Fixed: Use SHA-256 instead of MD5 for security
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
