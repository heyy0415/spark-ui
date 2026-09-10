package com.sparkrooter.runtime.application.port;

import com.sparkrooter.contracts.model.ToolSearch;
import java.util.Set;

/** 控制面查询端口；首期进程内适配，HTTP 适配为后续 change。 */
public interface ToolRegistryClient {
  ToolSearch.Response search(ToolSearch.Request request);

  /** 该 principal 可见的领域集合；意图分类输出必须落在其中，否则视为 none。 */
  Set<String> domains(ToolSearch.Principal principal);
}
