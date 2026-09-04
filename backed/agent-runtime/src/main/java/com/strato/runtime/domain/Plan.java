package com.strato.runtime.domain;

import java.util.List;

/** LLM（或规则）产出的执行计划：有序步骤列表。只存后端，不下发前端。 */
public record Plan(String domain, List<Step> steps) {
  public Plan {
    steps = List.copyOf(steps);
  }

  public Step step(int seq) {
    return steps.stream()
        .filter(s -> s.seq() == seq)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no step " + seq));
  }
}
