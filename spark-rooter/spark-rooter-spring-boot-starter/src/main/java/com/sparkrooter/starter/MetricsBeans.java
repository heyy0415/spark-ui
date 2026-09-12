package com.sparkrooter.starter;

import com.sparkrooter.spi.LlmMetricsSink;
import com.sparkrooter.spi.RunMetricsSink;
import com.sparkrooter.spi.ToolMetricsSink;
import com.sparkrooter.starter.metrics.MicrometerMetricsSinks;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 指标导出：宿主 classpath 上有 Micrometer 且已装配 {@link MeterRegistry} 时用它，否则落日志。
 *
 * <p>{@code @ConditionalOnClass} 在类上：没引 Micrometer 的宿主（provider 形态、不用监控的服务） 连类加载都不发生。
 *
 * <p><b>registry 用 {@link ObjectProvider} 而非 {@code @ConditionalOnBean}</b>：本类由 starter 的
 * {@code @Import} 组合进来，不是独立 AutoConfiguration，此时 {@code @ConditionalOnBean} 的求值 早于 actuator 注册
 * {@code MeterRegistry}，条件永远不成立——实测就是这样被静默跳过、悄悄退回 日志实现的。{@code ObjectProvider} 在注入时才解析，没有这个时序问题。
 *
 * <p>各 Bean 用 {@code @ConditionalOnMissingBean} 让位于宿主自定义实现（可能用别的监控栈）。
 *
 * <p>不导出端点：装 {@code micrometer-registry-prometheus} 后由 actuator 暴露 {@code
 * /actuator/prometheus}，本项目不替宿主选监控栈（非目标）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(MeterRegistry.class)
class MetricsBeans {

  @Bean
  @ConditionalOnMissingBean(LlmMetricsSink.class)
  LlmMetricsSink sparkRooterLlmMetricsSink(ObjectProvider<MeterRegistry> registry) {
    MeterRegistry r = registry.getIfAvailable();
    return r == null
        ? new com.sparkrooter.runtime.infra.llm.LogLlmMetricsSink()
        : MicrometerMetricsSinks.llm(r);
  }

  @Bean
  @ConditionalOnMissingBean(ToolMetricsSink.class)
  ToolMetricsSink sparkRooterToolMetricsSink(ObjectProvider<MeterRegistry> registry) {
    MeterRegistry r = registry.getIfAvailable();
    return r == null
        ? new com.sparkrooter.gateway.infra.LogToolMetricsSink()
        : MicrometerMetricsSinks.tool(r);
  }

  @Bean
  @ConditionalOnMissingBean(RunMetricsSink.class)
  RunMetricsSink sparkRooterRunMetricsSink(ObjectProvider<MeterRegistry> registry) {
    MeterRegistry r = registry.getIfAvailable();
    return r == null
        ? new com.sparkrooter.runtime.infra.LogRunMetricsSink()
        : MicrometerMetricsSinks.run(r);
  }
}
