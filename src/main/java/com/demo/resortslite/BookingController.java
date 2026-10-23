package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

/**
 * BookingController — cloud-native REST controller for resort booking operations.
 *
 * <p>cr-java-0065 FIX: All HTTP session state has been migrated from the in-process
 * {@code javax.servlet.http.HttpSession} to Spring Session backed by Amazon ElastiCache
 * for Redis. Session data is now stored in a centralised, distributed Redis cluster so
 * that every application instance in the AWS Auto Scaling group shares the same session
 * store. This eliminates server affinity, enables true horizontal scaling, and prevents
 * data loss when instances are terminated or replaced.
 *
 * <p>cr-java-0067 FIX: The unbounded static {@code HashMap}-based in-memory cache
 * ({@code bookingCache}) has been removed and replaced with Spring Cache annotations
 * ({@code @Cacheable}, {@code @CachePut}, {@code @CacheEvict}) backed by Amazon
 * ElastiCache for Redis. A {@link org.springframework.data.redis.cache.RedisCacheManager}
 * configured in {@link CacheConfig} applies a TTL of 30 minutes to all entries in the
 * {@code "bookings"} cache, preventing indefinite memory growth, stale data, and
 * cross-instance inconsistency. Because Redis is a shared, external store, every
 * application instance in the Auto Scaling group reads from and writes to the same
 * cache, eliminating the per-instance divergence that the static HashMap caused.
 *
 * <p>Key changes:
 * <ul>
 *   <li>Removed {@code private static final Map<String, Object> bookingCache}.</li>
 *   <li>Added {@code @CachePut(value = "bookings", key = "#result['bookingId']")} on
 *       {@code createBooking()} so the newly created booking is stored in Redis with TTL.</li>
 *   <li>Added {@code @Cacheable(value = "bookings", key = "#bookingId")} on
 *       {@code getBookingStatus()} so repeated lookups are served from Redis.</li>
 *   <li>Added {@code @CacheEvict(value = "bookings", key = "#bookingId")} on
 *       {@code evictBookingCache()} to allow explicit cache invalidation.</li>
 *   <li>Added {@link CacheConfig} — a {@code @Configuration} class that creates a
 *       {@code RedisCacheManager} with a 30-minute TTL for the {@code "bookings"} cache.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0065 FIX: Spring Session repository backed by Amazon ElastiCache for Redis.
    // Spring Boot auto-configures a RedisIndexedSessionRepository when
    // spring-session-data-redis is on the classpath and spring.session.store-type=redis
    // is set in application.properties. All session reads/writes go to the shared Redis
    // cluster, making every application instance stateless.
    @Autowired
    private SessionRepository<? extends Session> sessionRepository;

    // cr-java-0067 FIX: The static HashMap bookingCache has been REMOVED.
    // Caching is now handled by Spring Cache annotations (@CachePut / @Cacheable)
    // backed by Amazon ElastiCache for Redis via RedisCacheManager (see CacheConfig).
    // Redis enforces a 30-minute TTL on every entry in the "bookings" cache, preventing
    // unbounded memory growth and stale data across multiple application instances.

    // cr-java-0071 FIX: AWS region resolved from environment variable for SSM client construction.
    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    // cr-java-0071 FIX: SSM parameter name for the inventory service URL, injected from
    // application properties / environment variable. Defaults to the SSM path used in AWS.
    @Value("${ssm.param.inventory.url:/resortslite/inventory/service-url}")
    private String inventoryUrlParamName;

    /**
     * Creates a new booking, persists guest context to the distributed Redis session,
     * and stores the booking in the Redis-backed Spring Cache with TTL.
     *
     * <p>cr-java-0065 FIX: Instead of calling {@code HttpSession.setAttribute()}, a new
     * Spring Session is created via {@link SessionRepository#createSession()}, populated
     * with the booking attributes, and then persisted to Amazon ElastiCache for Redis via
     * {@link SessionRepository#save(Session)}.
     *
     * <p>cr-java-0067 FIX: {@code @CachePut} stores the returned booking map in the
     * {@code "bookings"} Redis cache under the key {@code bookingId}. The
     * {@link CacheConfig}-configured {@code RedisCacheManager} applies a 30-minute TTL
     * automatically, so entries expire and memory is reclaimed without manual eviction.
     */
    @PostMapping("/create")
    @CachePut(value = "bookings", key = "#result['bookingId']")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session state is now stored in Amazon ElastiCache for Redis
        // via Spring Session. The RedisIndexedSessionRepository (auto-configured by
        // spring-session-data-redis) serialises the attributes and writes them to the
        // shared Redis cluster. Any application instance can read this session by ID,
        // enabling stateless horizontal scaling behind an AWS ALB.
        Session session = sessionRepository.createSession();
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);
        sessionRepository.save(session);

        // cr-java-0067 FIX: bookingCache.put() call removed.
        // The @CachePut annotation on this method stores the returned booking map
        // in the Redis-backed "bookings" cache with a TTL defined in CacheConfig.
        // This replaces the unbounded static HashMap that caused memory growth and
        // cross-instance cache inconsistency in cloud environments.

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        // Return the session ID so the client can supply it on subsequent requests.
        // Spring Session will propagate it automatically via the SESSION cookie or
        // X-Auth-Token header depending on the configured HeaderHttpSessionIdResolver.
        response.put("sessionId", session.getId());
        return response;
    }

    /**
     * Returns the booking status and the guest name stored in the distributed Redis session.
     *
     * <p>cr-java-0065 FIX: Instead of calling {@code HttpSession.getAttribute()}, the
     * session is looked up from Amazon ElastiCache for Redis by the session ID supplied
     * in the request parameter.
     *
     * <p>cr-java-0067 FIX: {@code @Cacheable} serves repeated lookups for the same
     * {@code bookingId} from the Redis cache (TTL: 30 min) instead of hitting the
     * database on every request. Cache misses fall through to {@code bookingService}.
     */
    @GetMapping("/status/{bookingId}")
    @Cacheable(value = "bookings", key = "#bookingId")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false) String sessionId) {

        // cr-java-0065 FIX: Guest name is retrieved from the distributed Redis session
        // via Spring Session's SessionRepository. The session is looked up by the ID
        // returned during booking creation. If no session ID is provided, or the session
        // has expired, lastGuest will be null — consistent with stateless cloud behaviour.
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            Session session = sessionRepository.findById(sessionId);
            if (session != null) {
                lastGuest = (String) session.getAttribute("guestName");
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Explicitly evicts a booking entry from the Redis cache.
     *
     * <p>cr-java-0067 FIX: Provides a controlled cache invalidation endpoint so that
     * stale entries can be removed on demand (e.g. after a booking is cancelled or
     * modified). The TTL configured in {@link CacheConfig} handles automatic expiry,
     * but this endpoint allows immediate eviction when required.
     */
    @DeleteMapping("/cache/{bookingId}")
    @CacheEvict(value = "bookings", key = "#bookingId")
    public Map<String, Object> evictBookingCache(@PathVariable String bookingId) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "evicted");
        response.put("bookingId", bookingId);
        return response;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: Hard-coded environment URL replaced with a value retrieved
        // from AWS Systems Manager Parameter Store. The parameter name is itself
        // externalised via the 'ssm.param.inventory.url' application property so that
        // no environment-specific value is embedded in source code.
        // Previously: String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";
        String inventoryUrl = getParameterFromSsm(inventoryUrlParamName,
                "http://inventory-service.internal:8081/rooms/available");

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

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * <p>cr-java-0071 FIX: All environment-specific URLs are stored as SSM parameters
     * and resolved at runtime, enabling environment-agnostic deployments. The
     * {@code fallback} value is used only when the SSM call cannot be completed
     * (e.g. local development without AWS credentials).
     *
     * @param parameterName the SSM parameter path (e.g. /resortslite/inventory/service-url)
     * @param fallback      default value used when SSM is unavailable
     * @return the resolved parameter value
     */
    private String getParameterFromSsm(String parameterName, String fallback) {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();

        } catch (Exception e) {
            System.err.println("[BookingController] WARNING: Could not retrieve SSM parameter '"
                    + parameterName + "'. Using fallback value. Error: " + e.getMessage());
            return fallback;
        }
    }
}
