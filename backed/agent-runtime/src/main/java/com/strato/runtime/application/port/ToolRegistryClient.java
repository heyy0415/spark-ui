package com.strato.runtime.application.port;

import com.strato.contracts.model.ToolSearch;

/** 控制面查询端口；首期进程内适配，HTTP 适配为后续 change。 */
public interface ToolRegistryClient {
  ToolSearch.Response search(ToolSearch.Request request);
}
