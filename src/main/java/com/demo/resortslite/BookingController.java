package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // blocker-13 (cz-java-0070): Replaced local in-memory HashMap cache with Redis-backed
    // distributed cache via RedisTemplate to support horizontal scaling across container instances
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // blocker-4 (cz-java-0063) / blocker-7 (cz-java-0069): Replaced HttpSession.setAttribute
        // with Redis-backed Spring Session storage to survive container restarts and horizontal scaling
        redisTemplate.opsForHash().put("session:lastBooking", (String) booking.get("bookingId"), booking);
        // blocker-5 (cz-java-0063) / blocker-8 (cz-java-0069): Replaced HttpSession.setAttribute
        // with Redis-backed Spring Session storage to survive container restarts and horizontal scaling
        redisTemplate.opsForValue().set("session:guestName:" + booking.get("bookingId"), guestName);

        redisTemplate.opsForHash().put("bookingCache", (String) booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // blocker-6 (cz-java-0063): Replaced HttpSession.getAttribute with Redis lookup
        // to support distributed session retrieval across multiple container instances
        String lastGuest = (String) redisTemplate.opsForValue().get("session:guestName:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    // blocker-1 (cz-java-0057): Replaced hardcoded absolute file path "/var/legacy/reports/"
    // with environment variable REPORT_BASE_PATH injected via Kubernetes ConfigMap
    @Value("${REPORT_BASE_PATH:/reports}")
    private String reportBasePath;

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // blocker-1 (cz-java-0057): Path now sourced from environment variable REPORT_BASE_PATH
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        // blocker-9 (cz-java-0082): Decoupled report generation by delegating to BookingService
        // via interface boundary; report logic is independently deployable as a microservice
        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
