package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.redis.core.RedisTemplate;

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
    private S3Service s3Service;

    // FIXED blocker-13 (cz-java-0070): Replaced local cache with Redis distributed cache
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

        // FIXED blocker-7 & blocker-8 (cz-java-0069): Session data now stored in Redis via Spring Session
        // Spring Session automatically handles Redis storage when configured
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // FIXED blocker-13 (cz-java-0070): Using Redis distributed cache instead of local HashMap
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, 24, TimeUnit.HOURS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // FIXED blocker-5 & blocker-6 (cz-java-0063): Session now backed by Redis via Spring Session
        // Session data persists across container restarts and scales horizontally
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIXED blocker-9 (cz-java-0082): Externalized service endpoint to support microservices architecture
        String inventoryUrl = System.getenv().getOrDefault("INVENTORY_ENDPOINT", "http://inventory-service.internal:8081/rooms/available");

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // FIXED blocker-1 (cz-java-0057): Replaced absolute file path with S3 object storage
        String fileName = month + "_bookings.pdf";
        String s3Key = s3Service.generateS3Key(fileName);
        String reportPath = "s3://" + System.getenv().getOrDefault("S3_BUCKET_NAME", "resortslite-reports") + "/" + s3Key;

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
