package com.sparkrooter.runtime.infra.llm;

import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.runtime.application.ToolDisplayNames;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.spi.LlmMetricsSink;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/**
 * LLM 工厂（纯静态，starter 的 RuntimeBeans 装配）。三项配置任一缺失 → UnavailablePlanner 并 WARN：无模型不执行，不做规则兜底。 不使用
 * Spring AI 的自动配置 starter 属性（需要 key 才能启动），改为手工装配 OpenAiApi，以便无 key 也能启动。
 */
public final class LlmFactory {

  private static final Logger log = LoggerFactory.getLogger(LlmFactory.class);

  private LlmFactory() {}

  /** 是否配置齐三个环境变量。 */
  private static boolean configured(String baseUrl, String apiKey, String model) {
    return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
  }

  /** 共用 ChatClient 的持有者：只在三个变量齐全时创建 ChatClient；缺失时为 empty。 */
  public record SharedChat(Optional<ChatClient> client) {}

  /**
   * @param readTimeout 上游读超时。由 spark.llm.read-timeout 配置（默认 90s，与 sse-timeout 对齐）—— 读超时超过 SSE
   *     超时没有意义：SSE 先断，用户看不到结果而后端还在等
   */
  public static SharedChat chat(String baseUrl, String apiKey, String model, Duration readTimeout) {
    if (!configured(baseUrl, apiKey, model)) {
      log.warn(
          "spark.llm.base-url / api-key / model (or SPARK_LLM_*) not fully set; planner UNAVAILABLE — every request will fail until a model is configured");
      return new SharedChat(Optional.empty());
    }
    // 显式配置 HTTP 超时：Spring AI 默认 RestClient 的读超时对推理型模型太短（实测规划请求 10～60s，
    // 默认设置下直接 ResourceAccessException）。
    var factory = new JdkClientHttpRequestFactory();
    factory.setReadTimeout(readTimeout);
    OpenAiApi api =
        OpenAiApi.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .completionsPath(completionsPath(baseUrl))
            .restClientBuilder(RestClient.builder().requestFactory(factory))
            .build();
    OpenAiChatModel chatModel =
        OpenAiChatModel.builder().openAiApi(api).retryTemplate(fastFailRetry()).build();
    // 只记录路径形态；baseUrl / 模型名 / 密钥都是部署配置，不进日志与冻结产物
    log.info(
        "LLM enabled: spring-ai openai-compatible completionsPath={}", completionsPath(baseUrl));
    return new SharedChat(Optional.of(ChatClient.builder(chatModel).build()));
  }

  /** 规划器：有模型 → LlmPlanner；否则 UnavailablePlanner（任何请求直接失败）。 */
  public static LlmClient llmClient(
      SharedChat chat,
      ToolDisplayNames names,
      ToolMetaRegistry meta,
      SchemaValidator validator,
      Set<String> trustedOnlyArgs,
      String model,
      LlmCircuitBreaker circuit,
      LlmMetricsSink metrics) {
    return chat.client()
        .<LlmClient>map(
            c ->
                new LlmPlanner(c, model, names, meta, validator, trustedOnlyArgs, circuit, metrics))
        .orElseGet(UnavailablePlanner::new);
  }

  /**
   * 替换 Spring AI 默认 RetryTemplate（10 次、指数退避到 3 分钟，且监听器把含 baseUrl 的异常全文打进日志）： 上游 503 / 抖动重试 3 次、指数退避
   * 1s→2s→4s，既能穿过网关短时故障，又不至于拖到 SSE 客户端超时；监听器只记异常类名，不记消息（消息含网关地址）。
   */
  static RetryTemplate fastFailRetry() {
    return RetryTemplate.builder()
        // 上游 503 / 限流很常见：3 次、指数退避（1s → 2s → 4s，上限 5s），总耗时仍在 SSE 超时内
        .maxAttempts(3)
        .exponentialBackoff(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(5))
        .retryOn(RuntimeException.class)
        .withListener(
            new RetryListener() {
              @Override
              public <T, E extends Throwable> void onError(
                  RetryContext ctx, RetryCallback<T, E> cb, Throwable t) {
                Throwable root = t;
                while (root.getCause() != null) {
                  root = root.getCause();
                }
                log.warn(
                    "LLM call failed attempt={} top={} root={}",
                    ctx.getRetryCount(),
                    t.getClass().getSimpleName(),
                    root.getClass().getSimpleName());
              }
            })
        .build();
  }

  /**
   * Spring AI 默认把 "/v1/chat/completions" 拼到 baseUrl 后面。很多 OpenAI 兼容网关的 baseUrl 已含版本段（如
   * ".../api/v1"）， 再拼 "/v1" 会变成 ".../api/v1/v1/chat/completions" → 404。规则：baseUrl 以 "/v{n}" 结尾则只拼
   * "/chat/completions"。
   */
  static String completionsPath(String baseUrl) {
    String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    return trimmed.matches(".*/v\\d+$") ? "/chat/completions" : "/v1/chat/completions";
  }
}
