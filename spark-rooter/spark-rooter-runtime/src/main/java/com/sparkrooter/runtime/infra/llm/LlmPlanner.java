package com.sparkrooter.runtime.infra.llm;

import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.runtime.application.ToolDisplayNames;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.domain.RunFailure;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 模型主导的规划器（Spring AI 1.1，internalToolExecutionEnabled=false）：模型只输出结构化决策，PlanValidator 做通用校验；
 * 校验失败把原因喂回模型再试一次，仍失败 → TOOL_SELECTION_INVALID。实体复核失败转 Clarify（不是错误）。
 */
public final class LlmPlanner implements LlmClient {

  private static final Logger log = LoggerFactory.getLogger(LlmPlanner.class);
  private static final int MAX_ATTEMPTS = 2;

  private final ChatClient chat;
  private final String model;
  private final ToolDisplayNames displayNames;
  private final ToolMetaRegistry meta;
  private final SchemaValidator validator;
  private final Set<String> trustedOnlyArgs;

  /**
   * @param trustedOnlyArgs 需确认步骤中模型不得填写的参数名（来自各领域 ConfirmationRecheck.trustedArgKeys 的并集）
   */
  public LlmPlanner(
      ChatClient chat,
      String model,
      ToolDisplayNames displayNames,
      ToolMetaRegistry meta,
      SchemaValidator validator,
      Set<String> trustedOnlyArgs) {
    this.chat = chat;
    this.model = model;
    this.displayNames = displayNames;
    this.meta = meta;
    this.validator = validator;
    this.trustedOnlyArgs = Set.copyOf(trustedOnlyArgs);
  }

  @Override
  public Decision plan(PlanRequest req) {
    String feedback = null;
    RunFailure last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      PlanDraft draft;
      try {
        String userPrompt =
            PromptBuilder.user(req.message(), req.candidates(), req.context(), meta);
        if (feedback != null) {
          userPrompt += "\n上一次输出被拒绝，原因：" + feedback + "\n请修正后重新输出。";
        }
        draft =
            chat.prompt()
                .options(
                    OpenAiChatOptions.builder()
                        .model(model)
                        .internalToolExecutionEnabled(false)
                        .temperature(0.0)
                        .build())
                .system(PromptBuilder.system())
                .user(userPrompt)
                .call()
                .entity(PlanDraft.class);
      } catch (org.springframework.web.client.RestClientException
          | org.springframework.ai.retry.TransientAiException
          | org.springframework.ai.retry.NonTransientAiException e) {
        // 传输 / 上游 HTTP 错误：异常消息可能含网关地址与密钥片段，只记 upstream 返回的状态类文本片段（截断 + 去 sk- 串）
        log.warn(
            "llm upstream error type={} detail={}",
            e.getClass().getSimpleName(),
            redact(e.getMessage()));
        throw new RunFailure(
            "INTERNAL_ERROR", "llm transport failure: " + e.getClass().getSimpleName());
      } catch (RuntimeException e) {
        last = new RunFailure("TOOL_SELECTION_INVALID", "planner output unparseable");
        feedback = "输出不是合法的 JSON 对象";
        log.warn(
            "planner output unparseable attempt={}/{} cause={}",
            attempt,
            MAX_ATTEMPTS,
            e.getClass().getSimpleName());
        continue;
      }
      if (draft == null) {
        last = new RunFailure("TOOL_SELECTION_INVALID", "planner returned null");
        feedback = "没有输出";
        continue;
      }
      // 派发与校验由 PlanValidator.decide 承担（测试替身共用同一条路径）；这里只负责「失败则把原因喂回模型再试」
      try {
        return PlanValidator.decide(draft, req, displayNames, meta, trustedOnlyArgs, validator);
      } catch (RunFailure e) {
        last = e;
        feedback = e.getMessage();
        log.warn(
            "planner output rejected attempt={}/{} reason={}",
            attempt,
            MAX_ATTEMPTS,
            e.getMessage());
      }
    }
    throw last == null ? new RunFailure("TOOL_SELECTION_INVALID", "planner failed") : last;
  }

  /**
   * 上游错误详情脱敏：去掉 sk- 开头的密钥串、URL、模型名字段（OpenAI 兼容网关的错误响应常回显它），截断到 200 字符（doctor 有密钥形态扫描兜底）。
   *
   * <p>模型名、网关地址、密钥同属部署配置，不得进日志与冻结产物（agent-safety §5）。
   */
  private static String redact(String msg) {
    if (msg == null) {
      return "(no detail)";
    }
    String t =
        msg.replaceAll("sk-[A-Za-z0-9_-]+", "sk-***")
            .replaceAll("https?://\\S+", "<url>")
            .replaceAll("\"model\"\\s*:\\s*\"[^\"]+\"", "\"model\":\"***\"");
    return t.length() > 200 ? t.substring(0, 200) : t;
  }

  @Override
  public String name() {
    // 模型名与网关地址 / 密钥同属部署配置，不进日志与冻结产物
    return "llm";
  }
}
