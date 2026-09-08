package com.strato.spi;

/**
 * Registry 注册 Manifest 时回填 toolId → 用户可读名称；runtime 实现，用于 tool.selected.displayName。方向：registry →
 * spi ← runtime。
 */
public interface ToolNameSink {
  void register(String toolId, String name);
}
