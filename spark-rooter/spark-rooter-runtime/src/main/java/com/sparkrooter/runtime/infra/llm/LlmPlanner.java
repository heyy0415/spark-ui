package com.sparkrooter.runtime.infra.llm;

import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.runtime.application.ToolDisplayNames;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.domain.RunFailure;
import com.sparkrooter.spi.LlmMetricsSink;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
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
  private final LlmCircuitBreaker circuit;
  private final LlmMetricsSink metrics;

  /** 熔断打开时的用户可见文案：区别于「未配置模型」与「输出不合规」。 */
  static final String CIRCUIT_OPEN_TEXT = "模型服务暂时不可用，请稍后重试";

  /**
   * 熔断跳过的内部原因。抛出与埋点分类两处共用同一常量——散成字面量后，改文案会让埋点的 circuit_open 静默退化成 transport_error，且没有测试会红（评审 S-1）。
   */
  static final String CIRCUIT_OPEN_REASON = "llm circuit open";

  /**
   * @param trustedOnlyArgs 需确认步骤中模型不得填写的参数名（来自各领域 ConfirmationRecheck.trustedArgKeys 的并集）
   * @param circuit 传输失败熔断器；模型输出不合规不计入，否则「模型能力不足」会误触发熔断
   */
  public LlmPlanner(
      ChatClient chat,
      String model,
      ToolDisplayNames displayNames,
      ToolMetaRegistry meta,
      SchemaValidator validator,
      Set<String> trustedOnlyArgs,
      LlmCircuitBreaker circuit,
      LlmMetricsSink metrics) {
    this.chat = chat;
    this.model = model;
    this.displayNames = displayNames;
    this.meta = meta;
    this.validator = validator;
    this.trustedOnlyArgs = Set.copyOf(trustedOnlyArgs);
    this.circuit = circuit;
    this.metrics = metrics;
  }

  /**
   * 计时并在所有出口打点（成功、三类决策、校验失败、传输失败、熔断跳过），埋点失败不影响主流程。
   *
   * <p>包一层而不是在每个 return / throw 处各写一次：出口有六个，散落打点必漏。
   */
  @Override
  public Decision plan(PlanRequest req) {
    long startNanos = System.nanoTime();
    Usage usage = new Usage();
    try {
      Decision decision = planInternal(req, usage);
      emitMetrics(outcomeOf(decision), startNanos, usage, false);
      return decision;
    } catch (RunFailure e) {
      boolean circuitOpen = CIRCUIT_OPEN_REASON.equals(e.getMessage());
      String outcome =
          circuitOpen
              ? "circuit_open"
              : "TOOL_SELECTION_INVALID".equals(e.code()) ? "invalid_output" : "transport_error";
      emitMetrics(outcome, startNanos, usage, circuitOpen);
      throw e;
    }
  }

  /** 决策类别 → outcome 标签。 */
  private static String outcomeOf(Decision d) {
    if (d instanceof Planned) {
      return "planned";
    }
    return d instanceof Clarify ? "clarify" : "no_capability";
  }

  /** 埋点本身不能让请求失败：sink 是宿主可替换的，出错只记 debug。 */
  private void emitMetrics(String outcome, long startNanos, Usage usage, boolean circuitOpen) {
    long ms = (System.nanoTime() - startNanos) / 1_000_000;
    try {
      metrics.record(
          new LlmMetricsSink.Sample(
              outcome,
              ms,
              usage.promptTokens,
              usage.completionTokens,
              usage.attempts,
              circuitOpen));
    } catch (RuntimeException e) {
      log.debug("llm metrics sink failed: {}", e.getClass().getSimpleName());
    }
  }

  /** 从 ChatResponse 取 token 用量；上游网关不返回 usage 时保持 null（不写 0，那会被误读成「没消耗」）。 */
  private static void recordUsage(ChatResponse response, Usage usage) {
    if (response == null || response.getMetadata() == null) {
      return;
    }
    var u = response.getMetadata().getUsage();
    if (u == null) {
      return;
    }
    usage.promptTokens = u.getPromptTokens();
    usage.completionTokens = u.getCompletionTokens();
  }

  /** 可变的 token / 尝试次数累加器，由 planInternal 填充后交给打点。 */
  private static final class Usage {
    private Integer promptTokens;
    private Integer completionTokens;
    private int attempts;
  }

  private Decision planInternal(PlanRequest req, Usage usage) {
    // 熔断打开：不发请求，立刻失败并释放 agent-run 线程
    if (circuit.shouldSkip()) {
      log.warn("llm call skipped: circuit open");
      throw RunFailure.withUserText("INTERNAL_ERROR", CIRCUIT_OPEN_REASON, CIRCUIT_OPEN_TEXT);
    }
    String feedback = null;
    RunFailure last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      usage.attempts = attempt;
      PlanDraft draft;
      try {
        String userPrompt =
            PromptBuilder.user(req.message(), req.candidates(), req.context(), meta);
        if (feedback != null) {
          userPrompt += "\n上一次输出被拒绝，原因：" + feedback + "\n请修正后重新输出。";
        }
        // responseEntity 一次返回「原始 ChatResponse + 解析后的实体」，比先 entity() 再 chatResponse()
        // 少一次歧义（后者看起来像会再发一次请求）
        var response =
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
                .responseEntity(PlanDraft.class);
        recordUsage(response.response(), usage);
        draft = response.entity();
      } catch (org.springframework.web.client.RestClientException
          | org.springframework.ai.retry.TransientAiException
          | org.springframework.ai.retry.NonTransientAiException e) {
        // 传输 / 上游 HTTP 错误：异常消息可能含网关地址与密钥片段，只记 upstream 返回的状态类文本片段（截断 + 去 sk- 串）
        log.warn(
            "llm upstream error type={} detail={}",
            e.getClass().getSimpleName(),
            redact(e.getMessage()));
        circuit.recordTransportFailure();
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
      // 模型应答了 → 传输通路正常，清零熔断计数。输出是否合规是另一回事（见下方 catch）
      circuit.recordSuccess();
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
