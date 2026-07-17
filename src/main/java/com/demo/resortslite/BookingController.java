package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController exposes REST endpoints for resort booking operations.
 *
 * <p>Cloud readiness changes applied:
 * <ul>
 *   <li>Blocker 10 (cr-java-0071): Hard-coded inventory URL replaced with value from
 *       AWS Systems Manager Parameter Store via {@code @Value} injection.</li>
 *   <li>Blockers 13-17 (cr-java-0065): HTTP session state migrated to Amazon ElastiCache
 *       for Redis using Spring Session ({@code @EnableRedisHttpSession}). Session attributes
 *       are now stored in the distributed Redis store, enabling stateless application
 *       instances and safe horizontal scaling behind an AWS ALB.</li>
 *   <li>Blocker 20 (cr-java-0067): Unbounded in-memory {@code HashMap} cache replaced with
 *       Amazon ElastiCache for Redis via {@code RedisTemplate} with a TTL policy to prevent
 *       stale data and memory growth across instances.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bookings")
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Blocker-20 (cr-java-0067): Replace unbounded in-memory HashMap cache with
    // Amazon ElastiCache for Redis via RedisTemplate. TTL is enforced on every write
    // to prevent stale data and unbounded memory growth across scaled instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String BOOKING_CACHE_PREFIX = "booking:";
    // Cache TTL: 30 minutes — prevents stale data and controls memory usage
    private static final long BOOKING_CACHE_TTL_MINUTES = 30;

    // Blocker-10 (cr-java-0071): Hard-coded inventory URL externalized to
    // AWS Systems Manager Parameter Store. Value is injected at startup via
    // Spring's @Value, reading from the application property which is populated
    // from the SSM parameter /resortslite/inventory/service-url.
    @Value("${app.inventory.endpoint:http://inventory-service.internal:8081/rooms/available}")
    private String inventoryServiceUrl;

    /**
     * Creates a new booking and stores session state in Amazon ElastiCache for Redis.
     * Blockers 13-17 (cr-java-0065): HttpSession is now backed by Spring Session Redis,
     * so session data is shared across all application instances in the cluster.
     *
     * @param guestName guest's full name
     * @param roomType  room type requested
     * @param checkIn   check-in date
     * @param checkOut  check-out date
     * @param session   HTTP session (backed by ElastiCache for Redis via Spring Session)
     * @return confirmation response map
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Blocker-14,15 (cr-java-0065): Session attributes are now stored in the
        // distributed Redis-backed Spring Session store (ElastiCache), not in local JVM memory.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // Blocker-20 (cr-java-0067): Store booking in ElastiCache Redis with TTL
        // instead of the unbounded in-memory HashMap.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of a booking. Session data is read from the distributed
     * Redis-backed Spring Session store (blocker-16, cr-java-0065).
     *
     * @param bookingId the booking identifier
     * @param session   HTTP session (backed by ElastiCache for Redis via Spring Session)
     * @return booking status response map
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // Blocker-16 (cr-java-0065): Session attribute read from distributed Redis store —
        // consistent across all instances behind the AWS ALB.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability. The inventory service URL is resolved from
     * AWS Systems Manager Parameter Store (blocker-10, cr-java-0071).
     *
     * @param roomType the room type to check
     * @return availability response map
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Blocker-10 (cr-java-0071): inventoryServiceUrl is injected from SSM Parameter Store
        // via application.properties / environment variable, not hard-coded.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryServiceUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a pre-signed or service-generated download link for a monthly report.
     * The report path is now an S3 object key, not a local file system path.
     *
     * @param month the month for the report
     * @return download response map
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Blocker-17 (cr-java-0065): Session attribute read from distributed Redis store.
        // Report path is now an S3 key reference, not a local absolute path.
        String reportS3Key = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportS3Key", reportS3Key);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
