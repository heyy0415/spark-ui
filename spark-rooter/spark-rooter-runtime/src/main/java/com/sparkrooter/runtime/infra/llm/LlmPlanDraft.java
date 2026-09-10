package com.sparkrooter.runtime.infra.llm;

import java.util.List;
import java.util.Map;

/** 模型结构化输出的原始形态；经 ToolSelectionValidator 校验后才转成 domain.Plan。 */
public record LlmPlanDraft(List<DraftStep> steps) {
  public record DraftStep(String toolId, Map<String, String> args) {}
}
