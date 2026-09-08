package com.strato.runtime.application;

import com.strato.spi.ToolNameSink;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 工具的用户可读名称（tool.selected.displayName）注册表：实现 spi ToolNameSink，由 Registry 在启动注册每个 Manifest 时回填其
 * name，runtime 不再硬编码。消费方（编排器、两个 LlmClient、校验器）持有本 Bean 引用而不是快照，注册晚于构造也能看到。
 */
@Component
public class ToolDisplayNames implements ToolNameSink {

  private final Map<String, String> names = new ConcurrentHashMap<>();

  @Override
  public void register(String toolId, String name) {
    if (toolId == null || toolId.isBlank() || name == null || name.isBlank()) {
      return;
    }
    names.put(toolId, name);
  }

  /** 未知 toolId 回退为 toolId 本身。 */
  public String of(String toolId) {
    return names.getOrDefault(toolId, toolId);
  }

  public Map<String, String> all() {
    return Collections.unmodifiableMap(names);
  }
}
