package com.sparkrooter.runtime.application.port;

import com.sparkrooter.contracts.model.ToolSearch;
import com.sparkrooter.runtime.domain.Plan;
import java.util.List;
import java.util.Map;

/**
 * 规划端口。模型只"提议"工具与参数，不执行；返回的 toolId 必须落在 candidates 内（由 ToolSelectionValidator 校验）。
 * 实现：SpringAiLlmClient（Spring AI 1.1，内部工具执行关闭）或 RuleBasedLlmClient（无 key 回退）。
 */
public interface LlmClient {

  /** 规划请求：用户消息、领域、候选工具（六字段）、已识别实体（类型 → ID，来自消息与页面上下文）。 */
  record PlanRequest(
      String message,
      String domain,
      List<ToolSearch.ToolCandidate> candidates,
      Map<String, String> entities) {}

  /**
   * @throws MissingEntity 动词命中但目标工具必填实体缺失（编排器走友好提示 + run.completed，不算失败）
   * @throws com.sparkrooter.runtime.domain.RunFailure TOOL_SELECTION_INVALID 等
   */
  Plan plan(PlanRequest request);

  /** 规划器信号：目标工具需要某类实体而没有。 */
  final class MissingEntity extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String entityType;

    public MissingEntity(String entityType) {
      super("missing entity " + entityType);
      this.entityType = entityType;
    }

    public String entityType() {
      return entityType;
    }
  }

  /** 实现名称，用于日志与自检。 */
  String name();
}
