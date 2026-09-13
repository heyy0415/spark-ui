package com.sparkrooter.starter;

import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.infra.llm.LlmCircuitBreaker;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * spark 的就绪判定：模型没配就不该接流量。
 *
 * <p>只反映<b>静态</b>不可用：规划器是 {@code UnavailablePlanner}（三项 LLM 配置任一缺失）→ DOWN。这种状态不会自愈，K8s 把 Pod 从
 * Service 摘掉是对的——否则流量打到一个 100% 失败的实例上。
 *
 * <p><b>熔断不改状态，只进 detail</b>。{@link LlmCircuitBreaker} 的 OPEN → HALF_OPEN 转换只发生在真实请求调用 {@code
 * shouldSkip()} 时；若熔断即摘流量，则「无流量 → 无探测 → 永不恢复 → 永不挂回」，全部 Pod 会在网关抖动一次后被锁死。
 * 让流量继续进来才能恢复，代价是熔断期间的请求快速失败并告知用户——这正是熔断器的设计目的。
 */
final class SparkReadinessHealthIndicator implements HealthIndicator {

  /** 与 {@code UnavailablePlanner.name()} 一致；不 import 那个类以免 starter 与具体实现绑死。 */
  static final String UNAVAILABLE = "unavailable";

  private final LlmClient llm;
  private final LlmCircuitBreaker circuit;

  SparkReadinessHealthIndicator(LlmClient llm, LlmCircuitBreaker circuit) {
    this.llm = llm;
    this.circuit = circuit;
  }

  @Override
  public Health health() {
    String planner = llm.name();
    Health.Builder b = UNAVAILABLE.equals(planner) ? Health.down() : Health.up();
    return b.withDetail("planner", planner)
        .withDetail("circuit", circuit.state().name().toLowerCase())
        .build();
  }
}
