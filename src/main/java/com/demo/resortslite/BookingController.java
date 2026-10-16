package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// cz-java-0069: HttpSession is now backed by Spring Session + Amazon ElastiCache (Redis).
// The @EnableRedisHttpSession annotation in RedisSessionConfig activates transparent
// Redis-backed session storage, so all session.setAttribute / session.getAttribute calls
// are automatically persisted to Redis instead of JVM heap memory.
// This eliminates the in-memory session state that breaks container restarts and
// horizontal scaling across multiple EKS pod replicas.

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    // Injected from environment variable REPORT_BASE_PATH (cz-java-0057)
    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    // cz-java-0070: Cache TTL injected from environment variable CACHE_TTL_SECONDS.
    // Defaults to 3600 seconds (1 hour). Override via Kubernetes ConfigMap.
    @Value("${CACHE_TTL_SECONDS:3600}")
    private long cacheTtlSeconds;

    @Autowired
    private BookingService bookingService;

    // cz-java-0070: Replaced local in-process HashMap cache with Amazon ElastiCache (Redis)
    // via RedisTemplate. The local cache was instance-local and invisible to other EKS pod
    // replicas, breaking horizontal scaling. RedisTemplate delegates all cache reads/writes
    // to the shared ElastiCache cluster, making cached data available across all replicas.
    // Connection details (REDIS_HOST, REDIS_PORT) are injected via Kubernetes ConfigMap/Secret.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069: Session attributes are now stored in Amazon ElastiCache (Redis)
        // via Spring Session. The HttpSession interface is unchanged; Spring Session
        // transparently delegates all setAttribute/getAttribute calls to Redis,
        // making session data available across all EKS pod replicas and surviving
        // container restarts. Configured via RedisSessionConfig + spring-session-data-redis.
        session.setAttribute("lastBooking", booking);  // cz-java-0069: Redis-backed via Spring Session
        session.setAttribute("guestName", guestName);  // cz-java-0069: Redis-backed via Spring Session

        // cz-java-0070: Store booking in Amazon ElastiCache (Redis) instead of local HashMap.
        // All EKS pod replicas share the same Redis cache, ensuring consistent cache hits
        // regardless of which pod handles the request. TTL is configurable via CACHE_TTL_SECONDS.
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, cacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cz-java-0069: Session attribute is now read from Amazon ElastiCache (Redis)
        // via Spring Session. Any EKS pod replica can serve this request because
        // session data is stored externally in Redis, not in local JVM memory.
        String lastGuest = (String) session.getAttribute("guestName");

        // cz-java-0070: Read booking details from Amazon ElastiCache (Redis) cache.
        // Falls back to BookingService if the entry has expired or is not yet cached.
        String cacheKey = "booking:" + bookingId;
        @SuppressWarnings("unchecked")
        Map<String, Object> cachedBooking = (Map<String, Object>) redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP call to
        // internal inventory service. AWS ALB, WAF, and Well-Architected security review
        // enforce HTTPS. This call will be blocked or flagged in a cloud-native setup.
        String inventoryUrl = "http://inventory-service.internal:8081/rooms/available"; // cr-java-0088

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Replaced hardcoded absolute file path with environment variable REPORT_BASE_PATH (cz-java-0057)
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
