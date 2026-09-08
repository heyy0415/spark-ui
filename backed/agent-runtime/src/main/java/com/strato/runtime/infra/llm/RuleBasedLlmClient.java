package com.strato.runtime.infra.llm;

import com.strato.contracts.model.ToolSearch;
import com.strato.runtime.application.port.LlmClient;
import com.strato.runtime.domain.Plan;
import com.strato.runtime.domain.RunFailure;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 无 key 时的确定性回退。按领域与「是否有页面实体」二选一套模板；不理解自然语言，只保证链路可跑通与可验收。 refund（有实体）：eligibility.check → preview →
 * create（requiresConfirmation）。order：有实体 detail.get，无实体 list.search。
 */
public final class RuleBasedLlmClient implements LlmClient {

  /** 有页面实体（orderId）时的模板。 */
  private static final Map<String, List<String>> WITH_ENTITY =
      Map.of(
          "refund", List.of("refund.eligibility.check", "refund.preview", "refund.create"),
          "order", List.of("order.detail.get"));

  /** 无页面实体时的模板：只能选不需要 orderId 的工具。refund 无此类工具（被 EntityRequirementCheck 拦截，此处不可达）。 */
  private static final Map<String, List<String>> WITHOUT_ENTITY =
      Map.of("refund", List.of(), "order", List.of("order.list.search"));

  private final Map<String, String> displayNames;

  public RuleBasedLlmClient(Map<String, String> displayNames) {
    this.displayNames = displayNames;
  }

  @Override
  public Plan plan(PlanRequest req) {
    String orderId = req.entity().getOrDefault("id", "");
    boolean hasEntity = !orderId.isEmpty() && "order".equals(req.entity().get("type"));
    List<String> order = (hasEntity ? WITH_ENTITY : WITHOUT_ENTITY).get(req.domain());
    if (order == null) {
      throw new RunFailure("TOOL_SELECTION_INVALID", "no rule template for domain " + req.domain());
    }
    Map<String, ToolSearch.ToolCandidate> available = new java.util.HashMap<>();
    req.candidates().forEach(c -> available.put(c.toolId(), c));
    List<LlmPlanDraft.DraftStep> steps = new ArrayList<>();
    for (String toolId : order) {
      ToolSearch.ToolCandidate c = available.get(toolId);
      if (c == null) {
        continue;
      }
      Map<String, String> args = new java.util.LinkedHashMap<>();
      if (requiresOrderId(c)) {
        if (!hasEntity) {
          // 缺必填实体的步骤不生成（不再产出空参数去撞 Gateway）
          continue;
        }
        args.put("orderId", orderId);
      }
      steps.add(new LlmPlanDraft.DraftStep(toolId, args));
    }
    if (steps.isEmpty()) {
      throw new RunFailure(
          "TOOL_SELECTION_INVALID", "no executable step for domain " + req.domain());
    }
    return ToolSelectionValidator.validate(
        new LlmPlanDraft(steps), req.domain(), req.candidates(), displayNames);
  }

  private static boolean requiresOrderId(ToolSearch.ToolCandidate c) {
    for (var n : c.inputSchema().path("required")) {
      if ("orderId".equals(n.asText())) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String name() {
    return "rule-based";
  }
}
