package com.sparkrooter.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkrooter.runtime.domain.Run;
import com.sparkrooter.runtime.domain.RunRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Run 仓储的 Redis 实现：key {@code spark:run:{runId}} → {@link RunSnapshot} JSON，TTL = {@code runTtl}。
 *
 * <p>每次 {@code save} 重设 TTL（按 updatedAt 续期，与内存实现"按 updatedAt 淘汰"同一语义）。{@link #evictExpired()}
 * 返回空：过期由 Redis 负责，编排器无需再清任何进程内缓存——那些缓存已经进了 Run 聚合。
 */
public class RedisRunRepository implements RunRepository {

  static final String PREFIX = "spark:run:";

  private final StringRedisTemplate redis;
  private final Json json;
  private final Duration ttl;

  public RedisRunRepository(StringRedisTemplate redis, ObjectMapper mapper, Duration ttl) {
    this.redis = redis;
    this.json = new Json(mapper);
    this.ttl = ttl;
  }

  @Override
  public void save(Run run) {
    redis.opsForValue().set(PREFIX + run.runId(), json.write(RunSnapshot.of(run)), ttl);
  }

  @Override
  public Optional<Run> find(String runId) {
    String raw = redis.opsForValue().get(PREFIX + runId);
    return raw == null ? Optional.empty() : Optional.of(json.read(raw, RunSnapshot.class).toRun());
  }

  @Override
  public List<String> evictExpired() {
    return List.of();
  }
}
