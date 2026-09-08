package com.strato.runtime.application;

import com.strato.contracts.model.ToolSearch;
import com.strato.runtime.application.port.IntentClassifier;
import com.strato.runtime.application.port.ToolRegistryClient;
import com.strato.runtime.domain.DomainRouter;
import com.strato.spi.Principal;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 分层领域路由：规则优先（0 延迟）→ 模型补位（只输出该 principal 可见领域的枚举，经代码校验）→ none。 结果只决定去 Registry 查哪个领域的候选；鉴权仍在
 * Registry / Gateway。source 只进日志，不进 SSE。
 */
@Component
public class DomainResolver {

  /** 页面实体类型白名单：只有这些类型会作为提示进入分类 prompt。 */
  static final Set<String> ENTITY_HINT_WHITELIST = Set.of("order", "product");

  /** 路由决策；source ∈ {rule, model, none}。 */
  public record RouteDecision(Optional<String> domain, String source) {
    static RouteDecision rule(String d) {
      return new RouteDecision(Optional.of(d), "rule");
    }

    static RouteDecision model(String d) {
      return new RouteDecision(Optional.of(d), "model");
    }

    static RouteDecision none() {
      return new RouteDecision(Optional.empty(), "none");
    }
  }

  private final DomainRouter rules;
  private final IntentClassifier classifier;
  private final ToolRegistryClient registry;

  public DomainResolver(IntentClassifier classifier, ToolRegistryClient registry) {
    this.rules = DomainRouter.defaultRules();
    this.classifier = classifier;
    this.registry = registry;
  }

  public RouteDecision resolve(String message, Optional<String> entityType, Principal principal) {
    Optional<String> byRule = rules.route(message);
    if (byRule.isPresent()) {
      return RouteDecision.rule(byRule.get());
    }
    // 只在模型路径查一次 Registry：该 principal 可见的领域集合即分类枚举
    Set<String> known =
        registry.domains(new ToolSearch.Principal(principal.userId(), principal.tenantId()));
    Optional<String> hint = entityType.filter(ENTITY_HINT_WHITELIST::contains);
    return classifier
        .classify(message, hint, known)
        .filter(known::contains)
        .map(RouteDecision::model)
        .orElseGet(RouteDecision::none);
  }
}
