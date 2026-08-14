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

/**
 * Redis Cache Configuration for distributed caching with TTL.
 * 
 * FIXED cr-java-0067: Migrated unbounded in-memory caching to Amazon ElastiCache for Redis.
 * This configuration replaces the static HashMap cache with a distributed Redis cache that:
 * - Enforces time-to-live (TTL) policies to prevent indefinite memory growth
 * - Provides consistent cache data across multiple application instances
 * - Prevents out-of-memory errors from unbounded cache growth
 * - Eliminates stale data inconsistencies in horizontally scaled environments
 * - Enables centralized cache management and monitoring
 * 
 * For AWS deployment:
 * - Use Amazon ElastiCache for Redis (managed Redis service)
 * - Configure REDIS_HOST, REDIS_PORT, REDIS_PASSWORD environment variables
 * - Enable in-transit encryption (TLS) by setting REDIS_SSL=true
 * - Use Redis cluster mode for high availability
 * - Monitor cache hit/miss rates via CloudWatch metrics
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    /**
     * Configure Redis cache manager with TTL and serialization settings.
     * 
     * @param connectionFactory Redis connection factory (auto-configured by Spring Boot)
     * @return Configured RedisCacheManager with TTL policies
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // Configure cache with 1-hour TTL (3600 seconds)
        RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1)) // TTL: 1 hour - prevents indefinite memory growth
                .disableCachingNullValues() // Don't cache null values
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfig)
                .transactionAware() // Enable transaction support
                .build();
    }
}
