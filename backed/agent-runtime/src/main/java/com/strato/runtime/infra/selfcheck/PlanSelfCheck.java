package com.strato.runtime.infra.selfcheck;

import com.strato.contracts.model.ToolSearch;
import com.strato.runtime.application.ToolDisplayNames;
import com.strato.runtime.application.port.LlmClient;
import com.strato.runtime.application.port.ToolRegistryClient;
import com.strato.runtime.domain.Plan;
import com.strato.runtime.domain.RunFailure;
import com.strato.runtime.infra.llm.LlmPlanDraft;
import com.strato.runtime.infra.llm.ToolSelectionValidator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 规划自检：规则规划器对示例意图返回 3 步且第 3 步需确认；候选外 toolId 被校验器拒绝。 */
@Component
public class PlanSelfCheck implements com.strato.spi.SelfCheck {

  private static final Logger log = LoggerFactory.getLogger(PlanSelfCheck.class);

  private final LlmClient llm;
  private final ToolRegistryClient registry;
  private final ToolDisplayNames names;

  public PlanSelfCheck(LlmClient llm, ToolRegistryClient registry, ToolDisplayNames names) {
    this.llm = llm;
    this.registry = registry;
    this.names = names;
  }

  @Override
  public String name() {
    return "plan";
  }

  @Override
  public void run() {
    ToolSearch.Response found =
        registry.search(
            new ToolSearch.Request(
                "refund", null, new ToolSearch.Principal("user_001", "tenant_001"), null));
    List<ToolSearch.ToolCandidate> cands = found.tools();
    if (llm.name().equals("rule-based")) {
      Plan p =
          llm.plan(
              new LlmClient.PlanRequest(
                  "帮我把这个订单退款", "refund", cands, Map.of("type", "order", "id", "10001")));
      if (p.steps().size() != 3
          || !p.steps().get(2).requiresConfirmation()
          || !p.steps().get(2).toolId().equals("refund.create")) {
        throw new IllegalStateException("rule planner produced unexpected plan: " + p);
      }
      log.info("selfcheck: plan 3 steps, step3 requiresConfirmation OK");
    } else {
      log.info("selfcheck: plan skipped (live LLM {}), validator check only", llm.name());
    }
    try {
      ToolSelectionValidator.validate(
          new LlmPlanDraft(
              List.of(new LlmPlanDraft.DraftStep("refund.delete.everything", Map.of()))),
          "refund",
          cands,
          names);
      throw new IllegalStateException("validator accepted a toolId outside candidates");
    } catch (RunFailure expected) {
      log.info("selfcheck: invalid toolId rejected OK");
    }
  }
}
