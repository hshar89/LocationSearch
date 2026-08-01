package org.learning.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.learning.model.MergedPoi;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class RedisClient implements AutoCloseable {

    private static final String KEY_PREFIX = "poi:";

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedisClient(String host, int port) {
        this.jedisPool = new JedisPool(host, port);
    }

    public void savePoi(MergedPoi poi) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(KEY_PREFIX + poi.id(), objectMapper.writeValueAsString(poi));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public MergedPoi getPoi(String id) {
        try (Jedis jedis = jedisPool.getResource()) {
            String value = jedis.get(KEY_PREFIX + id);
            if (value == null) {
                return null;
            }
            return objectMapper.readValue(value, MergedPoi.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        jedisPool.close();
    }
}
