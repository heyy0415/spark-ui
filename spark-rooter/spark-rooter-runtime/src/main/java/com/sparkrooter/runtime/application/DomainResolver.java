package com.sparkrooter.runtime.application;

import com.sparkrooter.runtime.application.port.IntentClassifier;
import com.sparkrooter.runtime.application.port.ToolRegistryClient;
import com.sparkrooter.runtime.domain.DomainRouter;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 分层领域路由：规则优先（0 延迟）→ 模型补位（只输出可发现领域的枚举，经代码校验）→ none。 结果只决定去 Registry 查哪个领域的候选；内核不鉴权。source 只进日志，不进
 * SSE。
 */
@Component
public class DomainResolver {

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

  public RouteDecision resolve(String message) {
    Optional<String> byRule = rules.route(message);
    if (byRule.isPresent()) {
      return RouteDecision.rule(byRule.get());
    }
    // 只在模型路径查一次 Registry：可发现领域集合即分类枚举
    Set<String> known = registry.domains();
    return classifier
        .classify(message, known)
        .filter(known::contains)
        .map(RouteDecision::model)
        .orElseGet(RouteDecision::none);
  }
}
