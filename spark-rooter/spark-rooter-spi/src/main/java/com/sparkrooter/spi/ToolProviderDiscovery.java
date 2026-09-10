package com.sparkrooter.spi;

import java.util.List;

/** 工具提供方发现端口（<b>预留</b>）：Registry 启动时从它拉取全部 Manifest 来源。本期只有「本进程 Bean」实现；注册中心 / 配置中心发现为后续 change。 */
public interface ToolProviderDiscovery {
  List<ToolManifestSource> providers();
}
