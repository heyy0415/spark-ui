package com.sparkrooter.runtime.infra.llm;

import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.domain.RunFailure;

/** 未配置模型时的规划器：不做任何规则兜底，直接失败。内核不含领域知识，没有模型就无法理解用户请求（用户决策：无模型不执行）。 */
public final class UnavailablePlanner implements LlmClient {

  public static final String TEXT = "未配置模型，无法理解请求";

  @Override
  public Decision plan(PlanRequest request) {
    throw RunFailure.withUserText("INTERNAL_ERROR", "llm not configured", TEXT);
  }

  @Override
  public String name() {
    return "unavailable";
  }
}
