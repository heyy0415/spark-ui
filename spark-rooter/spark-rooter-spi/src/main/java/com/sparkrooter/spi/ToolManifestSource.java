package com.sparkrooter.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 领域模块暴露的 Manifest 来源；Registry 在启动时拉取并注册。领域模块因此不依赖 registry 模块。 */
public interface ToolManifestSource {
  List<JsonNode> manifests();
}
