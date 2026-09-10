package com.sparkrooter.starter;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * spark.* 配置（spec §2.3 默认值）。LLM 三项任一为空 → 规则规划器 + noop 分类器；三项同时兼容环境变量 SPARK_LLM_BASE_URL /
 * SPARK_LLM_API_KEY / SPARK_LLM_MODEL（见 {@link RuntimeBeans}），密钥不进日志。
 */
@ConfigurationProperties(prefix = "spark")
public record SparkRooterProperties(
    @DefaultValue Llm llm,
    @DefaultValue Runtime runtime,
    @DefaultValue Gateway gateway,
    @DefaultValue Web web,
    @DefaultValue Selfcheck selfcheck,
    @DefaultValue("host") String ownerTeam) {

  /** OpenAI 兼容端点；缺任一项即不启用模型。 */
  public record Llm(
      @DefaultValue("") String baseUrl,
      @DefaultValue("") String apiKey,
      @DefaultValue("") String model) {}

  /**
   * @param runPool Run 编排线程数
   * @param pingPool SSE ping 调度线程数
   * @param sseTimeout SSE 连接超时（LIVE 规划实测 9–39s）
   * @param tokenTtl 确认令牌有效期
   * @param memoryTtl 会话记忆有效期
   * @param runTtl Run 记录保留时间（按 updatedAt；到期后 GET /agent/runs/{id} 404，确认令牌自身有更短的 TTL）
   */
  public record Runtime(
      @DefaultValue("8") int runPool,
      @DefaultValue("2") int pingPool,
      @DefaultValue("90s") Duration sseTimeout,
      @DefaultValue("10m") Duration tokenTtl,
      @DefaultValue("30m") Duration memoryTtl,
      @DefaultValue("1h") Duration runTtl) {}

  /** 工具执行线程池大小。 */
  public record Gateway(@DefaultValue("8") int toolPool) {}

  /**
   * @param basePath /runs 端点前缀
   * @param internalEndpoints 是否装配 /internal/** 三个端点（默认不装配）
   */
  public record Web(
      @DefaultValue("/agent") String basePath, @DefaultValue("false") boolean internalEndpoints) {}

  /** 启动自检；示例宿主打开，生产建议关闭。 */
  public record Selfcheck(@DefaultValue("false") boolean enabled) {}
}
