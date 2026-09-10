package com.sparkrooter.runtime.infra.llm;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模型输出校验（agent-safety §2）：每个 toolId 必须在候选集合内，args 键必须在该工具 inputSchema.properties 内； 任一违反抛
 * TOOL_SELECTION_INVALID。高风险 / confirmation=required 的步骤标记 requiresConfirmation。
 *
 * <p>需确认步骤的金额类参数不允许由模型决定（agent-safety §3：金额在确认后由后端可信来源重算并覆盖）， 模型若填写即视为越权输出而拒绝。
 *
 * <p>需确认步骤的前置只读步骤（ToolMetaRegistry.prerequisites：注解声明或内核默认表）必须齐全且在它之前；需确认步骤的必填实体参数缺失 →
 * MissingEntity（与规则模式一致）。
 */
public final class ToolSelectionValidator {

  /** 模型不得为需确认步骤填写的参数：金额一律来自后端试算 / 重校验。 */
  static final Set<String> TRUSTED_ONLY_ARGS = Set.of("amount");

  private ToolSelectionValidator() {}

  /**
   * 规划前的确定性检查（两种模式共用，spec §2.4.3）：消息动词命中目标工具时——① 目标不在候选内（权限过滤掉了）→
   * TOOL_SELECTION_INVALID，不让模型退化成「只做只读步骤」；② 目标必填实体缺失（如无号码的「删除订单」）→ MissingEntity，
   * 编排器走友好提示。模型只在这两条都通过后才被调用。
   */
  public static void preflight(
      String message,
      String domain,
      List<ToolSearch.ToolCandidate> candidates,
      Map<String, String> entities,
      ToolMetaRegistry meta) {
    IntentVerbs.target(message, domain)
        .ifPresent(
            target -> {
              ToolSearch.ToolCandidate c =
                  candidates.stream()
                      .filter(x -> x.toolId().equals(target))
                      .findFirst()
                      .orElseThrow(
                          () ->
                              new RunFailure(
                                  "TOOL_SELECTION_INVALID",
                                  "target tool not in candidates: " + target));
              for (var n : c.inputSchema().path("required")) {
                String type = meta.entityTypeOf(n.asText());
                if (type != null && !entities.containsKey(type)) {
                  throw new LlmClient.MissingEntity(type);
                }
              }
            });
  }

  /** 单参数值校验：按 inputSchema.properties[k] 校验（string 直接校；integer / boolean 先转，转不了即不合规）。 */
  static void assertValueMatches(
      com.fasterxml.jackson.databind.JsonNode propSchema,
      String key,
      String value,
      String toolId,
      SchemaValidator validator) {
    if (propSchema == null || propSchema.isMissingNode()) {
      return;
    }
    com.fasterxml.jackson.databind.JsonNode typed;
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

  /** 实体类参数（orderId / productId）的值必须等于已识别实体，模型不得换成别的 ID；未识别的实体一律不得出现在参数里。 */
  public static Plan validate(
      LlmPlanDraft draft,
      String domain,
      List<ToolSearch.ToolCandidate> candidates,
      ToolDisplayNames displayNames,
      Map<String, String> entities,
      ToolMetaRegistry meta,
      SchemaValidator validator) {
    if (draft == null || draft.steps() == null || draft.steps().isEmpty()) {
      throw new RunFailure("TOOL_SELECTION_INVALID", "planner returned no steps");
    }
    Map<String, ToolSearch.ToolCandidate> byId =
        candidates.stream()
            .collect(Collectors.toMap(ToolSearch.ToolCandidate::toolId, Function.identity()));
    List<Step> steps = new ArrayList<>();
    int seq = 1;
    for (LlmPlanDraft.DraftStep d : draft.steps()) {
      ToolSearch.ToolCandidate c = byId.get(d.toolId());
      if (c == null) {
        throw new RunFailure("TOOL_SELECTION_INVALID", "toolId not in candidates: " + d.toolId());
      }
      Map<String, String> args = d.args() == null ? Map.of() : d.args();
      var props = c.inputSchema().path("properties");
      for (String k : args.keySet()) {
        if (!props.has(k)) {
          throw new RunFailure(
              "TOOL_SELECTION_INVALID", "arg not in inputSchema of " + d.toolId() + ": " + k);
        }
        // 值过该参数的 JSON Schema（enum / min / max / format / pattern）；Step 值是字符串，integer / boolean
        // 先按类型转
        assertValueMatches(props.path(k), k, args.get(k), d.toolId(), validator);
        String type = meta.entityTypeOf(k);
        if (type != null && !args.get(k).equals(entities.get(type))) {
          throw new RunFailure(
              "TOOL_SELECTION_INVALID",
              "entity arg " + k + " of " + d.toolId() + " does not match recognized entity");
        }
      }
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
        for (var n : c.inputSchema().path("required")) {
          String type = meta.entityTypeOf(n.asText());
          if (type != null && !args.containsKey(n.asText())) {
            throw new LlmClient.MissingEntity(type);
          }
        }
        for (String k : args.keySet()) {
          if (TRUSTED_ONLY_ARGS.contains(k)) {
            throw new RunFailure(
                "TOOL_SELECTION_INVALID",
                "planner must not set trusted-only arg on confirmation step "
                    + d.toolId()
                    + ": "
                    + k);
          }
        }
      }
      steps.add(
          new Step(seq++, c.toolId(), c.version(), displayNames.of(c.toolId()), args, confirm));
    }
    return new Plan(domain, steps);
  }
}
