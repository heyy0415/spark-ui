package com.sparkrooter.registry.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.model.ToolManifest;

/** 测试用 Manifest：一律从契约副本示例 tool-manifest.example.json 读取再改字段，保证结构契约合法且不在测试里手写领域词。 */
public final class Manifests {

  public static final SchemaValidator VALIDATOR = new SchemaValidator(new ObjectMapper());

  private Manifests() {}

  /** 示例原样（refund.eligibility.check@1.2.0，active，low/never）。 */
  public static ObjectNode exampleJson() {
    return (ObjectNode)
        VALIDATOR.readClasspathJson("contracts/examples/tool-manifest.example.json").deepCopy();
  }

  public static ToolManifest example() {
    return bind(exampleJson());
  }

  public static ToolManifest bind(JsonNode json) {
    return VALIDATOR.mapper().convertValue(json, ToolManifest.class);
  }

  public static ToolManifest withStatus(ToolManifest.Status status) {
    ObjectNode j = exampleJson();
    j.put("status", status.name());
    return bind(j);
  }

  /** 改 toolId / version / domain，其余沿用示例。 */
  public static ObjectNode json(String toolId, String version, String domain) {
    ObjectNode j = exampleJson();
    j.put("toolId", toolId).put("version", version).put("domain", domain);
    return j;
  }

  public static ToolManifest of(String toolId, String version, String domain) {
    return bind(json(toolId, version, domain));
  }
}
