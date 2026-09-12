package com.sparkrooter.starter.metrics;

import com.sparkrooter.spi.LlmMetricsSink;
import com.sparkrooter.spi.RunMetricsSink;
import com.sparkrooter.spi.ToolMetricsSink;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;

/**
 * 三个埋点端口的 Micrometer 实现。
 *
 * <p><b>标签集合硬编码</b>：每个指标的标签在下面逐个写死，不接受运行期传入的动态标签。理由是高基数标签
 * 会打爆时序库——这比没有监控更糟（公司规范明确禁止把用户标识、业务实体标识之类作标签）。 {@code toolId} 与各 outcome 都是有限集合，可安全作标签。
 *
 * <p>本类不捕获异常：调用方（Gateway / Runtime）已各自包了 try-catch，在此重复会掩盖问题来源。
 */
public final class MicrometerMetricsSinks {

  private MicrometerMetricsSinks() {}

  /** 指标名前缀，便于在看板里一次筛出本平台的全部指标。 */
  static final String PREFIX = "spark.";

  /** LLM：请求数 / 耗时 / token。token 是花钱的地方，单独计数。 */
  public static LlmMetricsSink llm(MeterRegistry registry) {
    return sample -> {
      Tags byOutcome = Tags.of("outcome", sample.outcome());
      registry.counter(PREFIX + "llm.requests", byOutcome).increment();
      registry
          .timer(PREFIX + "llm.duration", byOutcome)
          .record(Duration.ofMillis(sample.durationMs()));
      // token 可能为 null（上游网关不返回 usage），此时不计数——计 0 会把平均值算低
      if (sample.promptTokens() != null) {
        registry
            .counter(PREFIX + "llm.tokens", Tags.of("kind", "prompt"))
            .increment(sample.promptTokens());
      }
      if (sample.completionTokens() != null) {
        registry
            .counter(PREFIX + "llm.tokens", Tags.of("kind", "completion"))
            .increment(sample.completionTokens());
      }
    };
  }

  /** 工具：调用数（按 toolId + status）/ 耗时（按 toolId）。 */
  public static ToolMetricsSink tool(MeterRegistry registry) {
    return sample -> {
      registry
          .counter(
              PREFIX + "tool.invocations",
              Tags.of("toolId", sample.toolId(), "status", sample.status()))
          .increment();
      registry
          .timer(PREFIX + "tool.duration", Tags.of("toolId", sample.toolId()))
          .record(Duration.ofMillis(sample.durationMs()));
    };
  }

  /** Run：出口分布（成功率的分母与分子都在这一个指标里）。 */
  public static RunMetricsSink run(MeterRegistry registry) {
    return sample -> {
      Tags byOutcome = Tags.of("outcome", sample.outcome());
      registry.counter(PREFIX + "run.outcomes", byOutcome).increment();
      registry
          .timer(PREFIX + "run.duration", byOutcome)
          .record(Duration.ofMillis(sample.durationMs()));
    };
  }
}
