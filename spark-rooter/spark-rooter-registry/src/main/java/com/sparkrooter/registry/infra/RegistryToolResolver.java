package com.sparkrooter.registry.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.registry.domain.ToolRegistryRepository;
import com.sparkrooter.spi.ToolResolver;
import java.util.Optional;

/** Gateway 寻址端口的实现：返回已注册 Manifest 的 JsonNode（Gateway 需要 inputSchema / outputSchema / execution）。 */
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
