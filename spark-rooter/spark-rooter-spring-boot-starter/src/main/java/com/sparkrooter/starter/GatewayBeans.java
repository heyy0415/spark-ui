package com.sparkrooter.starter;

import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.gateway.application.InvokeToolUseCase;
import com.sparkrooter.gateway.domain.IdempotencyStore;
import com.sparkrooter.gateway.infra.InMemoryIdempotencyStore;
import com.sparkrooter.gateway.infra.LogAuditSink;
import com.sparkrooter.spi.AuditSink;
import com.sparkrooter.spi.RunContextPropagator;
import com.sparkrooter.spi.ToolAccessPolicy;
import com.sparkrooter.spi.ToolHandler;
import com.sparkrooter.spi.ToolResolver;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 执行面（Gateway）装配。审计默认落日志；幂等表默认内存；工具线程池 spark.gateway.tool-pool。 */
@Configuration(proxyBeanMethods = false)
class GatewayBeans {

  @Bean
  @ConditionalOnMissingBean(AuditSink.class)
  AuditSink sparkRooterAuditSink() {
    return new LogAuditSink();
  }

  @Bean
  @ConditionalOnMissingBean(IdempotencyStore.class)
  IdempotencyStore sparkRooterIdempotencyStore() {
    return new InMemoryIdempotencyStore();
  }

  /**
   * 工具执行池：有界 + AbortPolicy。队列满时 {@code submit} 同步抛 RejectedExecutionException， 它不经
   * ExecutionException 包装，会冒泡到 RunOrchestrator.invoke 的 catch(RuntimeException) 转成
   * TOOL_EXECUTION_FAILED —— 用户看到「执行过程中工具调用失败，请稍后重试」，行为正确（失败而非挂死）。
   */
  @Bean(destroyMethod = "shutdown")
  ExecutorService sparkRooterToolExecutor(SparkRooterProperties props) {
    return NamedThreads.boundedPool(
        props.gateway().toolPool(), props.gateway().toolQueue(), "gateway-tool-");
  }

  @Bean
  InvokeToolUseCase sparkRooterInvokeToolUseCase(
      ToolResolver resolver,
      ObjectProvider<ToolAccessPolicy> access,
      RunContextPropagator propagator,
      IdempotencyStore idempotency,
      AuditSink audit,
      SchemaValidator validator,
      ObjectProvider<ToolHandler> handlers,
      @Qualifier("sparkRooterToolExecutor") ExecutorService toolExecutor) {
    return new InvokeToolUseCase(
        resolver,
        access,
        propagator,
        idempotency,
        audit,
        validator,
        handlers.orderedStream().toList(),
        toolExecutor);
  }
}
