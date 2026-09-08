package com.strato.runtime.application.port;

import java.util.Optional;
import java.util.Set;

/**
 * 意图分类端口（agent-safety §2「规则优先、模型补位、代码兜底」的第 2 层）。 只在关键词规则未命中时调用；输出必须落在 knownDomains 内，否则调用方视为
 * none。分类结果只决定去 Registry 查哪个领域的候选，不参与任何鉴权。
 */
public interface IntentClassifier {

  /**
   * @param message 用户原文（实现负责 sanitize 后进 prompt，禁止落日志）
   * @param entityType 页面选中实体类型（已经白名单过滤）；仅作提示
   * @param knownDomains 该 principal 可见的领域集合；返回值必须是其子集
   * @return 领域名；empty = none / 无法判定 / 未配置模型
   */
  Optional<String> classify(String message, Optional<String> entityType, Set<String> knownDomains);

  String name();
}
