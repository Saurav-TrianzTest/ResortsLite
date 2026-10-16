package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * RedisConfig – configures the Spring Data Redis infrastructure beans required
 * for the cr-java-0067 fix (In-Memory Caching Without TTL).
 *
 * <h3>cr-java-0067 Remediation</h3>
 * <p>The former static {@code HashMap<String, Object> bookingCache} in
 * {@link BookingController} was an unbounded, instance-local in-memory cache
 * with no TTL or expiration policy.  In a horizontally-scaled cloud environment
 * this causes:</p>
 * <ul>
 *   <li>Indefinite memory growth → potential OOM errors on long-running instances.</li>
 *   <li>Cache inconsistency → each EC2/ECS instance holds a different view of the data.</li>
 *   <li>Stale data → entries are never evicted, so outdated booking records persist.</li>
 * </ul>
 *
 * <p>This configuration provides a {@link RedisTemplate} that connects to
 * <strong>Amazon ElastiCache for Redis</strong>, replacing the local cache with a
 * centralised, TTL-aware store that is shared across all application instances.</p>
 *
 * <h3>Connection configuration</h3>
 * <p>All connection parameters are externalised via environment variables so that
 * no endpoint or port is hard-coded in source code.  In AWS deployments these
 * values are injected from AWS Systems Manager Parameter Store:</p>
 * <pre>
 *   SPRING_REDIS_HOST  – ElastiCache primary endpoint hostname  (default: localhost)
 *   SPRING_REDIS_PORT  – Redis port                             (default: 6379)
 * </pre>
 *
 * <h3>Serialization</h3>
 * <ul>
 *   <li>Keys   → {@link StringRedisSerializer} (human-readable string keys)</li>
 *   <li>Values → {@link GenericJackson2JsonRedisSerializer} (JSON-serialised objects)</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    /**
     * ElastiCache Redis primary endpoint hostname.
     * Resolved from the environment variable {@code SPRING_REDIS_HOST}
     * or the Spring property {@code spring.redis.host}.
     * In AWS deployments set via SSM Parameter Store key:
     * {@code /resortslite/redis/host}.
     */
    @Value("${spring.redis.host:${SPRING_REDIS_HOST:localhost}}")
    private String redisHost;

    /**
     * ElastiCache Redis port.
     * Resolved from the environment variable {@code SPRING_REDIS_PORT}
     * or the Spring property {@code spring.redis.port}.
     * Default: 6379.
     */
    @Value("${spring.redis.port:${SPRING_REDIS_PORT:6379}}")
    private int redisPort;

    /**
     * Creates a Lettuce-based {@link RedisConnectionFactory} pointing at the
     * Amazon ElastiCache for Redis cluster.
     *
     * <p>Lettuce is the default client bundled with
     * {@code spring-boot-starter-data-redis} and supports both standalone and
     * cluster ElastiCache topologies without additional dependencies.</p>
     *
     * @return configured {@link LettuceConnectionFactory}
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * cr-java-0067 FIX: Provides a {@link RedisTemplate} for storing booking cache
     * entries in Amazon ElastiCache for Redis with TTL-based expiration.
     *
     * <p>Serialization strategy:</p>
     * <ul>
     *   <li>Key serializer   : {@link StringRedisSerializer} — keys are stored as
     *       plain UTF-8 strings (e.g. {@code "booking:cache:BK-1A2B3C4D"}).</li>
     *   <li>Value serializer : {@link GenericJackson2JsonRedisSerializer} — booking
     *       {@code Map<String, Object>} values are serialised as JSON, making them
     *       inspectable via the Redis CLI and compatible with other consumers.</li>
     * </ul>
     *
     * <p>TTL is applied per entry at write time in {@link BookingController} using
     * {@code redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(ttl))},
     * ensuring every cache entry expires automatically and memory growth is bounded.</p>
     *
     * @param redisConnectionFactory the Lettuce connection factory (injected above)
     * @return configured {@link RedisTemplate}
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);

        // Use String serializer for keys — produces readable Redis key names
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Use JSON serializer for values — booking Maps are stored as JSON objects
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
