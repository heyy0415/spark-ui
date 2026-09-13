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
    @DefaultValue Storage storage,
    @DefaultValue("host") String ownerTeam) {

  /**
   * 四个状态存储（Run / 确认令牌 / 幂等 / 会话记忆）放在哪。
   *
   * <p>{@code memory}：进程内，单副本或粘性路由下可用；重启即丢。{@code redis}：需引入 {@code spark-rooter-redis} 模块并配 {@code
   * spring.data.redis.*}，hub 可任意多副本。取值不认识时按 memory 处理并 WARN——不静默换成别的。
   *
   * @param type memory | redis
   */
  public record Storage(@DefaultValue("memory") String type) {}

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
   * @param runQueue Run 编排队列容量。默认 32 ≈ 4 倍核心数：排到第 32 位的预期等待 (32/8)×9~39s = 36~156s 已超出
   *     sseTimeout，后来者必然等不到结果，不如直接拒绝。<b>压测实测</b>（fake planner，见 change feat-production-hardening 的
   *     load_test.md）：独立会话 32 并发 0 拒绝、p99 55ms；64 并发从第 8 个请求开始拒绝，拒绝毫秒级返回， 无一条挂到超时。接真模型后一个线程被占几十秒，约
   *     40 个并发对话是单副本实际上限——要更多就水平扩，别调大队列（只是把拒绝延后成超时）
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
   * 工具执行线程池与幂等表。
   *
   * @param toolQueue 队列容量。默认 64 取 runQueue 的 2 倍，因为一个 Run 的计划可能含多个工具步骤（需确认的工具还要先跑前置只读步骤）。 压测实测：128
   *     并发下拒绝全部来自编排池，工具池从未成为瓶颈
   * @param maxConcurrentPerSession 单会话在飞工具调用上限；≤ 0 关闭限制。默认 4 与 {@code toolQueue=64} 挂钩——需 16
   *     个并发会话才能占满池，让「一个用户拖垮所有人」不再可能，同时 4 个并发只读查询对 正常交互（一次对话一个请求）有充足余量。**改 toolQueue 时应同步复核此值**。
   *     压测实测：同会话 4 并发 0 拒绝，8 并发开始出现 session busy
   * @param idempotencyTtl 幂等结果保留窗口（内存实现按它淘汰已完成记录；Redis 实现作 key TTL）。默认 24h：应长于任何合理的客户端重试窗口，
   *     短于一天没人会重试同一个 key；再长只是白占内存
   */
  public record Gateway(
      @DefaultValue("8") int toolPool,
      @DefaultValue("64") int toolQueue,
      @DefaultValue("4") int maxConcurrentPerSession,
      @DefaultValue("24h") Duration idempotencyTtl) {}

  /**
   * @param basePath /runs 端点前缀
   * @param internalEndpoints 是否装配 /internal/** 三个端点（默认不装配）
   */
  public record Web(
      @DefaultValue("/agent") String basePath, @DefaultValue("false") boolean internalEndpoints) {}

  /** 启动自检；示例宿主打开，生产建议关闭。 */
  public record Selfcheck(@DefaultValue("false") boolean enabled) {}
}
