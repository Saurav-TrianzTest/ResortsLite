package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Fixed: cr-java-0067 - Replace in-memory caching with Amazon ElastiCache for Redis
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${CACHE_TTL:3600}")
    private long cacheTtl;

    private SsmClient ssmClient;

    public BookingController(@Value("${aws.region:us-east-1}") String region) {
        this.awsRegion = region;
        this.ssmClient = SsmClient.builder()
                .region(Region.of(region))
                .build();
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Fixed: cr-java-0065 - Replace HTTP session storage with Amazon ElastiCache for Redis
        // Spring Session automatically stores session data in Redis when configured
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // Fixed: cr-java-0067 - Store in Redis with TTL instead of unbounded in-memory cache
        String bookingId = (String) booking.get("bookingId");
        String cacheKey = "booking:" + bookingId;
        redisTemplate.opsForValue().set(cacheKey, booking, cacheTtl, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // Fixed: cr-java-0065 - Session data now stored in Redis, accessible across all instances
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Fixed: cr-java-0071 - Externalize environment URLs using AWS Systems Manager Parameter Store
        String inventoryUrl = getInventoryServiceUrl();

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Fixed: cr-java-0061, cr-java-0062, cr-java-0063 - Reports now stored in S3
        // The ReportService handles S3 storage, so we just need to reference the S3 location
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        response.put("storage", "Amazon S3");
        response.put("note", "Reports are stored in S3 bucket configured in application.properties");
        return response;
    }

    /**
     * Retrieves the inventory service URL from AWS Systems Manager Parameter Store.
     * Fixed: cr-java-0071 - Externalize environment URLs using Parameter Store
     * 
     * @return The inventory service URL
     */
    private String getInventoryServiceUrl() {
        try {
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name("/resorts/config/inventory-service-url")
                    .withDecryption(false)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(parameterRequest);
            return response.parameter().value();
        } catch (Exception e) {
            // Fallback to environment variable if Parameter Store is not available
            return System.getenv().getOrDefault("INVENTORY_ENDPOINT", 
                    "https://inventory-service.internal:8081/rooms/available");
        }
    }
}
