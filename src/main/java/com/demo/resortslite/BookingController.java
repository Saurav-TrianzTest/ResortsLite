package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cr-java-0065 remediation: HttpSession is now backed by Amazon ElastiCache for Redis
// via Spring Session (spring-session-data-redis). The standard HttpSession API is
// preserved, but session state is stored in a distributed Redis cluster instead of
// in-process JVM memory. This eliminates server affinity, enables horizontal scaling
// across multiple EC2 instances behind an AWS ALB, and ensures session data survives
// instance termination and auto-scaling events.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 remediation: Replaced unbounded in-memory HashMap cache with
    // Amazon ElastiCache for Redis via RedisTemplate. Each cache entry is stored
    // with a configurable TTL (default 30 minutes) to prevent indefinite memory
    // growth, ensure stale data expiration, and provide a single shared cache
    // visible to all EC2 instances in the Auto Scaling group.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * Cache key prefix for booking entries stored in Amazon ElastiCache for Redis.
     * Namespacing prevents key collisions with other data stored in the same Redis cluster.
     */
    private static final String BOOKING_CACHE_PREFIX = "booking:";

    /**
     * TTL for booking cache entries in Amazon ElastiCache for Redis.
     * Sourced from the {@code BOOKING_CACHE_TTL_SECONDS} environment variable.
     * Defaults to 1800 seconds (30 minutes) when the variable is not set.
     *
     * <p><strong>cr-java-0067 fix</strong>: Enforcing a TTL ensures cache entries
     * expire automatically, preventing indefinite memory growth and stale data
     * inconsistencies across multiple EC2 instances.</p>
     */
    private static final long BOOKING_CACHE_TTL_SECONDS;

    static {
        String ttlEnv = System.getenv("BOOKING_CACHE_TTL_SECONDS");
        BOOKING_CACHE_TTL_SECONDS = (ttlEnv != null && !ttlEnv.isEmpty())
                ? Long.parseLong(ttlEnv)
                : 1800L;
    }

    /**
     * AWS region sourced from the environment variable {@code AWS_REGION}.
     * Defaults to {@code us-east-1} when the variable is not set.
     */
    private static final String AWS_REGION =
            System.getenv("AWS_REGION") != null
                    ? System.getenv("AWS_REGION")
                    : "us-east-1";

    /**
     * AWS Systems Manager Parameter Store parameter name for the inventory service URL.
     * The parameter name is read from the environment variable
     * {@code SSM_INVENTORY_URL_PARAM} so it can be configured per environment without
     * code changes (12-factor app principle III).
     *
     * <p><strong>cr-java-0071 fix</strong>: Replaces the hard-coded URL
     * {@code "http://inventory-service.internal:8081/rooms/available"} (original line 66)
     * with a reference to an AWS SSM Parameter Store parameter, enabling environment-agnostic
     * deployments.</p>
     */
    // cr-java-0071 [original line 66]: hard-coded inventory URL → SSM Parameter Store lookup
    private static final String INVENTORY_URL_PARAM =
            System.getenv("SSM_INVENTORY_URL_PARAM") != null
                    ? System.getenv("SSM_INVENTORY_URL_PARAM")
                    : "/resortslite/inventory/service-url";

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * <p><strong>cr-java-0071 remediation</strong>: All hard-coded environment-specific URLs
     * are replaced with values fetched from AWS SSM Parameter Store, enabling
     * environment-agnostic deployments across dev, staging, and production without
     * code changes.</p>
     *
     * @param parameterName the SSM parameter name (e.g. "/resortslite/inventory/service-url")
     * @return the parameter value stored in SSM Parameter Store
     */
    private String getSsmParameter(String parameterName) {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(AWS_REGION))
                .build()) {

            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve SSM parameter '" + parameterName + "': " + e.getMessage(), e);
        }
    }

    /**
     * Stores a booking entry in Amazon ElastiCache for Redis with a TTL.
     *
     * <p><strong>cr-java-0067 remediation</strong>: Replaces the unbounded in-memory
     * {@code HashMap} put operation with a Redis {@code opsForValue().set()} call that
     * includes a TTL. This ensures cache entries expire automatically, preventing
     * indefinite memory growth and stale data inconsistencies across EC2 instances.</p>
     *
     * @param bookingId the booking identifier used as the cache key
     * @param booking   the booking data map to cache
     */
    private void cacheBooking(String bookingId, Map<String, Object> booking) {
        String cacheKey = BOOKING_CACHE_PREFIX + bookingId;
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Retrieves a booking entry from Amazon ElastiCache for Redis.
     *
     * <p><strong>cr-java-0067 remediation</strong>: Replaces the unbounded in-memory
     * {@code HashMap} get operation with a Redis {@code opsForValue().get()} call.
     * The value is served from the shared Redis cluster, making it available to all
     * EC2 instances in the Auto Scaling group.</p>
     *
     * @param bookingId the booking identifier used as the cache key
     * @return the cached booking data map, or {@code null} if not found or expired
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getCachedBooking(String bookingId) {
        String cacheKey = BOOKING_CACHE_PREFIX + bookingId;
        return (Map<String, Object>) redisTemplate.opsForValue().get(cacheKey);
    }

    /**
     * Creates a new booking and stores session state in Amazon ElastiCache for Redis
     * via Spring Session (cr-java-0065 remediation).
     *
     * <p>The {@link HttpSession} parameter is transparently backed by Redis through
     * Spring Session's {@code RedisIndexedHttpSessionRepository}. Session attributes
     * written here are replicated to the ElastiCache Redis cluster, making them
     * available to any EC2 instance in the Auto Scaling group that handles subsequent
     * requests — eliminating server affinity and enabling true horizontal scaling.</p>
     *
     * <p>Redis connection details are supplied via environment variables
     * {@code REDIS_HOST} and {@code REDIS_PORT} (see {@code application.properties}
     * and {@code SessionConfig}). In production these point to the ElastiCache
     * primary endpoint.</p>
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cr-java-0065 remediation: HttpSession is now a Spring Session proxy backed
            // by Amazon ElastiCache for Redis. Session data is stored in the distributed
            // Redis cluster, not in local JVM memory, enabling stateless application
            // instances and safe horizontal scaling behind AWS ALB.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 remediation: session.setAttribute calls now persist data to
        // Amazon ElastiCache for Redis via Spring Session's RedisIndexedHttpSessionRepository.
        // The session is distributed and accessible from any EC2 instance in the cluster,
        // resolving the server-affinity issue caused by in-process HTTP session storage.
        session.setAttribute("lastBooking", booking); // cr-java-0065 remediated — stored in Redis
        session.setAttribute("guestName", guestName); // cr-java-0065 remediated — stored in Redis

        // cr-java-0067 remediation: Replaced bookingCache.put() on the unbounded in-memory
        // HashMap with a Redis-backed cache entry that includes a TTL. The booking is now
        // stored in Amazon ElastiCache for Redis, shared across all EC2 instances, and
        // automatically expired after BOOKING_CACHE_TTL_SECONDS seconds.
        cacheBooking((String) booking.get("bookingId"), booking); // cr-java-0067 remediated — stored in Redis with TTL

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the booking status and the last guest name retrieved from the distributed
     * Redis-backed session (cr-java-0065 remediation).
     *
     * <p>The {@link HttpSession} is backed by Amazon ElastiCache for Redis via Spring
     * Session. {@code session.getAttribute("guestName")} fetches the value from the
     * shared Redis cluster, so any EC2 instance handling this request will see the
     * same session data regardless of which instance processed the original
     * {@code /create} request.</p>
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // cr-java-0065 remediation: HttpSession is Spring Session proxy backed by
            // Amazon ElastiCache for Redis — session reads are served from the distributed
            // Redis cluster, not from instance-local memory.
            HttpSession session) {

        // cr-java-0065 remediation: session.getAttribute now reads from Amazon ElastiCache
        // for Redis via Spring Session. The value is available on every EC2 instance in
        // the cluster, eliminating the null-on-other-instance problem caused by in-process
        // HTTP session storage.
        String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065 remediated — read from Redis

        // cr-java-0067 remediation: Attempt to serve booking details from the Redis cache
        // before falling back to the service layer. The cached entry is shared across all
        // EC2 instances and expires automatically after BOOKING_CACHE_TTL_SECONDS seconds.
        Map<String, Object> cachedBooking = getCachedBooking(bookingId); // cr-java-0067 remediated — read from Redis

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 fix [original line 66]: Replaced hard-coded URL
        // "http://inventory-service.internal:8081/rooms/available" with a value retrieved
        // from AWS Systems Manager Parameter Store. The parameter name is stored in
        // INVENTORY_URL_PARAM (sourced from env var SSM_INVENTORY_URL_PARAM), enabling
        // environment-agnostic deployments without code changes per environment.
        String inventoryUrl = getSsmParameter(INVENTORY_URL_PARAM); // cr-java-0071 remediated

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
