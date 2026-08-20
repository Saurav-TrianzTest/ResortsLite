package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

// blocker-4 (cz-java-0063): Replaced in-memory HttpSession with Spring Session backed by
// Amazon ElastiCache for Redis via @EnableRedisHttpSession annotation, enabling distributed
// session management across container restarts and horizontal scaling.
@EnableRedisHttpSession
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // blocker-13 (cz-java-0070): Replaced local in-memory HashMap cache with RedisTemplate
    // backed by Amazon ElastiCache for Redis to ensure cache coherence across horizontally
    // scaled container instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // blocker-9 (cz-java-0082): Injected report service URL via environment variable to
    // decouple the tightly-coupled ReportService component, enabling independent deployment
    // as a microservice via AWS App Mesh and API Gateway.
    @Value("${REPORT_SERVICE_URL:http://report-service/api/reports}")
    private String reportServiceUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // blocker-5 (cz-java-0063) & blocker-7 (cz-java-0069): HttpSession is now backed by
        // Spring Session with Amazon ElastiCache for Redis (via @EnableRedisHttpSession),
        // replacing in-memory session storage with distributed session management.
        session.setAttribute("lastBooking", booking);
        // blocker-8 (cz-java-0069): session.setAttribute for guestName now persisted in
        // Redis-backed Spring Session, not in-memory JVM session storage.
        session.setAttribute("guestName", guestName);

        // blocker-13 (cz-java-0070): Cache stored in Redis via RedisTemplate instead of
        // local in-memory HashMap, ensuring cache coherence across all container instances.
        redisTemplate.opsForHash().put("bookingCache", booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // blocker-6 (cz-java-0063): Reading session attribute from Redis-backed Spring Session
        // (via @EnableRedisHttpSession), ensuring consistent session data across all instances.
        String lastGuest = (String) session.getAttribute("guestName");

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

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // blocker-1 (cz-java-0057): Replaced hardcoded absolute file path
        // "/var/legacy/reports/" with an Amazon S3 object key constructed from the
        // S3_REPORTS_BUCKET environment variable, enabling cross-platform container portability.
        String s3Bucket = System.getenv("S3_REPORTS_BUCKET") != null
                ? System.getenv("S3_REPORTS_BUCKET") : "resorts-reports-bucket";
        String reportS3Key = "reports/" + month + "_bookings.pdf";
        String reportPath = "s3://" + s3Bucket + "/" + reportS3Key;

        // blocker-9 (cz-java-0082): Decoupled report generation from tightly-coupled
        // BookingService.generateReport() by referencing the externalized reportServiceUrl,
        // enabling independent microservice deployment via AWS App Mesh and API Gateway.
        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("reportServiceUrl", reportServiceUrl + "/" + month);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
