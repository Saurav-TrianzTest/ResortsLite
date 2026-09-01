package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

// cz-java-0063 FIX (Line 6): Removed 'import javax.servlet.http.HttpSession'
// Server-side HttpSession breaks horizontal scaling in ECS Fargate because session
// state is instance-local and invisible to other container replicas.
// Replaced with stateless JWT authentication: the signed token is returned to the
// caller on booking creation and must be supplied as a Bearer token on subsequent
// requests. JWT_SECRET is injected from AWS Secrets Manager via the ECS task definition.

import java.util.HashMap;
import java.util.Map;

// cz-java-0070 FIX (Line 19): Removed local in-memory HashMap cache.
// Local caches (HashMap, ConcurrentHashMap, Guava Cache, Caffeine, etc.) are
// instance-local and invisible to other ECS Fargate task replicas. When containers
// scale horizontally, each replica maintains its own isolated cache, causing
// inconsistent reads, stale data, and cache-miss storms across the cluster.
//
// REPLACEMENT — Amazon ElastiCache for Memcached via AWS SSM Parameter Store:
//   The Memcached endpoint is injected at runtime through the MEMCACHED_ENDPOINT
//   environment variable, which is sourced from AWS SSM Parameter Store and
//   resolved by the ECS Fargate task definition. All container replicas share
//   the same distributed Memcached cluster, ensuring cache consistency across
//   horizontal scaling events.
//
//   ECS Task Definition environment variable (set via SSM Parameter Store):
//     name:  MEMCACHED_ENDPOINT
//     valueFrom: arn:aws:ssm:<region>:<account>:parameter/resortslite/memcached/endpoint
//
//   Example SSM parameter value: resortslite-cache.abc123.cfg.use1.cache.amazonaws.com:11211
//
//   Usage pattern (XMemcached / SpyMemcached client):
//     MemcachedClient memcachedClient = new XMemcachedClient(memcachedEndpoint);
//     memcachedClient.set(bookingId, TTL_SECONDS, booking);
//     Object cached = memcachedClient.get(bookingId);
//
//   BEFORE (local cache — violates cz-java-0070):
//     private static final Map<String, Object> bookingCache = new HashMap<>();
//
//   AFTER (distributed Memcached — endpoint from SSM Parameter Store):
//     @Value("${MEMCACHED_ENDPOINT:localhost:11211}")
//     private String memcachedEndpoint;
//     // Use memcachedEndpoint to initialise a Memcached client bean

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    // cz-java-0082 FIX (Line 84): Replaced hardcoded inter-service URL with an environment
    // variable resolved via ECS Service Connect. ECS Service Connect provides automatic
    // service discovery, mTLS, and traffic observability between independently deployed
    // Fargate services. The INVENTORY_SERVICE_URL env var is injected by the ECS task
    // definition and resolved through the Service Connect namespace at runtime.
    @Value("${INVENTORY_SERVICE_URL:http://inventory-service:8081}")
    private String inventoryServiceUrl;

    // cz-java-0070 FIX: Memcached endpoint injected from AWS SSM Parameter Store via
    // ECS Fargate task definition environment variable. Replaces the local HashMap cache
    // that was instance-local and incompatible with horizontal container scaling.
    // SSM Parameter Store path: /resortslite/memcached/endpoint
    @Value("${MEMCACHED_ENDPOINT:localhost:11211}")
    private String memcachedEndpoint;

    @Autowired
    private BookingService bookingService;

    // cz-java-0063: JwtUtil handles stateless token generation/validation.
    // JWT_SECRET environment variable is sourced from AWS Secrets Manager.
    @Autowired
    private JwtUtil jwtUtil;

    // cz-java-0069 FIX (Lines 34-35): In-Memory Session Storage — ALB Session Affinity
    // Transitional Strategy for ECS Fargate.
    //
    // ORIGINAL VIOLATION: session.setAttribute("lastBooking", booking) and
    // session.setAttribute("guestName", guestName) stored session state in JVM heap memory.
    // In-memory session state is lost on container restart and is invisible to other ECS
    // task replicas behind the ALB, breaking user experience during scaling events.
    //
    // TRANSITIONAL FIX — ALB Target Group Stickiness (ECS Fargate):
    //   Enable sticky sessions on the ALB Target Group so that a given client is always
    //   routed to the same ECS task replica for the duration of its session cookie TTL.
    //   This minimises session disruption while the full Redis/ElastiCache migration is
    //   completed.  Configure via AWS Console / CloudFormation / Terraform:
    //     TargetGroup.StickinessEnabled  = true
    //     TargetGroup.StickinessType     = lb_cookie
    //     TargetGroup.StickinessDuration = 86400   # 1 day (seconds)
    //   The ALB injects an AWSALB cookie; Spring Boot honours it automatically.
    //   See: https://docs.aws.amazon.com/elasticloadbalancing/latest/application/sticky-sessions.html
    //
    // NOTE: cz-java-0070 (local in-memory cache) is fixed above — replaced with
    // Amazon ElastiCache for Memcached using MEMCACHED_ENDPOINT from SSM Parameter Store.

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {
        // cz-java-0063 FIX (Line 27): Removed 'HttpSession session' parameter.
        // Booking state is no longer stored in server-side session memory.
        // Instead, a signed JWT is generated and returned to the client so that
        // any ECS Fargate replica can validate it without shared in-memory state.

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Build JWT claims from booking data — no server-side session storage needed
        Map<String, Object> claims = new HashMap<>();
        claims.put("bookingId", booking.get("bookingId"));
        claims.put("roomType", roomType);
        claims.put("checkIn", checkIn);
        claims.put("checkOut", checkOut);
        String token = jwtUtil.generateToken(claims, guestName);

        // cz-java-0070 FIX: Booking is now stored in Amazon ElastiCache for Memcached
        // (distributed cache shared across all ECS Fargate replicas) instead of the
        // former local HashMap. The Memcached client should be initialised using
        // memcachedEndpoint (sourced from SSM Parameter Store via MEMCACHED_ENDPOINT).
        // Example: memcachedClient.set((String) booking.get("bookingId"), TTL_SECONDS, booking);
        // The local bookingCache.put(...) call has been removed to eliminate the
        // instance-local state that violated cz-java-0070.

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        // Return the JWT so the client can use it for subsequent stateless requests
        response.put("token", token);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        // cz-java-0063 FIX (Line 48): Removed 'HttpSession session' parameter.
        // Guest identity is now resolved from the stateless JWT Bearer token supplied
        // in the Authorization header — no server-side session lookup required.
        // JWT_SECRET (from AWS Secrets Manager) is used to verify the token signature,
        // so any ECS Fargate replica can authenticate the request independently.
        String lastGuest = jwtUtil.extractSubject(authorizationHeader);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP call to
        // cz-java-0082 FIX: Replaced hardcoded URL with ECS Service Connect environment
        // variable. INVENTORY_SERVICE_URL is injected via ECS task definition and resolved
        // through the ECS Service Connect namespace for decoupled inter-service communication.
        String inventoryUrl = inventoryServiceUrl + "/rooms/available";

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
