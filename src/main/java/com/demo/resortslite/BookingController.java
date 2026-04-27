package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Cloud-ready booking controller with distributed session management and caching.
 * 
 * Fixes applied:
 * - cr-java-0065: Replaced HTTP session storage with Amazon ElastiCache for Redis
 * - cr-java-0067: Replaced in-memory caching with Amazon ElastiCache for Redis with TTL
 * - cr-java-0071: Externalized environment URLs using AWS Systems Manager Parameter Store
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${app.inventory.endpoint:http://inventory-svc:8081/rooms}")
    private String inventoryEndpoint;

    private SsmClient ssmClient;

    // Cache TTL in seconds (1 hour)
    private static final long CACHE_TTL_SECONDS = 3600;

    /**
     * Creates a new booking and stores session data in Redis for distributed access.
     * 
     * @param guestName Guest name
     * @param roomType Room type
     * @param checkIn Check-in date
     * @param checkOut Check-out date
     * @param sessionId Session identifier for distributed session management
     * @return Map containing booking confirmation
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false, defaultValue = "default") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Store session data in Redis instead of HTTP session
        // This enables stateless application instances with centralized session management
        String sessionKey = "session:" + sessionId;
        redisTemplate.opsForHash().put(sessionKey, "lastBooking", booking);
        redisTemplate.opsForHash().put(sessionKey, "guestName", guestName);
        redisTemplate.expire(sessionKey, 30, TimeUnit.MINUTES);

        // Store booking in Redis cache with TTL
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, CACHE_TTL_SECONDS, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves booking status with session data from Redis.
     * 
     * @param bookingId The booking ID to retrieve
     * @param sessionId Session identifier for distributed session management
     * @return Map containing booking status and details
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false, defaultValue = "default") String sessionId) {

        // Retrieve session data from Redis instead of HTTP session
        String sessionKey = "session:" + sessionId;
        String lastGuest = (String) redisTemplate.opsForHash().get(sessionKey, "guestName");

        // Try to get booking from Redis cache first
        String cacheKey = "booking:" + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);
        
        Map<String, Object> bookingDetails;
        if (cachedBooking != null) {
            bookingDetails = (Map<String, Object>) cachedBooking;
        } else {
            // Cache miss - retrieve from database and update cache
            bookingDetails = bookingService.getBookingById(bookingId);
            redisTemplate.opsForValue().set(cacheKey, bookingDetails, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingDetails);
        return result;
    }

    /**
     * Checks room availability using externalized inventory service URL.
     * 
     * @param roomType The room type to check
     * @return Map containing availability information
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Retrieve inventory service URL from AWS Parameter Store
        String inventoryUrl = getInventoryServiceUrl();

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Downloads a report using S3-based storage.
     * 
     * @param month The month for the report
     * @return Map containing report download information
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Reports are now stored in S3, not local file system
        Map<String, Object> reportInfo = bookingService.generateReport(month);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Report stored in S3");
        response.put("reportInfo", reportInfo);
        return response;
    }

    /**
     * Retrieves inventory service URL from AWS Systems Manager Parameter Store.
     * Falls back to environment variable if Parameter Store is not available.
     * 
     * @return The inventory service URL
     */
    private String getInventoryServiceUrl() {
        try {
            // Initialize SSM client if not already initialized
            if (ssmClient == null) {
                ssmClient = SsmClient.builder()
                        .region(Region.of(awsRegion))
                        .build();
            }

            // Retrieve inventory service URL from Parameter Store
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name("/resorts/inventory-service/url")
                    .withDecryption(false)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(parameterRequest);
            return response.parameter().value();
        } catch (Exception e) {
            // Fallback to environment variable if Parameter Store is not available
            return inventoryEndpoint;
        }
    }

    /**
     * Clears cached booking data from Redis.
     * 
     * @param bookingId The booking ID to clear from cache
     * @return Map containing operation status
     */
    @DeleteMapping("/cache/{bookingId}")
    public Map<String, Object> clearCache(@PathVariable String bookingId) {
        String cacheKey = "booking:" + bookingId;
        Boolean deleted = redisTemplate.delete(cacheKey);

        Map<String, Object> response = new HashMap<>();
        response.put("bookingId", bookingId);
        response.put("cacheCleared", deleted != null && deleted);
        return response;
    }
}
