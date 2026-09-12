package com.sparkrooter.runtime.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 熔断器状态机。用可推进的固定时钟测时间驱动的转换，不靠 sleep。
 *
 * <p>最关键的一条是 {@link #validationFailuresDoNotOpenTheCircuit()}：模型输出不合规不该被当成服务不可用，
 * 否则一个能用但不够聪明的模型会被判成宕机。
 */
final class LlmCircuitBreakerTest {

  private static final Duration OPEN_FOR = Duration.ofSeconds(30);

  /** 可推进的时钟：熔断器只读 Instant.now(clock)，推进它即模拟时间流逝。 */
  private static final class MovableClock extends Clock {
    private Instant now = Instant.parse("2026-09-12T00:00:00Z");

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }
  }

  private MovableClock clock = new MovableClock();

  private LlmCircuitBreaker breaker(int threshold) {
    return new LlmCircuitBreaker(true, threshold, OPEN_FOR, clock);
  }

  @Test
  void closedInitiallyAndAllowsCalls() {
    LlmCircuitBreaker cb = breaker(2);
    assertThat(cb.shouldSkip()).isFalse();
    assertThat(cb.state()).isEqualTo(LlmCircuitBreaker.State.CLOSED);
  }

  @Test
  void opensAfterConsecutiveTransportFailuresReachThreshold() {
    LlmCircuitBreaker cb = breaker(2);

    cb.recordTransportFailure();
    assertThat(cb.shouldSkip()).as("阈值未达到，仍应放行").isFalse();

    cb.recordTransportFailure();
    assertThat(cb.state()).isEqualTo(LlmCircuitBreaker.State.OPEN);
    assertThat(cb.shouldSkip()).as("达到阈值后短路").isTrue();
  }

  @Test
  void successResetsFailureCountSoCircuitStaysClosed() {
    LlmCircuitBreaker cb = breaker(2);
    cb.recordTransportFailure();
    cb.recordSuccess();
    // 计数已清零：再来一次失败不足以达到阈值 2
    cb.recordTransportFailure();
    assertThat(cb.shouldSkip()).isFalse();
    assertThat(cb.state()).isEqualTo(LlmCircuitBreaker.State.CLOSED);
  }

  @Test
  void staysOpenDuringSilenceThenProbesAfterOpenDuration() {
    LlmCircuitBreaker cb = breaker(1);
    cb.recordTransportFailure();
    assertThat(cb.shouldSkip()).isTrue();

    clock.advance(Duration.ofSeconds(29));
    assertThat(cb.shouldSkip()).as("静默期未满，继续短路").isTrue();

    clock.advance(Duration.ofSeconds(2));
    assertThat(cb.shouldSkip()).as("静默期满，放行一个探测请求").isFalse();
  }

  @Test
  void probeSuccessClosesTheCircuit() {
    LlmCircuitBreaker cb = breaker(1);
    cb.recordTransportFailure();
    clock.advance(OPEN_FOR.plusSeconds(1));
    assertThat(cb.shouldSkip()).isFalse(); // 探测放行

    cb.recordSuccess();
    assertThat(cb.state()).isEqualTo(LlmCircuitBreaker.State.CLOSED);
    assertThat(cb.shouldSkip()).isFalse();
  }

  @Test
  void probeFailureReopensTheCircuitAndRestartsSilence() {
    LlmCircuitBreaker cb = breaker(1);
    cb.recordTransportFailure();
    clock.advance(OPEN_FOR.plusSeconds(1));
    assertThat(cb.shouldSkip()).isFalse(); // 探测放行

    cb.recordTransportFailure(); // 探测也失败
    assertThat(cb.state()).isEqualTo(LlmCircuitBreaker.State.OPEN);
    assertThat(cb.shouldSkip()).isTrue();

    // 静默期从重新打开的时刻起算，而不是沿用第一次
    clock.advance(Duration.ofSeconds(29));
    assertThat(cb.shouldSkip()).isTrue();
  }

  /**
   * 模型输出不合规不计入熔断。
   *
   * <p>这是熔断器与 {@code LlmPlanner} 的契约：只有 catch 到传输类异常时才调 recordTransportFailure，
   * 校验失败（TOOL_SELECTION_INVALID）走的是另一条路径，根本不碰熔断器。这里用「不调用即不计数」来固化该约定。
   */
  @Test
  void validationFailuresDoNotOpenTheCircuit() {
    LlmCircuitBreaker cb = breaker(2);
    // 模拟连续多次「模型应答了但输出不合规」：planner 会 recordSuccess（传输通路正常）而不是记失败
    for (int i = 0; i < 10; i++) {
      cb.recordSuccess();
    }
    assertThat(cb.shouldSkip()).isFalse();
    assertThat(cb.state()).isEqualTo(LlmCircuitBreaker.State.CLOSED);
  }

  @Test
  void disabledBreakerNeverSkips() {
    LlmCircuitBreaker cb = new LlmCircuitBreaker(false, 1, OPEN_FOR, clock);
    cb.recordTransportFailure();
    cb.recordTransportFailure();
    assertThat(cb.shouldSkip()).isFalse();
    assertThat(cb.state()).isEqualTo(LlmCircuitBreaker.State.CLOSED);
  }

  /** 阈值 0 或负数应被当作 1，避免配置错误导致「第一次成功就熔断」这种荒谬行为。 */
  @Test
  void thresholdIsClampedToAtLeastOne() {
    LlmCircuitBreaker cb = new LlmCircuitBreaker(true, 0, OPEN_FOR, clock);
    assertThat(cb.shouldSkip()).isFalse();
    cb.recordTransportFailure();
    assertThat(cb.shouldSkip()).isTrue();
  }
}
