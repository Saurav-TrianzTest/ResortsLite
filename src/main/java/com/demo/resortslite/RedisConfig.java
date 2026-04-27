package com.demo.resortslite;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Redis configuration for distributed session management.
 * Enables Spring Session with Redis to support horizontal scaling and container restarts.
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 3600)
public class RedisConfig {
    // Spring Boot auto-configuration handles Redis connection based on application.properties
}
