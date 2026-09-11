package com.sparkrooter.runtime.infra.llm;

import java.util.List;
import java.util.Map;

/** 模型结构化输出的原始形态；经 PlanValidator 校验后才转成 Decision。 */
public record PlanDraft(String action, List<DraftStep> steps, List<Missing> missing, String reply) {
  public record DraftStep(String toolId, Map<String, String> args) {}

  public record Missing(String entity, String reason) {}
}
