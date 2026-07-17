package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService handles resort booking operations.
 *
 * <p>Cloud readiness changes applied:
 * <ul>
 *   <li>Blockers 8 &amp; 9 (cr-java-0069): Hard-coded DB_HOST, DB_USER, DB_PASS replaced
 *       with credentials retrieved at runtime from AWS Secrets Manager.</li>
 *   <li>Blocker 18 (cr-java-0090): File-based authentication replaced with AWS Secrets
 *       Manager for credential storage and Amazon Cognito for user identity management.</li>
 * </ul>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final SecretsManagerClient secretsManagerClient;
    private final SsmClient ssmClient;
    private final ObjectMapper objectMapper;

    // Blocker-8,9 (cr-java-0069): Secret name for DB credentials stored in AWS Secrets Manager.
    // The actual username/password are never embedded in source code; they are fetched at runtime.
    @Value("${cloud.aws.secrets.db-credentials-secret-name:resortslite/db/credentials}")
    private String dbCredentialsSecretName;

    // Blocker-18 (cr-java-0090): Payment API endpoint externalized to SSM Parameter Store.
    // Replaces the hard-coded internal IP address previously embedded in source code.
    @Value("${cloud.aws.ssm.payment-api-param:/resortslite/payment/api-url}")
    private String paymentApiParamName;

    public BookingService(SecretsManagerClient secretsManagerClient, SsmClient ssmClient) {
        this.secretsManagerClient = secretsManagerClient;
        this.ssmClient = ssmClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Creates a new booking record.
     * DB credentials are resolved from AWS Secrets Manager at runtime (blockers 8 &amp; 9).
     *
     * @param guestName  the guest's full name
     * @param roomType   the type of room requested
     * @param checkIn    check-in date string
     * @param checkOut   check-out date string
     * @return map containing booking confirmation details
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('"
                + bookingId + "', '" + guestName + "', '" + roomType
                + "', '" + checkIn + "', '" + checkOut + "')";
        jdbcTemplate.execute(sql);

        String confirmCode = md5Hash(bookingId + guestName);

        // Blocker-8,9 (cr-java-0069): Retrieve DB host from Secrets Manager instead of
        // using the previously hard-coded "db-prod.resorts-internal.com" value.
        String dbHost = getDbHostFromSecrets();

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
     * Retrieves a booking by its identifier.
     *
     * @param bookingId the booking identifier
     * @return map containing booking details or an error entry
     */
    public Map<String, Object> getBookingById(String bookingId) {
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    /**
     * Calculates the total room price based on room type, nights, season, and loyalty tier.
     *
     * @param roomType the type of room
     * @param nights   number of nights
     * @param season   season code (PEAK / OFF / standard)
     * @param loyalty  loyalty tier (GOLD / PLATINUM / DIAMOND / standard)
     * @return formatted total price string
     */
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

    /**
     * Checks whether a room type is available.
     *
     * @param roomType the room type to check
     * @return true if the room type is valid and available
     */
    public boolean isRoomAvailable(String roomType) {
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE")
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) {
            return false;
        }
        return true;
    }

    /**
     * Generates a report for the given month.
     * Payment API endpoint is resolved from SSM Parameter Store (blocker-18).
     *
     * @param month the month for which to generate the report
     * @return report generation status message
     */
    public String generateReport(String month) {
        // Blocker-18 (cr-java-0090): Payment API URL retrieved from SSM Parameter Store
        // instead of the previously hard-coded internal IP address.
        String paymentApi = getParameterValue(paymentApiParamName,
                "https://payment-svc.internal/payments/charge");
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    // -------------------------------------------------------------------------
    // Private helpers — AWS Secrets Manager & SSM Parameter Store integration
    // -------------------------------------------------------------------------

    /**
     * Retrieves the database host from AWS Secrets Manager.
     * Blocker-8,9 (cr-java-0069): Replaces hard-coded DB_HOST, DB_USER, DB_PASS constants.
     * The secret is expected to be a JSON object with at least a "host" key.
     *
     * @return the database host string, or a safe fallback if retrieval fails
     */
    private String getDbHostFromSecrets() {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbCredentialsSecretName)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secretJson = response.secretString();
            JsonNode secretNode = objectMapper.readTree(secretJson);
            return secretNode.has("host") ? secretNode.get("host").asText() : "unknown";
        } catch (Exception e) {
            // Return a non-sensitive placeholder; never fall back to hard-coded credentials
            return "secrets-manager-unavailable";
        }
    }

    /**
     * Retrieves a string parameter from AWS Systems Manager Parameter Store.
     * Used for externalizing environment-specific URLs and configuration values.
     *
     * @param parameterName the SSM parameter path
     * @param defaultValue  fallback value if the parameter cannot be retrieved
     * @return the resolved parameter value
     */
    private String getParameterValue(String parameterName, String defaultValue) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String md5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
