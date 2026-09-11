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
}
