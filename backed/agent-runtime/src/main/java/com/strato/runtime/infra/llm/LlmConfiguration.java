package com.strato.runtime.infra.llm;

import com.strato.runtime.application.ToolDisplayNames;
import com.strato.runtime.application.port.IntentClassifier;
import com.strato.runtime.application.port.LlmClient;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 装配。三个环境变量任一缺失 → RuleBasedLlmClient 并 WARN（backend-standard §7）。 不使用 Spring AI 的自动配置 starter
 * 属性（需要 key 才能启动），改为手工装配 OpenAiApi，以便无 key 也能启动。
 */
@Configuration
public class LlmConfiguration {

  private static final Logger log = LoggerFactory.getLogger(LlmConfiguration.class);

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

  @Bean
  public SharedChat stratoChatClient(
      @Value("${STRATO_LLM_BASE_URL:}") String baseUrl,
      @Value("${STRATO_LLM_API_KEY:}") String apiKey,
      @Value("${STRATO_LLM_MODEL:}") String model) {
    if (!configured(baseUrl, apiKey, model)) {
      log.warn(
          "STRATO_LLM_BASE_URL / STRATO_LLM_API_KEY / STRATO_LLM_MODEL not fully set; using rule-based planner and noop classifier");
      return new SharedChat(Optional.empty());
    }
    OpenAiApi api =
        OpenAiApi.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .completionsPath(completionsPath(baseUrl))
            .build();
    OpenAiChatModel chatModel = OpenAiChatModel.builder().openAiApi(api).build();
    // 只记录模型名与路径形态，不记录 baseUrl（内部网关地址不进日志 / 冻结产物）
    log.info(
        "LLM enabled: spring-ai openai-compatible model={} completionsPath={}",
        model,
        completionsPath(baseUrl));
    return new SharedChat(Optional.of(ChatClient.builder(chatModel).build()));
  }

  @Bean
  public LlmClient llmClient(SharedChat chat, @Value("${STRATO_LLM_MODEL:}") String model) {
    return chat.client()
        .<LlmClient>map(c -> new SpringAiLlmClient(c, model, ToolDisplayNames.all()))
        .orElseGet(() -> new RuleBasedLlmClient(ToolDisplayNames.all()));
  }

  @Bean
  public IntentClassifier intentClassifier(
      SharedChat chat, @Value("${STRATO_LLM_MODEL:}") String model) {
    return chat.client()
        .<IntentClassifier>map(c -> new SpringAiIntentClassifier(c, model))
        .orElseGet(NoopIntentClassifier::new);
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
