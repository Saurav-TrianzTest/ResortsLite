package com.demo.resortslite;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Redis Session Configuration for distributed session management.
 * 
 * FIXED cr-java-0065: Migrated HTTP session storage to Amazon ElastiCache for Redis.
 * This configuration enables Spring Session to automatically store all HTTP session
 * data in Redis instead of in-memory, allowing:
 * - Stateless application instances
 * - Horizontal scaling across multiple EC2 instances
 * - Session persistence across instance restarts and auto-scaling events
 * - Load balancing without sticky sessions
 * 
 * For AWS deployment:
 * - Use Amazon ElastiCache for Redis (managed Redis service)
 * - Configure REDIS_HOST, REDIS_PORT, REDIS_PASSWORD environment variables
 * - Enable in-transit encryption (TLS) by setting REDIS_SSL=true
 * - Use Redis cluster mode for high availability
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 3600) // 1 hour session timeout
public class RedisSessionConfig {
    // Spring Session automatically configures Redis connection using properties
    // from application.properties (spring.redis.*)
    // No additional bean configuration needed for basic setup
}
