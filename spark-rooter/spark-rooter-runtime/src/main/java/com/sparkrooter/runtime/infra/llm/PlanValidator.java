package com.sparkrooter.runtime.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.contracts.model.ToolSearch;
import com.sparkrooter.runtime.application.ToolDisplayNames;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.domain.Plan;
import com.sparkrooter.runtime.domain.RunFailure;
import com.sparkrooter.runtime.domain.Step;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 模型输出的通用校验（agent-safety §2 / §3）。与领域无关，是模型主导规划下唯一的硬边界：
 *
 * <ol>
 *   <li>toolId ∈ 候选集合
 *   <li>args 键 ⊆ inputSchema.properties，值过 JSON Schema
 *   <li>实体参数的值必须能在「用户原话 ∪ 会话记忆实体值 ∪ 最近列表行 ID」中找到，且匹配 @SparkParam.pattern（有则校）——模型负责理解，代码负责核实
 *   <li>需确认步骤：前置齐全且在前；可信参数（ConfirmationRecheck.trustedArgKeys）不得由模型填写
 *   <li>@SparkDefault 补齐未填参数，使 argsDigest 确定
 * </ol>
 *
 * 1 / 2 / 4 违反抛 TOOL_SELECTION_INVALID（调用方重试一次）；3 违反视为缺实体（EntityMissing）。
 */
public final class PlanValidator {

  private PlanValidator() {}

  /** 实体复核失败：模型填的实体值不在原话与上下文中。 */
  public static final class EntityMissing extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient String entityType;

    EntityMissing(String entityType, String reason) {
      super(reason);
      this.entityType = entityType;
    }

    public String entityType() {
      return entityType;
    }
  }

  public static Plan validate(
      PlanDraft draft,
      String message,
      LlmClient.Context ctx,
      List<ToolSearch.ToolCandidate> candidates,
      ToolDisplayNames displayNames,
      ToolMetaRegistry meta,
      Set<String> trustedOnlyArgs,
      SchemaValidator validator) {
    if (draft.steps() == null || draft.steps().isEmpty()) {
      throw new RunFailure("TOOL_SELECTION_INVALID", "planner returned no steps");
    }
    Map<String, ToolSearch.ToolCandidate> byId =
        candidates.stream()
            .collect(Collectors.toMap(ToolSearch.ToolCandidate::toolId, Function.identity()));
    Set<String> knownIds = knownIds(message, ctx);
    List<Step> steps = new ArrayList<>();
    int seq = 1;
    for (PlanDraft.DraftStep d : draft.steps()) {
      ToolSearch.ToolCandidate c = byId.get(d.toolId());
      if (c == null) {
        throw new RunFailure("TOOL_SELECTION_INVALID", "toolId not in candidates: " + d.toolId());
      }
      Map<String, String> args = new LinkedHashMap<>(d.args() == null ? Map.of() : d.args());
      JsonNode props = c.inputSchema().path("properties");
      for (Map.Entry<String, String> e : args.entrySet()) {
        String k = e.getKey();
        if (!props.has(k)) {
          throw new RunFailure(
              "TOOL_SELECTION_INVALID", "arg not in inputSchema of " + d.toolId() + ": " + k);
        }
        assertValueMatches(props.path(k), k, e.getValue(), d.toolId(), validator);
        ToolMetaRegistry.ParamMeta pm = meta.param(c.toolId(), k);
        if (pm != null && pm.isEntity()) {
          verifyEntity(pm, e.getValue(), knownIds, d.toolId());
        }
      }
      // 默认值补齐（写进 args 使 argsDigest 确定）
      meta.find(c.toolId())
          .ifPresent(
              m ->
                  m.params()
                      .values()
                      .forEach(
                          p -> {
                            if (p.defaultValue() != null) {
                              args.putIfAbsent(p.name(), p.defaultValue());
                            }
                          }));
      boolean confirm =
          c.confirmation() == ToolManifest.Confirmation.required
              || c.riskLevel() == ToolManifest.RiskLevel.high;
      if (confirm) {
        for (String pre : meta.prerequisites(c.toolId())) {
          boolean before = steps.stream().anyMatch(st -> st.toolId().equals(pre));
          if (!before) {
            throw new RunFailure(
                "TOOL_SELECTION_INVALID",
                "confirmation step " + c.toolId() + " missing prerequisite " + pre);
          }
        }
        for (String k : args.keySet()) {
          if (trustedOnlyArgs.contains(k)) {
            throw new RunFailure(
                "TOOL_SELECTION_INVALID",
                "planner must not set trusted-only arg on confirmation step "
                    + d.toolId()
                    + ": "
                    + k);
          }
        }
      }
      // 必填实体参数缺失 → 缺实体（不是校验失败）
      for (JsonNode n : c.inputSchema().path("required")) {
        String k = n.asText();
        ToolMetaRegistry.ParamMeta pm = meta.param(c.toolId(), k);
        if (pm != null && pm.isEntity() && !args.containsKey(k)) {
          throw new EntityMissing(pm.entity(), "required entity arg " + k + " missing");
        }
      }
      steps.add(
          new Step(seq++, c.toolId(), c.version(), displayNames.of(c.toolId()), args, confirm));
    }
    return new Plan(steps.get(0).toolId().split("\\.")[0], steps);
  }

  /** 原话 ∪ 记忆实体值 ∪ 最近列表行 ID：实体值必须能在其中找到（原话用子串匹配）。 */
  static Set<String> knownIds(String message, LlmClient.Context ctx) {
    Set<String> ids = new HashSet<>(ctx.entities().values());
    ids.addAll(ctx.lastRowIds());
    ids.add(message == null ? "" : message);
    return ids;
  }

  static void verifyEntity(
      ToolMetaRegistry.ParamMeta pm, String value, Set<String> knownIds, String toolId) {
    if (pm.pattern() != null && !Pattern.compile(pm.pattern()).matcher(value).matches()) {
      throw new EntityMissing(
          pm.entity(), "entity arg " + pm.name() + " of " + toolId + " does not match pattern");
    }
    boolean found = knownIds.stream().anyMatch(k -> k.equals(value) || k.contains(value));
    if (!found) {
      throw new EntityMissing(
          pm.entity(),
          "entity arg " + pm.name() + " of " + toolId + " not present in message or context");
    }
  }

  /** 单参数值校验：按 inputSchema.properties[k] 校验（string 直接校；integer / boolean 先转，转不了即不合规）。 */
  static void assertValueMatches(
      JsonNode propSchema, String key, String value, String toolId, SchemaValidator validator) {
    if (propSchema == null || propSchema.isMissingNode()) {
      return;
    }
    JsonNode typed;
    String type = propSchema.path("type").asText("string");
    try {
      typed =
          switch (type) {
            case "integer" -> validator.mapper().getNodeFactory().numberNode(Long.parseLong(value));
            case "number" ->
                validator.mapper().getNodeFactory().numberNode(Double.parseDouble(value));
            case "boolean" ->
                validator.mapper().getNodeFactory().booleanNode(Boolean.parseBoolean(value));
            default -> validator.mapper().getNodeFactory().textNode(value);
          };
    } catch (NumberFormatException e) {
      throw new RunFailure(
          "TOOL_SELECTION_INVALID", "arg " + key + " of " + toolId + " is not a " + type);
    }
    var errors = validator.validateWithInlineSchema(propSchema, typed);
    if (!errors.isEmpty()) {
      throw new RunFailure(
          "TOOL_SELECTION_INVALID",
          "arg "
              + key
              + " of "
              + toolId
              + " violates inputSchema: "
              + errors.iterator().next().getMessage());
    }
  }

  /**
   * 缺失的实体类型：优先取模型声明的 missing[]；模型只给了目标工具（steps[0]）没填 missing 时，从该工具 inputSchema.required
   * 里第一个尚未填值的实体参数反推——模型常只在 reply 里用自然语言追问，不能因此丢掉澄清屏。
   */
  static Optional<String> missingEntity(
      PlanDraft draft, List<ToolSearch.ToolCandidate> candidates, ToolMetaRegistry meta) {
    Optional<String> declared =
        draft.missing() == null
            ? Optional.empty()
            : draft.missing().stream()
                .map(PlanDraft.Missing::entity)
                .filter(e -> e != null && !e.isBlank())
                .findFirst();
    // 模型可能把中文 label 当类型名写回来：规范化到已注册的类型名，失败则继续按目标工具反推
    Optional<String> normalized = declared.flatMap(meta::normalizeEntityType);
    if (normalized.isPresent()) {
      return normalized;
    }
    if (draft.steps() == null || draft.steps().isEmpty()) {
      return Optional.empty();
    }
    PlanDraft.DraftStep step = draft.steps().get(0);
    ToolSearch.ToolCandidate c =
        candidates.stream().filter(x -> x.toolId().equals(step.toolId())).findFirst().orElse(null);
    if (c == null) {
      return Optional.empty();
    }
    Map<String, String> args = step.args() == null ? Map.of() : step.args();
    for (JsonNode n : c.inputSchema().path("required")) {
      String k = n.asText();
      ToolMetaRegistry.ParamMeta pm = meta.param(c.toolId(), k);
      if (pm != null && pm.isEntity() && !args.containsKey(k)) {
        return Optional.of(pm.entity());
      }
    }
    return Optional.empty();
  }
}
