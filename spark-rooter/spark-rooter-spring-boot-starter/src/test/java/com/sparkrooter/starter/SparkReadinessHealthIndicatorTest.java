package com.sparkrooter.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.infra.llm.LlmCircuitBreaker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/** 就绪判定三态。第三条是本类存在的理由：熔断 OPEN 时**必须仍是 UP**——熔断器靠真实请求恢复，摘掉流量它就永远恢复不了。 */
final class SparkReadinessHealthIndicatorTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC);

  private static LlmClient planner(String name) {
    return new LlmClient() {
      @Override
      public Decision plan(PlanRequest request) {
        throw new UnsupportedOperationException();
      }

      @Override
      public String name() {
        return name;
      }
    };
  }

  private static LlmCircuitBreaker closedCircuit() {
    return new LlmCircuitBreaker(true, 2, Duration.ofSeconds(30), CLOCK);
  }

  @Test
  void unavailablePlannerIsDown() {
    Health h = new SparkReadinessHealthIndicator(planner("unavailable"), closedCircuit()).health();
    assertThat(h.getStatus()).isEqualTo(Status.DOWN);
    assertThat(h.getDetails()).containsEntry("planner", "unavailable");
  }

  @Test
  void configuredPlannerWithClosedCircuitIsUp() {
    Health h = new SparkReadinessHealthIndicator(planner("llm"), closedCircuit()).health();
    assertThat(h.getStatus()).isEqualTo(Status.UP);
    assertThat(h.getDetails()).containsEntry("planner", "llm").containsEntry("circuit", "closed");
  }

  /** 熔断 OPEN：状态仍 UP，只在 detail 暴露。改成 DOWN / OUT_OF_SERVICE 这条就红——它守的是"别把熔断器锁死"。 */
  @Test
  void openCircuitStaysUpAndOnlyShowsInDetails() {
    LlmCircuitBreaker circuit = closedCircuit();
    circuit.recordTransportFailure();
    circuit.recordTransportFailure();
    assertThat(circuit.state()).isEqualTo(LlmCircuitBreaker.State.OPEN);

    Health h = new SparkReadinessHealthIndicator(planner("llm"), circuit).health();

    assertThat(h.getStatus()).as("熔断不摘流量，否则永不恢复").isEqualTo(Status.UP);
    assertThat(h.getDetails()).containsEntry("circuit", "open");
  }
}
