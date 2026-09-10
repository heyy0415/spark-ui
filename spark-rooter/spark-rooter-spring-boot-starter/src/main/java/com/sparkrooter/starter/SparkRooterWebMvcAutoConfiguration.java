package com.sparkrooter.starter;

import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.gateway.application.InvokeToolUseCase;
import com.sparkrooter.registry.application.RegisterToolUseCase;
import com.sparkrooter.registry.application.SearchToolsUseCase;
import com.sparkrooter.registry.domain.ToolRegistryRepository;
import com.sparkrooter.runtime.application.RunOrchestrator;
import com.sparkrooter.spi.RunContextPropagator;
import com.sparkrooter.spi.SessionIdResolver;
import com.sparkrooter.webmvc.gateway.GatewayExceptionHandler;
import com.sparkrooter.webmvc.gateway.ToolGatewayController;
import com.sparkrooter.webmvc.registry.RegistryExceptionHandler;
import com.sparkrooter.webmvc.registry.ToolRegistryController;
import com.sparkrooter.webmvc.runtime.AgentRunController;
import com.sparkrooter.webmvc.runtime.RuntimeExceptionHandler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Web 端点装配：只在 Servlet Web 应用装配；/agent/runs 前缀由 spark.web.base-path 决定（Controller 的 @RequestMapping
 * 占位符）； /internal/** 三个端点默认不装配（spark.web.internal-endpoints=true 打开，示例宿主打开供 e2e 直调）。
 */
@AutoConfiguration(after = SparkRooterAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SparkRooterWebMvcAutoConfiguration {

  @Bean(destroyMethod = "shutdown")
  ScheduledExecutorService sparkRooterPingScheduler(SparkRooterProperties props) {
    return Executors.newScheduledThreadPool(
        props.runtime().pingPool(), NamedThreads.named("sse-ping-"));
  }

  @Bean
  AgentRunController sparkRooterAgentRunController(
      RunOrchestrator orchestrator,
      SchemaValidator validator,
      @Qualifier("sparkRooterRunExecutor") ExecutorService runExecutor,
      @Qualifier("sparkRooterPingScheduler") ScheduledExecutorService pingScheduler,
      SessionIdResolver sessions,
      RunContextPropagator propagator,
      SparkRooterProperties props) {
    return new AgentRunController(
        orchestrator,
        validator,
        validator.mapper(),
        runExecutor,
        pingScheduler,
        sessions,
        propagator,
        props.runtime().sseTimeout().toMillis());
  }

  @Bean
  RuntimeExceptionHandler sparkRooterRuntimeExceptionHandler() {
    return new RuntimeExceptionHandler();
  }

  /** /internal/** 端点：默认关闭。 */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "spark.web", name = "internal-endpoints", havingValue = "true")
  static class InternalEndpoints {

    @Bean
    ToolRegistryController sparkRooterToolRegistryController(
        RegisterToolUseCase register,
        SearchToolsUseCase search,
        ToolRegistryRepository repo,
        SchemaValidator validator) {
      return new ToolRegistryController(register, search, repo, validator);
    }

    @Bean
    RegistryExceptionHandler sparkRooterRegistryExceptionHandler() {
      return new RegistryExceptionHandler();
    }

    @Bean
    ToolGatewayController sparkRooterToolGatewayController(
        InvokeToolUseCase invoke, SchemaValidator validator) {
      return new ToolGatewayController(invoke, validator);
    }

    @Bean
    GatewayExceptionHandler sparkRooterGatewayExceptionHandler() {
      return new GatewayExceptionHandler();
    }
  }
}
