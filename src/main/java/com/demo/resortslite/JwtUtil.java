package com.demo.resortslite;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.Map;

/**
 * Stateless JWT utility for ECS Fargate deployments (cz-java-0063).
 *
 * The signing secret is injected via the JWT_SECRET environment variable,
 * which is sourced from AWS Secrets Manager in the ECS task definition.
 * This eliminates server-side HttpSession dependency so that any container
 * replica can validate tokens without shared in-memory state.
 */
@Component
public class JwtUtil {

    /** Token validity: 1 hour (3 600 000 ms). */
    private static final long EXPIRATION_MS = 3_600_000L;

    /**
     * Returns the HMAC-SHA256 signing key derived from the JWT_SECRET
     * environment variable.  Falls back to a default value only for local
     * development; production deployments MUST supply JWT_SECRET via the
     * ECS task definition / AWS Secrets Manager.
     */
    private Key signingKey() {
        String secret = System.getenv("JWT_SECRET");
        if (secret == null || secret.isEmpty()) {
            // Local-dev fallback — never used in production containers
            secret = "local-dev-secret-change-in-production-env";
        }
        // Ensure the key is at least 256 bits for HS256
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Generates a signed JWT containing the supplied claims.
     *
     * @param claims  arbitrary key/value pairs to embed in the token payload
     * @param subject the principal identifier (e.g. guestName)
     * @return compact, URL-safe JWT string
     */
    public String generateToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Validates and parses a JWT, returning its claims.
     *
     * @param token compact JWT string from the Authorization header
     * @return parsed {@link Claims}
     * @throws io.jsonwebtoken.JwtException if the token is invalid or expired
     */
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * Extracts the subject (guestName) from a Bearer token header value.
     *
     * @param authorizationHeader value of the HTTP Authorization header
     * @return subject string, or {@code null} if the header is absent/invalid
     */
    public String extractSubject(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        try {
            String token = authorizationHeader.substring(7);
            return parseToken(token).getSubject();
        } catch (Exception e) {
            return null;
        }
    }
}
