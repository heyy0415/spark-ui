package com.strato.runtime.infra.llm;

import com.strato.runtime.application.ToolDisplayNames;
import com.strato.runtime.application.port.LlmClient;
import com.strato.runtime.domain.Plan;
import com.strato.runtime.domain.RunFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Spring AI 1.1 实现（backend-standard §1）：internalToolExecutionEnabled=false，模型只输出结构化计划草案； 候选外 toolId
 * 由 ToolSelectionValidator 拒绝并重试一次，仍失败 → TOOL_SELECTION_INVALID。
 */
public final class SpringAiLlmClient implements LlmClient {

  private static final Logger log = LoggerFactory.getLogger(SpringAiLlmClient.class);
  private static final int MAX_ATTEMPTS = 2;

  private final ChatClient chat;
  private final String model;
  private final ToolDisplayNames displayNames;

  public SpringAiLlmClient(ChatClient chat, String model, ToolDisplayNames displayNames) {
    this.chat = chat;
    this.model = model;
    this.displayNames = displayNames;
  }

  @Override
  public Plan plan(PlanRequest req) {
    RunFailure last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        LlmPlanDraft draft =
            chat.prompt()
                .options(
                    OpenAiChatOptions.builder()
                        .model(model)
                        .internalToolExecutionEnabled(false)
                        .temperature(0.0)
                        .build())
                .system(PromptBuilder.system())
                .user(
                    PromptBuilder.user(req.message(), req.domain(), req.candidates(), req.entity()))
                .call()
                .entity(LlmPlanDraft.class);
        return ToolSelectionValidator.validate(draft, req.domain(), req.candidates(), displayNames);
      } catch (RunFailure e) {
        last = e;
        log.warn(
            "planner output rejected attempt={}/{} reason={}",
            attempt,
            MAX_ATTEMPTS,
            e.getMessage());
      }
    }
    throw last;
  }

  @Override
  public String name() {
    return "spring-ai:" + model;
  }
}
