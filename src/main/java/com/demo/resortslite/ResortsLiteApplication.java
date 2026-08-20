package com.demo.resortslite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * ResortsLite Spring Boot application entry point.
 *
 * Cloud readiness annotations:
 *  - @EnableRedisHttpSession: activates Spring Session backed by Amazon ElastiCache
 *    for Redis, replacing in-process HTTP session storage (fix for cr-java-0065).
 *  - @EnableCaching: activates Spring Cache backed by Amazon ElastiCache for Redis
 *    with TTL policies configured in application.properties (fix for cr-java-0067).
 */
@SpringBootApplication
@EnableRedisHttpSession
@EnableCaching
public class ResortsLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResortsLiteApplication.class, args);
    }
}
