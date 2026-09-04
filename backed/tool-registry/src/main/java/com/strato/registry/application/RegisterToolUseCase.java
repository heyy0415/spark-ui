package com.strato.registry.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.strato.contracts.SchemaValidator;
import com.strato.contracts.model.ToolManifest;
import com.strato.registry.domain.ToolRegistryRepository;
import com.strato.registry.domain.ToolVersionConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 注册用例：契约校验 → 反序列化 → 原子写入；重复即 409。 */
@Service
public class RegisterToolUseCase {

  private static final Logger log = LoggerFactory.getLogger(RegisterToolUseCase.class);

  private final ToolRegistryRepository repo;
  private final SchemaValidator validator;

  public RegisterToolUseCase(ToolRegistryRepository repo, SchemaValidator validator) {
    this.repo = repo;
    this.validator = validator;
  }

  /** 校验并注册；成功返回 Manifest，重复抛 {@link ToolVersionConflictException}。 */
  public ToolManifest execute(JsonNode manifestJson) {
    validator.assertValid("tool-manifest", manifestJson);
    ToolManifest manifest = validator.mapper().convertValue(manifestJson, ToolManifest.class);
    // inputSchema / outputSchema 必须本身是可编译的 JSON Schema，注册时即暴露
    validator.validateWithInlineSchema(
        manifest.inputSchema(), validator.mapper().createObjectNode());
    validator.validateWithInlineSchema(
        manifest.outputSchema(), validator.mapper().createObjectNode());
    if (!repo.putIfAbsent(manifest)) {
      throw new ToolVersionConflictException(manifest.key());
    }
    log.info(
        "registered tool {} domain={} risk={}",
        manifest.key(),
        manifest.domain(),
        manifest.risk().level());
    return manifest;
  }
}
