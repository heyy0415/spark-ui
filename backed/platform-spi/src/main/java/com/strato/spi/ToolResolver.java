package com.strato.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/** Registry 提供给 Gateway 的进程内寻址：toolId@version → 已注册的 Manifest。 */
public interface ToolResolver {
  Optional<JsonNode> resolve(String toolId, String version);
}
