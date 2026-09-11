package com.sparkrooter.runtime.application.port;

import com.sparkrooter.contracts.model.ToolSearch;
import com.sparkrooter.runtime.domain.Plan;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 规划端口：把「用户原话 + 会话上下文 + 全部候选工具」交给模型，得到一个已经过 PlanValidator 校验的决策。 内核对候选工具的语义没有任何理解——
 * 领域知识全部来自 @SparkTool / @SparkParam 的 description / verbs / label / entity。
 */
public interface LlmClient {

  /**
   * @param message 用户原话
   * @param candidates 全部可发现工具（六字段投影）
   * @param context 会话上下文：记忆实体（类型 → ID）、最近列表行 ID、上一轮挂起的原话；模型据此解析「第二个」「刚才那单」
   */
  record PlanRequest(String message, List<ToolSearch.ToolCandidate> candidates, Context context) {}

  /** 会话上下文（只有 ID 与短文本，不含业务数据）。 */
  record Context(
      Map<String, String> entities, List<String> lastRowIds, Optional<String> pendingMessage) {
    public static Context empty() {
      return new Context(Map.of(), List.of(), Optional.empty());
    }
  }

  /** 模型决策：三选一。 */
  sealed interface Decision permits Planned, Clarify, NoCapability {}

  /** 可执行计划（已校验）。 */
  record Planned(Plan plan) implements Decision {}

  /**
   * 目标明确但缺实体：编排器按 entityType 查澄清候选源出列表，或直接把 reply 回给用户。
   *
   * @param entityType 缺失的实体类型名；null 表示模型只想追问
   */
  record Clarify(String entityType, String reply) implements Decision {}

  /** 候选里没有能做这件事的工具。 */
  record NoCapability(String reply) implements Decision {}

  /**
   * @throws com.sparkrooter.runtime.domain.RunFailure INTERNAL_ERROR（模型不可用 /
   *     传输失败）、TOOL_SELECTION_INVALID（两次输出都不合规）
   */
  Decision plan(PlanRequest request);

  /** 实现名称，用于日志与自检（不含模型名）。 */
  String name();
}
