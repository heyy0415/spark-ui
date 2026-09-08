package com.strato.runtime.infra.inprocess;

import com.strato.contracts.model.ToolSearch;
import com.strato.registry.api.ToolSearchPort;
import com.strato.runtime.application.port.ToolRegistryClient;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 进程内适配：只依赖 registry 的 api 包接口（project-structure §2）。HTTP 适配为后续 change。 */
@Component
public class InProcessToolRegistryClient implements ToolRegistryClient {
  private final ToolSearchPort registry;

  public InProcessToolRegistryClient(ToolSearchPort registry) {
    this.registry = registry;
  }

  @Override
  public ToolSearch.Response search(ToolSearch.Request request) {
    return registry.search(request);
  }

  @Override
  public Set<String> domains(ToolSearch.Principal principal) {
    return registry.domains(principal);
  }
}
