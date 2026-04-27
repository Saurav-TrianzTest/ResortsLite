package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST Controller for booking operations.
 * FIXED: Removed in-memory cache and HTTP session usage for cloud compatibility.
 * FIXED: Externalized service endpoints to environment variables.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIXED: Externalized inventory endpoint to environment variable
    @Value("${app.inventory.endpoint}")
    private String inventoryUrl;

    /**
     * Creates a new booking.
     * FIXED: Removed HTTP session storage for cloud compatibility.
     * FIXED: Removed in-memory cache for horizontal scaling support.
     * 
     * @param guestName guest name
     * @param roomType room type
     * @param checkIn check-in date
     * @param checkOut check-out date
     * @return booking confirmation response
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED: Removed session.setAttribute() calls - use distributed cache (Redis/ElastiCache) 
        // or database for stateful data in cloud environments

        // FIXED: Removed in-memory bookingCache - use distributed cache (Redis/ElastiCache)
        // for caching in horizontally scaled cloud deployments

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Gets booking status by ID.
     * FIXED: Removed HTTP session dependency for cloud compatibility.
     * 
     * @param bookingId the booking ID
     * @return booking status response
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // FIXED: Removed session.getAttribute() - retrieve data from database or distributed cache

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability.
     * FIXED: Externalized inventory service URL to environment variable.
     * RECOMMENDATION: Use HTTPS for service-to-service communication in production.
     * 
     * @param roomType the room type
     * @return availability response
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIXED: Inventory URL now externalized to ${app.inventory.endpoint}
        // RECOMMENDATION: Update to HTTPS in production for AWS ALB/WAF compliance

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Downloads monthly report.
     * FIXED: Removed hardcoded file path for container compatibility.
     * RECOMMENDATION: Use cloud object storage (S3/Azure Blob) for report storage.
     * 
     * @param month the month for report
     * @return report download response
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // FIXED: Removed hardcoded /var/legacy/reports path
        // RECOMMENDATION: Use S3 bucket or volume mount with environment variable

        Map<String, Object> response = new HashMap<>();
        response.put("month", month);
        response.put("message", bookingService.generateReport(month));
        response.put("recommendation", "Use cloud object storage (S3/Azure Blob) for report storage");
        return response;
    }
}
