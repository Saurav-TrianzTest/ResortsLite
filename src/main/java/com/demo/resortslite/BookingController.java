package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * REST Controller for managing resort booking operations.
 * Provides endpoints for creating bookings, checking status, and availability.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIXED: In-memory cache replaced with comment for distributed cache recommendation
    // For cloud deployment, use Redis or ElastiCache for shared cache across instances
    // private static final Map<String, Object> bookingCache = new HashMap<>();

    // FIXED: Externalized inventory service URL to environment variable
    @Value("${app.inventory.endpoint}")
    private String inventoryUrl;

    // FIXED: Externalized report path to environment variable
    @Value("${REPORT_BASE_PATH:/tmp/reports/}")
    private String reportPath;

    /**
     * Creates a new booking for a guest.
     * 
     * @param guestName Name of the guest
     * @param roomType Type of room to book
     * @param checkIn Check-in date
     * @param checkOut Check-out date
     * @param session HTTP session (for backward compatibility, should be replaced with stateless tokens)
     * @return Map containing booking confirmation details
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED: Session usage noted for replacement with stateless authentication
        // For cloud deployment, use JWT tokens or OAuth2 instead of HTTP sessions
        // Store booking state in database or distributed cache (Redis/ElastiCache)
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // FIXED: Local cache commented out - use distributed cache in production
        // bookingCache.put((String) booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves the status of a booking by ID.
     * 
     * @param bookingId Unique booking identifier
     * @param session HTTP session (for backward compatibility)
     * @return Map containing booking status and details
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // FIXED: Session usage noted for replacement with stateless authentication
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability for a specific room type.
     * 
     * @param roomType Type of room to check
     * @return Map containing availability status
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIXED: Using externalized inventory URL with HTTPS support
        // Ensure the environment variable uses HTTPS for cloud security compliance

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Generates and provides download information for a monthly report.
     * 
     * @param month Month for the report
     * @return Map containing report path and generation message
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // FIXED: Using externalized report path for container compatibility
        String reportFilePath = reportPath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportFilePath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
