package com.sparkrooter.runtime.application;

import static com.sparkrooter.runtime.support.OrchestratorFixture.CLOSE;
import static com.sparkrooter.runtime.support.OrchestratorFixture.CONV;
import static com.sparkrooter.runtime.support.OrchestratorFixture.GET;
import static com.sparkrooter.runtime.support.OrchestratorFixture.LIST;
import static com.sparkrooter.runtime.support.OrchestratorFixture.SESSION;
import static com.sparkrooter.runtime.support.OrchestratorFixture.TRACE;
import static com.sparkrooter.runtime.support.OrchestratorFixture.plan;
import static com.sparkrooter.runtime.support.OrchestratorFixture.step;
import static com.sparkrooter.runtime.support.TestFixtures.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.domain.RunState;
import com.sparkrooter.runtime.support.Fakes;
import com.sparkrooter.runtime.support.OrchestratorFixture;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 编排器确认路径（T07b，agent-safety §3）：令牌签发 / 会话校验 / 重放 / 重校验 + 可信参数 / fail-closed。 */
final class RunOrchestratorConfirmTest {

  private static final Map<String, String> ARGS = Map.of("itemId", "10001");

  /** 走到 WAITING_CONFIRMATION：GET 前置执行完，出确认屏，签发令牌，连接关闭。 */
  private static String startUntilWaiting(
      OrchestratorFixture f, Fakes.RecordingSink sink, String status) {
    f.llm.returns(
        new LlmClient.Planned(plan(step(1, GET, ARGS, false), step(2, CLOSE, ARGS, true))));
    f.gateway.on(
        GET,
        args ->
            MAPPER
                .createObjectNode()
                .put("itemId", "10001")
                .put("status", status)
                .put("amount", "9.50"));
    f.gateway.on(CLOSE, args -> MAPPER.createObjectNode().put("closed", true));
    return f.start("关闭 10001", sink);
  }

  private static String tokenOf(Fakes.RecordingSink sink) {
    JsonNode actions = sink.data("ui.replace").path("ui").path("actions");
    for (JsonNode a : actions) {
      if ("submit".equals(a.path("type").asText())) {
        return a.path("confirmationToken").asText();
      }
    }
    throw new AssertionError("no submit action");
  }

  // ⑤ 需确认步骤 → 确认屏 + 令牌 + 等待
  @Test
  void confirmationStepStopsWithScreenTokenAndWaitingState() {
    OrchestratorFixture f = new OrchestratorFixture();
    Fakes.RecordingSink sink = new Fakes.RecordingSink();

    String runId = startUntilWaiting(f, sink, "OPEN");

    assertThat(sink.names())
        .containsExactly(
            "run.started",
            "tool.selected",
            "tool.started",
            "tool.completed",
            "ui.replace",
            "confirmation.required");
    assertThat(f.gateway.calledToolIds()).containsExactly(GET); // 目标工具未执行
    JsonNode ui = sink.data("ui.replace").path("ui");
    assertThat(ui.path("components"))
        .extracting(c -> c.path("type").asText())
        .containsExactly("Card", "Form");
    String token = tokenOf(sink);
    assertThat(token).startsWith("ct_").isNotEqualTo("ct_" + "0".repeat(32)); // 占位令牌绝不下发
    JsonNode required = sink.data("confirmation.required");
    assertThat(required.path("actionId").asText()).isEqualTo("confirm-close");
    assertThat(required.path("expiresAt").asText())
        .isEqualTo(OrchestratorFixture.NOW.plusSeconds(600).toString());
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.WAITING_CONFIRMATION);
    assertThat(sink.closed).isEqualTo(1);
  }

  // ⑥ sessionId 不符 → 拒绝本次请求，Run 状态不变
  @Test
  void confirmFromAnotherSessionIsRejectedWithoutChangingRun() {
    OrchestratorFixture f = new OrchestratorFixture();
    Fakes.RecordingSink first = new Fakes.RecordingSink();
    String runId = startUntilWaiting(f, first, "OPEN");
    String token = tokenOf(first);

    Fakes.RecordingSink sink = new Fakes.RecordingSink();
    f.orchestrator.confirm(
        runId, "confirm-close", token, Map.of("reason", "DAMAGED"), "other-session", TRACE, sink);

    assertThat(sink.names()).containsExactly("run.failed");
    assertThat(sink.data("run.failed").path("code").asText()).isEqualTo("CONFIRMATION_REJECTED");
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.WAITING_CONFIRMATION);
    assertThat(f.gateway.calledToolIds()).containsExactly(GET);
    // 令牌未被消费：原会话仍可确认
    Fakes.RecordingSink retry = new Fakes.RecordingSink();
    f.orchestrator.confirm(
        runId, "confirm-close", token, Map.of("reason", "DAMAGED"), SESSION, TRACE, retry);
    assertThat(retry.names()).endsWith("run.completed");
  }

  // ⑦ 正确令牌 → 重校验 → 目标执行（trustedArgs 覆盖）→ 结果屏 → 完成
  @Test
  void validConfirmationRechecksThenExecutesWithTrustedArgs() {
    OrchestratorFixture f = new OrchestratorFixture();
    Fakes.RecordingSink first = new Fakes.RecordingSink();
    String runId = startUntilWaiting(f, first, "OPEN");
    String token = tokenOf(first);

    Fakes.RecordingSink sink = new Fakes.RecordingSink();
    f.orchestrator.confirm(
        runId, "confirm-close", token, Map.of("reason", "DAMAGED"), SESSION, TRACE, sink);

    assertThat(sink.names())
        .containsExactly(
            "tool.selected",
            "tool.started",
            "tool.completed", // recheck: GET
            "tool.selected",
            "tool.started",
            "tool.completed", // target: CLOSE
            "ui.replace",
            "run.completed");
    assertThat(f.gateway.calledToolIds()).containsExactly(GET, GET, CLOSE);
    var closeCall = f.gateway.calls.get(2);
    assertThat(closeCall.arguments().path("itemId").asText()).isEqualTo("10001");
    assertThat(closeCall.arguments().path("reason").asText()).isEqualTo("DAMAGED"); // formData 合并
    assertThat(closeCall.arguments().path("amount").asText()).isEqualTo("9.50"); // 可信参数来自重校验输出
    assertThat(closeCall.executionContext().idempotencyKey()).isEqualTo(runId + "-" + CLOSE + "-2");
    assertThat(f.gateway.calls.get(1).executionContext().idempotencyKey())
        .isEqualTo(runId + "-" + GET + "-recheck");
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.COMPLETED);
    assertThat(f.memory.find(SESSION, CONV)).isPresent();
    assertThat(sink.closed).isEqualTo(1);
  }

  // ⑦' 重校验拒绝 → CONFIRMATION_REJECTED 且策略文案，目标不执行
  @Test
  void recheckRejectionFailsRunWithPolicyText() {
    OrchestratorFixture f = new OrchestratorFixture();
    Fakes.RecordingSink first = new Fakes.RecordingSink();
    String runId = startUntilWaiting(f, first, "OPEN");
    String token = tokenOf(first);
    // 确认期间状态变化：重校验读到 CLOSED
    f.gateway.on(
        GET, args -> MAPPER.createObjectNode().put("itemId", "10001").put("status", "CLOSED"));

    Fakes.RecordingSink sink = new Fakes.RecordingSink();
    f.orchestrator.confirm(
        runId, "confirm-close", token, Map.of("reason", "DAMAGED"), SESSION, TRACE, sink);

    assertThat(sink.names()).endsWith("run.failed");
    assertThat(sink.data("run.failed").path("code").asText()).isEqualTo("CONFIRMATION_REJECTED");
    assertThat(sink.data("run.failed").path("message").asText()).isEqualTo("对象状态已变化，本次操作未执行");
    assertThat(f.gateway.calledToolIds()).containsExactly(GET, GET);
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.FAILED);
  }

  // ⑧ 同一令牌二次确认 → 拒绝且状态不变（已 COMPLETED 的 Run 不会被打成 FAILED）
  @Test
  void replayedTokenIsRejectedAndDoesNotAlterRun() {
    OrchestratorFixture f = new OrchestratorFixture();
    Fakes.RecordingSink first = new Fakes.RecordingSink();
    String runId = startUntilWaiting(f, first, "OPEN");
    String token = tokenOf(first);
    f.orchestrator.confirm(
        runId,
        "confirm-close",
        token,
        Map.of("reason", "DAMAGED"),
        SESSION,
        TRACE,
        new Fakes.RecordingSink());
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.COMPLETED);

    Fakes.RecordingSink sink = new Fakes.RecordingSink();
    f.orchestrator.confirm(
        runId, "confirm-close", token, Map.of("reason", "DAMAGED"), SESSION, TRACE, sink);

    assertThat(sink.names()).containsExactly("run.failed");
    assertThat(sink.data("run.failed").path("code").asText()).isEqualTo("CONFIRMATION_REJECTED");
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.COMPLETED);
    assertThat(f.gateway.calledToolIds()).containsExactly(GET, GET, CLOSE);
  }

  // ⑧' 伪造 / 未知令牌在等待态 → 拒绝且仍可用真令牌确认
  @Test
  void forgedTokenIsRejectedWhileWaitingAndRealTokenStillWorks() {
    OrchestratorFixture f = new OrchestratorFixture();
    Fakes.RecordingSink first = new Fakes.RecordingSink();
    String runId = startUntilWaiting(f, first, "OPEN");

    Fakes.RecordingSink forged = new Fakes.RecordingSink();
    f.orchestrator.confirm(
        runId, "confirm-close", "ct_" + "f".repeat(32), Map.of(), SESSION, TRACE, forged);
    assertThat(forged.data("run.failed").path("code").asText()).isEqualTo("CONFIRMATION_REJECTED");
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.WAITING_CONFIRMATION);

    Fakes.RecordingSink real = new Fakes.RecordingSink();
    f.orchestrator.confirm(
        runId, "confirm-close", tokenOf(first), Map.of("reason", "DAMAGED"), SESSION, TRACE, real);
    assertThat(real.names()).endsWith("run.completed");
  }

  // ⑧'' formData 键不在白名单 → 令牌被消费并拒绝，Run 失败（防前端注入 amount）
  @Test
  void formDataOutsideWhitelistIsRejected() {
    OrchestratorFixture f = new OrchestratorFixture();
    Fakes.RecordingSink first = new Fakes.RecordingSink();
    String runId = startUntilWaiting(f, first, "OPEN");

    Fakes.RecordingSink sink = new Fakes.RecordingSink();
    f.orchestrator.confirm(
        runId,
        "confirm-close",
        tokenOf(first),
        Map.of("reason", "DAMAGED", "amount", "0.01"),
        SESSION,
        TRACE,
        sink);

    assertThat(sink.data("run.failed").path("code").asText()).isEqualTo("CONFIRMATION_REJECTED");
    assertThat(f.gateway.calledToolIds()).containsExactly(GET);
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.FAILED);
  }

  // ⑫ 确认后缺 ConfirmationRecheck → INTERNAL_ERROR（fail-closed）
  @Test
  void missingRecheckFailsClosedAfterTokenConsumed() {
    OrchestratorFixture f =
        new OrchestratorFixture(
            List.of(new Fakes.FakeScreens(Set.of(LIST, GET, CLOSE), Set.of(CLOSE))),
            List.of(), // 无重校验
            true);
    Fakes.RecordingSink first = new Fakes.RecordingSink();
    String runId = startUntilWaiting(f, first, "OPEN");
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.WAITING_CONFIRMATION);

    Fakes.RecordingSink sink = new Fakes.RecordingSink();
    f.orchestrator.confirm(
        runId, "confirm-close", tokenOf(first), Map.of("reason", "DAMAGED"), SESSION, TRACE, sink);

    assertThat(sink.names()).containsExactly("run.failed");
    assertThat(sink.data("run.failed").path("code").asText()).isEqualTo("INTERNAL_ERROR");
    assertThat(f.gateway.calledToolIds()).containsExactly(GET); // 目标工具未执行
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.FAILED);
  }

  // 附：未知 runId 的确认 → RunNotFound
  @Test
  void confirmUnknownRunThrowsRunNotFound() {
    OrchestratorFixture f = new OrchestratorFixture();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                f.orchestrator.confirm(
                    "run_nope",
                    "confirm-close",
                    "ct_x",
                    Map.of(),
                    SESSION,
                    TRACE,
                    new Fakes.RecordingSink()))
        .isInstanceOf(RunOrchestrator.RunNotFound.class);
  }
}
