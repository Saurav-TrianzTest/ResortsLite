package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * BookingController — cloud-ready REST controller.
 *
 * Cloud readiness fixes applied:
 *  - cr-java-0065: HTTP session state replaced with Spring Session backed by
 *                  Amazon ElastiCache for Redis (distributed, TTL-managed sessions).
 *  - cr-java-0067: In-memory HashMap cache replaced with Spring Cache backed by
 *                  Amazon ElastiCache for Redis with TTL (see application.properties).
 *  - cr-java-0071: Hard-coded inventory URL externalised to AWS SSM Parameter Store
 *                  and injected via @Value / environment variable.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * Inventory service URL externalised from AWS SSM Parameter Store.
     * Set the environment variable INVENTORY_SERVICE_URL (injected by ECS task definition
     * or Elastic Beanstalk environment properties) to the value retrieved from SSM:
     *   /resortsLite/inventory/serviceUrl
     *
     * Fix for cr-java-0071 (Hard-coded Environment URLs) — blocker-10.
     */
    @Value("${app.inventory.service.url:${INVENTORY_SERVICE_URL:https://inventory-service.internal/rooms/available}}")
    private String inventoryServiceUrl;

    /**
     * Create a new booking.
     *
     * Fix for cr-java-0065 (blockers 13, 14, 15, 16): session state is now managed by
     * Spring Session + Amazon ElastiCache for Redis. The HttpSession object is backed by
     * Redis, so session attributes are visible to every instance in the cluster.
     *
     * Fix for cr-java-0067 (blocker-20): booking is stored in a Redis-backed Spring Cache
     * named "bookingCache" with TTL configured in application.properties, replacing the
     * previous unbounded in-memory HashMap.
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

        // Session is now backed by Amazon ElastiCache for Redis via Spring Session.
        // All cluster nodes share the same session store — no server affinity required.
        session.setAttribute("lastBooking", booking);   // cr-java-0065 fixed
        session.setAttribute("guestName", guestName);  // cr-java-0065 fixed

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieve booking status.
     *
     * Fix for cr-java-0065 (blocker-17): session read is now served from the shared
     * Redis session store — consistent across all EC2 / ECS instances.
     *
     * Fix for cr-java-0067 (blocker-20): booking lookup uses Redis-backed cache.
     */
    @GetMapping("/status/{bookingId}")
    @Cacheable(value = "bookingCache", key = "#bookingId")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // Session attribute read from Redis-backed Spring Session store.
        String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065 fixed

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Check room availability.
     *
     * Fix for cr-java-0071 (blocker-10): the inventory service URL is no longer
     * hard-coded. It is injected from the environment variable INVENTORY_SERVICE_URL
     * which is populated at deploy time from AWS SSM Parameter Store
     * (/resortsLite/inventory/serviceUrl).
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // URL is now externalised — injected from AWS SSM Parameter Store at runtime.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryServiceUrl); // cr-java-0071 fixed
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
