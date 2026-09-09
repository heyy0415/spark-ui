package com.strato.runtime.infra.selfcheck;

import com.strato.contracts.model.ToolSearch;
import com.strato.runtime.application.ToolDisplayNames;
import com.strato.runtime.application.port.LlmClient;
import com.strato.runtime.application.port.ToolRegistryClient;
import com.strato.runtime.domain.Plan;
import com.strato.runtime.domain.RunFailure;
import com.strato.runtime.domain.Step;
import com.strato.runtime.infra.llm.IntentVerbs;
import com.strato.runtime.infra.llm.LlmPlanDraft;
import com.strato.runtime.infra.llm.ToolSelectionValidator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 规划自检（spec §2.4.3）：① IntentVerbs 表引用的 toolId 都已注册；② 规则规划器对 5 条核心消息各断言 toolId 序列； ③ 候选外 toolId
 * 被校验器拒绝；④ 需确认步骤缺前置被拒绝。真模型模式跳过 ②。
 */
@Component
public class PlanSelfCheck implements com.strato.spi.SelfCheck {

  private static final Logger log = LoggerFactory.getLogger(PlanSelfCheck.class);
  private static final ToolSearch.Principal PRINCIPAL =
      new ToolSearch.Principal("user_001", "tenant_001");

  /** 一条核心消息：领域、消息、已识别实体、期望 toolId 序列。 */
  record Case(String domain, String message, Map<String, String> entities, List<String> expect) {}

  private static final List<Case> CASES =
      List.of(
          new Case(
              "refund",
              "帮我把这个订单退款",
              Map.of("order", "10001"),
              List.of("refund.eligibility.check", "refund.preview", "refund.create")),
          new Case(
              "order", "查看订单 10002 的物流", Map.of("order", "10002"), List.of("order.logistics.get")),
          new Case(
              "aftersale",
              "订单 10002 申请售后",
              Map.of("order", "10002"),
              List.of("aftersale.list.get", "aftersale.create")),
          new Case(
              "order",
              "删除订单 10005",
              Map.of("order", "10005"),
              List.of("order.detail.get", "order.delete")),
          new Case("product", "有什么商品", Map.of(), List.of("product.list.search")),
          // 评审 S-3：路由领域内的动词优先（「退货」在售后表更靠前，但路由到 refund 就该选 refund.create）
          new Case(
              "refund",
              "订单 10002 退货退款",
              Map.of("order", "10002"),
              List.of("refund.eligibility.check", "refund.preview", "refund.create")));

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
    Set<String> registered = new HashSet<>();
    for (String d : registry.domains(PRINCIPAL)) {
      candidates(d).forEach(c -> registered.add(c.toolId()));
    }
    for (String id : IntentVerbs.referencedToolIds()) {
      if (!registered.contains(id)) {
        throw new IllegalStateException("IntentVerbs references unregistered tool " + id);
      }
    }
    log.info("selfcheck: intent verbs reference registered tools OK");

    if (llm.name().equals("rule-based")) {
      for (Case c : CASES) {
        Plan p =
            llm.plan(
                new LlmClient.PlanRequest(
                    c.message(), c.domain(), candidates(c.domain()), c.entities()));
        List<String> got = p.steps().stream().map(Step::toolId).toList();
        if (!got.equals(c.expect())) {
          throw new IllegalStateException(
              "rule planner mismatch for domain " + c.domain() + ": " + got + " != " + c.expect());
        }
        Step last = p.steps().get(p.steps().size() - 1);
        boolean writes = IntentVerbs.PREREQUISITES.containsKey(last.toolId());
        if (writes != last.requiresConfirmation()) {
          throw new IllegalStateException("confirmation flag wrong for " + last.toolId());
        }
      }
      log.info("selfcheck: plan 6 messages OK");
    } else {
      log.info("selfcheck: plan skipped (live LLM {}), validator check only", llm.name());
    }

    List<ToolSearch.ToolCandidate> refund = candidates("refund");
    try {
      ToolSelectionValidator.validate(
          new LlmPlanDraft(
              List.of(new LlmPlanDraft.DraftStep("refund.delete.everything", Map.of()))),
          "refund",
          refund,
          names,
          Map.of());
      throw new IllegalStateException("validator accepted a toolId outside candidates");
    } catch (RunFailure expected) {
      log.info("selfcheck: invalid toolId rejected OK");
    }
    try {
      ToolSelectionValidator.validate(
          new LlmPlanDraft(
              List.of(new LlmPlanDraft.DraftStep("refund.create", Map.of("orderId", "10003")))),
          "refund",
          refund,
          names,
          Map.of("order", "10003"));
      throw new IllegalStateException("validator accepted confirmation step without prerequisites");
    } catch (RunFailure expected) {
      log.info("selfcheck: missing prerequisite rejected OK");
    }
    try {
      // 评审 S-5：模型把实体参数换成别的订单号必须被拒
      ToolSelectionValidator.validate(
          new LlmPlanDraft(
              List.of(new LlmPlanDraft.DraftStep("refund.status.get", Map.of("orderId", "10009")))),
          "refund",
          refund,
          names,
          Map.of("order", "10001"));
      throw new IllegalStateException(
          "validator accepted an entity arg that differs from recognized entity");
    } catch (RunFailure expected) {
      log.info("selfcheck: foreign entity arg rejected OK");
    }
  }

  private List<ToolSearch.ToolCandidate> candidates(String domain) {
    return registry.search(new ToolSearch.Request(domain, null, PRINCIPAL, null)).tools();
  }
}
