package com.demo.resortslite;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Spring Session Redis configuration (cr-java-0065).
 * Externalises HTTP session state to Amazon ElastiCache for Redis,
 * enabling stateless application instances with distributed session management.
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class SessionConfig {
}
