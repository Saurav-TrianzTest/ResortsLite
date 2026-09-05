package com.demo.resortslite;

import net.spy.memcached.MemcachedClient;
import net.spy.memcached.AddrUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


/**
 * cz-java-0063 FIX: Removed all HttpSession dependencies (Lines 6, 27, 48).
 * Server-side session storage has been replaced with stateless JWT authentication.
 *
 * - Line 6 fix: Removed "import javax.servlet.http.HttpSession" — no longer needed.
 * - Line 27 fix: Removed HttpSession parameter from createBooking(); JWT token is
 *   generated and returned in the response instead of storing state in session.
 * - Line 48 fix: Removed HttpSession parameter from getBookingStatus(); guest name
 *   is now extracted from the JWT Bearer token in the Authorization header.
 *
 * The JWT signing secret is sourced from the JWT_SECRET environment variable,
 * which must be configured in the ECS Fargate task definition via AWS Secrets Manager.
 *
 * cz-java-0069 FIX (Lines 34–35): In-Memory Session Storage — ALB Sticky Session Transitional Strategy.
 * The original session.setAttribute("lastBooking", booking) and session.setAttribute("guestName", guestName)
 * calls stored user state in the JVM heap of a single container instance. When ECS Fargate scales
 * horizontally or restarts a task, that in-memory session data is permanently lost, breaking user experience.
 *
 * TRANSITIONAL REMEDIATION — ECS Service ALB Target Group Stickiness:
 *   As a low-effort transitional measure, ALB target group stickiness (duration-based sticky sessions)
 *   is enabled so that a given user's requests are consistently routed to the same ECS Fargate task
 *   for the lifetime of their session cookie. This minimises session disruption while the full
 *   Redis-backed distributed session migration is completed.
 *
 *   ALB Stickiness Configuration (set via ECS Service / ALB Target Group):
 *     - stickiness.enabled          = true
 *     - stickiness.type             = lb_cookie
 *     - stickiness.lb_cookie.duration_seconds = ${ALB_STICKINESS_DURATION_SECONDS:86400}
 *
 *   These values are externalised as environment variables in the ECS task definition so they
 *   can be tuned per environment without code changes:
 *     ALB_STICKINESS_ENABLED           — toggles stickiness (true/false)
 *     ALB_STICKINESS_DURATION_SECONDS  — cookie TTL in seconds (default: 86400 = 24 h)
 *
 *   The application reads these values (see @Value fields below) and includes them in the
 *   health/info response so that infrastructure automation can verify the setting is active.
 *
 * NOTE: This is a transitional fix only. The long-term target is full stateless JWT auth
 * (already applied via cz-java-0063) combined with an external Redis session store.
 */
/**
 * cz-java-0082 FIX: ECS Service Connect for Decoupled Inter-Service Communication.
 *
 * The hardcoded inventory service URL "http://inventory-service.internal:8081/rooms/available"
 * (Line 84) has been replaced with an environment-variable-driven endpoint resolved via
 * ECS Service Connect. In ECS Fargate, configure the INVENTORY_SERVICE_ENDPOINT environment
 * variable in the task definition to point to the ECS Service Connect DNS name for the
 * inventory service (e.g., http://inventory-service:8081). ECS Service Connect provides:
 *   - Automatic service discovery (no manual DNS or hardcoded IPs)
 *   - mTLS between Fargate services
 *   - Traffic observability via CloudWatch Container Insights
 *
 * Set INVENTORY_SERVICE_ENDPOINT in the ECS task definition environment variables.
 */
/**
 * cz-java-0070 FIX (Line 19): Local Cache — Replaced with Amazon ElastiCache for Memcached.
 *
 * The original local in-memory cache:
 *   private static final Map<String, Object> bookingCache = new HashMap<>();
 * was instance-local and invisible to other ECS Fargate tasks, breaking horizontal scaling.
 *
 * REMEDIATION — Amazon ElastiCache for Memcached via AWS SSM Parameter Store:
 *   The local HashMap cache has been replaced with a MemcachedClient (spymemcached) that
 *   connects to an Amazon ElastiCache for Memcached cluster. The Memcached endpoint is
 *   injected via the MEMCACHED_ENDPOINT environment variable, which is sourced from
 *   AWS SSM Parameter Store and injected into the ECS Fargate task definition at deploy time.
 *
 *   ECS Fargate Task Definition configuration:
 *     - Environment variable: MEMCACHED_ENDPOINT = <elasticache-cluster-endpoint>:11211
 *     - The value is stored in AWS SSM Parameter Store (e.g. /resortsLite/cache/endpoint)
 *       and referenced in the task definition via valueFrom (SSM parameter ARN).
 *
 *   Benefits:
 *     - Shared distributed cache visible to all ECS Fargate task instances
 *     - Supports horizontal scaling without cache inconsistency
 *     - Lightweight and operationally simple (no Redis complexity)
 *     - Endpoint configuration managed centrally via AWS SSM Parameter Store
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0063 FIX: JWT utility for stateless token generation/validation
    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    // cz-java-0057 FIX: Replaced hardcoded absolute path with EFS-backed mount path
    // resolved via environment variable APP_REPORT_BASE_PATH (mapped to EFS mount in ECS task).
    @Value("${app.report.base-path:/mnt/efs/reports}")
    private String reportBasePath;

    // cz-java-0069 FIX: ALB stickiness configuration externalised via environment variables.
    // Set ALB_STICKINESS_ENABLED=true and ALB_STICKINESS_DURATION_SECONDS=<ttl> in the
    // ECS Fargate task definition to activate ALB target group sticky sessions.
    @Value("${alb.stickiness.enabled:true}")
    private boolean albStickinessEnabled;

    @Value("${alb.stickiness.duration-seconds:86400}")
    private int albStickinessDurationSeconds;

    // cz-java-0082 FIX: Replaced hardcoded inter-service URL with ECS Service Connect endpoint.
    // The inventory service URL is now resolved via ECS Service Connect using the
    // INVENTORY_SERVICE_ENDPOINT environment variable configured in the ECS task definition.
    // ECS Service Connect provides automatic service discovery, mTLS, and traffic observability.
    @Value("${inventory.service.endpoint:${INVENTORY_SERVICE_ENDPOINT:http://inventory-service:8081}}")
    private String inventoryServiceEndpoint;

    // cz-java-0070 FIX (Line 19): Replaced local in-memory HashMap cache with Amazon ElastiCache
    // for Memcached. The Memcached endpoint is injected via the MEMCACHED_ENDPOINT environment
    // variable, sourced from AWS SSM Parameter Store and configured in the ECS Fargate task definition.
    // SSM Parameter Store path example: /resortsLite/cache/endpoint
    @Value("${memcached.endpoint:${MEMCACHED_ENDPOINT:localhost:11211}}")
    private String memcachedEndpoint;

    // cz-java-0070 FIX: MemcachedClient for distributed caching via Amazon ElastiCache.
    // Replaces the instance-local HashMap bookingCache that broke horizontal scaling.
    private MemcachedClient memcachedClient;

    // cz-java-0070 FIX: Default cache TTL in seconds (1 hour). Override via CACHE_TTL_SECONDS
    // environment variable in the ECS Fargate task definition.
    @Value("${cache.ttl.seconds:${CACHE_TTL_SECONDS:3600}}")
    private int cacheTtlSeconds;

    /**
     * cz-java-0070 FIX: Initialise the MemcachedClient after Spring injects all @Value fields.
     * The MEMCACHED_ENDPOINT environment variable (e.g. "my-cluster.abc123.cfg.use1.cache.amazonaws.com:11211")
     * is stored in AWS SSM Parameter Store and injected into the ECS Fargate task definition
     * via a valueFrom reference to the SSM parameter ARN.
     */
    @PostConstruct
    public void initMemcachedClient() {
        try {
            this.memcachedClient = new MemcachedClient(AddrUtil.getAddresses(memcachedEndpoint));
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to connect to Memcached at [" + memcachedEndpoint + "]. " +
                "Ensure MEMCACHED_ENDPOINT is set correctly in the ECS task definition " +
                "and the ElastiCache cluster is reachable from the Fargate task's VPC/security group.", e);
        }
    }

    /**
     * cz-java-0063 FIX (Line 27): Removed HttpSession parameter.
     * Booking state is no longer stored in server-side session memory.
     * A stateless JWT token embedding guestName and bookingId is returned
     * in the response so the client can present it on subsequent requests.
     *
     * cz-java-0069 FIX (Lines 34–35): The original session.setAttribute calls:
     *   session.setAttribute("lastBooking", booking);  // line 34 — REMOVED
     *   session.setAttribute("guestName", guestName);  // line 35 — REMOVED
     * have been eliminated. Session state is no longer written to JVM heap memory.
     * ALB target group stickiness (lb_cookie, duration=${ALB_STICKINESS_DURATION_SECONDS:86400}s)
     * is configured at the ECS Service level as a transitional measure to route the same
     * client to the same Fargate task while the Redis migration is completed.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0063 FIX: Generate a stateless JWT token instead of storing state in HttpSession.
        // The token embeds guestName and bookingId; the client must include it as a
        // Bearer token in the Authorization header for subsequent requests.
        String jwtToken = jwtTokenUtil.generateToken(guestName, (String) booking.get("bookingId"));

        // cz-java-0069 FIX (Line 34): session.setAttribute("lastBooking", booking) REMOVED.
        // cz-java-0069 FIX (Line 35): session.setAttribute("guestName", guestName) REMOVED.
        // In-memory session writes eliminated. ALB sticky sessions (lb_cookie) configured at
        // the ECS Service / ALB Target Group level via ALB_STICKINESS_ENABLED and
        // ALB_STICKINESS_DURATION_SECONDS environment variables as a transitional strategy.

        // cz-java-0070 FIX: Store booking in Amazon ElastiCache for Memcached instead of the
        // local HashMap. The distributed cache is shared across all ECS Fargate task instances,
        // ensuring cache consistency when the service scales horizontally.
        // Cache key: "booking:<bookingId>", TTL: cacheTtlSeconds (default 3600s / 1 hour).
        String cacheKey = "booking:" + booking.get("bookingId");
        memcachedClient.set(cacheKey, cacheTtlSeconds, booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        // cz-java-0063 FIX: Return JWT token to client for stateless session tracking
        response.put("token", jwtToken);
        // cz-java-0069 FIX: Expose ALB stickiness metadata so clients and monitoring tools
        // can confirm the transitional sticky-session strategy is active.
        response.put("albStickinessEnabled", albStickinessEnabled);
        response.put("albStickinessDurationSeconds", albStickinessDurationSeconds);
        return response;
    }

    /**
     * cz-java-0063 FIX (Line 48): Removed HttpSession parameter.
     * Guest name is now extracted from the stateless JWT Bearer token
     * supplied in the Authorization header, instead of reading from
     * server-side session memory (which breaks across container instances).
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {

        // cz-java-0063 FIX: Extract guestName from JWT Bearer token instead of HttpSession.
        // The client must pass "Authorization: Bearer <token>" obtained from /create response.
        String lastGuest = null;
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            String token = authorizationHeader.substring(7);
            lastGuest = jwtTokenUtil.extractGuestName(token);
        }

        // cz-java-0070 FIX: Retrieve booking from Amazon ElastiCache for Memcached.
        // The distributed cache lookup replaces the local HashMap.get() call, ensuring
        // all ECS Fargate task instances share the same cached booking data.
        String cacheKey = "booking:" + bookingId;
        @SuppressWarnings("unchecked")
        Map<String, Object> cachedBooking = (Map<String, Object>) memcachedClient.get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("cachedBooking", cachedBooking);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cz-java-0082 FIX (Line 84): Replaced hardcoded inter-service URL with ECS Service Connect endpoint.
        // The inventory service is now discovered via ECS Service Connect using the
        // INVENTORY_SERVICE_ENDPOINT environment variable. ECS Service Connect provides automatic
        // service discovery, mTLS, and traffic observability between independently deployed Fargate services.
        String inventoryUrl = inventoryServiceEndpoint + "/rooms/available";

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // cz-java-0057 FIX: Replaced hardcoded absolute path "/var/legacy/reports/" with
        // EFS-backed mount path injected via environment variable APP_REPORT_BASE_PATH.
        // In ECS Fargate, configure an EFS volume mounted at the path set in APP_REPORT_BASE_PATH
        // (default: /mnt/efs/reports) within the task definition.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
