package com.sparkrooter.runtime.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Run 聚合：幂等迁移、计划只能附一次、步骤游标、失败码、幂等键格式。 */
final class RunTest {

  private static final Instant T0 = Instant.parse("2026-09-11T00:00:00Z");
  private static final Instant T1 = T0.plusSeconds(1);

  private static Run run() {
    return new Run("run_x", "conv", "sess", "hello", T0);
  }

  private static Plan plan() {
    return new Plan(
        "demo",
        List.of(
            new Step(1, "demo.item.get", "1.0.0", "查看条目", Map.of("itemId", "10001"), false),
            new Step(2, "demo.item.close", "1.0.0", "关闭条目", Map.of("itemId", "10001"), true)));
  }

  @Test
  void startsCreatedWithTimestamps() {
    Run r = run();
    assertThat(r.state()).isEqualTo(RunState.CREATED);
    assertThat(r.createdAt()).isEqualTo(T0);
    assertThat(r.updatedAt()).isEqualTo(T0);
    assertThat(r.plan()).isEmpty();
    assertThat(r.currentStep()).isEmpty();
    assertThat(r.failureCode()).isEmpty();
    assertThat(r.sessionId()).isEqualTo("sess");
    assertThat(r.message()).isEqualTo("hello");
  }

  @Test
  void legalTransitionUpdatesStateAndTime() {
    Run r = run();
    r.transition(RunState.PLANNING, T1);
    assertThat(r.state()).isEqualTo(RunState.PLANNING);
    assertThat(r.updatedAt()).isEqualTo(T1);
  }

  @Test
  void sameStateTransitionIsNoopAndDoesNotTouchTime() {
    Run r = run();
    r.transition(RunState.PLANNING, T1);
    r.transition(RunState.PLANNING, T1.plusSeconds(5));
    assertThat(r.updatedAt()).isEqualTo(T1);
  }

  @Test
  void illegalTransitionThrowsAndKeepsState() {
    Run r = run();
    assertThatThrownBy(() -> r.transition(RunState.COMPLETED, T1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CREATED -> COMPLETED");
    assertThat(r.state()).isEqualTo(RunState.CREATED);
  }

  @Test
  void planCanOnlyBeAttachedOnce() {
    Run r = run();
    r.attachPlan(plan(), T1);
    assertThat(r.plan()).isPresent();
    assertThatThrownBy(() -> r.attachPlan(plan(), T1)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void currentStepAdvancesThroughPlanThenEmpty() {
    Run r = run();
    r.attachPlan(plan(), T1);
    assertThat(r.currentStep()).map(Step::seq).contains(1);
    r.advance(T1);
    assertThat(r.currentStep()).map(Step::seq).contains(2);
    assertThat(r.nextSeq()).isEqualTo(2);
    r.advance(T1);
    assertThat(r.currentStep()).isEmpty();
  }

  @Test
  void failRecordsCodeAndIsTerminal() {
    Run r = run();
    r.transition(RunState.PLANNING, T1);
    r.fail("TOOL_SELECTION_INVALID", T1);
    assertThat(r.state()).isEqualTo(RunState.FAILED);
    assertThat(r.failureCode()).contains("TOOL_SELECTION_INVALID");
    assertThat(r.state().terminal()).isTrue();
  }

  @Test
  void idempotencyKeyIsRunToolSeq() {
    Run r = run();
    Step s = plan().step(2);
    assertThat(r.idempotencyKeyFor(s)).isEqualTo("run_x-demo.item.close-2");
  }

  @Test
  void stepRejectsSeqBelowOne() {
    assertThatThrownBy(() -> new Step(0, "a.b.c", "1.0.0", "x", Map.of(), false))
        .isInstanceOf(IllegalArgumentException.class);
    Step s = new Step(1, "a.b.c", "1.0.0", "x", null, false);
    assertThat(s.fixedArgs()).isEmpty();
  }

  @Test
  void planLooksUpStepBySeq() {
    assertThat(plan().step(2).toolId()).isEqualTo("demo.item.close");
    assertThatThrownBy(() -> plan().step(3)).isInstanceOf(IllegalArgumentException.class);
  }

  // ---------------------------------------------------------------- 自包含状态（多副本前置）

  /** attachPlan 只快照计划里出现的工具 schema；其余候选丢弃。 */
  @Test
  void attachPlanSnapshotsSchemasForPlannedToolsOnly() {
    Run r = run();
    r.attachPlan(
        plan(),
        Map.of(
            "demo.item.get", "{\"type\":\"object\"}",
            "demo.item.close", "{\"type\":\"object\",\"x\":1}",
            "demo.item.list", "{\"unused\":true}"),
        T1);
    assertThat(r.stepSchemas().keySet())
        .containsExactlyInAnyOrder("demo.item.get", "demo.item.close");
    assertThat(r.stepSchema("demo.item.list")).isEmpty();
  }

  /** 屏 / 前置输出 / 澄清标记都是可变状态，写入刷新 updatedAt；clearStepOutputs 只清输出不清屏。 */
  @Test
  void transientStateLivesOnRunAndClearsSelectively() {
    Run r = run();
    r.setCurrentUi("{\"screenId\":\"s\"}", T1);
    r.putStepOutput("demo.item.get", "{\"status\":\"OPEN\"}", T1.plusSeconds(1));
    r.markClarified(T1.plusSeconds(2));

    assertThat(r.currentUi()).contains("{\"screenId\":\"s\"}");
    assertThat(r.stepOutput("demo.item.get")).contains("{\"status\":\"OPEN\"}");
    assertThat(r.clarified()).isTrue();
    assertThat(r.updatedAt()).isEqualTo(T1.plusSeconds(2));

    r.clearStepOutputs();
    assertThat(r.stepOutputs()).isEmpty();
    assertThat(r.currentUi()).isPresent();
  }

  /** restore 逐字段还原（供共享存储反序列化）；不校验迁移合法性——快照里的状态就是事实。 */
  @Test
  void restoreRebuildsEveryField() {
    Run restored =
        Run.restore(
            "run_r",
            "conv",
            "sess",
            "msg",
            T0,
            T1,
            RunState.WAITING_CONFIRMATION,
            plan(),
            2,
            null,
            "{\"screenId\":\"c\"}",
            Map.of("demo.item.get", "{\"a\":1}"),
            Map.of("demo.item.close", "{\"type\":\"object\"}"),
            true);
    assertThat(restored.runId()).isEqualTo("run_r");
    assertThat(restored.state()).isEqualTo(RunState.WAITING_CONFIRMATION);
    assertThat(restored.createdAt()).isEqualTo(T0);
    assertThat(restored.updatedAt()).isEqualTo(T1);
    assertThat(restored.nextSeq()).isEqualTo(2);
    assertThat(restored.currentStep()).map(Step::toolId).contains("demo.item.close");
    assertThat(restored.currentUi()).contains("{\"screenId\":\"c\"}");
    assertThat(restored.stepOutput("demo.item.get")).contains("{\"a\":1}");
    assertThat(restored.stepSchema("demo.item.close")).isPresent();
    assertThat(restored.clarified()).isTrue();
    assertThat(restored.failureCode()).isEmpty();
    // null 集合安全
    Run bare =
        Run.restore(
            "r", "c", "s", "m", T0, T0, RunState.CREATED, null, 1, null, null, null, null, false);
    assertThat(bare.stepOutputs()).isEmpty();
    assertThat(bare.stepSchemas()).isEmpty();
  }
}
