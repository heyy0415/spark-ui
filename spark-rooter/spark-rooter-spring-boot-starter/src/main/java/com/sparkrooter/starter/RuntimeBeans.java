package com.sparkrooter.starter;

import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.gateway.api.ToolInvokePort;
import com.sparkrooter.registry.api.ToolSearchPort;
import com.sparkrooter.runtime.application.ConfirmationTokenService;
import com.sparkrooter.runtime.application.DomainResolver;
import com.sparkrooter.runtime.application.EntityRequirementCheck;
import com.sparkrooter.runtime.application.RecheckRegistry;
import com.sparkrooter.runtime.application.RunOrchestrator;
import com.sparkrooter.runtime.application.ToolDisplayNames;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.runtime.application.port.IntentClassifier;
import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.application.port.ToolGatewayClient;
import com.sparkrooter.runtime.application.port.ToolRegistryClient;
import com.sparkrooter.runtime.application.screen.ScreenRegistry;
import com.sparkrooter.runtime.domain.ConfirmationTokenStore;
import com.sparkrooter.runtime.domain.RunRepository;
import com.sparkrooter.runtime.infra.InMemoryConfirmationTokenStore;
import com.sparkrooter.runtime.infra.InMemoryRunRepository;
import com.sparkrooter.runtime.infra.inprocess.InProcessToolGatewayClient;
import com.sparkrooter.runtime.infra.inprocess.InProcessToolRegistryClient;
import com.sparkrooter.runtime.infra.llm.IntentVerbs;
import com.sparkrooter.runtime.infra.llm.LlmFactory;
import com.sparkrooter.spi.ConfirmationRecheck;
import com.sparkrooter.spi.RunContextPropagator;
import com.sparkrooter.spi.ScreenBuilder;
import com.sparkrooter.spi.SessionIdResolver;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 决策面（Runtime）装配 + 宿主端口默认实现。默认 SessionIdResolver / RunContextPropagator 只适合本地演示，构造期 WARN（spec §2.3
 * / §2.7）。
 */
@Configuration(proxyBeanMethods = false)
class RuntimeBeans {

  private static final Logger log = LoggerFactory.getLogger(RuntimeBeans.class);

  // ---- 宿主端口默认实现

  @Bean
  @ConditionalOnMissingBean(SessionIdResolver.class)
  SessionIdResolver sparkRooterSessionIdResolver() {
    log.warn("SessionIdResolver 为 demo 实现（sessionId = conversationId，无隔离）；生产必须由宿主实现为绑定自己的登录态");
    return conversationId -> conversationId;
  }

  @Bean
  @ConditionalOnMissingBean(RunContextPropagator.class)
  RunContextPropagator sparkRooterRunContextPropagator() {
    log.warn("RunContextPropagator 为 no-op：宿主请求线程的 ThreadLocal / SecurityContext 不会传播到 spark 工作线程");
    return new RunContextPropagator() {
      @Override
      public Object capture() {
        return null;
      }

      @Override
      public void restore(Object captured) {}

      @Override
      public void clear() {}
    };
  }

  // ---- 基础设施

  @Bean
  @ConditionalOnMissingBean(name = "sparkRooterClock")
  Clock sparkRooterClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean(RunRepository.class)
  RunRepository sparkRooterRunRepository() {
    return new InMemoryRunRepository();
  }

  @Bean
  @ConditionalOnMissingBean(ConfirmationTokenStore.class)
  ConfirmationTokenStore sparkRooterConfirmationTokenStore() {
    return new InMemoryConfirmationTokenStore();
  }

  @Bean(destroyMethod = "shutdown")
  ExecutorService sparkRooterRunExecutor(SparkRooterProperties props) {
    return Executors.newFixedThreadPool(
        props.runtime().runPool(), NamedThreads.named("agent-run-"));
  }

  @Bean
  ToolDisplayNames sparkRooterToolDisplayNames() {
    return new ToolDisplayNames();
  }

  /**
   * @SparkTool 元数据表；内核默认表供手写 Manifest 工具回落。
   */
  @Bean
  ToolMetaRegistry sparkRooterToolMetaRegistry() {
    return new ToolMetaRegistry(IntentVerbs.PREREQUISITES, EntityRequirementCheck.ENTITY_ARGS);
  }

  @Bean
  @ConditionalOnMissingBean(ToolRegistryClient.class)
  ToolRegistryClient sparkRooterToolRegistryClient(ToolSearchPort registry) {
    return new InProcessToolRegistryClient(registry);
  }

  @Bean
  @ConditionalOnMissingBean(ToolGatewayClient.class)
  ToolGatewayClient sparkRooterToolGatewayClient(ToolInvokePort gateway) {
    return new InProcessToolGatewayClient(gateway);
  }

  // ---- LLM：属性优先，回落环境变量 SPARK_LLM_*（Spring 宽松绑定不认 BASE_URL 里的下划线，显式兼容）

  @Bean
  LlmFactory.SharedChat sparkRooterChatClient(SparkRooterProperties props, Environment env) {
    return LlmFactory.chat(
        pick(props.llm().baseUrl(), env, "SPARK_LLM_BASE_URL"),
        pick(props.llm().apiKey(), env, "SPARK_LLM_API_KEY"),
        pick(props.llm().model(), env, "SPARK_LLM_MODEL"));
  }

  @Bean
  @ConditionalOnMissingBean(LlmClient.class)
  LlmClient sparkRooterLlmClient(
      LlmFactory.SharedChat chat,
      ToolDisplayNames names,
      ToolMetaRegistry meta,
      Clock sparkRooterClock,
      SparkRooterProperties props,
      Environment env) {
    return LlmFactory.llmClient(
        chat, names, meta, sparkRooterClock, pick(props.llm().model(), env, "SPARK_LLM_MODEL"));
  }

  @Bean
  @ConditionalOnMissingBean(IntentClassifier.class)
  IntentClassifier sparkRooterIntentClassifier(
      LlmFactory.SharedChat chat, SparkRooterProperties props, Environment env) {
    return LlmFactory.intentClassifier(chat, pick(props.llm().model(), env, "SPARK_LLM_MODEL"));
  }

  private static String pick(String fromProps, Environment env, String envVar) {
    return fromProps != null && !fromProps.isBlank() ? fromProps : env.getProperty(envVar, "");
  }

  // ---- 编排

  @Bean
  ScreenRegistry sparkRooterScreenRegistry(
      ObjectProvider<ScreenBuilder> builders, SchemaValidator validator) {
    return new ScreenRegistry(builders.orderedStream().toList(), validator);
  }

  @Bean
  RecheckRegistry sparkRooterRecheckRegistry(ObjectProvider<ConfirmationRecheck> rechecks) {
    return new RecheckRegistry(rechecks.orderedStream().toList());
  }

  @Bean
  DomainResolver sparkRooterDomainResolver(
      IntentClassifier classifier, ToolRegistryClient registry) {
    return new DomainResolver(classifier, registry);
  }

  @Bean
  ConfirmationTokenService sparkRooterConfirmationTokenService(
      ConfirmationTokenStore store, Clock sparkRooterClock, SparkRooterProperties props) {
    return new ConfirmationTokenService(store, sparkRooterClock, props.runtime().tokenTtl());
  }

  @Bean
  RunOrchestrator sparkRooterRunOrchestrator(
      RunRepository runs,
      DomainResolver resolver,
      ToolRegistryClient registry,
      ToolGatewayClient gateway,
      LlmClient llm,
      ScreenRegistry screens,
      RecheckRegistry rechecks,
      ToolDisplayNames displayNames,
      ConfirmationTokenService tokens,
      ToolMetaRegistry meta,
      SchemaValidator validator,
      Clock sparkRooterClock) {
    return new RunOrchestrator(
        runs,
        resolver,
        registry,
        gateway,
        llm,
        screens,
        rechecks,
        displayNames,
        tokens,
        meta,
        validator,
        sparkRooterClock);
  }
}
