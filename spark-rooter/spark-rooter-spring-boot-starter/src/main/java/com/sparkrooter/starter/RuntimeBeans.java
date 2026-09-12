package com.sparkrooter.starter;

import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.gateway.api.ToolInvokePort;
import com.sparkrooter.registry.api.ToolSearchPort;
import com.sparkrooter.runtime.application.ConfirmationTokenService;
import com.sparkrooter.runtime.application.RecheckRegistry;
import com.sparkrooter.runtime.application.RunOrchestrator;
import com.sparkrooter.runtime.application.ToolDisplayNames;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.application.port.ToolGatewayClient;
import com.sparkrooter.runtime.application.port.ToolRegistryClient;
import com.sparkrooter.runtime.application.screen.ScreenRegistry;
import com.sparkrooter.runtime.domain.ConfirmationTokenStore;
import com.sparkrooter.runtime.domain.RunRepository;
import com.sparkrooter.runtime.infra.InMemoryConfirmationTokenStore;
import com.sparkrooter.runtime.infra.InMemoryConversationMemory;
import com.sparkrooter.runtime.infra.InMemoryRunRepository;
import com.sparkrooter.runtime.infra.inprocess.InProcessToolGatewayClient;
import com.sparkrooter.runtime.infra.inprocess.InProcessToolRegistryClient;
import com.sparkrooter.runtime.infra.llm.LlmCircuitBreaker;
import com.sparkrooter.runtime.infra.llm.LlmFactory;
import com.sparkrooter.runtime.infra.llm.LogLlmMetricsSink;
import com.sparkrooter.spi.ConfirmationRecheck;
import com.sparkrooter.spi.ConversationMemory;
import com.sparkrooter.spi.LlmMetricsSink;
import com.sparkrooter.spi.RunContextPropagator;
import com.sparkrooter.spi.ScreenBuilder;
import com.sparkrooter.spi.SessionIdResolver;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 决策面（Runtime）装配 + 宿主端口默认实现。宿主未提供 SessionIdResolver 时默认拒绝启动（starter 不能「默认不安全」）；
 * spark.runtime.demo-session-resolver=true 才放行演示实现并 WARN。RunContextPropagator 默认 no-op，构造期
 * WARN（spec §2.3 / §2.7）。
 */
@Configuration(proxyBeanMethods = false)
class RuntimeBeans {

  private static final Logger log = LoggerFactory.getLogger(RuntimeBeans.class);

  /** 缺宿主 SessionIdResolver 时的启动失败文案：两条出路都写明，避免接入方翻文档。 */
  static final String SESSION_RESOLVER_REQUIRED =
      "spark-rooter: no SessionIdResolver bean found. 会话隔离键必须绑定宿主登录态：请实现 com.sparkrooter.spi.SessionIdResolver 并注册为 Bean；"
          + "仅本地演示可设 spark.runtime.demo-session-resolver=true 使用演示实现（sessionId = conversationId，无会话隔离）。";

  // ---- 宿主端口默认实现

  @Bean
  @ConditionalOnMissingBean(SessionIdResolver.class)
  SessionIdResolver sparkRooterSessionIdResolver(SparkRooterProperties props) {
    if (!props.runtime().demoSessionResolver()) {
      throw new IllegalStateException(SESSION_RESOLVER_REQUIRED);
    }
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
  RunRepository sparkRooterRunRepository(SparkRooterProperties props, Clock sparkRooterClock) {
    return new InMemoryRunRepository(props.runtime().runTtl(), sparkRooterClock);
  }

  @Bean
  @ConditionalOnMissingBean(ConversationMemory.class)
  ConversationMemory sparkRooterConversationMemory(
      SparkRooterProperties props, Clock sparkRooterClock) {
    return new InMemoryConversationMemory(props.runtime().memoryTtl(), sparkRooterClock);
  }

  @Bean
  @ConditionalOnMissingBean(ConfirmationTokenStore.class)
  ConfirmationTokenStore sparkRooterConfirmationTokenStore() {
    return new InMemoryConfirmationTokenStore();
  }

  /** Run 编排池：有界 + AbortPolicy，过载时由 AgentRunController 转成 SSE run.failed 而非静默排队。 */
  @Bean(destroyMethod = "shutdown")
  ExecutorService sparkRooterRunExecutor(SparkRooterProperties props) {
    return NamedThreads.boundedPool(
        props.runtime().runPool(), props.runtime().runQueue(), "agent-run-");
  }

  @Bean
  ToolDisplayNames sparkRooterToolDisplayNames() {
    return new ToolDisplayNames();
  }

  /**
   * @SparkTool 元数据表（内核无默认表：全部领域语义来自注解）。
   */
  @Bean
  ToolMetaRegistry sparkRooterToolMetaRegistry() {
    return new ToolMetaRegistry();
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
        pick(props.llm().model(), env, "SPARK_LLM_MODEL"),
        props.llm().readTimeout());
  }

  /** LLM 熔断器：连续传输失败达阈值后短路，避免模型网关挂掉时线程池被占满。 */
  @Bean
  @ConditionalOnMissingBean(LlmCircuitBreaker.class)
  LlmCircuitBreaker sparkRooterLlmCircuitBreaker(
      SparkRooterProperties props, Clock sparkRooterClock) {
    var c = props.llm().circuit();
    return new LlmCircuitBreaker(
        c.enabled(), c.failureThreshold(), c.openDuration(), sparkRooterClock);
  }

  /** LLM 埋点：默认落 LLM_METRICS 日志；宿主要接 Micrometer 自行定义同类型 Bean 即覆盖。 */
  @Bean
  @ConditionalOnMissingBean(LlmMetricsSink.class)
  LlmMetricsSink sparkRooterLlmMetricsSink() {
    return new LogLlmMetricsSink();
  }

  /** 规划器：模型主导；未配置模型 → UnavailablePlanner（任何请求直接失败，不做规则兜底）。可信参数集合 = 各领域 recheck 声明的并集。 */
  @Bean
  @ConditionalOnMissingBean(LlmClient.class)
  LlmClient sparkRooterLlmClient(
      LlmFactory.SharedChat chat,
      ToolDisplayNames names,
      ToolMetaRegistry meta,
      SchemaValidator validator,
      ObjectProvider<ConfirmationRecheck> rechecks,
      SparkRooterProperties props,
      Environment env,
      LlmCircuitBreaker circuit,
      LlmMetricsSink llmMetrics) {
    Set<String> trusted = new java.util.HashSet<>();
    rechecks.orderedStream().forEach(r -> trusted.addAll(r.trustedArgKeys()));
    return LlmFactory.llmClient(
        chat,
        names,
        meta,
        validator,
        trusted,
        pick(props.llm().model(), env, "SPARK_LLM_MODEL"),
        circuit,
        llmMetrics);
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
  ConfirmationTokenService sparkRooterConfirmationTokenService(
      ConfirmationTokenStore store, Clock sparkRooterClock, SparkRooterProperties props) {
    return new ConfirmationTokenService(store, sparkRooterClock, props.runtime().tokenTtl());
  }

  @Bean
  RunOrchestrator sparkRooterRunOrchestrator(
      RunRepository runs,
      ToolRegistryClient registry,
      ToolGatewayClient gateway,
      LlmClient llm,
      ScreenRegistry screens,
      RecheckRegistry rechecks,
      ToolDisplayNames displayNames,
      ConfirmationTokenService tokens,
      ToolMetaRegistry meta,
      ConversationMemory memory,
      SchemaValidator validator,
      Clock sparkRooterClock) {
    return new RunOrchestrator(
        runs,
        registry,
        gateway,
        llm,
        screens,
        rechecks,
        displayNames,
        tokens,
        meta,
        memory,
        validator,
        sparkRooterClock);
  }
}
