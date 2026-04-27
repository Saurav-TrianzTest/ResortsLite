package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private S3Service s3Service;

    // FIXED blocker-13 (cz-java-0070): Replaced local in-memory cache with Redis-backed distributed cache
    // Local cache removed - now using @Cacheable with Redis via CacheConfig

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED blockers 4, 5, 7, 8 (cz-java-0063, cz-java-0069): Session now backed by Redis via Spring Session
        // HttpSession is now distributed across containers via Amazon ElastiCache for Redis
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    @Cacheable(value = "bookingStatus", key = "#bookingId")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // FIXED blockers 6 (cz-java-0063): Session getAttribute now uses Redis-backed distributed session
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIXED blocker-9 (cz-java-0082): Externalized service endpoint to environment variable
        // Service discovery and communication now handled via AWS App Mesh/API Gateway
        String inventoryUrl = bookingService.getInventoryEndpoint();

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // FIXED blocker-1 (cz-java-0057): Replaced absolute file path with S3 object storage
        // Files now stored in Amazon S3 for cross-platform compatibility
        String s3Key = "reports/" + month + "_bookings.pdf";
        String reportMessage = bookingService.generateReport(month);

        Map<String, Object> response = new HashMap<>();
        response.put("s3Bucket", s3Service.getBucketName());
        response.put("s3Key", s3Key);
        response.put("message", reportMessage);
        return response;
    }
}
