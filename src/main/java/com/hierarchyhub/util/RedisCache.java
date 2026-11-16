package com.hierarchyhub.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class RedisCache {

    private final RedisConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    public RedisCache(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
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
            connection.set(bytes(key), serialized, Expiration.from(timeout, unit), SetOption.upsert());
        } catch (Exception e) {
            // a cache write failing should never fail the request it's caching for
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
