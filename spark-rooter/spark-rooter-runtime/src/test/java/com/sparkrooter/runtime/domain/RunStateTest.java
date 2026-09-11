package com.sparkrooter.runtime.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Run 状态机迁移表全枚举：与 run-summary.state / 06-backend-module-spec 的六状态图一致。 */
final class RunStateTest {

  private static final Map<RunState, Set<RunState>> ALLOWED =
      Map.of(
          RunState.CREATED, EnumSet.of(RunState.PLANNING, RunState.FAILED),
          RunState.PLANNING, EnumSet.of(RunState.EXECUTING, RunState.COMPLETED, RunState.FAILED),
          RunState.EXECUTING,
              EnumSet.of(RunState.WAITING_CONFIRMATION, RunState.COMPLETED, RunState.FAILED),
          RunState.WAITING_CONFIRMATION, EnumSet.of(RunState.EXECUTING, RunState.FAILED),
          RunState.COMPLETED, EnumSet.noneOf(RunState.class),
          RunState.FAILED, EnumSet.noneOf(RunState.class));

  @Test
  void transitionTableMatchesSpec() {
    for (RunState from : RunState.values()) {
      for (RunState to : RunState.values()) {
        boolean expected = from == to || ALLOWED.get(from).contains(to);
        assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isEqualTo(expected);
      }
    }
  }

  @Test
  void selfTransitionIsAlwaysAllowedAsIdempotentNoop() {
    for (RunState s : RunState.values()) {
      assertThat(s.canTransitionTo(s)).isTrue();
    }
  }

  @Test
  void onlyCompletedAndFailedAreTerminal() {
    assertThat(EnumSet.allOf(RunState.class).stream().filter(RunState::terminal))
        .containsExactlyInAnyOrder(RunState.COMPLETED, RunState.FAILED);
  }
}
