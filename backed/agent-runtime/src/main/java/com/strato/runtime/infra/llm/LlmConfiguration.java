package com.strato.runtime.infra.llm;

import com.strato.runtime.application.ToolDisplayNames;
import com.strato.runtime.application.port.IntentClassifier;
import com.strato.runtime.application.port.LlmClient;
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

  @Bean
  public LlmClient llmClient(
      @Value("${STRATO_LLM_BASE_URL:}") String baseUrl,
      @Value("${STRATO_LLM_API_KEY:}") String apiKey,
      @Value("${STRATO_LLM_MODEL:}") String model) {
    if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
      log.warn(
          "STRATO_LLM_BASE_URL / STRATO_LLM_API_KEY / STRATO_LLM_MODEL not fully set; using rule-based planner");
      return new RuleBasedLlmClient(ToolDisplayNames.all());
    }
    ChatClient chat = chatClient(baseUrl, apiKey);
    log.info(
        "LLM planner enabled: spring-ai openai-compatible model={} baseUrl={} completionsPath={}",
        model,
        baseUrl,
        completionsPath(baseUrl));
    return new SpringAiLlmClient(chat, model, ToolDisplayNames.all());
  }

  /** 意图分类器与规划器共用同一组环境变量与 ChatClient 装配（backend-standard §7）。 */
  @Bean
  public IntentClassifier intentClassifier(
      @Value("${STRATO_LLM_BASE_URL:}") String baseUrl,
      @Value("${STRATO_LLM_API_KEY:}") String apiKey,
      @Value("${STRATO_LLM_MODEL:}") String model) {
    if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
      return new NoopIntentClassifier();
    }
    log.info("LLM intent classifier enabled: model={}", model);
    return new SpringAiIntentClassifier(chatClient(baseUrl, apiKey), model);
  }

  private static ChatClient chatClient(String baseUrl, String apiKey) {
    OpenAiApi api =
        OpenAiApi.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .completionsPath(completionsPath(baseUrl))
            .build();
    OpenAiChatModel chatModel = OpenAiChatModel.builder().openAiApi(api).build();
    return ChatClient.builder(chatModel).build();
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
