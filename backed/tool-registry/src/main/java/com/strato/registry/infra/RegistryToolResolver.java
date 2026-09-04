package com.strato.registry.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.strato.contracts.SchemaValidator;
import com.strato.registry.domain.ToolRegistryRepository;
import com.strato.spi.ToolResolver;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Gateway 寻址端口的实现：返回已注册 Manifest 的 JsonNode（Gateway 需要 inputSchema / outputSchema / execution）。 */
@Component
public class RegistryToolResolver implements ToolResolver {

  private final ToolRegistryRepository repo;
  private final SchemaValidator validator;

  public RegistryToolResolver(ToolRegistryRepository repo, SchemaValidator validator) {
    this.repo = repo;
    this.validator = validator;
  }

  @Override
  public Optional<JsonNode> resolve(String toolId, String version) {
    return repo.find(toolId, version).map(m -> validator.mapper().valueToTree(m));
  }
}
