package com.sparkrooter.runtime.application;

import static com.sparkrooter.runtime.support.OrchestratorFixture.GET;
import static com.sparkrooter.runtime.support.OrchestratorFixture.plan;
import static com.sparkrooter.runtime.support.OrchestratorFixture.step;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.domain.RunFailure;
import com.sparkrooter.runtime.support.Fakes;
import com.sparkrooter.runtime.support.OrchestratorFixture;
import com.sparkrooter.spi.RunMetricsSink;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Run 埋点覆盖每个出口（feat-runtime-limits-and-metrics G1，评审 S-1）。
 *
 * <p>漏任一出口，成功率指标就失真——而「看起来有监控但数字是错的」比没监控更危险。
 */
final class RunMetricsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 正常完成 → outcome=completed，且带上计划步数。 */
  @Test
  void completedRunIsMetered() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.llm.returns(new LlmClient.Planned(plan(step(1, GET, Map.of("itemId", "10001"), false))));
    f.gateway.on(GET, args -> MAPPER.createObjectNode().put("status", "OPEN"));

    f.start("看看 10001", new Fakes.RecordingSink());

    assertThat(f.runMetrics).hasSize(1);
    RunMetricsSink.Sample s = f.runMetrics.get(0);
    assertThat(s.outcome()).isEqualTo("completed");
    assertThat(s.steps()).as("完成的 Run 应带计划步数").isEqualTo(1);
    assertThat(s.durationMs()).isGreaterThanOrEqualTo(0);
  }

  /** 规划失败 → outcome 是具体的 RunFailureCode，不是笼统的 "failed"。 */
  @Test
  void failedRunIsMeteredWithItsFailureCode() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.llm.throwsFailure(new RunFailure("TOOL_SELECTION_INVALID", "bad draft"));

    f.start("x", new Fakes.RecordingSink());

    assertThat(f.runMetrics).hasSize(1);
    assertThat(f.runMetrics.get(0).outcome())
        .as("outcome 必须是具体错误码，否则看板分不出失败原因")
        .isEqualTo("TOOL_SELECTION_INVALID");
  }

  /** 无计划的出口（无能力）steps=0，不能是随机值。 */
  @Test
  void runWithoutPlanReportsZeroSteps() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.llm.returns(new LlmClient.NoCapability("做不到"));

    f.start("x", new Fakes.RecordingSink());

    assertThat(f.runMetrics).hasSize(1);
    assertThat(f.runMetrics.get(0).steps()).isZero();
  }

  /** 埋点样本不含高基数标识（runId / sessionId / conversationId）。 */
  @Test
  void sampleCarriesNoHighCardinalityIds() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.llm.returns(new LlmClient.NoCapability("做不到"));

    String runId = f.start("x", new Fakes.RecordingSink());

    assertThat(f.runMetrics).hasSize(1);
    assertThat(f.runMetrics.get(0).toString())
        .as("高基数标识会打爆时序库，不得进指标")
        .doesNotContain(runId)
        .doesNotContain("sess")
        .doesNotContain("conv");
  }

  /**
   * 过载（RATE_LIMITED）的用户文案必须与「工具真的失败」区分（阶段 4 评审 F-2）。
   *
   * <p>二者对用户的含义不同：过载稍后重试有意义，工具失败可能反复失败。spec 承诺了 「当前请求较多，请稍后重试」，但实现最初把它归进 TOOL_EXECUTION_FAILED
   * 的通用文案。
   */
  @Test
  void rateLimitedShowsOverloadTextNotGenericToolFailure() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.llm.returns(new LlmClient.Planned(plan(step(1, GET, Map.of("itemId", "10001"), false))));
    f.gateway.fails(GET, com.sparkrooter.contracts.model.ToolInvoke.ErrorCode.RATE_LIMITED);
    Fakes.RecordingSink sink = new Fakes.RecordingSink();

    f.start("看看 10001", sink);

    var failed = sink.data("run.failed");
    assertThat(failed.path("message").asText()).as("过载应提示稍后重试，而非笼统的工具失败").isEqualTo("当前请求较多，请稍后重试");
  }
}
