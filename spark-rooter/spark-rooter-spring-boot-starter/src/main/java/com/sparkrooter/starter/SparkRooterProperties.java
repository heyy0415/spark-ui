package com.sparkrooter.starter;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * spark.* 配置（spec §2.3 默认值）。LLM 三项任一为空 → {@code UnavailablePlanner}：所有请求直接失败并返回「未配置模型，无法理解请求」，
 * 不做规则兜底；三项同时兼容环境变量 SPARK_LLM_BASE_URL / SPARK_LLM_API_KEY / SPARK_LLM_MODEL（见 {@link
 * RuntimeBeans}），密钥不进日志。
 */
@ConfigurationProperties(prefix = "spark")
public record SparkRooterProperties(
    @DefaultValue Llm llm,
    @DefaultValue Runtime runtime,
    @DefaultValue Gateway gateway,
    @DefaultValue Web web,
    @DefaultValue Selfcheck selfcheck,
    @DefaultValue Providers providers,
    @DefaultValue("host") String ownerTeam) {

  /**
   * 远程 provider 的调用方认证（微服务形态）。
   *
   * <p><b>不配 = 本 hub 不接受远程工具</b>：{@code RegistrationGuard} 拒绝一切 {@code protocol=http} 注册，且不装配
   * {@code HttpToolTransport}。单体宿主什么都不用配，行为与本 change 之前完全一致。
   *
   * <p>配置形如 {@code spark.providers.tokens.order-service=xxx}。令牌与服务名绑定——只校验「令牌 有效」不够，那样任一 provider
   * 被攻破即可冒充其他所有 provider 注册伪造的高危工具。
   *
   * @param tokens serviceName → 共享密钥；密钥不进日志
   */
  public record Providers(@DefaultValue java.util.Map<String, String> tokens) {}

  /**
   * OpenAI 兼容端点；缺任一项即不启用模型。
   *
   * @param readTimeout 上游读超时。默认 90s 与 {@code runtime.sse-timeout} 对齐——读超时超过 SSE 超时没有意义（SSE
   *     先断，用户看不到结果而后端还在等）。推理模型需要更久时，两者要一起配大
   */
  public record Llm(
      @DefaultValue("") String baseUrl,
      @DefaultValue("") String apiKey,
      @DefaultValue("") String model,
      @DefaultValue("90s") Duration readTimeout,
      @DefaultValue Circuit circuit) {}

  /**
   * LLM 熔断：连续传输失败达阈值后短路，不再发请求，直接告知用户不可用。
   *
   * @param failureThreshold 连续失败阈值。默认 2 而非更大值，因为 Spring AI 的 {@code RetryTemplate} 已在 {@code
   *     OpenAiChatModel} 内部重试 3 次——熔断器看到的「一次失败」= 3 次真实网关请求，取 2 意味着 6 次真实请求后熔断。
   *     只统计传输类失败；模型输出不合规（TOOL_SELECTION_INVALID）不计入，否则「模型能力不足」会误触发熔断
   * @param openDuration OPEN 状态的静默期，之后转 HALF_OPEN 放一个探测请求
   */
  public record Circuit(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("2") int failureThreshold,
      @DefaultValue("30s") Duration openDuration) {}

  /**
   * @param runPool Run 编排线程数
   * @param pingPool SSE ping 调度线程数
   * @param sseTimeout SSE 连接超时（LIVE 规划实测 9–39s）
   * @param tokenTtl 确认令牌有效期
   * @param memoryTtl 会话记忆有效期
   * @param runTtl Run 记录保留时间（按 updatedAt；到期后 GET /agent/runs/{id} 404，确认令牌自身有更短的 TTL）
   * @param demoSessionResolver 宿主未提供 {@code SessionIdResolver} 时是否允许用演示实现（sessionId =
   *     conversationId，无会话隔离）。 默认 false：缺宿主实现直接拒绝启动，避免 starter「默认不安全」；只在本地演示时显式打开。
   * @param runQueue Run 编排队列容量。默认 32 ≈ 4 倍核心数：按规划实测 9–39s 估算，排到第 32 位的预期等待 (32/8)×9~39s = 36~156s
   *     已超出 sseTimeout 上限，即后来者必然等不到结果，不如直接拒绝。调整时请按 sseTimeout 与实际规划耗时重算
   */
  public record Runtime(
      @DefaultValue("8") int runPool,
      @DefaultValue("2") int pingPool,
      @DefaultValue("90s") Duration sseTimeout,
      @DefaultValue("10m") Duration tokenTtl,
      @DefaultValue("30m") Duration memoryTtl,
      @DefaultValue("1h") Duration runTtl,
      @DefaultValue("false") boolean demoSessionResolver,
      @DefaultValue("32") int runQueue) {}

  /**
   * 工具执行线程池。
   *
   * @param toolQueue 队列容量。默认 64 取 runQueue 的 2 倍，因为一个 Run 的计划可能含多个工具步骤（需确认的工具还要先跑前置只读步骤）
   */
  public record Gateway(@DefaultValue("8") int toolPool, @DefaultValue("64") int toolQueue) {}

  /**
   * @param basePath /runs 端点前缀
   * @param internalEndpoints 是否装配 /internal/** 三个端点（默认不装配）
   */
  public record Web(
      @DefaultValue("/agent") String basePath, @DefaultValue("false") boolean internalEndpoints) {}

  /** 启动自检；示例宿主打开，生产建议关闭。 */
  public record Selfcheck(@DefaultValue("false") boolean enabled) {}
}
