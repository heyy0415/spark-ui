package com.sparkrooter.runtime.application.port;

import com.sparkrooter.contracts.model.ToolSearch;
import java.util.Set;

/** 控制面查询端口；首期进程内适配，HTTP 适配为后续 change。 */
public interface ToolRegistryClient {
  /**
   * @param sessionId 宿主会话键，透传给宿主 ToolAccessPolicy（内部自检可为 null）
   */
  ToolSearch.Response search(ToolSearch.Request request, String sessionId);

  /** 可发现工具所属领域集合；意图分类输出必须落在其中，否则视为 none。 */
  Set<String> domains();
}
