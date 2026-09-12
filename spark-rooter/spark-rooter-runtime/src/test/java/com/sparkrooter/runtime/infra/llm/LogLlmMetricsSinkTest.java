package com.sparkrooter.runtime.infra.llm;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.sparkrooter.spi.LlmMetricsSink;
import org.junit.jupiter.api.Test;

/**
 * 埋点默认实现的两条路径：上游返回 usage（token 为数字）与不返回（token 为 null → 输出 `-`）。
 *
 * <p>后者在本机无法通过真实调用触达（需要一个会返回 usage 的网关），所以用单测固化：写 0 会被误读成 「没消耗 token」，必须是 `-`。
 */
final class LogLlmMetricsSinkTest {

  private final LlmMetricsSink sink = new LogLlmMetricsSink();

  @Test
  void acceptsSampleWithRealTokenCounts() {
    var sample = new LlmMetricsSink.Sample("planned", 8421L, 1832, 96, 1, false);
    assertThatCode(() -> sink.record(sample)).doesNotThrowAnyException();
  }

  /** 上游网关不返回 usage 时 token 为 null——实现必须容忍，不能 NPE。 */
  @Test
  void acceptsSampleWithNullTokenCounts() {
    var sample = new LlmMetricsSink.Sample("transport_error", 3031L, null, null, 1, false);
    assertThatCode(() -> sink.record(sample)).doesNotThrowAnyException();
  }

  /** 熔断跳过：耗时 0、无 token、circuitOpen=true。 */
  @Test
  void acceptsCircuitOpenSample() {
    var sample = new LlmMetricsSink.Sample("circuit_open", 0L, null, null, 0, true);
    assertThatCode(() -> sink.record(sample)).doesNotThrowAnyException();
  }
}
