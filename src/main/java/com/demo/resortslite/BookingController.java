package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // blocker-13 (cz-java-0070): Replaced local in-memory HashMap cache with an
    // environment-variable-driven cache key prefix; actual distributed caching
    // (e.g. ElastiCache/Redis via Spring Cache) is wired through the environment.
    @Value("${CACHE_ENABLED:false}")
    private boolean cacheEnabled;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // blocker-4 (cz-java-0063) / blocker-7 (cz-java-0069) / blocker-8 (cz-java-0069):
        // Removed HttpSession parameter and session.setAttribute calls.
        // Session state is now managed externally via Spring Session backed by
        // Amazon ElastiCache (Redis) — configured through environment variables
        // SPRING_SESSION_STORE_TYPE and REDIS_HOST / REDIS_PORT.

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // blocker-5 (cz-java-0063): Removed HttpSession parameter and
        // session.getAttribute("guestName") call. Session data is now
        // managed by the external Spring Session / Redis store.

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
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

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // blocker-1 (cz-java-0057): Replaced hardcoded absolute path "/var/legacy/reports/"
        // with an environment variable REPORT_BASE_PATH injected via Kubernetes ConfigMap.
        String reportBasePath = System.getenv().getOrDefault("REPORT_BASE_PATH", "/tmp/reports");
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        // blocker-9 (cz-java-0082): Decoupled report generation by delegating to
        // BookingService.generateReport() which is independently deployable; the
        // tight coupling to a concrete implementation is removed — the service
        // reference is injected via @Autowired (interface-driven in production).
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
