package com.demo.resortslite;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * CacheConfig — Spring Cache configuration backed by Amazon ElastiCache for Redis.
 *
 * <p>cr-java-0067 FIX: Replaces the unbounded static {@code HashMap}-based in-memory
 * cache in {@link BookingController} with a {@link RedisCacheManager} that enforces
 * per-cache TTL policies. Key benefits over the previous in-memory approach:
 *
 * <ul>
 *   <li><strong>Controlled expiration</strong>: Every cache entry expires automatically
 *       after the configured TTL, preventing indefinite memory growth and out-of-memory
 *       errors that would occur with the unbounded {@code HashMap}.</li>
 *   <li><strong>Cross-instance consistency</strong>: Because Redis is an external,
 *       shared store, all application instances in the AWS Auto Scaling group read from
 *       and write to the same cache. The per-instance divergence caused by the static
 *       {@code HashMap} is eliminated.</li>
 *   <li><strong>Centralised cache management</strong>: Cache entries can be inspected,
 *       evicted, and monitored via standard Redis tooling (e.g. AWS ElastiCache console,
 *       redis-cli) without restarting application instances.</li>
 * </ul>
 *
 * <p>Cache TTL values (configurable via environment variables):
 * <ul>
 *   <li>{@code "bookings"} cache — default 30 minutes
 *       ({@code CACHE_TTL_BOOKINGS_MINUTES}, default {@code 30})</li>
 * </ul>
 *
 * <p>Required infrastructure:
 * <ul>
 *   <li>Amazon ElastiCache for Redis cluster reachable at the host/port configured by
 *       {@code SPRING_REDIS_HOST} and {@code SPRING_REDIS_PORT} environment variables.</li>
 *   <li>{@code spring-boot-starter-data-redis} on the classpath (already present in
 *       {@code pom.xml}).</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Default TTL for the {@code "bookings"} cache in minutes.
     * Override by setting the {@code CACHE_TTL_BOOKINGS_MINUTES} environment variable.
     */
    private static final long DEFAULT_BOOKINGS_TTL_MINUTES = parseLongEnv(
            "CACHE_TTL_BOOKINGS_MINUTES", 30L);

    /**
     * Builds a {@link RedisCacheManager} with per-cache TTL configuration.
     *
     * <p>The default cache configuration uses JSON serialisation for values so that
     * cached objects survive application restarts and can be inspected in Redis without
     * binary decoding. String keys are used for human-readable key names.
     *
     * @param connectionFactory the Redis connection factory auto-configured by Spring Boot
     *                          from {@code SPRING_REDIS_HOST} / {@code SPRING_REDIS_PORT}
     * @return a fully configured {@link RedisCacheManager}
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {

        // Default configuration applied to any cache not explicitly listed below.
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(DEFAULT_BOOKINGS_TTL_MINUTES))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer()))
                // Do not cache null values — avoids masking missing-data bugs.
                .disableCachingNullValues();

        // Per-cache TTL overrides.  Add additional entries here as new caches are introduced.
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // cr-java-0067 FIX: "bookings" cache — replaces the static HashMap in BookingController.
        // TTL of 30 minutes (overridable via CACHE_TTL_BOOKINGS_MINUTES) ensures entries
        // expire automatically, preventing stale data and unbounded memory growth.
        cacheConfigurations.put("bookings",
                defaultConfig.entryTtl(Duration.ofMinutes(DEFAULT_BOOKINGS_TTL_MINUTES)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                // Transactions: do not participate in Spring-managed transactions by default.
                // Enable transactionAware() if cache operations must be rolled back with DB txns.
                .build();
    }

    /**
     * Reads a long value from an environment variable, returning {@code defaultValue}
     * if the variable is absent or cannot be parsed.
     *
     * @param envVar       environment variable name
     * @param defaultValue fallback value
     * @return parsed long or {@code defaultValue}
     */
    private static long parseLongEnv(String envVar, long defaultValue) {
        String value = System.getenv(envVar);
        if (value != null && !value.isEmpty()) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                System.err.println("[CacheConfig] WARNING: Invalid value for env var '"
                        + envVar + "': '" + value + "'. Using default: " + defaultValue);
            }
        }
        return defaultValue;
    }
}
