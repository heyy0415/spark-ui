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
    // 与规则模式一致的确定性前置：动词命中的目标工具不在候选（如无权用户说「删除」）→ 不调模型，直接 TOOL_SELECTION_INVALID
    ToolSelectionValidator.preflight(req.message(), req.domain(), req.candidates(), req.entities());
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
                    PromptBuilder.user(
                        req.message(), req.domain(), req.candidates(), req.entities()))
                .call()
                .entity(LlmPlanDraft.class);
        return ToolSelectionValidator.validate(draft, req.domain(), req.candidates(), displayNames);
      } catch (MissingEntity e) {
        // 目标工具缺必填实体：编排器走友好提示，不重试、不当传输错误
        throw e;
      } catch (RunFailure e) {
        last = e;
        log.warn(
            "planner output rejected attempt={}/{} reason={}",
            attempt,
            MAX_ATTEMPTS,
            e.getMessage());
      } catch (RuntimeException e) {
        // 传输 / 上游错误：异常消息含网关地址，不得进日志与 SSE；只保留类名，不带 cause
        throw new RunFailure(
            "INTERNAL_ERROR", "llm transport failure: " + e.getClass().getSimpleName());
      }
    }
    throw last;
  }

  @Override
  public String name() {
    return "spring-ai:" + model;
  }
}
