package com.strato.runtime.infra.llm;

import com.strato.runtime.application.port.IntentClassifier;
import java.util.Optional;
import java.util.Set;

/** 未配置 LLM 时的回退：不分类，恒 none（规则未命中即无能力路径，与首期行为一致）。 */
public final class NoopIntentClassifier implements IntentClassifier {

  @Override
  public Optional<String> classify(
      String message, Optional<String> entityType, Set<String> knownDomains) {
    return Optional.empty();
  }

  @Override
  public String name() {
    return "noop";
  }
}
