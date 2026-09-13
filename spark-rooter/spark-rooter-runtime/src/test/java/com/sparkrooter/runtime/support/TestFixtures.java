package com.sparkrooter.runtime.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.contracts.model.ToolSearch;
import com.sparkrooter.runtime.application.ToolDisplayNames;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.spi.annotation.ParamFormat;
import com.sparkrooter.spi.tool.ParamMeta;
import com.sparkrooter.spi.tool.ToolMeta;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * runtime 测试夹具：ObjectMapper 与 contracts PlatformMapper.create 口径一致（JavaTimeModule / 禁
 * timestamps；Jdk8Module 不在 runtime 类路径，测试不经 Jackson 序列化 Optional）， 契约合法的 ToolCandidate
 * 构造器，ToolMetaRegistry / ToolDisplayNames 构造器。工具全部用中性名（demo.*），不含示例领域词。
 */
public final class TestFixtures {

  /** 中性实体类型：与 @SparkParam.entity 一样是宿主自定义字符串。 */
  public static final String ENTITY = "item";

  public static final String ENTITY_LABEL = "条目";

  public static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .setDefaultPropertyInclusion(
              JsonInclude.Value.construct(
                  JsonInclude.Include.NON_ABSENT, JsonInclude.Include.ALWAYS));

  public static final SchemaValidator VALIDATOR = new SchemaValidator(MAPPER);

  private TestFixtures() {}

  // ---------------------------------------------------------------- inputSchema

  /** {itemId: string(1..64)}，required=[itemId]。 */
  public static ObjectNode idSchema() {
    ObjectNode s = MAPPER.createObjectNode();
    s.put("type", "object").put("additionalProperties", false);
    s.putArray("required").add("itemId");
    s.putObject("properties")
        .putObject("itemId")
        .put("type", "string")
        .put("minLength", 1)
        .put("maxLength", 64);
    return s;
  }

  /** {status: enum[OPEN,CLOSED], limit: integer 1..50}，无 required。 */
  public static ObjectNode listSchema() {
    ObjectNode s = MAPPER.createObjectNode();
    s.put("type", "object").put("additionalProperties", false);
    ObjectNode props = s.putObject("properties");
    props.putObject("status").put("type", "string").putArray("enum").add("OPEN").add("CLOSED");
    props.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 50);
    return s;
  }

  /** {itemId: string, amount: string}，required=[itemId]。amount 是「由系统填写」的可信参数。 */
  public static ObjectNode confirmSchema() {
    ObjectNode s = idSchema();
    ((ObjectNode) s.get("properties")).putObject("amount").put("type", "string");
    return s;
  }

  // ---------------------------------------------------------------- candidates

  public static ToolSearch.ToolCandidate candidate(
      String toolId,
      JsonNode inputSchema,
      ToolManifest.RiskLevel risk,
      ToolManifest.Confirmation confirmation) {
    return new ToolSearch.ToolCandidate(
        toolId, "1.0.0", "描述 " + toolId, inputSchema, risk, confirmation);
  }

  public static ToolSearch.ToolCandidate readOnly(String toolId, JsonNode inputSchema) {
    return candidate(
        toolId, inputSchema, ToolManifest.RiskLevel.low, ToolManifest.Confirmation.never);
  }

  public static ToolSearch.ToolCandidate needsConfirm(String toolId, JsonNode inputSchema) {
    return candidate(
        toolId, inputSchema, ToolManifest.RiskLevel.high, ToolManifest.Confirmation.required);
  }

  // ---------------------------------------------------------------- meta

  public static ParamMeta entityParam(String name, String pattern) {
    return new ParamMeta(
        name, ENTITY, ENTITY_LABEL, pattern, null, null, ParamFormat.NONE, null, false);
  }

  public static ParamMeta plainParam(String name, String defaultValue, boolean integer) {
    return new ParamMeta(
        name, null, null, null, null, null, ParamFormat.NONE, defaultValue, integer);
  }

  public static ToolMeta meta(
      String toolId, List<String> prerequisites, String clarifiesEntity, ParamMeta... params) {
    return meta(toolId, prerequisites, clarifiesEntity, false, params);
  }

  /** 带 sideEffect 的元数据（写工具）：编排器据此在客户端断开后决定能否提前终止。 */
  public static ToolMeta meta(
      String toolId,
      List<String> prerequisites,
      String clarifiesEntity,
      boolean sideEffect,
      ParamMeta... params) {
    Map<String, ParamMeta> byName = new LinkedHashMap<>();
    for (ParamMeta p : params) {
      byName.put(p.name(), p);
    }
    return new ToolMeta(
        toolId, "1.0.0", "demo", prerequisites, clarifiesEntity, List.of(), byName, sideEffect);
  }

  public static ToolMetaRegistry registry(ToolMeta... metas) {
    ToolMetaRegistry r = new ToolMetaRegistry();
    for (ToolMeta m : metas) {
      r.register(m);
    }
    return r;
  }

  public static ToolDisplayNames names(String... toolIdAndName) {
    ToolDisplayNames n = new ToolDisplayNames();
    for (int i = 0; i + 1 < toolIdAndName.length; i += 2) {
      n.register(toolIdAndName[i], toolIdAndName[i + 1]);
    }
    return n;
  }
}
