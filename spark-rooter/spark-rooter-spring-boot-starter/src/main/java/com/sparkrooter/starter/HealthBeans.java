package com.sparkrooter.starter;

import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.infra.llm.LlmCircuitBreaker;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 健康探针：宿主 classpath 上有 actuator 时注册一个名为 {@code sparkRooter} 的 {@link HealthIndicator}。
 *
 * <p>{@code @ConditionalOnClass} 在类上，与 {@link MetricsBeans} 同一手法：没引 actuator 的宿主连类加载都不发生。
 *
 * <p>宿主要让它参与 readiness 探针，需自行把 {@code sparkRooter} 加进 {@code
 * management.endpoint.health.group.readiness.include}（示例宿主已加）。默认它只出现在 {@code /actuator/health}
 * 的组件列表里， 不影响 liveness——模型没配是不该接流量，不是该被重启。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(HealthIndicator.class)
class HealthBeans {

  @Bean
  @ConditionalOnMissingBean(name = "sparkRooterHealthIndicator")
  HealthIndicator sparkRooterHealthIndicator(LlmClient llm, LlmCircuitBreaker circuit) {
    return new SparkReadinessHealthIndicator(llm, circuit);
  }
}
