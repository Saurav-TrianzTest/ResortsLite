package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIXED cr-java-0067: Replaced unbounded in-memory HashMap with Amazon ElastiCache for Redis
    // The static HashMap cache has been removed and replaced with Spring Cache annotations
    // that use Redis as the backing store. This provides:
    // - Time-to-live (TTL) policies to prevent indefinite memory growth
    // - Consistent cache data across multiple EC2 instances
    // - Prevention of out-of-memory errors
    // - Elimination of stale data inconsistencies in horizontally scaled environments
    // - Centralized cache management via ElastiCache
    // See RedisCacheConfig.java for cache configuration with 1-hour TTL

    /**
     * FIXED cr-java-0065: HTTP session storage now backed by Amazon ElastiCache for Redis.
     * 
     * With Spring Session Data Redis enabled (see RedisSessionConfig.java), all HttpSession
     * operations automatically store data in Redis instead of local memory. This enables:
     * - Stateless application instances (no server affinity required)
     * - Horizontal scaling across multiple EC2 instances
     * - Session persistence across instance restarts and auto-scaling events
     * - Load balancing without sticky sessions
     * 
     * The HttpSession API remains unchanged, but the underlying storage is now distributed.
     * Session data is automatically serialized to Redis and shared across all instances.
     * 
     * FIXED cr-java-0067: Booking cache now uses Redis with TTL via @CachePut annotation.
     * Cache entries automatically expire after 1 hour (configured in RedisCacheConfig.java).
     */
    @PostMapping("/create")
    @CachePut(value = "bookingCache", key = "#result['bookingId']")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED cr-java-0065: Session data now stored in Redis (via Spring Session)
        // These setAttribute calls automatically persist to ElastiCache for Redis
        // Data is accessible from any instance in the cluster
        session.setAttribute("lastBooking", booking); // Now Redis-backed
        session.setAttribute("guestName", guestName); // Now Redis-backed

        // FIXED cr-java-0067: @CachePut annotation automatically stores booking in Redis cache
        // with TTL of 1 hour. No manual HashMap manipulation needed.
        // The booking is cached using bookingId as the key and will expire automatically.

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * FIXED cr-java-0065: Session reads now retrieve data from Redis.
     * 
     * getAttribute calls automatically fetch data from ElastiCache for Redis,
     * ensuring consistent session state across all application instances.
     * 
     * FIXED cr-java-0067: Booking retrieval now uses Redis cache via @Cacheable annotation.
     * If the booking exists in cache and hasn't expired, it's returned from Redis.
     * Otherwise, the method executes and the result is cached with TTL.
     */
    @GetMapping("/status/{bookingId}")
    @Cacheable(value = "bookingCache", key = "#bookingId", unless = "#result == null")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // FIXED cr-java-0065: Reading from Redis-backed session (via Spring Session)
        // Returns consistent data regardless of which instance handles the request
        String lastGuest = (String) session.getAttribute("guestName"); // Now Redis-backed

        // FIXED cr-java-0067: @Cacheable annotation checks Redis cache first
        // If bookingId exists in cache and hasn't expired (TTL < 1 hour), returns cached data
        // Otherwise, fetches from database and caches the result in Redis
        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
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
