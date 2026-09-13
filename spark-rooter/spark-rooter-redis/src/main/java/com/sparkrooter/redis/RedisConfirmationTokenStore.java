package com.sparkrooter.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkrooter.runtime.domain.ConfirmationToken;
import com.sparkrooter.runtime.domain.ConfirmationTokenStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 确认令牌的 Redis 实现：key {@code spark:token:{token}} → JSON，TTL 取自令牌自身的 expiresAt。
 *
 * <p><b>consume 用 GETDEL</b>（Redis ≥ 6.2）：读取并删除是一个原子命令，并发 / 重放的确认请求里只有一个能拿到值。
 * 这就是确认路径唯一的互斥（agent-safety §3）——不能换成先 GET 再 DEL，那会让两个请求都读到令牌。
 */
public class RedisConfirmationTokenStore implements ConfirmationTokenStore {

  static final String PREFIX = "spark:token:";

  private final StringRedisTemplate redis;
  private final Json json;
  private final Clock clock;

  public RedisConfirmationTokenStore(StringRedisTemplate redis, ObjectMapper mapper, Clock clock) {
    this.redis = redis;
    this.json = new Json(mapper);
    this.clock = clock;
  }

  @Override
  public void put(ConfirmationToken token) {
    Duration ttl = Duration.between(Instant.now(clock), token.expiresAt());
    if (ttl.isNegative() || ttl.isZero()) {
      // 已过期的令牌没必要落库；调用方随后 consume 会得到 empty，与内存实现"存了但过期"的表现一致
      return;
    }
    redis.opsForValue().set(PREFIX + token.token(), json.write(token), ttl);
  }

  @Override
  public Optional<ConfirmationToken> consume(String token) {
    String raw = redis.opsForValue().getAndDelete(PREFIX + token);
    return raw == null ? Optional.empty() : Optional.of(json.read(raw, ConfirmationToken.class));
  }
}
