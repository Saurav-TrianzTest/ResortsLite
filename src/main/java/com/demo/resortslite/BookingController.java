package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

import javax.servlet.http.HttpSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.JedisPooled;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Redis cache replacing in-memory HashMap (cr-java-0067)
    // Lazily initialised on first use, with TTL for automatic expiration
    private JedisPooled jedis;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final int CACHE_TTL_SECONDS = 3600;

    // SSM client for Parameter Store lookups (cr-java-0071)
    private SsmClient ssmClient;
    private String parameterPath;

    private JedisPooled getJedis() {
        if (jedis == null) {
            String redisEndpoint = System.getenv("ELASTICACHE_ENDPOINT");
            if (redisEndpoint == null || redisEndpoint.isEmpty()) {
                redisEndpoint = "localhost";
            }
            String portEnv = System.getenv("REDIS_PORT");
            int redisPort = (portEnv != null) ? Integer.parseInt(portEnv) : 6379;
            jedis = new JedisPooled(redisEndpoint, redisPort);
        }
        return jedis;
    }

    private SsmClient getSsmClient() {
        if (ssmClient == null) {
            ssmClient = SsmClient.builder().build();
        }
        return ssmClient;
    }

    private String getParameterPath() {
        if (parameterPath == null) {
            parameterPath = System.getenv("APP_PARAMETER_PATH");
            if (parameterPath == null) {
                parameterPath = "/app/config/";
            }
        }
        return parameterPath;
    }

    private String getParameterFromSsm(String parameterKey) {
        String fullPath = getParameterPath() + parameterKey;
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(fullPath)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = getSsmClient().getParameter(request);
            return response.parameter().value();
        } catch (SsmException e) {
            return null;
        }
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Session state now backed by Redis via Spring Session (cr-java-0065)
        session.setAttribute("lastBooking", booking); // cr-java-0065
        session.setAttribute("guestName", guestName); // cr-java-0065

        // Cache in Redis with TTL instead of in-memory HashMap (cr-java-0067)
        try {
            String cacheKey = "booking:" + booking.get("bookingId");
            String serialized = objectMapper.writeValueAsString(booking);
            getJedis().setex(cacheKey, CACHE_TTL_SECONDS, serialized);
        } catch (Exception e) {
            // Cache failure does not break the application
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // Session state now backed by Redis via Spring Session (cr-java-0065)
        String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065

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
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path externalised to Parameter Store (cr-java-0071)
        String reportBase = getParameterFromSsm("report-path");
        if (reportBase == null || reportBase.isEmpty()) {
            reportBase = "reports/";
        }
        String reportPath = reportBase + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
