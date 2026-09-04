package com.strato.runtime.infra.llm;

import com.strato.runtime.application.ToolDisplayNames;
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
    OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build();
    OpenAiChatModel chatModel = OpenAiChatModel.builder().openAiApi(api).build();
    ChatClient chat = ChatClient.builder(chatModel).build();
    log.info(
        "LLM planner enabled: spring-ai openai-compatible model={} baseUrl={}", model, baseUrl);
    return new SpringAiLlmClient(chat, model, ToolDisplayNames.all());
  }
}
