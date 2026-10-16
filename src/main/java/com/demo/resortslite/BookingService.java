package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 [Cloud Readiness / CRITICAL]: Hard-coded database credentials replaced
    // with AWS Secrets Manager integration. The secret name is externalised to an
    // environment variable (DB_SECRET_NAME) so no credentials appear in source code or
    // container images. Credentials are fetched at runtime and never stored in fields.
    @Value("${aws.secretsmanager.db-secret-name:${DB_SECRET_NAME:resortslite/db/credentials}}")
    private String dbSecretName;

    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    // cr-java-0090 [Security & Authentication / HIGH]: Amazon Cognito User Pool configuration.
    // User Pool ID and Client ID are externalised to environment variables / AWS SSM Parameter
    // Store so no identity-provider details are hardcoded in source code or container images.
    // Set COGNITO_USER_POOL_ID and COGNITO_CLIENT_ID in your ECS task definition, EC2 launch
    // template, or via a startup script that reads from SSM Parameter Store:
    //   aws ssm get-parameter --name /resortslite/cognito/user-pool-id --with-decryption
    //   aws ssm get-parameter --name /resortslite/cognito/client-id    --with-decryption
    @Value("${aws.cognito.user-pool-id:${COGNITO_USER_POOL_ID:}}")
    private String cognitoUserPoolId;

    @Value("${aws.cognito.client-id:${COGNITO_CLIENT_ID:}}")
    private String cognitoClientId;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Retrieves database credentials from AWS Secrets Manager at runtime.
     * The secret is expected to be a JSON object with "username" and "password" keys,
     * matching the format used by AWS RDS automatic secret rotation.
     *
     * @return a Map containing "username" and "password" keys
     */
    private Map<String, String> getDbCredentials() {
        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretJson = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode secretNode = mapper.readTree(secretJson);

            Map<String, String> credentials = new HashMap<>();
            credentials.put("username", secretNode.get("username").asText());
            credentials.put("password", secretNode.get("password").asText());
            return credentials;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve database credentials from AWS Secrets Manager (secret: "
                            + dbSecretName + "): " + e.getMessage(), e);
        }
    }

    /**
     * cr-java-0090 FIX: Validates a guest identity token against Amazon Cognito User Pool.
     *
     * <p>Authentication credentials and user data are no longer stored in local files or
     * hardcoded in source code. Instead, user identity is managed entirely by Amazon Cognito:
     * <ul>
     *   <li>User accounts, passwords, and profile attributes live in the Cognito User Pool.</li>
     *   <li>The User Pool ID is externalised via the {@code COGNITO_USER_POOL_ID} environment
     *       variable (backed by AWS SSM Parameter Store key
     *       {@code /resortslite/cognito/user-pool-id}).</li>
     *   <li>Cognito handles password hashing, MFA, account lockout, and token rotation
     *       automatically — no custom credential storage is required in the application.</li>
     *   <li>The returned {@code AdminGetUserResponse} contains verified user attributes
     *       (email, name, custom:loyalty_tier) sourced directly from the Cognito User Pool,
     *       not from a local file or database table.</li>
     * </ul>
     *
     * @param username the Cognito username (typically the guest's email address)
     * @return a Map of verified user attributes from the Cognito User Pool,
     *         or an empty Map if the user does not exist
     */
    public Map<String, String> validateGuestIdentity(String username) {
        if (cognitoUserPoolId == null || cognitoUserPoolId.isEmpty()) {
            // Cognito not configured — return empty map (graceful degradation for local dev)
            return new HashMap<>();
        }
        try (CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(username)
                    .build();

            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);

            Map<String, String> userAttributes = new HashMap<>();
            userAttributes.put("username", getUserResponse.username());
            userAttributes.put("userStatus", getUserResponse.userStatusAsString());

            // Extract standard and custom Cognito user attributes
            for (AttributeType attribute : getUserResponse.userAttributes()) {
                userAttributes.put(attribute.name(), attribute.value());
            }
            return userAttributes;

        } catch (UserNotFoundException e) {
            // User does not exist in the Cognito User Pool
            return new HashMap<>();
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to validate guest identity via Amazon Cognito (userPool: "
                            + cognitoUserPoolId + ", username: " + username + "): "
                            + e.getMessage(), e);
        }
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // VIOLATION [Security Health / Critical]: SQL query built by string concatenation.
        // An attacker can pass guestName = "'; DROP TABLE bookings; --" to destroy data.
        // Use parameterised queries (JdbcTemplate with '?') to prevent SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('" // sql-inject-001
                + bookingId + "', '" + guestName + "', '" + roomType               // sql-inject-001
                + "', '" + checkIn + "', '" + checkOut + "')";                     // sql-inject-001
        jdbcTemplate.execute(sql);

        // VIOLATION [Security Health / High]: MD5 is a broken hash algorithm (RFC 6151).
        // Do not use MD5 for any security-related hashing. Use SHA-256 or bcrypt.
        String confirmCode = md5Hash(bookingId + guestName); // sec-weak-hash-001

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // DB_HOST hard-coded field removed; credentials are now fetched from Secrets Manager
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // VIOLATION [Security Health / Critical]: SQL injection via string concatenation.
        // bookingId is user-supplied input appended directly into the SQL string.
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'"; // sql-inject-001
        Map<String, Object> result = new HashMap<>();
        try {
            // cr-java-0090 FIX: Authentication for data access is now delegated to Amazon
            // Cognito. The caller must present a valid Cognito JWT token (validated upstream
            // by API Gateway or Spring Security + Cognito JWKS). No file-based user store
            // or hardcoded credentials are used to gate access to booking records.
            result = jdbcTemplate.queryForMap(sql);
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
        // VIOLATION [Code Sustainability / Medium]: Duplicated validation logic.
        // Same room type validation is repeated here and in calculateRoomPrice.
        // Should be extracted to a shared RoomType enum or validator.
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE") // dup-logic-001
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) { // dup-logic-001
            return false;
        }
        return true;
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
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
