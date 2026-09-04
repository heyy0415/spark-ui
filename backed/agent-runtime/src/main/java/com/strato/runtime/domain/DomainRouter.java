package com.strato.runtime.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 确定性领域路由（agent-safety §2）：先用关键词规则确定 domain，再让模型在该 domain 的候选内选工具。 纯函数，无框架依赖；规则表按声明顺序匹配，首个命中即返回。
 */
public final class DomainRouter {

  private final Map<String, List<String>> keywordsByDomain;

  public DomainRouter(Map<String, List<String>> keywordsByDomain) {
    this.keywordsByDomain = new LinkedHashMap<>(keywordsByDomain);
  }

  /** 首期规则表：退款词优先于订单词，因为"订单退款"应路由到 refund。 */
  public static DomainRouter defaultRules() {
    Map<String, List<String>> m = new LinkedHashMap<>();
    m.put("refund", List.of("退款", "退钱", "退货", "refund"));
    m.put("order", List.of("订单", "order", "物流", "发货"));
    return new DomainRouter(m);
  }

  public Optional<String> route(String message) {
    if (message == null) {
      return Optional.empty();
    }
    String lower = message.toLowerCase();
    for (Map.Entry<String, List<String>> e : keywordsByDomain.entrySet()) {
      for (String kw : e.getValue()) {
        if (lower.contains(kw.toLowerCase())) {
          return Optional.of(e.getKey());
        }
      }
    }
    return Optional.empty();
  }
}
