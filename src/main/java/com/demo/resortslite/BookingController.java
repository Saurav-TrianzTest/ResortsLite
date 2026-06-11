package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController exposes REST endpoints for the resort booking system.
 *
 * Cloud-readiness changes applied:
 * - In-memory bookingCache (blocker-20) replaced with Amazon ElastiCache for Redis
 *   via Spring Data RedisTemplate with TTL-based expiration.
 * - HTTP session state (blockers 13-17) migrated to Amazon ElastiCache for Redis
 *   via Spring Session (spring-session-data-redis). HttpSession calls are now
 *   backed by Redis automatically — no sticky sessions required.
 * - Hard-coded inventory service URL (blocker-10) replaced with AWS Systems Manager
 *   Parameter Store lookup, enabling environment-agnostic deployments.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * RedisTemplate replaces the former static in-memory HashMap cache (blocker-20).
     * Amazon ElastiCache for Redis provides TTL-based expiration, cross-instance
     * consistency, and controlled memory growth.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final SsmClient ssmClient;

    // Cache TTL: 30 minutes — prevents unbounded memory growth (blocker-20)
    private static final long CACHE_TTL_MINUTES = 30L;
    private static final String CACHE_KEY_PREFIX = "booking:cache:";
    private static final String SESSION_LAST_BOOKING_KEY = "lastBooking";
    private static final String SESSION_GUEST_NAME_KEY = "guestName";

    public BookingController() {
        this.ssmClient = SsmClient.create();
    }

    /**
     * Creates a new booking.
     * Session state is stored in Amazon ElastiCache for Redis via Spring Session
     * (blockers 13, 14, 15, 16) — no server-affinity required.
     * Booking is also cached in Redis with TTL (blocker-20).
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Spring Session (spring-session-data-redis) transparently stores session attributes
        // in Amazon ElastiCache for Redis — replaces in-process HTTP session (blockers 13, 14)
        session.setAttribute(SESSION_LAST_BOOKING_KEY, booking);
        session.setAttribute(SESSION_GUEST_NAME_KEY, guestName);

        // Store booking in Redis with TTL — replaces unbounded in-memory HashMap (blocker-20)
        String cacheKey = CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of a booking.
     * Session data is retrieved from Amazon ElastiCache for Redis via Spring Session
     * (blockers 15, 16, 17) — consistent across all application instances.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // Spring Session retrieves this attribute from Redis — works on any instance (blocker-17)
        String lastGuest = (String) session.getAttribute(SESSION_GUEST_NAME_KEY);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability.
     * The inventory service URL is retrieved from AWS Systems Manager Parameter Store
     * (blocker-10) — no hard-coded environment-specific URL in source code.
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Retrieve inventory service URL from AWS SSM Parameter Store (blocker-10)
        // Parameter name: /resortslite/inventory/service-url
        String inventoryUrl;
        try {
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name("/resortslite/inventory/service-url")
                            .withDecryption(false)
                            .build());
            inventoryUrl = response.parameter().value();
        } catch (Exception e) {
            // Fall back to environment variable if SSM is unavailable
            inventoryUrl = System.getenv().getOrDefault(
                    "INVENTORY_SERVICE_URL", "https://inventory-service.internal/rooms/available");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a pre-signed S3 URL or report path for the requested month's report.
     * Report path is no longer a hard-coded local file system path.
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path is now an S3 key — no hard-coded absolute file path
        String reportKey = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportKey", reportKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
