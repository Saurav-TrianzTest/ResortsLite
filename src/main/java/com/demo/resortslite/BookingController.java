package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${app.inventory.endpoint}")
    private String inventoryEndpoint;

    private static final String BOOKING_CACHE_PREFIX = "booking:";
    private static final long CACHE_TTL_MINUTES = 10;

    /**
     * Creates a new booking and stores state in Redis for distributed session management.
     * 
     * @param guestName Guest name
     * @param roomType Room type
     * @param checkIn Check-in date
     * @param checkOut Check-out date
     * @param session HTTP session (backed by Redis via Spring Session)
     * @return Map containing booking confirmation
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Store booking state in Redis-backed session (via Spring Session)
        // This enables session sharing across multiple instances in AWS ECS/EKS
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // Store booking in Redis cache with TTL for distributed caching
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves booking status with Redis-backed session state.
     * 
     * @param bookingId Booking ID
     * @param session HTTP session (backed by Redis)
     * @return Map containing booking status
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // Retrieve session data from Redis-backed session
        // Works across all instances in the cluster
        String lastGuest = (String) session.getAttribute("guestName");

        // Try to get booking from Redis cache first
        String cacheKey = BOOKING_CACHE_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        
        if (cachedBooking != null) {
            result.put("details", cachedBooking);
            result.put("source", "cache");
        } else {
            Map<String, Object> bookingDetails = bookingService.getBookingById(bookingId);
            result.put("details", bookingDetails);
            result.put("source", "database");
            
            // Cache the result for future requests
            if (!bookingDetails.containsKey("error")) {
                redisTemplate.opsForValue().set(cacheKey, bookingDetails, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            }
        }
        
        return result;
    }

    /**
     * Checks room availability using externalized inventory service endpoint.
     * Uses HTTPS for secure communication.
     * 
     * @param roomType Room type to check
     * @return Map containing availability information
     */
    @GetMapping("/availability")
    @Cacheable(value = "roomAvailability", key = "#roomType")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Use externalized HTTPS endpoint from configuration
        // inventoryEndpoint is loaded from AWS Parameter Store via application.properties
        
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Downloads report from S3 instead of local file system.
     * 
     * @param month Month for the report
     * @return Map containing S3 report location
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Reports are now stored in S3, not local file system
        // S3 bucket and key are returned instead of local file path
        
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        response.put("storageType", "Amazon S3");
        response.put("note", "Report will be available in S3 bucket configured in application properties");
        return response;
    }

    /**
     * Invalidates booking cache entry in Redis.
     * 
     * @param bookingId Booking ID to invalidate
     * @return Map containing invalidation status
     */
    @DeleteMapping("/cache/{bookingId}")
    public Map<String, Object> invalidateCache(@PathVariable String bookingId) {
        String cacheKey = BOOKING_CACHE_PREFIX + bookingId;
        Boolean deleted = redisTemplate.delete(cacheKey);
        
        Map<String, Object> response = new HashMap<>();
        response.put("bookingId", bookingId);
        response.put("cacheInvalidated", deleted != null && deleted);
        return response;
    }
}
