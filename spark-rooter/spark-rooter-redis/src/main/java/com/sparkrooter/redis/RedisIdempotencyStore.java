package com.sparkrooter.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkrooter.contracts.model.ToolInvoke;
import com.sparkrooter.gateway.domain.IdempotencyStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 幂等表的 Redis 实现：key {@code spark:idem:{scope}/{idempotencyKey}}。
 *
 * <p>状态机与内存实现一致，用值区分："CLAIMING" = 占位中，其他 = 已完成的响应 JSON：
 *
 * <ul>
 *   <li>{@code claim}：{@code SET NX PX claimTtl} 写 CLAIMING → Owner；已有值且不是 CLAIMING → Replay；是
 *       CLAIMING → Awaiting。
 *   <li>{@code complete}：覆盖为响应 JSON，TTL 换成 {@code resultTtl}（幂等重放窗口）。
 *   <li>{@code release}：Lua「值仍是 CLAIMING 才 DEL」，不会误删已完成的结果。
 * </ul>
 *
 * <p><b>Awaiting 的 future 靠轮询完成</b>（每 {@value #POLL_MS} ms GET 一次）：Redis 没有"值变化时通知本进程"的廉价原语，
 * 而等待方只出现在「同 key 并发提交」这一异常路径上，正常链路零轮询。轮询看到两种变化：值变成响应 → 正常完成；值变成 nil（owner release 或崩溃后 key 过期）→
 * 异常完成，让调用方重新 claim——与内存实现里 release 唤醒等待者的语义对齐。
 *
 * <p>claim key 的 TTL = 工具超时 + 余量：Gateway 的 {@code f.get(timeoutMs)} 超时后会 release，正常路径 owner 总在 key
 * 过期前动作；余量防"owner 刚 complete 而 key 先过期"的竞态。
 */
public class RedisIdempotencyStore implements IdempotencyStore {

  private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);

  static final String PREFIX = "spark:idem:";
  static final String CLAIMING = "CLAIMING";
  static final long POLL_MS = 50;

  /** 只删仍是占位的 key；已完成的结果必须保留，否则重放失效。 */
  private static final DefaultRedisScript<Long> RELEASE_IF_CLAIMING =
      new DefaultRedisScript<>(
          "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end"
              + " return 0",
          Long.class);

  private final StringRedisTemplate redis;
  private final Json json;
  private final Duration claimTtl;
  private final Duration resultTtl;
  private final ScheduledExecutorService poller;

  /**
   * @param claimTtl 占位 key 的 TTL；应 ≥ 工具最长超时 + 余量
   * @param resultTtl 已完成结果的保留窗口（spark.gateway.idempotency-ttl）
   * @param poller 等待方轮询用的调度器；由装配方提供并负责关闭
   */
  public RedisIdempotencyStore(
      StringRedisTemplate redis,
      ObjectMapper mapper,
      Duration claimTtl,
      Duration resultTtl,
      ScheduledExecutorService poller) {
    this.redis = redis;
    this.json = new Json(mapper);
    this.claimTtl = claimTtl;
    this.resultTtl = resultTtl;
    this.poller = poller;
  }

  private static String key(String scope, String idem) {
    return PREFIX + scope + "/" + idem;
  }

  @Override
  public Claim claim(String scope, String idempotencyKey) {
    String k = key(scope, idempotencyKey);
    Boolean acquired = redis.opsForValue().setIfAbsent(k, CLAIMING, claimTtl);
    if (Boolean.TRUE.equals(acquired)) {
      return new Claim.Owner();
    }
    String current = redis.opsForValue().get(k);
    if (current == null) {
      // SET NX 失败与 GET 之间 key 过期了：再试一次占位
      Boolean retry = redis.opsForValue().setIfAbsent(k, CLAIMING, claimTtl);
      if (Boolean.TRUE.equals(retry)) {
        return new Claim.Owner();
      }
      current = redis.opsForValue().get(k);
      if (current == null) {
        // 极端竞态：再让等待方走轮询路径，它会在下一轮 GET 到 nil 并重 claim
        return new Claim.Awaiting(pollUntilSettled(k));
      }
    }
    if (!CLAIMING.equals(current)) {
      return new Claim.Replay(json.read(current, ToolInvoke.Response.class));
    }
    return new Claim.Awaiting(pollUntilSettled(k));
  }

  @Override
  public void complete(String scope, String idempotencyKey, ToolInvoke.Response response) {
    redis.opsForValue().set(key(scope, idempotencyKey), json.write(response), resultTtl);
  }

  @Override
  public void release(String scope, String idempotencyKey) {
    redis.execute(RELEASE_IF_CLAIMING, List.of(key(scope, idempotencyKey)), CLAIMING);
  }

  @Override
  public Optional<ToolInvoke.Response> find(String scope, String idempotencyKey) {
    String current = redis.opsForValue().get(key(scope, idempotencyKey));
    if (current == null || CLAIMING.equals(current)) {
      return Optional.empty();
    }
    return Optional.of(json.read(current, ToolInvoke.Response.class));
  }

  /** 轮询直到值不再是 CLAIMING：响应 → 正常完成；nil → 异常完成（调用方重 claim）。 */
  private CompletableFuture<ToolInvoke.Response> pollUntilSettled(String k) {
    CompletableFuture<ToolInvoke.Response> f = new CompletableFuture<>();
    poller.schedule(() -> poll(k, f), POLL_MS, TimeUnit.MILLISECONDS);
    return f;
  }

  private void poll(String k, CompletableFuture<ToolInvoke.Response> f) {
    if (f.isDone()) {
      return; // 调用方已放弃（超时），不再轮询
    }
    try {
      String current = redis.opsForValue().get(k);
      if (current == null) {
        f.completeExceptionally(new IllegalStateException("idempotency claim released"));
        return;
      }
      if (!CLAIMING.equals(current)) {
        f.complete(json.read(current, ToolInvoke.Response.class));
        return;
      }
      poller.schedule(() -> poll(k, f), POLL_MS, TimeUnit.MILLISECONDS);
    } catch (RuntimeException e) {
      log.warn("idempotency poll failed key={}", k, e);
      f.completeExceptionally(e);
    }
  }
}
