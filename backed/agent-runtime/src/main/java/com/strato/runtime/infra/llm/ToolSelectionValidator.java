package com.strato.runtime.infra.llm;

import com.strato.contracts.model.ToolManifest;
import com.strato.contracts.model.ToolSearch;
import com.strato.runtime.application.ToolDisplayNames;
import com.strato.runtime.domain.Plan;
import com.strato.runtime.domain.RunFailure;
import com.strato.runtime.domain.Step;
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
 */
public final class ToolSelectionValidator {

  /** 模型不得为需确认步骤填写的参数：金额一律来自后端试算 / 重校验。 */
  static final Set<String> TRUSTED_ONLY_ARGS = Set.of("amount");

  private ToolSelectionValidator() {}

  public static Plan validate(
      LlmPlanDraft draft,
      String domain,
      List<ToolSearch.ToolCandidate> candidates,
      ToolDisplayNames displayNames) {
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
      }
      boolean confirm =
          c.confirmation() == ToolManifest.Confirmation.required
              || c.riskLevel() == ToolManifest.RiskLevel.high;
      if (confirm) {
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
