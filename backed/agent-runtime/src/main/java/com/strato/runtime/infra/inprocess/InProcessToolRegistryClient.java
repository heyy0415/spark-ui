package com.strato.runtime.infra.inprocess;

import com.strato.contracts.model.ToolSearch;
import com.strato.registry.application.SearchToolsUseCase;
import com.strato.runtime.application.port.ToolRegistryClient;
import org.springframework.stereotype.Component;

/** 进程内适配：直接调用 registry 模块的 application 用例。HTTP 适配为后续 change。 */
@Component
public class InProcessToolRegistryClient implements ToolRegistryClient {
  private final SearchToolsUseCase search;

  public InProcessToolRegistryClient(SearchToolsUseCase search) {
    this.search = search;
  }

  @Override
  public ToolSearch.Response search(ToolSearch.Request request) {
    return search.execute(request);
  }
}
