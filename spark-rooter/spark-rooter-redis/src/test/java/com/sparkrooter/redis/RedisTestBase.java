package com.sparkrooter.redis;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 测试的公共底座：本机 6379 通才跑（{@link EnabledIf} 用），用 db 15 隔离并在每个测试前清空。
 *
 * <p>为什么不用 Testcontainers：本仓的门禁在无 Docker daemon 的机器上也要能跑（实测本机就是），且 Redis 单二进制随处可得。 跳过时 surefire
 * 报告里会有 skipped 计数，CI 摘要能看见"Redis 契约测试没跑"，不会误以为全绿。
 */
abstract class RedisTestBase {

  static final int DB = 15;

  static boolean redisAvailable() {
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress("127.0.0.1", 6379), 300);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  static StringRedisTemplate template() {
    RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration("127.0.0.1", 6379);
    cfg.setDatabase(DB);
    LettuceConnectionFactory f = new LettuceConnectionFactory(cfg);
    f.afterPropertiesSet();
    StringRedisTemplate t = new StringRedisTemplate(f);
    t.afterPropertiesSet();
    return t;
  }

  /** 每个测试用独立前缀而不是 FLUSHDB：并行跑的测试类不互相清库。 */
  static String scope() {
    return "t-" + UUID.randomUUID().toString().substring(0, 8);
  }

  static final Duration SHORT = Duration.ofSeconds(30);
}
