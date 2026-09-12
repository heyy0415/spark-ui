package com.sparkrooter.runtime.infra.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 调用的熔断器：连续传输失败达阈值后短路，不再发请求。
 *
 * <p><b>解决什么</b>：模型网关挂掉时，每个请求各自等满读超时，`agent-run-*` 线程池很快被占满，系统连 「快速告知用户不可用」都做不到。熔断后第 N+1
 * 个请求立刻失败，线程立即释放。
 *
 * <p><b>只统计传输类失败</b>：模型输出不合规（`TOOL_SELECTION_INVALID`）不是服务不可用，计入会让 「模型能力不足」误触发熔断，把一个能用但不够聪明的模型判成宕机。
 *
 * <p><b>阈值语义</b>：Spring AI 的 `RetryTemplate` 已在 `OpenAiChatModel` 内部重试 3 次，所以这里的 「一次失败」= 3
 * 次真实网关请求。默认阈值 2 意味着约 6 次真实请求后熔断。
 *
 * <p>状态机：CLOSED →（连续失败 ≥ 阈值）→ OPEN →（静默期满）→ HALF_OPEN →（一次成功）→ CLOSED， HALF_OPEN 失败则回 OPEN
 * 并重置静默期。用两个原子变量无锁实现；并发下允许多个请求同时通过 HALF_OPEN 探测（多打几个探测请求无害，比加锁划算）。
 *
 * <p>状态是进程内的：多副本部署时各自独立熔断。单副本下完全有效。
 */
public final class LlmCircuitBreaker {

  private static final Logger log = LoggerFactory.getLogger(LlmCircuitBreaker.class);

  /** OPEN 起始时刻的哨兵值：表示当前不在 OPEN 状态。 */
  private static final long NOT_OPEN = -1L;

  /** 熔断器状态，仅用于日志与测试断言；运行时判定直接看两个原子变量。 */
  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final boolean enabled;
  private final int failureThreshold;
  private final Duration openDuration;
  private final Clock clock;

  private final AtomicInteger consecutiveFailures = new AtomicInteger();
  private final AtomicLong openedAtEpochMs = new AtomicLong(NOT_OPEN);

  public LlmCircuitBreaker(
      boolean enabled, int failureThreshold, Duration openDuration, Clock clock) {
    this.enabled = enabled;
    this.failureThreshold = Math.max(1, failureThreshold);
    this.openDuration = openDuration;
    this.clock = clock;
  }

  /**
   * 当前是否短路（应跳过真实调用）。
   *
   * <p>顺带完成 OPEN → HALF_OPEN 的时间驱动转换：静默期满则放行，让调用方发一个探测请求。
   */
  public boolean shouldSkip() {
    if (!enabled) {
      return false;
    }
    long openedAt = openedAtEpochMs.get();
    if (openedAt == NOT_OPEN) {
      return false;
    }
    long elapsed = Instant.now(clock).toEpochMilli() - openedAt;
    if (elapsed < openDuration.toMillis()) {
      return true;
    }
    // 静默期满：转 HALF_OPEN（清掉 OPEN 标记），本次请求作为探测放行
    if (openedAtEpochMs.compareAndSet(openedAt, NOT_OPEN)) {
      log.info("llm circuit half-open: probing after {}ms", elapsed);
    }
    return false;
  }

  /** 传输类失败：累计并在达阈值时熔断。模型输出不合规不要调用这个方法。 */
  public void recordTransportFailure() {
    if (!enabled) {
      return;
    }
    int failures = consecutiveFailures.incrementAndGet();
    if (failures >= failureThreshold && openedAtEpochMs.get() == NOT_OPEN) {
      openedAtEpochMs.set(Instant.now(clock).toEpochMilli());
      log.warn(
          "llm circuit open: {} consecutive transport failures (threshold {}), skipping calls for {}",
          failures,
          failureThreshold,
          openDuration);
    }
  }

  /** 调用成功：清零失败计数并退出 OPEN。 */
  public void recordSuccess() {
    if (!enabled) {
      return;
    }
    if (consecutiveFailures.getAndSet(0) > 0) {
      log.info("llm circuit closed: call succeeded");
    }
    openedAtEpochMs.set(NOT_OPEN);
  }

  /** 供日志与测试使用；不参与运行时判定。 */
  public State state() {
    if (!enabled) {
      return State.CLOSED;
    }
    if (openedAtEpochMs.get() != NOT_OPEN) {
      return State.OPEN;
    }
    return consecutiveFailures.get() >= failureThreshold ? State.HALF_OPEN : State.CLOSED;
  }
}
