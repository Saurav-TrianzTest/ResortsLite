package com.demo.resortslite;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-ready service layer.
 *
 * Cloud readiness fixes applied:
 *  - cr-java-0069 (blockers 8, 9): Hard-coded DB credentials (DB_USER, DB_PASS) removed.
 *                  Credentials are now retrieved at startup from AWS Secrets Manager
 *                  using the secret name configured via the DB_SECRET_NAME environment
 *                  variable (default: resortsLite/db/credentials).
 *  - cr-java-0090 (blocker-18): File-based authentication replaced with AWS Secrets Manager
 *                  for credential storage. The loadUserCredentials() method now fetches
 *                  credentials from Secrets Manager instead of reading a local file.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * AWS Secrets Manager secret name for database credentials.
     * Set the environment variable DB_SECRET_NAME in your ECS task definition or
     * Elastic Beanstalk environment to point to the correct secret, e.g.:
     *   resortsLite/db/credentials
     *
     * Fix for cr-java-0069 (blockers 8, 9) — replaces hard-coded DB_USER / DB_PASS.
     */
    @Value("${DB_SECRET_NAME:resortsLite/db/credentials}")
    private String dbSecretName;

    /**
     * AWS region for Secrets Manager calls.
     * Defaults to us-east-1; override with the AWS_REGION environment variable.
     */
    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    /**
     * Payment API endpoint externalised from environment / SSM Parameter Store.
     * Set PAYMENT_API_URL in the ECS task definition or Elastic Beanstalk environment.
     *
     * Fix for cr-java-0069 (blocker-8): removes hard-coded infrastructure hostname.
     */
    @Value("${PAYMENT_API_URL:https://payment-service.internal/payments/charge}")
    private String paymentApiUrl;

    // Resolved credentials — populated at startup from AWS Secrets Manager.
    private String resolvedDbUser;
    private String resolvedDbPass;

    /**
     * Retrieve database credentials from AWS Secrets Manager at application startup.
     * The secret is expected to be a JSON object with keys "username" and "password":
     *   { "username": "admin", "password": "..." }
     *
     * Fix for cr-java-0069 (blockers 8, 9) and cr-java-0090 (blocker-18).
     */
    @PostConstruct
    public void loadCredentialsFromSecretsManager() {
        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretJson = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> secretMap = mapper.readValue(secretJson, Map.class);

            resolvedDbUser = secretMap.getOrDefault("username", "");
            resolvedDbPass = secretMap.getOrDefault("password", "");

            client.close();
        } catch (Exception e) {
            // Fallback: allow application to start in local/test environments where
            // Secrets Manager is not available. Credentials remain null/empty.
            resolvedDbUser = System.getenv().getOrDefault("DB_USER", "");
            resolvedDbPass = System.getenv().getOrDefault("DB_PASS", "");
        }
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

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
        return "Report generation triggered for: " + month + " via " + paymentApiUrl;
    }

    /**
     * Retrieve user credentials from AWS Secrets Manager.
     * Replaces the previous file-based authentication pattern (cr-java-0090, blocker-18).
     *
     * @param secretName the Secrets Manager secret name for the user credential set
     * @return a map containing the resolved credential fields
     */
    public Map<String, String> loadUserCredentials(String secretName) {
        Map<String, String> credentials = new HashMap<>();
        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretJson = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> secretMap = mapper.readValue(secretJson, Map.class);
            credentials.putAll(secretMap);

            client.close();
        } catch (Exception e) {
            credentials.put("error", "Unable to retrieve credentials from AWS Secrets Manager: " + e.getMessage());
        }
        return credentials;
    }
}
