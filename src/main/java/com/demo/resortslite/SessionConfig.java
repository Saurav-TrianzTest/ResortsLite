package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cr-java-0065 remediation — Spring Session with Amazon ElastiCache for Redis.
 *
 * <p>Enables distributed HTTP session management backed by Amazon ElastiCache for Redis
 * using Spring Session's {@code @EnableRedisHttpSession}. This replaces the default
 * in-process {@code HttpSession} implementation with a Redis-backed session store,
 * making all session state available to every EC2 instance in the Auto Scaling group.</p>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Spring Session intercepts every {@link javax.servlet.http.HttpSession} interaction
 *       in the application (e.g. {@code session.setAttribute} / {@code session.getAttribute}
 *       in {@link BookingController}).</li>
 *   <li>Session data is serialised and stored in the Amazon ElastiCache Redis cluster
 *       instead of in local JVM heap memory.</li>
 *   <li>Any EC2 instance that receives a subsequent request with the same session cookie
 *       reads the session from Redis, eliminating server affinity and enabling safe
 *       horizontal scaling behind an AWS Application Load Balancer (ALB).</li>
 * </ol>
 *
 * <h3>Configuration (12-factor app — environment variables)</h3>
 * <ul>
 *   <li>{@code REDIS_HOST} — ElastiCache primary endpoint hostname
 *       (default: {@code localhost} for local development)</li>
 *   <li>{@code REDIS_PORT} — ElastiCache port (default: {@code 6379})</li>
 *   <li>{@code REDIS_PASSWORD} — ElastiCache auth token, if AUTH is enabled
 *       (default: empty — no auth for local development)</li>
 *   <li>{@code SESSION_TIMEOUT_SECONDS} — session TTL in seconds
 *       (default: {@code 1800} = 30 minutes)</li>
 * </ul>
 *
 * <p>In production, set these environment variables in the ECS task definition,
 * EKS pod spec, or Elastic Beanstalk environment properties to point to the
 * ElastiCache cluster endpoint.</p>
 *
 * <p><strong>cr-java-0067 remediation</strong>: Also exposes a {@link RedisTemplate}
 * bean with {@link StringRedisSerializer} for keys and
 * {@link GenericJackson2JsonRedisSerializer} for values. This template is used by
 * {@link BookingController} to store booking cache entries in Amazon ElastiCache for
 * Redis with a configurable TTL, replacing the unbounded in-memory {@code HashMap}
 * that caused indefinite memory growth and stale data inconsistencies across
 * multiple EC2 instances.</p>
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class SessionConfig {

    /**
     * ElastiCache Redis primary endpoint hostname.
     * Injected from the {@code REDIS_HOST} environment variable via
     * {@code application.properties} binding {@code spring.redis.host}.
     * Defaults to {@code localhost} for local development.
     */
    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    /**
     * ElastiCache Redis port.
     * Injected from the {@code REDIS_PORT} environment variable via
     * {@code application.properties} binding {@code spring.redis.port}.
     * Defaults to {@code 6379}.
     */
    @Value("${spring.redis.port:6379}")
    private int redisPort;

    /**
     * ElastiCache Redis auth token (optional).
     * Injected from the {@code REDIS_PASSWORD} environment variable via
     * {@code application.properties} binding {@code spring.redis.password}.
     * Leave empty for clusters without AUTH enabled (e.g. local development).
     */
    @Value("${spring.redis.password:}")
    private String redisPassword;

    /**
     * Creates a Lettuce-based Redis connection factory pointing at the
     * Amazon ElastiCache cluster.
     *
     * <p>Lettuce is the recommended non-blocking Redis client for Spring Boot 2.x
     * and is included transitively via {@code spring-boot-starter-data-redis}.</p>
     *
     * @return configured {@link LettuceConnectionFactory}
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisHost);
        config.setPort(redisPort);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.setPassword(redisPassword);
        }
        return new LettuceConnectionFactory(config);
    }

    /**
     * Creates a {@link RedisTemplate} for storing and retrieving booking cache entries
     * in Amazon ElastiCache for Redis.
     *
     * <p><strong>cr-java-0067 remediation</strong>: This template is injected into
     * {@link BookingController} to replace the unbounded in-memory {@code HashMap}
     * cache. Keys are serialised as plain UTF-8 strings (e.g.
     * {@code "booking:BK-12345"}); values are serialised as JSON using
     * {@link GenericJackson2JsonRedisSerializer}, which handles {@code Map<String, Object>}
     * round-trips correctly without requiring a fixed type hierarchy.</p>
     *
     * <p>Cache entries are written with a TTL (default 1800 seconds, configurable via
     * the {@code BOOKING_CACHE_TTL_SECONDS} environment variable) so they expire
     * automatically, preventing indefinite memory growth in the Redis cluster.</p>
     *
     * @return configured {@link RedisTemplate} with String keys and JSON values
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate() {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory());
        // Use plain String serializer for keys to keep them human-readable in Redis
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // Use Jackson JSON serializer for values to support Map<String, Object> round-trips
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
