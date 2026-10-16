package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

// cr-java-0090 remediation: Amazon Cognito imports for cloud-native user identity management.
// Cognito replaces any file-based or hardcoded credential storage for user authentication.
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
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

    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    // AWS Secrets Manager secret name for database credentials (cr-java-0069 remediation).
    // The secret name is read from the environment variable DB_SECRET_NAME so it can be
    // configured per environment without code changes (12-factor app principle III).
    private static final String DB_SECRET_NAME =
            System.getenv("DB_SECRET_NAME") != null
                    ? System.getenv("DB_SECRET_NAME")
                    : "resortslite/db/credentials";

    // AWS region is read from the environment variable AWS_REGION (standard AWS convention).
    private static final String AWS_REGION =
            System.getenv("AWS_REGION") != null
                    ? System.getenv("AWS_REGION")
                    : "us-east-1";

    // cr-java-0090 remediation: Amazon Cognito User Pool configuration.
    // These values are read from environment variables so they can be configured per
    // environment without code changes (12-factor app principle III).
    // Set COGNITO_USER_POOL_ID and COGNITO_APP_CLIENT_ID in your ECS task definition,
    // EKS pod spec, or Elastic Beanstalk environment properties.
    private static final String COGNITO_USER_POOL_ID =
            System.getenv("COGNITO_USER_POOL_ID") != null
                    ? System.getenv("COGNITO_USER_POOL_ID")
                    : "us-east-1_defaultPoolId";

    private static final String COGNITO_APP_CLIENT_ID =
            System.getenv("COGNITO_APP_CLIENT_ID") != null
                    ? System.getenv("COGNITO_APP_CLIENT_ID")
                    : "defaultAppClientId";

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * The secret is expected to be stored as a JSON string with keys "username" and "password".
     * Example secret value: {"username":"admin","password":"Resort$Pass#2019!"}
     *
     * @return a Map containing "username" and "password" keys
     */
    private Map<String, String> getDbCredentialsFromSecretsManager() {
        Map<String, String> credentials = new HashMap<>();
        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.of(AWS_REGION))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(DB_SECRET_NAME)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretString = response.secretString();

            // Parse the JSON secret value to extract username and password
            ObjectMapper mapper = new ObjectMapper();
            JsonNode secretJson = mapper.readTree(secretString);
            credentials.put("username", secretJson.get("username").asText());
            credentials.put("password", secretJson.get("password").asText());

            client.close();
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve database credentials from AWS Secrets Manager (secret: "
                            + DB_SECRET_NAME + "): " + e.getMessage(), e);
        }
        return credentials;
    }

    /**
     * cr-java-0090 remediation: Authenticates a user via Amazon Cognito User Pool.
     *
     * <p>Replaces any file-based or hardcoded credential storage for user authentication.
     * Credentials are never stored locally — authentication is delegated entirely to
     * Amazon Cognito, which provides centralized, encrypted, and auditable identity
     * management with built-in user lifecycle management.</p>
     *
     * <p>The Cognito User Pool ID and App Client ID are read from environment variables
     * (COGNITO_USER_POOL_ID, COGNITO_APP_CLIENT_ID) so they can be configured per
     * environment without code changes.</p>
     *
     * @param username the Cognito username (typically an email address)
     * @param password the user's password (never stored locally)
     * @return a Map containing the Cognito authentication tokens (IdToken, AccessToken, RefreshToken)
     * @throws RuntimeException if authentication fails or Cognito is unreachable
     */
    public Map<String, String> authenticateUser(String username, String password) {
        Map<String, String> authTokens = new HashMap<>();
        try {
            CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                    .region(Region.of(AWS_REGION))
                    .build();

            // Build the auth parameters for ADMIN_USER_PASSWORD_AUTH flow.
            // This flow requires the Cognito User Pool to have ALLOW_ADMIN_USER_PASSWORD_AUTH enabled.
            Map<String, String> authParams = new HashMap<>();
            authParams.put("USERNAME", username);
            authParams.put("PASSWORD", password);

            AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
                    .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                    .clientId(COGNITO_APP_CLIENT_ID)
                    .userPoolId(COGNITO_USER_POOL_ID)
                    .authParameters(authParams)
                    .build();

            AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);

            // Extract tokens from the Cognito authentication result.
            // These tokens are returned to the caller and must NOT be stored in local files.
            authTokens.put("idToken", authResponse.authenticationResult().idToken());
            authTokens.put("accessToken", authResponse.authenticationResult().accessToken());
            authTokens.put("refreshToken", authResponse.authenticationResult().refreshToken());
            authTokens.put("tokenType", authResponse.authenticationResult().tokenType());

            cognitoClient.close();
        } catch (NotAuthorizedException e) {
            throw new RuntimeException(
                    "Authentication failed for user '" + username + "': Invalid credentials.", e);
        } catch (UserNotFoundException e) {
            throw new RuntimeException(
                    "Authentication failed: User '" + username + "' not found in Cognito User Pool.", e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to authenticate via Amazon Cognito (UserPool: "
                            + COGNITO_USER_POOL_ID + "): " + e.getMessage(), e);
        }
        return authTokens;
    }

    /**
     * cr-java-0090 remediation: Retrieves user profile data from Amazon Cognito User Pool.
     *
     * <p>User data is managed centrally in Cognito rather than in local files,
     * enabling consistent user lifecycle management across all application instances.</p>
     *
     * @param username the Cognito username to look up
     * @return a Map containing the user's Cognito attributes
     */
    public Map<String, String> getUserFromCognito(String username) {
        Map<String, String> userAttributes = new HashMap<>();
        try {
            CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                    .region(Region.of(AWS_REGION))
                    .build();

            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(COGNITO_USER_POOL_ID)
                    .username(username)
                    .build();

            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);

            // Map Cognito user attributes to a simple key-value structure
            getUserResponse.userAttributes().forEach(attr ->
                    userAttributes.put(attr.name(), attr.value()));
            userAttributes.put("userStatus", getUserResponse.userStatusAsString());

            cognitoClient.close();
        } catch (UserNotFoundException e) {
            throw new RuntimeException(
                    "User '" + username + "' not found in Cognito User Pool: "
                            + COGNITO_USER_POOL_ID, e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve user from Amazon Cognito (UserPool: "
                            + COGNITO_USER_POOL_ID + "): " + e.getMessage(), e);
        }
        return userAttributes;
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
        booking.put("dbHost", DB_HOST);
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // VIOLATION [Security Health / Critical]: SQL injection via string concatenation.
        // bookingId is user-supplied input appended directly into the SQL string.
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'"; // sql-inject-001
        Map<String, Object> result = new HashMap<>();
        try {
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
