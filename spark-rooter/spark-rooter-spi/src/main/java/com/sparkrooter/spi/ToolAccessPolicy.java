package com.sparkrooter.spi;

/**
 * 可选的工具级访问策略：Registry 候选过滤与 Gateway 执行前都会问它。内核不定义用户模型，宿主要按用户判定就从自己经 RunContextPropagator
 * 恢复的上下文取。默认全放行。
 */
public interface ToolAccessPolicy {
  boolean allowed(String toolId, String sessionId);
}
