package com.sparkrooter.runtime.infra.llm;

import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.model.ToolSearch;
import com.sparkrooter.runtime.application.ToolDisplayNames;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.domain.Plan;
import com.sparkrooter.runtime.domain.RunFailure;
import java.time.Clock;
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

  private final ToolMetaRegistry meta;
  private final Clock clock;
  private final SchemaValidator validator;

  public SpringAiLlmClient(
      ChatClient chat,
      String model,
      ToolDisplayNames displayNames,
      ToolMetaRegistry meta,
      Clock clock,
      SchemaValidator validator) {
    this.chat = chat;
    this.model = model;
    this.displayNames = displayNames;
    this.meta = meta;
    this.clock = clock;
    this.validator = validator;
  }

  @Override
  public Plan plan(PlanRequest req) {
    // 与规则模式一致的确定性前置：动词命中的目标工具不在候选（如无权用户说「删除」）→ 不调模型，直接 TOOL_SELECTION_INVALID
    ToolSelectionValidator.preflight(
        req.message(), req.domain(), req.candidates(), req.entities(), meta);
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
        return ToolSelectionValidator.validate(
            fillDefaults(draft, req),
            req.domain(),
            req.candidates(),
            displayNames,
            req.entities(),
            meta,
            validator);
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
      } catch (org.springframework.web.client.RestClientException
          | org.springframework.ai.retry.TransientAiException
          | org.springframework.ai.retry.NonTransientAiException e) {
        // 传输 / 上游 HTTP 错误（含 Spring AI 对 4xx / 5xx 的包装，它们不继承 RestClientException）：
        // 异常消息含网关地址与响应体，不得进日志与 SSE；只保留类名，不带 cause
        throw new RunFailure(
            "INTERNAL_ERROR", "llm transport failure: " + e.getClass().getSimpleName());
      } catch (RuntimeException e) {
        // 模型输出解析失败（非 JSON / 结构不符）：与校验失败同等对待，再给一次机会
        last = new RunFailure("TOOL_SELECTION_INVALID", "planner output unparseable");
        log.warn(
            "planner output unparseable attempt={}/{} cause={}",
            attempt,
            MAX_ATTEMPTS,
            e.getClass().getSimpleName());
      }
    }
    throw last;
  }

  /** 模型未填的参数用确定性抽取值与 @SparkDefault 补齐（模型填了的保留，由校验器按 schema 校验），使两种模式的 argsDigest 一致。 */
  private LlmPlanDraft fillDefaults(LlmPlanDraft draft, PlanRequest req) {
    if (draft == null || draft.steps() == null) {
      return draft;
    }
    java.util.Map<String, ToolSearch.ToolCandidate> byId = new java.util.HashMap<>();
    req.candidates().forEach(c -> byId.put(c.toolId(), c));
    java.util.List<LlmPlanDraft.DraftStep> steps = new java.util.ArrayList<>();
    for (LlmPlanDraft.DraftStep d : draft.steps()) {
      ToolSearch.ToolCandidate c = byId.get(d.toolId());
      if (c == null) {
        steps.add(d);
        continue;
      }
      java.util.Map<String, String> merged =
          new java.util.LinkedHashMap<>(
              RuleBasedLlmClient.argsFor(c, req.message(), req.entities(), meta, clock));
      if (d.args() != null) {
        merged.putAll(d.args());
      }
      steps.add(new LlmPlanDraft.DraftStep(d.toolId(), merged));
    }
    return new LlmPlanDraft(steps);
  }

  @Override
  public String name() {
    // 模型名与网关地址 / 密钥同属部署配置，不进日志与冻结产物
    return "spring-ai";
  }
}
