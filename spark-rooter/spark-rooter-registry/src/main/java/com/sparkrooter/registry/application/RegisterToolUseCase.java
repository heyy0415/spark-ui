package com.sparkrooter.registry.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.registry.api.ConfirmationCoveragePolicy;
import com.sparkrooter.registry.domain.ToolRegistryRepository;
import com.sparkrooter.registry.domain.ToolVersionConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 注册用例：契约校验 → 反序列化 → 确认覆盖校验 → 原子写入；重复即 409。 */
public class RegisterToolUseCase {

  private static final Logger log = LoggerFactory.getLogger(RegisterToolUseCase.class);

  private final ToolRegistryRepository repo;
  private final SchemaValidator validator;
  private final ConfirmationCoveragePolicy coverage;

  public RegisterToolUseCase(
      ToolRegistryRepository repo, SchemaValidator validator, ConfirmationCoveragePolicy coverage) {
    this.repo = repo;
    this.validator = validator;
    this.coverage = coverage;
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
    // 需确认工具必须在本 hub 有确认屏与重校验。放在写入前：远程 Manifest 是启动自检之后才推来的，
    // 只靠启动自检会让缺口推迟到用户点确认时才暴露（见 ConfirmationCoveragePolicy）
    coverage.check(manifest);
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
