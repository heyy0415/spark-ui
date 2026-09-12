package com.sparkrooter.starter;

import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.tool.ToolTransport;
import com.sparkrooter.gateway.application.InvokeToolUseCase;
import com.sparkrooter.gateway.domain.IdempotencyStore;
import com.sparkrooter.gateway.infra.InMemoryIdempotencyStore;
import com.sparkrooter.gateway.infra.LogAuditSink;
import com.sparkrooter.gateway.infra.transport.HttpToolTransport;
import com.sparkrooter.gateway.infra.transport.InProcessToolTransport;
import com.sparkrooter.gateway.infra.transport.ManifestProviderEndpointResolver;
import com.sparkrooter.spi.AuditSink;
import com.sparkrooter.spi.ProviderAuth;
import com.sparkrooter.spi.ProviderEndpointResolver;
import com.sparkrooter.spi.RunContextPropagator;
import com.sparkrooter.spi.ToolAccessPolicy;
import com.sparkrooter.spi.ToolHandler;
import com.sparkrooter.spi.ToolResolver;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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

  /**
   * 进程内传输：单体内嵌形态的执行路径，持有 toolId@version → handler 注册表。
   *
   * <p>{@code @ConditionalOnMissingBean} 供宿主替换（如加审计埋点的装饰器）。HTTP 传输由 provider 支持模块另行装配， 本 starter
   * 不含它 —— 单体宿主不该因为引入 starter 就多一个 HTTP 客户端。
   */
  @Bean
  @ConditionalOnMissingBean
  InProcessToolTransport sparkRooterInProcessToolTransport(ObjectProvider<ToolHandler> handlers) {
    InProcessToolTransport transport = new InProcessToolTransport();
    // 宿主手写的 ToolHandler Bean；@SparkTool 扫描出的适配器由 SparkToolScanner 在启动期追加
    handlers.orderedStream().forEach(transport::register);
    return transport;
  }

  /** provider 地址解析：默认取 Manifest 里声明的 baseUrl。接注册中心的宿主定义同类型 Bean 即覆盖。 */
  @Bean
  @ConditionalOnMissingBean
  ProviderEndpointResolver sparkRooterProviderEndpointResolver() {
    return new ManifestProviderEndpointResolver();
  }

  /**
   * HTTP 传输：**仅当宿主装配了 {@link ProviderAuth} 时才创建**。
   *
   * <p>单体宿主没有这个 Bean，于是也没有 HTTP 传输——「引入 starter 不该多一个 HTTP 客户端」。 这与 {@code RegistrationGuard}
   * 是同一条逻辑：没配认证就不支持远程，而不是不检查。
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(ProviderAuth.class)
  HttpToolTransport sparkRooterHttpToolTransport(
      ProviderEndpointResolver resolver, ProviderAuth auth, SchemaValidator validator) {
    return new HttpToolTransport(resolver, auth, validator.mapper());
  }

  @Bean
  InvokeToolUseCase sparkRooterInvokeToolUseCase(
      ToolResolver resolver,
      ObjectProvider<ToolAccessPolicy> access,
      RunContextPropagator propagator,
      IdempotencyStore idempotency,
      AuditSink audit,
      SchemaValidator validator,
      @Qualifier("sparkRooterToolExecutor") ExecutorService toolExecutor,
      ObjectProvider<ToolTransport> transports) {
    return new InvokeToolUseCase(
        resolver,
        access,
        propagator,
        idempotency,
        audit,
        validator,
        toolExecutor,
        transports.orderedStream().toList());
  }
}
