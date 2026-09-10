package com.sparkrooter.starter;

import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.infra.selfcheck.ContractsSelfCheck;
import com.sparkrooter.gateway.application.InvokeToolUseCase;
import com.sparkrooter.gateway.domain.IdempotencyStore;
import com.sparkrooter.gateway.infra.selfcheck.GatewayIdempotencySelfCheck;
import com.sparkrooter.registry.domain.ToolRegistryRepository;
import com.sparkrooter.runtime.application.RecheckRegistry;
import com.sparkrooter.runtime.application.ToolDisplayNames;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.application.port.ToolGatewayClient;
import com.sparkrooter.runtime.application.port.ToolRegistryClient;
import com.sparkrooter.runtime.application.screen.ScreenRegistry;
import com.sparkrooter.runtime.domain.ConfirmationTokenStore;
import com.sparkrooter.runtime.infra.selfcheck.ConfirmationCoverageSelfCheck;
import com.sparkrooter.runtime.infra.selfcheck.InlineActionSelfCheck;
import com.sparkrooter.runtime.infra.selfcheck.PlanSelfCheck;
import com.sparkrooter.runtime.infra.selfcheck.TokenSelfCheck;
import com.sparkrooter.spi.SelfCheck;
import com.sparkrooter.starter.selfcheck.ManifestParitySelfCheck;
import com.sparkrooter.starter.selfcheck.ProxyInvocationSelfCheck;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 启动自检（spark.selfcheck.enabled=true 才装配）。平台 8 项（含 manifest parity / proxy invocation） + 宿主自己的
 * SelfCheck Bean（如 refund.create idempotent）一起由 Runner 执行。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spark.selfcheck", name = "enabled", havingValue = "true")
class SelfCheckBeans {

  @Bean
  SelfCheckRunner sparkRooterSelfCheckRunner(ObjectProvider<SelfCheck> checks) {
    return new SelfCheckRunner(checks.orderedStream().toList());
  }

  @Bean
  SelfCheck sparkRooterContractsSelfCheck(SchemaValidator validator) {
    return new ContractsSelfCheck(validator);
  }

  @Bean
  SelfCheck sparkRooterGatewayIdempotencySelfCheck(
      IdempotencyStore store, @Qualifier("sparkRooterToolExecutor") ExecutorService toolExecutor) {
    return new GatewayIdempotencySelfCheck(store, toolExecutor);
  }

  @Bean
  SelfCheck sparkRooterTokenSelfCheck(ConfirmationTokenStore store) {
    return new TokenSelfCheck(store);
  }

  @Bean
  SelfCheck sparkRooterPlanSelfCheck(
      LlmClient llm, ToolRegistryClient registry, ToolDisplayNames names, ToolMetaRegistry meta) {
    return new PlanSelfCheck(llm, registry, names, meta);
  }

  @Bean
  SelfCheck sparkRooterManifestParitySelfCheck(
      ToolRegistryRepository repo, SchemaValidator validator) {
    return new ManifestParitySelfCheck(repo, validator);
  }

  /** 自检专用 @SparkTool Bean 与计数切面（只在自检开启时存在）。 */
  @Bean
  ProxyInvocationSelfCheck.EchoTools sparkRooterSelfCheckEchoTools() {
    return new ProxyInvocationSelfCheck.EchoTools();
  }

  @Bean
  ProxyInvocationSelfCheck.CountingAspect sparkRooterSelfCheckCountingAspect() {
    return new ProxyInvocationSelfCheck.CountingAspect();
  }

  @Bean
  SelfCheck sparkRooterProxyInvocationSelfCheck(
      InvokeToolUseCase gateway,
      ProxyInvocationSelfCheck.CountingAspect aspect,
      SchemaValidator validator) {
    return new ProxyInvocationSelfCheck(gateway, aspect, validator.mapper());
  }

  @Bean
  SelfCheck sparkRooterConfirmationCoverageSelfCheck(
      ToolRegistryClient registry,
      ScreenRegistry screens,
      RecheckRegistry rechecks,
      SchemaValidator validator) {
    return new ConfirmationCoverageSelfCheck(registry, screens, rechecks, validator.mapper());
  }

  @Bean
  SelfCheck sparkRooterInlineActionSelfCheck(
      ToolGatewayClient gateway, ScreenRegistry screens, SchemaValidator validator) {
    return new InlineActionSelfCheck(gateway, screens, validator.mapper());
  }
}
