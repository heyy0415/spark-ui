package com.strato.runtime.application.port;

import com.strato.contracts.model.ToolSearch;
import com.strato.runtime.domain.Plan;
import java.util.List;
import java.util.Map;

/**
 * 规划端口。模型只"提议"工具与参数，不执行；返回的 toolId 必须落在 candidates 内（由 ToolSelectionValidator 校验）。
 * 实现：SpringAiLlmClient（Spring AI 1.1，内部工具执行关闭）或 RuleBasedLlmClient（无 key 回退）。
 */
public interface LlmClient {

  /** 规划请求：用户消息、领域、候选工具（六字段）、页面上下文中的实体。 */
  record PlanRequest(
      String message,
      String domain,
      List<ToolSearch.ToolCandidate> candidates,
      Map<String, String> entity) {}

  Plan plan(PlanRequest request);

  /** 实现名称，用于日志与自检。 */
  String name();
}
