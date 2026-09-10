package com.sparkrooter.runtime.infra.llm;

import com.sparkrooter.runtime.application.ToolDisplayNames;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.runtime.application.port.IntentClassifier;
import com.sparkrooter.runtime.application.port.LlmClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetryTemplate;

/**
 * LLM 工厂（纯静态，starter 的 RuntimeBeans 装配）。三项配置任一缺失 → RuleBasedLlmClient 并 WARN（backend-standard §7）。
 * 不使用 Spring AI 的自动配置 starter 属性（需要 key 才能启动），改为手工装配 OpenAiApi，以便无 key 也能启动。
 */
public final class LlmFactory {

  private static final Logger log = LoggerFactory.getLogger(LlmFactory.class);

  private LlmFactory() {}

  /** 是否配置齐三个环境变量；分类器与规划器共用同一判定。 */
  private static boolean configured(String baseUrl, String apiKey, String model) {
    return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
  }

  /**
   * 共用 ChatClient 的持有者：只在三个变量齐全时创建 ChatClient；缺失时为 empty，两个消费方各自回退（规划器 → 规则模板，分类器 → noop）。不能直接把
   * Optional&lt;ChatClient&gt; 注册为 Bean —— Spring 会把注入点的 Optional 解释为「可选依赖一个 ChatClient
   * Bean」而绕过它（实测：LLM enabled 已打日志，规划器却仍是 rule-based）。
   */
  public record SharedChat(Optional<ChatClient> client) {}

  public static SharedChat chat(String baseUrl, String apiKey, String model) {
    if (!configured(baseUrl, apiKey, model)) {
      log.warn(
          "spark.llm.base-url / api-key / model (or SPARK_LLM_*) not fully set; using rule-based planner and noop classifier");
      return new SharedChat(Optional.empty());
    }
    OpenAiApi api =
        OpenAiApi.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .completionsPath(completionsPath(baseUrl))
            .build();
    OpenAiChatModel chatModel =
        OpenAiChatModel.builder().openAiApi(api).retryTemplate(fastFailRetry()).build();
    // 只记录路径形态；baseUrl / 模型名 / 密钥都是部署配置，不进日志与冻结产物
    log.info(
        "LLM enabled: spring-ai openai-compatible completionsPath={}", completionsPath(baseUrl));
    return new SharedChat(Optional.of(ChatClient.builder(chatModel).build()));
  }

  /** 两个实现都持有 ToolDisplayNames Bean 引用（Registry 启动时才回填 name），不能取构造期快照。 */
  public static LlmClient llmClient(
      SharedChat chat, ToolDisplayNames names, ToolMetaRegistry meta, Clock clock, String model) {
    return chat.client()
        .<LlmClient>map(c -> new SpringAiLlmClient(c, model, names, meta, clock))
        .orElseGet(() -> new RuleBasedLlmClient(names, meta, clock));
  }

  public static IntentClassifier intentClassifier(SharedChat chat, String model) {
    return chat.client()
        .<IntentClassifier>map(c -> new SpringAiIntentClassifier(c, model))
        .orElseGet(NoopIntentClassifier::new);
  }

  /**
   * 替换 Spring AI 默认 RetryTemplate（10 次、指数退避到 3 分钟，且监听器把含 baseUrl 的异常全文打进日志）： 上游抖动时最多重试 1 次、退避
   * 500ms，让一次规划在秒级内失败而不是拖到 SSE 客户端超时；监听器只记异常类名，不记消息（消息含网关地址）。
   */
  static RetryTemplate fastFailRetry() {
    return RetryTemplate.builder()
        .maxAttempts(2)
        .fixedBackoff(Duration.ofMillis(500))
        .retryOn(RuntimeException.class)
        .withListener(
            new RetryListener() {
              @Override
              public <T, E extends Throwable> void onError(
                  RetryContext ctx, RetryCallback<T, E> cb, Throwable t) {
                log.warn(
                    "LLM call failed attempt={} cause={}",
                    ctx.getRetryCount(),
                    t.getClass().getSimpleName());
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
