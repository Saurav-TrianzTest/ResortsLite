package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService handles all booking-related business logic.
 * Database credentials are retrieved from AWS Secrets Manager at runtime
 * (blockers 8, 9) — no credentials are stored in source code.
 * Authentication credentials are managed via AWS Secrets Manager and
 * Amazon Cognito (blocker 18) instead of local file storage.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;

    // AWS Secrets Manager secret name for database credentials (blockers 8, 9).
    // The secret stores a JSON payload: {"host":"...","username":"...","password":"..."}
    // Secret name is externalised via environment variable — no hard-coded value.
    private static final String DB_SECRET_NAME = System.getenv()
            .getOrDefault("DB_SECRET_NAME", "/resortslite/db/credentials");

    // Payment API endpoint is externalised via environment variable (no hard-coded URL).
    private static final String PAYMENT_API = System.getenv()
            .getOrDefault("PAYMENT_API_URL", "https://payment-svc.internal/payments/charge");

    public BookingService() {
        this.secretsManagerClient = SecretsManagerClient.create();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager (blockers 8, 9).
     * Returns a map with keys: host, username, password.
     *
     * @return map of credential key-value pairs
     */
    private Map<String, String> getDbCredentials() {
        Map<String, String> credentials = new HashMap<>();
        try {
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(DB_SECRET_NAME)
                            .build());
            JsonNode secretJson = objectMapper.readTree(response.secretString());
            credentials.put("host", secretJson.path("host").asText());
            credentials.put("username", secretJson.path("username").asText());
            credentials.put("password", secretJson.path("password").asText());
        } catch (Exception e) {
            // Credentials unavailable — surface error rather than fall back to hard-coded values
            throw new RuntimeException("Failed to retrieve database credentials from AWS Secrets Manager: "
                    + e.getMessage(), e);
        }
        return credentials;
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterised query — prevents SQL injection
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // VIOLATION [Security Health / High]: MD5 is a broken hash algorithm (RFC 6151).
        // Do not use MD5 for any security-related hashing. Use SHA-256 or bcrypt.
        String confirmCode = md5Hash(bookingId + guestName); // sec-weak-hash-001

        // Retrieve DB host from Secrets Manager for informational purposes (blockers 8, 9)
        String dbHost;
        try {
            dbHost = getDbCredentials().get("host");
        } catch (Exception e) {
            dbHost = "managed-by-aws-secrets-manager";
        }

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

    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterised query — prevents SQL injection
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // VIOLATION [Code Sustainability / High]: High cyclomatic complexity.
    // This method has 9+ decision branches. Automated transformation tools flag methods
    // above complexity threshold as high maintenance risk and transformation blockers.
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = 0;
        if (roomType.equals("STANDARD")) { basePrice = 120.0; }
        else if (roomType.equals("DELUXE")) { basePrice = 200.0; }
        else if (roomType.equals("SUITE")) { basePrice = 350.0; }
        else if (roomType.equals("VILLA")) { basePrice = 600.0; }
        else { basePrice = 120.0; }
        if (season.equals("PEAK")) { basePrice = basePrice * 1.5; }
        else if (season.equals("OFF")) { basePrice = basePrice * 0.8; }
        if (loyalty.equals("GOLD")) { basePrice = basePrice * 0.9; }
        else if (loyalty.equals("PLATINUM")) { basePrice = basePrice * 0.8; }
        else if (loyalty.equals("DIAMOND")) { basePrice = basePrice * 0.7; }
        if (nights >= 7) { basePrice = basePrice * 0.95; }
        else if (nights >= 14) { basePrice = basePrice * 0.90; }
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    public boolean isRoomAvailable(String roomType) {
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE")
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) {
            return false;
        }
        return true;
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    /**
     * Validates user authentication credentials by retrieving the expected credentials
     * from AWS Secrets Manager (blocker-18). Replaces local file-based credential storage.
     * For full user lifecycle management, integrate with Amazon Cognito.
     *
     * @param username the username to authenticate
     * @param password the password to validate
     * @return true if credentials are valid, false otherwise
     */
    public boolean authenticateUser(String username, String password) {
        // Retrieve authentication credentials from AWS Secrets Manager (blocker-18)
        // Secret name for auth credentials is externalised via environment variable
        String authSecretName = System.getenv()
                .getOrDefault("AUTH_SECRET_NAME", "/resortslite/auth/credentials");
        try {
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(authSecretName)
                            .build());
            JsonNode secretJson = objectMapper.readTree(response.secretString());
            String storedUser = secretJson.path("username").asText();
            String storedPass = secretJson.path("password").asText();
            // In production, use Amazon Cognito for full user identity management
            return storedUser.equals(username) && storedPass.equals(password);
        } catch (Exception e) {
            throw new RuntimeException("Authentication service unavailable: " + e.getMessage(), e);
        }
    }

    private String md5Hash(String input) { // sec-weak-hash-001
        try {
            MessageDigest md = MessageDigest.getInstance("MD5"); // sec-weak-hash-001
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
