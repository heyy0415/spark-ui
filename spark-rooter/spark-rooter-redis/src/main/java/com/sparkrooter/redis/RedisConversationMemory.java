package com.sparkrooter.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkrooter.spi.ConversationMemory;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 会话记忆的 Redis 实现：key {@code spark:memory:{sessionId}/{conversationId}} → JSON，TTL = memoryTtl。 */
public class RedisConversationMemory implements ConversationMemory {

  static final String PREFIX = "spark:memory:";

  private final StringRedisTemplate redis;
  private final Json json;
  private final Duration ttl;

  public RedisConversationMemory(StringRedisTemplate redis, ObjectMapper mapper, Duration ttl) {
    this.redis = redis;
    this.json = new Json(mapper);
    this.ttl = ttl;
  }

  @Override
  public void put(String sessionId, String conversationId, Memory memory) {
    redis.opsForValue().set(key(sessionId, conversationId), json.write(memory), ttl);
  }

  @Override
  public Optional<Memory> find(String sessionId, String conversationId) {
    String raw = redis.opsForValue().get(key(sessionId, conversationId));
    return raw == null ? Optional.empty() : Optional.of(json.read(raw, Memory.class));
  }

  private static String key(String sessionId, String conversationId) {
    return PREFIX + sessionId + "/" + conversationId;
  }
}
