package com.demo.resortslite;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * cz-java-0063 FIX: Stateless JWT token utility replacing server-side HttpSession.
 *
 * The JWT signing secret is injected from the environment variable JWT_SECRET,
 * which must be set in the ECS Fargate task definition (sourced from AWS Secrets Manager).
 * This ensures no session state is stored on any container instance, enabling safe
 * horizontal scaling and container restarts without session loss.
 */
@Component
public class JwtTokenUtil {

    private static final long TOKEN_VALIDITY_MS = 24 * 60 * 60 * 1000L; // 24 hours

    // cz-java-0063 FIX: JWT signing secret injected via environment variable JWT_SECRET.
    // In ECS Fargate, set JWT_SECRET from AWS Secrets Manager in the task definition.
    @Value("${jwt.secret:${JWT_SECRET:changeme-override-in-production}}")
    private String jwtSecret;

    /**
     * Generate a stateless JWT token embedding the guest name and booking ID as claims.
     * Replaces session.setAttribute("guestName", ...) and session.setAttribute("lastBooking", ...).
     */
    public String generateToken(String guestName, String bookingId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("guestName", guestName);
        claims.put("bookingId", bookingId);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(guestName)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + TOKEN_VALIDITY_MS))
                .signWith(SignatureAlgorithm.HS256, jwtSecret.getBytes())
                .compact();
    }

    /**
     * Extract the guest name claim from a JWT token.
     * Replaces session.getAttribute("guestName").
     */
    public String extractGuestName(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .setSigningKey(jwtSecret.getBytes())
                    .parseClaimsJws(token)
                    .getBody();
            return claims.get("guestName", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Validate a JWT token (signature + expiry).
     */
    public boolean validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        try {
            Jwts.parser()
                    .setSigningKey(jwtSecret.getBytes())
                    .parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
