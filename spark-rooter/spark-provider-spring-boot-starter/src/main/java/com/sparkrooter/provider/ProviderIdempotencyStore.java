package com.sparkrooter.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * provider 侧的幂等结果缓存（feat-provider-http-transport §3.1a.1）。
 *
 * <p>存在的理由：hub 的 {@code IdempotencyStore} 只覆盖 <b>hub 侧重复发起</b>，覆盖不到网络重传与 hub 重试——那两种情况下请求是真的第二次到达
 * provider。跨进程后若 provider 侧没有这一层， Manifest 上的 {@code idempotency=required} 就只是一句声明，没人兑现。
 *
 * <p>键是 {@code sessionId + idempotencyKey}：与 hub 侧同一口径，便于两侧日志比对。
 *
 * <p><b>默认实现是进程内的</b>，因此只在单实例或粘性路由下完全有效；多实例部署时 hub 的重试可能落到 另一个 provider 实例上而绕过本缓存。宿主要强一致就定义同类型 Bean
 * 换成 Redis 等共享存储 （与其他 spi 端口一致的替换方式）。这个限制必须显式写出来，而不是让人以为跨进程幂等已经万无一失。
 */
public class ProviderIdempotencyStore {

  /** 容量上限：超出即整体清空。防止长期运行的 provider 无界增长（简单策略，够用且可预测）。 */
  private static final int MAX_ENTRIES = 10_000;

  private final Map<String, JsonNode> results = new ConcurrentHashMap<>();
  private final AtomicLong evictions = new AtomicLong();

  /** 已缓存的结果，无则 null。 */
  public JsonNode find(String sessionId, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return null;
    }
    return results.get(key(sessionId, idempotencyKey));
  }

  /** 记住一次成功执行的结果。只记成功：失败可以重试，不该被缓存成"终态"。 */
  public void remember(String sessionId, String idempotencyKey, JsonNode output) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return;
    }
    if (results.size() >= MAX_ENTRIES) {
      results.clear();
      evictions.incrementAndGet();
    }
    results.put(key(sessionId, idempotencyKey), output);
  }

  /** 清空次数，供监控与自检。 */
  public long evictions() {
    return evictions.get();
  }

  public int size() {
    return results.size();
  }

  private static String key(String sessionId, String idempotencyKey) {
    return sessionId + "|" + idempotencyKey;
  }
}
