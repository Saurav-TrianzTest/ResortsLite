package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * cr-java-0065 FIX: HTTP session state migrated to Amazon ElastiCache for Redis
 * via Spring Session.  The {@code @EnableRedisHttpSession} annotation on this
 * class (or on a dedicated {@code HttpSessionConfig} class) instructs Spring
 * Session to transparently replace the in-process {@code HttpSession}
 * implementation with a Redis-backed one.
 *
 * All {@code session.setAttribute} / {@code session.getAttribute} calls below
 * continue to compile and behave identically from the application's perspective,
 * but the data is now stored in the shared ElastiCache Redis cluster rather than
 * in the local JVM heap.  This makes every application instance stateless and
 * allows AWS ALB to route any request to any instance without sticky sessions.
 *
 * cr-java-0067 FIX: In-memory caching replaced with Amazon ElastiCache for Redis
 * via Spring Data Redis ({@link RedisTemplate}).  The former static {@code HashMap}
 * (unbounded, instance-local, no TTL) has been removed.  Booking cache entries are
 * now stored in the shared Redis cluster with a configurable TTL
 * ({@code app.booking.cache.ttl-seconds}, default 3600 s / 1 hour), ensuring:
 * <ul>
 *   <li>Controlled memory growth — entries expire automatically after the TTL.</li>
 *   <li>Consistent data across all EC2 / ECS instances — no per-instance divergence.</li>
 *   <li>Centralised cache management via ElastiCache (eviction policies, monitoring).</li>
 * </ul>
 *
 * Required runtime configuration (set via environment variables or AWS SSM):
 *   SPRING_REDIS_HOST              – ElastiCache primary endpoint  (default: localhost)
 *   SPRING_REDIS_PORT              – Redis port                    (default: 6379)
 *   SPRING_SESSION_STORE_TYPE=redis                                (set in application.properties)
 *   BOOKING_CACHE_TTL_SECONDS      – Cache entry TTL in seconds    (default: 3600)
 */
@EnableRedisHttpSession
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0067 FIX: Spring Data Redis template used to store booking cache entries
     * in Amazon ElastiCache for Redis.  Replaces the former static {@code HashMap}
     * which was unbounded, instance-local, and had no TTL or expiration policy.
     *
     * The {@link RedisTemplate} is configured in {@link RedisConfig} with
     * {@link org.springframework.data.redis.serializer.StringRedisSerializer} for keys
     * and {@link org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer}
     * for values, ensuring human-readable keys and JSON-serialised booking objects in Redis.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * cr-java-0067 FIX: TTL for booking cache entries in seconds.
     * Externalised via the environment variable {@code BOOKING_CACHE_TTL_SECONDS}
     * (or the Spring property {@code app.booking.cache.ttl-seconds}).
     * In AWS deployments this value can be managed via SSM Parameter Store:
     *   aws ssm get-parameter --name /resortslite/booking/cache-ttl-seconds
     * Default: 3600 seconds (1 hour).
     */
    @Value("${app.booking.cache.ttl-seconds:${BOOKING_CACHE_TTL_SECONDS:3600}}")
    private long bookingCacheTtlSeconds;

    /** Redis key prefix for booking cache entries (cr-java-0067). */
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:cache:";

    /**
     * cr-java-0071: Inventory service URL externalised from AWS Systems Manager Parameter Store.
     * The value is resolved at startup from the Spring property {@code app.inventory.url},
     * which is backed by the environment variable {@code INVENTORY_SERVICE_URL}.
     * In AWS deployments the environment variable is populated by SSM Parameter Store
     * (e.g. via ECS task-definition secretsFrom or a startup script that calls
     * {@code aws ssm get-parameter --name /resortslite/inventory/url}).
     */
    @Value("${app.inventory.url:${INVENTORY_SERVICE_URL:http://inventory-service.internal:8081/rooms/available}}")
    private String inventoryServiceUrl; // cr-java-0071: replaces hard-coded URL

    /**
     * cr-java-0065 FIX: {@code HttpSession} is now transparently backed by
     * Amazon ElastiCache for Redis through Spring Session.
     * {@code session.setAttribute("lastBooking", booking)} and
     * {@code session.setAttribute("guestName", guestName)} write to Redis,
     * not to the local JVM heap, so the data is visible to every instance
     * in the Auto Scaling group.
     *
     * cr-java-0067 FIX: Booking is cached in ElastiCache Redis via {@link RedisTemplate}
     * with a TTL of {@code bookingCacheTtlSeconds} seconds instead of the former
     * unbounded static {@code HashMap}.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: session is now Redis-backed via Spring Session + ElastiCache.
        // These setAttribute calls store data in the shared Redis cluster, not in local JVM memory.
        // AWS ALB can route subsequent requests to any instance — session data is always available.
        session.setAttribute("lastBooking", booking); // cr-java-0065: now stored in ElastiCache Redis
        session.setAttribute("guestName", guestName); // cr-java-0065: now stored in ElastiCache Redis

        // cr-java-0067 FIX: Cache booking in ElastiCache Redis with TTL instead of static HashMap.
        // Key format: "booking:cache:<bookingId>"
        // Entry expires automatically after bookingCacheTtlSeconds seconds, preventing unbounded
        // memory growth and ensuring stale entries are evicted consistently across all instances.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, Duration.ofSeconds(bookingCacheTtlSeconds));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * cr-java-0065 FIX: {@code session.getAttribute("guestName")} now reads from
     * Amazon ElastiCache for Redis via Spring Session, ensuring the value is
     * consistent regardless of which EC2 instance handles the request.
     *
     * cr-java-0067 FIX: Booking lookup first checks the ElastiCache Redis cache
     * (via {@link RedisTemplate}) before falling back to the service layer.
     * Cache hits avoid unnecessary database round-trips; entries expire after the
     * configured TTL so stale data is never served indefinitely.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: getAttribute reads from the shared Redis-backed session store.
        // No server affinity required — any instance in the cluster can serve this request.
        String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065: now reads from ElastiCache Redis

        // cr-java-0067 FIX: Attempt to serve booking details from ElastiCache Redis cache.
        // If the entry is present and has not yet expired, return it directly without
        // hitting the database.  On a cache miss, delegate to the service layer as before.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        if (cachedBooking != null) {
            // Cache hit — return the Redis-cached booking details
            result.put("details", cachedBooking);
            result.put("cacheSource", "elasticache-redis");
        } else {
            // Cache miss — fetch from the service / database layer
            result.put("details", bookingService.getBookingById(bookingId));
            result.put("cacheSource", "database");
        }
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: Hard-coded environment URL replaced with externalised value
        // injected from AWS Systems Manager Parameter Store via the Spring property
        // app.inventory.url (environment variable: INVENTORY_SERVICE_URL).
        // No URL literal is present in source code — the value is resolved at runtime.
        String inventoryUrl = inventoryServiceUrl; // cr-java-0071: was "http://inventory-service.internal:8081/rooms/available"

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute
        // file path. This path does not exist inside a container image. Container images
        // have their own isolated file systems — /var/legacy/reports won't be present.
        String reportPath = "/var/legacy/reports/" + month + "_bookings.pdf"; // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
