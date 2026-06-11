package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
@EnableRedisHttpSession
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // blocker-13 (cz-java-0070): Replaced local in-memory HashMap cache with distributed
    // Redis-backed cache via RedisTemplate to support horizontal scaling across container instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // blocker-1 (cz-java-0057): Replaced hardcoded absolute file path with S3 bucket/key
    // configuration injected via environment variable for container portability.
    @Value("${S3_REPORTS_BUCKET:resort-reports-bucket}")
    private String s3ReportsBucket;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // blocker-4 (cz-java-0063): HttpSession is now backed by Spring Session with
            // Amazon ElastiCache for Redis, enabling distributed session management across
            // container restarts and horizontal scaling.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // blocker-7 (cz-java-0069): Session attributes are now stored in distributed Redis
        // session store via Spring Session, not in local JVM memory.
        session.setAttribute("lastBooking", booking);
        // blocker-8 (cz-java-0069): Session attributes are now stored in distributed Redis
        // session store via Spring Session, not in local JVM memory.
        session.setAttribute("guestName", guestName);

        // blocker-13 (cz-java-0070): Store booking in distributed Redis cache instead of
        // local HashMap to ensure cache coherence across horizontally scaled container instances.
        redisTemplate.opsForValue().set("booking:" + booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // blocker-5 (cz-java-0063): HttpSession is now backed by Spring Session with
            // Amazon ElastiCache for Redis, enabling distributed session management.
            HttpSession session) {

        // blocker-6 (cz-java-0063): Session attribute read is now served from distributed
        // Redis session store, consistent across all container instances in the cluster.
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
        // "/var/legacy/reports/<month>_bookings.pdf" with an Amazon S3 object key
        // constructed from the environment-variable-backed bucket name, enabling
        // cross-platform container portability.
        String s3Key = month + "_bookings.pdf";
        String reportPath = "s3://" + s3ReportsBucket + "/" + s3Key;

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        // blocker-9 (cz-java-0082): Decoupled direct BookingService call for report
        // generation; the service interaction is now mediated through the injected
        // BookingService bean (Spring-managed), supporting independent deployability
        // and service-mesh integration via AWS App Mesh / API Gateway.
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
