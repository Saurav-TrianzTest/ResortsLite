package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Redis Cache Service for distributed caching.
 * Replaces local in-memory caches with distributed Redis cache.
 */
@Service
public class RedisCacheService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * Store value in Redis cache with TTL
     * @param key Cache key
     * @param value Value to cache
     * @param ttlMinutes Time to live in minutes
     */
    public void put(String key, Object value, long ttlMinutes) {
        redisTemplate.opsForValue().set(key, value, ttlMinutes, TimeUnit.MINUTES);
    }

    /**
     * Retrieve value from Redis cache
     * @param key Cache key
     * @return Cached value or null
     */
    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * Remove value from Redis cache
     * @param key Cache key
     */
    public void remove(String key) {
        redisTemplate.delete(key);
    }

    /**
     * Check if key exists in cache
     * @param key Cache key
     * @return true if exists
     */
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
