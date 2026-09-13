package com.sparkrooter.runtime.application;

import static com.sparkrooter.runtime.support.OrchestratorFixture.CLOSE;
import static com.sparkrooter.runtime.support.OrchestratorFixture.CONV;
import static com.sparkrooter.runtime.support.OrchestratorFixture.GET;
import static com.sparkrooter.runtime.support.OrchestratorFixture.LIST;
import static com.sparkrooter.runtime.support.OrchestratorFixture.SESSION;
import static com.sparkrooter.runtime.support.OrchestratorFixture.plan;
import static com.sparkrooter.runtime.support.OrchestratorFixture.step;
import static com.sparkrooter.runtime.support.TestFixtures.ENTITY;
import static com.sparkrooter.runtime.support.TestFixtures.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.domain.RunFailure;
import com.sparkrooter.runtime.domain.RunState;
import com.sparkrooter.runtime.support.Fakes;
import com.sparkrooter.runtime.support.OrchestratorFixture;
import com.sparkrooter.spi.ConversationMemory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** 编排器非确认路径（T07a）：无候选 / 无能力 / 澄清 / 只读执行 / 规划失败 / fail-closed / argsDigest。 */
final class RunOrchestratorTest {

  // ① 无候选
  @Test
  void noCandidatesEndsWithNoCapabilityText() {
    OrchestratorFixture f = new OrchestratorFixture(List.of(), List.of(), false);
    Fakes.RecordingSink sink = new Fakes.RecordingSink();

    String runId = f.start("hello", sink);

    assertThat(sink.names()).containsExactly("run.started", "message.delta", "run.completed");
    assertThat(sink.data("message.delta").path("text").asText()).isEqualTo("当前没有可用能力处理该请求");
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.COMPLETED);
    assertThat(f.llm.requests).isEmpty();
    assertThat(sink.closed).isEqualTo(1);
  }

  // ② NoCapability
  @Test
  void noCapabilityDecisionReplaysModelReply() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.llm.returns(new LlmClient.NoCapability("做不到"));
    Fakes.RecordingSink sink = new Fakes.RecordingSink();

    f.start("帮我订机票", sink);

    assertThat(sink.names()).containsExactly("run.started", "message.delta", "run.completed");
    assertThat(sink.data("message.delta").path("text").asText()).isEqualTo("做不到");
    assertThat(f.gateway.calls).isEmpty();
    // 候选全部交给模型，且按 sessionId 查 Registry
    assertThat(f.llm.requests)
        .singleElement()
        .satisfies(r -> assertThat(r.candidates()).hasSize(3));
    assertThat(f.registryClient.sessionIds).containsExactly(SESSION);
  }

  // ③ Clarify 无 clarifier → reply
  @Test
  void clarifyWithoutClarifierFallsBackToReply() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.llm.returns(new LlmClient.Clarify("galaxy", "请问哪一个？"));
    Fakes.RecordingSink sink = new Fakes.RecordingSink();

    f.start("看看详情", sink);

    assertThat(sink.names()).containsExactly("run.started", "message.delta", "run.completed");
    assertThat(sink.data("message.delta").path("text").asText()).isEqualTo("请问哪一个？");
    assertThat(f.gateway.calls).isEmpty();
  }

  // ③' Clarify 有 clarifier → 澄清表 + 记忆挂起原话
  @Test
  void clarifyWithClarifierShowsTableAndRemembersRows() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.llm.returns(new LlmClient.Clarify(ENTITY, "请选择"));
    f.gateway.on(
        LIST,
        args -> {
          ObjectNode out = MAPPER.createObjectNode();
          var items = out.putArray("items");
          items.addObject().put("itemId", "10001").put("status", "OPEN");
          items.addObject().put("itemId", "10002").put("status", "OPEN");
          return out;
        });
    Fakes.RecordingSink sink = new Fakes.RecordingSink();

    String runId = f.start("关闭条目", sink);

    assertThat(sink.names())
        .containsExactly(
            "run.started",
            "tool.selected",
            "tool.started",
            "tool.completed",
            "ui.replace",
            "message.delta",
            "run.completed");
    assertThat(f.gateway.calledToolIds()).containsExactly(LIST);
    var ui = sink.data("ui.replace").path("ui");
    assertThat(ui.path("components").get(0).path("type").asText()).isEqualTo("Table");
    assertThat(ui.path("components").get(0).path("props").path("rows")).hasSize(2);
    assertThat(sink.data("message.delta").path("text").asText()).contains("请选择要操作的条目");
    // 澄清屏计入记忆：最近列表行 + 挂起原话，供下一轮「第二个」
    ConversationMemory.Memory m = f.memory.find(SESSION, CONV).orElseThrow();
    assertThat(m.lastTable().rowIds()).containsExactly("10001", "10002");
    assertThat(m.lastTable().pendingMessage()).isEqualTo("关闭条目");
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.COMPLETED);
  }

  // ④ 单只读步骤
  @Test
  void singleReadOnlyStepEmitsToolEventsUiAndRemembersEntity() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.llm.returns(new LlmClient.Planned(plan(step(1, GET, Map.of("itemId", "10001"), false))));
    f.gateway.on(
        GET,
        args ->
            MAPPER
                .createObjectNode()
                .put("itemId", args.path("itemId").asText())
                .put("status", "OPEN"));
    Fakes.RecordingSink sink = new Fakes.RecordingSink();

    String runId = f.start("看看 10001", sink);

    assertThat(sink.names())
        .containsExactly(
            "run.started",
            "tool.selected",
            "tool.started",
            "tool.completed",
            "ui.replace",
            "run.completed");
    assertThat(sink.data("tool.selected").path("displayName").asText()).isEqualTo("查看条目");
    assertThat(sink.data("tool.completed").path("status").asText()).isEqualTo("succeeded");
    assertThat(sink.data("run.started").path("conversationId").asText()).isEqualTo(CONV);
    // Gateway 请求：参数按 inputSchema 类型化、幂等键 = runId-toolId-seq、sessionId 透传
    var req = f.gateway.calls.get(0);
    assertThat(req.arguments().path("itemId").asText()).isEqualTo("10001");
    assertThat(req.executionContext().idempotencyKey()).isEqualTo(runId + "-" + GET + "-1");
    assertThat(req.executionContext().sessionId()).isEqualTo(SESSION);
    // 结果屏来自领域 ScreenBuilder，且可经 lastUi 取回
    assertThat(sink.data("ui.replace").path("ui").path("components").get(0).path("type").asText())
        .isEqualTo("Card");
    assertThat(f.orchestrator.lastUi(runId)).isPresent();
    // 成功终态写记忆：实体类型 → ID
    assertThat(f.memory.find(SESSION, CONV))
        .map(ConversationMemory.Memory::entities)
        .contains(Map.of(ENTITY, "10001"));
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.COMPLETED);
  }

  // ④' 列表工具结果屏是 Table → 记忆 lastTable 行 ID
  @Test
  void tableResultRemembersRowIdsForOrdinalReference() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.llm.returns(new LlmClient.Planned(plan(step(1, LIST, Map.of("limit", "20"), false))));
    f.gateway.on(
        LIST,
        args -> {
          ObjectNode out = MAPPER.createObjectNode();
          out.putArray("items").addObject().put("itemId", "10005");
          return out;
        });
    Fakes.RecordingSink sink = new Fakes.RecordingSink();

    f.start("列出条目", sink);

    assertThat(f.gateway.calls.get(0).arguments().path("limit").isIntegralNumber()).isTrue();
    ConversationMemory.Memory m = f.memory.find(SESSION, CONV).orElseThrow();
    assertThat(m.lastTable().toolId()).isEqualTo(LIST);
    assertThat(m.lastTable().rowIds()).containsExactly("10005");
  }

  // ⑨ 规划失败
  @Test
  void plannerFailureBecomesRunFailedWithCode() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.llm.throwsFailure(new RunFailure("TOOL_SELECTION_INVALID", "two bad drafts"));
    Fakes.RecordingSink sink = new Fakes.RecordingSink();

    String runId = f.start("x", sink);

    assertThat(sink.names()).containsExactly("run.started", "run.failed");
    var failed = sink.data("run.failed");
    assertThat(failed.path("code").asText()).isEqualTo("TOOL_SELECTION_INVALID");
    assertThat(failed.path("message").asText()).isEqualTo("暂时无法为该请求制定可执行的方案");
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.FAILED);
    assertThat(f.runs.find(runId).flatMap(r -> r.failureCode())).contains("TOOL_SELECTION_INVALID");
    // 失败的 Run 不写记忆
    assertThat(f.memory.find(SESSION, CONV)).isEmpty();
    assertThat(sink.closed).isEqualTo(1);
  }

  // ⑨' 模型不可用：userText 透传给用户
  @Test
  void plannerUserTextIsShownVerbatim() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.llm.throwsFailure(
        RunFailure.withUserText("INTERNAL_ERROR", "llm not configured", "未配置模型，无法理解请求"));
    Fakes.RecordingSink sink = new Fakes.RecordingSink();

    f.start("x", sink);

    assertThat(sink.data("run.failed").path("message").asText()).isEqualTo("未配置模型，无法理解请求");
    assertThat(sink.data("run.failed").path("code").asText()).isEqualTo("INTERNAL_ERROR");
  }

  // ⑨'' 工具执行失败
  @Test
  void toolFailureBecomesToolExecutionFailed() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.llm.returns(new LlmClient.Planned(plan(step(1, GET, Map.of("itemId", "10001"), false))));
    f.gateway.fails(GET, com.sparkrooter.contracts.model.ToolInvoke.ErrorCode.HANDLER_ERROR);
    Fakes.RecordingSink sink = new Fakes.RecordingSink();

    f.start("看看 10001", sink);

    assertThat(sink.names())
        .containsExactly(
            "run.started", "tool.selected", "tool.started", "tool.completed", "run.failed");
    assertThat(sink.data("tool.completed").path("status").asText()).isEqualTo("failed");
    assertThat(sink.data("run.failed").path("code").asText()).isEqualTo("TOOL_EXECUTION_FAILED");
  }

  // ⑨''' 输出不合契约
  @Test
  void outputInvalidFromGatewayMapsToToolOutputInvalid() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.llm.returns(new LlmClient.Planned(plan(step(1, GET, Map.of("itemId", "10001"), false))));
    f.gateway.fails(GET, com.sparkrooter.contracts.model.ToolInvoke.ErrorCode.OUTPUT_INVALID);
    Fakes.RecordingSink sink = new Fakes.RecordingSink();

    f.start("看看 10001", sink);

    assertThat(sink.data("run.failed").path("code").asText()).isEqualTo("TOOL_OUTPUT_INVALID");
  }

  // ⑩ argsDigest
  @Test
  void argsDigestIsKeyOrderIndependentAndDeterministic() {
    Map<String, String> a = new java.util.LinkedHashMap<>();
    a.put("itemId", "10001");
    a.put("amount", "1.00");
    Map<String, String> b = new java.util.LinkedHashMap<>();
    b.put("amount", "1.00");
    b.put("itemId", "10001");
    assertThat(RunOrchestrator.argsDigest(a)).isEqualTo(RunOrchestrator.argsDigest(b)).hasSize(32);
    assertThat(RunOrchestrator.argsDigest(new TreeMap<>(Map.of("itemId", "10002"))))
        .isNotEqualTo(RunOrchestrator.argsDigest(a));
  }

  // ⑪ fail-closed：需确认工具没有领域确认屏
  @Test
  void confirmationToolWithoutDomainScreenFailsClosed() {
    OrchestratorFixture f =
        new OrchestratorFixture(
            List.of(new Fakes.FakeScreens(Set.of(LIST, GET, CLOSE), Set.of())), // 无确认屏
            List.of(new Fakes.FakeRecheck(CLOSE, GET)),
            true);
    f.llm.returns(
        new LlmClient.Planned(
            plan(
                step(1, GET, Map.of("itemId", "10001"), false),
                step(2, CLOSE, Map.of("itemId", "10001"), true))));
    f.gateway.on(GET, args -> MAPPER.createObjectNode().put("status", "OPEN"));
    Fakes.RecordingSink sink = new Fakes.RecordingSink();

    String runId = f.start("关闭 10001", sink);

    assertThat(sink.names()).endsWith("run.failed").doesNotContain("confirmation.required");
    assertThat(sink.data("run.failed").path("code").asText()).isEqualTo("INTERNAL_ERROR");
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.FAILED);
    // 兜底屏绝不放行高风险工具：目标工具从未被调用
    assertThat(f.gateway.calledToolIds()).containsExactly(GET);
  }

  // 附：find / lastUi 对未知 runId 为空
  @Test
  void unknownRunIdIsEmpty() {
    OrchestratorFixture f = new OrchestratorFixture();
    assertThat(f.orchestrator.find("run_nope")).isEmpty();
    assertThat(f.orchestrator.lastUi("run_nope")).isEmpty();
  }

  // ---------------------------------------------------------------- Run 自包含（多副本前置）

  /**
   * 最近一次屏从仓储读，不在编排器实例里：第二个编排器（模拟另一个 hub 副本）共享同一仓储即能取到同一屏。
   *
   * <p>改造前 lastUi 是实例内的 Map，这条会得到 empty——它就是"确认请求可以落到任意副本"的最小证明。
   */
  @Test
  void lastUiIsReadFromRepositoryNotFromInstance() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.llm.returns(new LlmClient.Planned(plan(step(1, GET, Map.of("itemId", "10001"), false))));
    f.gateway.on(GET, args -> MAPPER.createObjectNode().put("itemId", "10001"));
    String runId = f.start("看看 10001", new Fakes.RecordingSink());

    // 另一个副本：全新的编排器，只共享 RunRepository
    OrchestratorFixture other = OrchestratorFixture.replicaOf(f);

    assertThat(other.orchestrator.lastUi(runId)).isPresent();
    assertThat(other.orchestrator.lastUi(runId).map(u -> u.components().get(0).type().name()))
        .contains("Card");
    assertThat(f.runs.find(runId).flatMap(r -> r.currentUi())).isPresent();
  }

  /** 计划各步骤的 inputSchema 随 Run 快照（不依赖注册表当下状态）；只保留计划里的工具。 */
  @Test
  void attachPlanSnapshotsOnlyPlannedToolSchemas() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.llm.returns(new LlmClient.Planned(plan(step(1, GET, Map.of("itemId", "10001"), false))));
    f.gateway.on(GET, args -> MAPPER.createObjectNode());
    String runId = f.start("看看 10001", new Fakes.RecordingSink());

    var run = f.runs.find(runId).orElseThrow();
    assertThat(run.stepSchemas().keySet()).containsExactly(GET);
    assertThat(run.stepSchema(GET).orElseThrow()).contains("\"itemId\"");
    // 终态后前置输出已清空，屏保留
    assertThat(run.stepOutputs()).isEmpty();
    assertThat(run.currentUi()).isPresent();
  }

  // ---------------------------------------------------------------- 客户端断开

  /** 断开后下一步是只读工具：终止，不再调用 Gateway，Run FAILED（没人在看结果，白烧一次调用）。 */
  @Test
  void clientGoneBeforeReadOnlyStepAbandonsRun() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.llm.returns(new LlmClient.Planned(plan(step(1, GET, Map.of("itemId", "10001"), false))));
    f.gateway.on(GET, args -> MAPPER.createObjectNode());
    Fakes.RecordingSink sink = new Fakes.RecordingSink();
    sink.gone = true;

    String runId = f.start("看看 10001", sink);

    assertThat(f.gateway.calls).as("只读步骤不该再执行").isEmpty();
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.FAILED);
    assertThat(f.runs.find(runId).flatMap(r -> r.failureCode())).contains("INTERNAL_ERROR");
  }

  /** 断开后下一步是写工具：照跑——不留半截的写链。 */
  @Test
  void clientGoneBeforeWriteStepStillExecutes() {
    OrchestratorFixture f = new OrchestratorFixture();
    String touch = "demo.item.touch";
    f.meta.register(
        com.sparkrooter.runtime.support.TestFixtures.meta(touch, List.of(), null, true));
    f.llm.returns(new LlmClient.Planned(plan(step(1, touch, Map.of(), false))));
    f.gateway.on(touch, args -> MAPPER.createObjectNode().put("touched", true));
    Fakes.RecordingSink sink = new Fakes.RecordingSink();
    sink.gone = true;

    String runId = f.start("touch", sink);

    assertThat(f.gateway.calledToolIds()).containsExactly(touch);
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.COMPLETED);
  }

  /** 元数据里没有这个工具（如 http provider 工具）：取不到 sideEffect 视为写，fail-safe 照跑。 */
  @Test
  void clientGoneWithUnknownToolMetaIsTreatedAsWrite() {
    OrchestratorFixture f = new OrchestratorFixture();
    String unknown = "demo.item.remote";
    f.llm.returns(new LlmClient.Planned(plan(step(1, unknown, Map.of(), false))));
    f.gateway.on(unknown, args -> MAPPER.createObjectNode().put("ok", true));
    Fakes.RecordingSink sink = new Fakes.RecordingSink();
    sink.gone = true;

    String runId = f.start("remote", sink);

    assertThat(f.gateway.calledToolIds()).containsExactly(unknown);
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.COMPLETED);
  }

  // ---------------------------------------------------------------- 终态落库顺序（多副本）

  /**
   * 记忆写入抛异常，Run 仍必须以 COMPLETED 落库。
   *
   * <p>改造前顺序是 remember → save；remember 抛了就跳过 save，共享存储里的 Run 永远停在 EXECUTING，另一副本看到一个 永远"执行中"的
   * Run。内存版对象引用已是 COMPLETED 所以看不出来——这是只在多副本下暴露的顺序 bug（评审 F-3）。 用一个 save 时快照状态的仓储把"落库那一刻的状态"钉住。
   */
  @Test
  void completeSavesTerminalStateEvenIfMemoryWriteThrows() {
    OrchestratorFixture f = new OrchestratorFixture();
    f.memory.failNextPut = true;
    java.util.List<RunState> savedStates = new java.util.ArrayList<>();
    f.runs.onSave = r -> savedStates.add(r.state());
    f.llm.returns(new LlmClient.Planned(plan(step(1, GET, Map.of("itemId", "10001"), false))));
    f.gateway.on(GET, args -> MAPPER.createObjectNode().put("itemId", "10001"));
    Fakes.RecordingSink sink = new Fakes.RecordingSink();

    String runId = f.start("看看 10001", sink);

    assertThat(savedStates).as("落库过 COMPLETED").contains(RunState.COMPLETED);
    assertThat(f.runs.find(runId)).map(r -> r.state()).contains(RunState.COMPLETED);
    assertThat(sink.names()).endsWith("run.completed");
  }
}
