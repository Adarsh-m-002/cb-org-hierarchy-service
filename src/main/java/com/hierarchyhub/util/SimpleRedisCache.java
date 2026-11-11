package com.hierarchyhub.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A plain string-keyed cache over {@link RedisConnection} (not {@code RedisTemplate}/{@code StringRedisTemplate} -
 * either one's own constructor unconditionally builds an {@code ObjectHashMapper}, which needs a newer
 * spring-data-commons than this app is pinned to for the legacy v1 Neo4j module; {@code RedisConnection} is a lower
 * level than that and is unaffected). Values are JSON (via the app's existing {@link ObjectMapper}), not Java
 * serialization - a deliberate, low-risk format change since this is only ever a derived cache, never the source of
 * truth: an empty cache after this change just means the next read recomputes instead of hitting a stale entry.
 */
@Component
public class SimpleRedisCache {

    private final RedisConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    public SimpleRedisCache(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
    }

    public boolean hasKey(String key) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            return Boolean.TRUE.equals(connection.exists(bytes(key)));
        }
    }

    /** The cached value under {@code key}, deserialized as a list of maps, or {@code null} if absent or unreadable. */
    public List<Map<String, Object>> getList(String key) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            byte[] value = connection.get(bytes(key));
            if (value == null) {
                return null;
            }
            return objectMapper.readValue(value, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    public void setWithTtl(String key, Object value, long timeout, TimeUnit unit) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            byte[] serialized = objectMapper.writeValueAsBytes(value);
            connection.set(bytes(key), serialized);
            connection.expire(bytes(key), unit.toSeconds(timeout));
        } catch (Exception e) {
            // a cache write failing should never fail the request it's caching for
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
