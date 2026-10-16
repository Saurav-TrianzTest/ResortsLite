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
 * cz-java-0069 – In-Memory Session Storage remediation.
 *
 * Replaces the default in-memory HttpSession with Spring Session backed by
 * Amazon ElastiCache for Redis.  @EnableRedisHttpSession instructs Spring
 * Session to intercept every HttpSession operation and transparently delegate
 * it to Redis, so session data survives container restarts and is shared
 * across all EKS pod replicas without sticky sessions on the ALB.
 *
 * cz-java-0070 – Local Cache remediation.
 *
 * Provides a RedisTemplate<String, Object> bean used by BookingController to
 * store and retrieve booking cache entries in Amazon ElastiCache (Redis).
 * This replaces the local in-process HashMap that was invisible to other EKS
 * pod replicas and broke horizontal scaling.
 *
 * Connection parameters are read from environment variables so that no
 * credentials or hostnames are hard-coded in the image:
 *   REDIS_HOST     – ElastiCache primary endpoint  (default: localhost)
 *   REDIS_PORT     – ElastiCache port              (default: 6379)
 *   SESSION_TIMEOUT_SECONDS – max-inactive interval (default: 1800 / 30 min)
 *
 * In EKS these variables are injected via a Kubernetes Secret / ConfigMap
 * and the pod's IAM role (IRSA) controls access to the ElastiCache cluster.
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    // cz-java-0069: Redis host injected from environment variable REDIS_HOST
    @Value("${REDIS_HOST:localhost}")
    private String redisHost;

    // cz-java-0069: Redis port injected from environment variable REDIS_PORT
    @Value("${REDIS_PORT:6379}")
    private int redisPort;

    /**
     * Lettuce-based Redis connection factory.
     * Lettuce is the default client bundled with spring-boot-starter-data-redis
     * and supports TLS connections to ElastiCache in-transit encryption.
     */
    @Bean
    public LettuceConnectionFactory connectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * cz-java-0070: RedisTemplate for distributed cache operations.
     *
     * Used by BookingController to store and retrieve booking entries in
     * Amazon ElastiCache (Redis), replacing the local in-process HashMap.
     * Keys are serialised as plain strings; values are serialised as JSON
     * using GenericJackson2JsonRedisSerializer so that Map<String, Object>
     * entries survive round-trips through Redis without type-erasure issues.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate() {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory());
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
