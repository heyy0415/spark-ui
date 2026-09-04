package com.strato.runtime.infra.llm;

import com.strato.runtime.application.port.LlmClient;
import com.strato.runtime.domain.Plan;
import com.strato.runtime.domain.RunFailure;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 无 key 时的确定性回退（spec §2.2）。按领域套用固定模板；不理解自然语言，只保证链路可跑通与可验收。 refund：eligibility.check → preview →
 * create（requiresConfirmation）。order：detail.get。
 */
public final class RuleBasedLlmClient implements LlmClient {

  private static final Map<String, List<String>> TEMPLATE =
      Map.of(
          "refund", List.of("refund.eligibility.check", "refund.preview", "refund.create"),
          "order", List.of("order.detail.get"));

  private final Map<String, String> displayNames;

  public RuleBasedLlmClient(Map<String, String> displayNames) {
    this.displayNames = displayNames;
  }

  @Override
  public Plan plan(PlanRequest req) {
    List<String> order = TEMPLATE.get(req.domain());
    if (order == null) {
      throw new RunFailure("TOOL_SELECTION_INVALID", "no rule template for domain " + req.domain());
    }
    Set<String> available = new java.util.HashSet<>();
    req.candidates().forEach(c -> available.add(c.toolId()));
    String orderId = req.entity().getOrDefault("id", "");
    List<LlmPlanDraft.DraftStep> steps = new ArrayList<>();
    for (String toolId : order) {
      if (!available.contains(toolId)) {
        continue;
      }
      Map<String, String> args = new java.util.LinkedHashMap<>();
      if (!orderId.isEmpty()) {
        args.put("orderId", orderId);
      }
      steps.add(new LlmPlanDraft.DraftStep(toolId, args));
    }
    return ToolSelectionValidator.validate(
        new LlmPlanDraft(steps), req.domain(), req.candidates(), displayNames);
  }

  @Override
  public String name() {
    return "rule-based";
  }
}
