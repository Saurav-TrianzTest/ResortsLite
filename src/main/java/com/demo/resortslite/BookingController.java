package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIXED: blocker-13 (cz-java-0070) - Replaced local cache with distributed Redis cache
    @Autowired
    private RedisCacheService redisCacheService;

    // FIXED: blocker-1 (cz-java-0057) - Replaced absolute file path with S3 storage
    @Autowired
    private S3StorageService s3StorageService;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED: blocker-4, blocker-5, blocker-7, blocker-8 (cz-java-0063, cz-java-0069)
        // Session data now stored in Redis via Spring Session - persists across container restarts
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // FIXED: blocker-13 (cz-java-0070) - Using distributed Redis cache with TTL
        String bookingId = (String) booking.get("bookingId");
        redisCacheService.put("booking:" + bookingId, booking, 60); // 60 minutes TTL

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // FIXED: blocker-6 (cz-java-0063) - Session managed by Redis, accessible across instances
        String lastGuest = (String) session.getAttribute("guestName");

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
        // FIXED: blocker-9 (cz-java-0082) - Using service interface for loose coupling
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // FIXED: blocker-1 (cz-java-0057) - Using S3 storage instead of absolute file path
        String reportKey = "reports/" + month + "_bookings.pdf";
        String reportUrl = s3StorageService.getFileUrl(reportKey);

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportUrl);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
