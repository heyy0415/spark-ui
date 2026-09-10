package com.sparkrooter.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.AbsoluteIri;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.SchemaMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 契约校验器：加载 classpath:contracts/*.schema.json（构建时从 .harness/contracts 复制），按契约名校验 JsonNode。
 *
 * <p>所有跨边界数据（HTTP 请求体、工具参数 / 输出、LLM 输出、SSE 事件）进入应用前必须经此校验。 本类无 Spring 依赖，可在任意模块使用。
 */
public final class SchemaValidator {

  /** 契约 $id 的公共前缀；跨文件 $ref 通过 SchemaMapper 映射回 classpath。 */
  public static final String ID_PREFIX = "https://spark-rooter.local/contracts/v1/";

  /** 9 个契约名（不含 .schema.json 后缀），与 .harness/contracts 一致。 */
  public static final List<String> CONTRACT_NAMES =
      List.of(
          "action-request",
          "error-response",
          "intent-request",
          "run-summary",
          "sse-events",
          "tool-invoke",
          "tool-manifest",
          "tool-search",
          "ui-schema");

  private final ObjectMapper mapper;
  private final Map<String, JsonSchema> schemas;
  private final Map<String, JsonSchema> fragments = new java.util.concurrent.ConcurrentHashMap<>();
  private final JsonSchemaFactory factory;

  public SchemaValidator(ObjectMapper mapper) {
    this.mapper = mapper;
    SchemaMapper toClasspath =
        (absoluteIri) -> {
          String iri = absoluteIri.toString();
          if (iri.startsWith(ID_PREFIX)) {
            return AbsoluteIri.of("classpath:contracts/" + iri.substring(ID_PREFIX.length()));
          }
          return null;
        };
    this.factory =
        JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012, b -> b.schemaMappers(m -> m.add(toClasspath)));
    Map<String, JsonSchema> loaded = new LinkedHashMap<>();
    for (String name : CONTRACT_NAMES) {
      loaded.put(name, load(name));
    }
    this.schemas = Collections.unmodifiableMap(loaded);
  }

  private JsonSchema load(String name) {
    SchemaValidatorsConfig config =
        SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    JsonSchema schema =
        factory.getSchema(SchemaLocation.of(ID_PREFIX + name + ".schema.json"), config);
    // 预编译，尽早暴露 Schema 自身错误
    schema.initializeValidators();
    return schema;
  }

  /** 校验 node 是否符合契约；返回空集合即通过。 */
  public Set<ValidationMessage> validate(String contractName, JsonNode node) {
    JsonSchema schema = schemas.get(contractName);
    if (schema == null) {
      throw new IllegalArgumentException("unknown contract: " + contractName);
    }
    return schema.validate(node);
  }

  /** 校验失败抛 {@link ContractViolationException}。 */
  public void assertValid(String contractName, JsonNode node) {
    Set<ValidationMessage> errors = validate(contractName, node);
    if (!errors.isEmpty()) {
      throw new ContractViolationException(contractName, errors);
    }
  }

  /** 按契约的子定义校验（如 tool-invoke 的 "#/$defs/request"）。 用于根结构为 {request, response} 的契约只校验入站一半。 */
  public void assertValid(String contractName, String fragment, JsonNode node) {
    if (!schemas.containsKey(contractName)) {
      throw new IllegalArgumentException("unknown contract: " + contractName);
    }
    JsonSchema schema =
        fragments.computeIfAbsent(
            contractName + fragment,
            k -> {
              SchemaValidatorsConfig config =
                  SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
              JsonSchema s =
                  factory.getSchema(
                      SchemaLocation.of(ID_PREFIX + contractName + ".schema.json" + fragment),
                      config);
              s.initializeValidators();
              return s;
            });
    Set<ValidationMessage> errors = schema.validate(node);
    if (!errors.isEmpty()) {
      throw new ContractViolationException(contractName + fragment, errors);
    }
  }

  /**
   * 入站请求体绑定：先按契约（或其子定义）校验原始 JSON，再转成 record。 所有 Controller 的 @RequestBody 必须经此方法，保证
   * additionalProperties / pattern / const 等约束在边界生效（contracts.md §1）。
   *
   * @param fragment 子定义指针（如 "#/$defs/request"），整份契约传 null
   */
  public <T> T bind(String contractName, String fragment, JsonNode body, Class<T> type) {
    if (body == null || body.isNull() || body.isMissingNode()) {
      throw new ContractViolationException(contractName, Set.of());
    }
    if (fragment == null) {
      assertValid(contractName, body);
    } else {
      assertValid(contractName, fragment, body);
    }
    return mapper.convertValue(body, type);
  }

  /** 用任意内嵌 JSON Schema（如 Manifest 的 inputSchema / outputSchema）校验数据。Gateway 用它做工具参数与输出校验。 */
  public Set<ValidationMessage> validateWithInlineSchema(JsonNode inlineSchema, JsonNode data) {
    SchemaValidatorsConfig config =
        SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    return factory.getSchema(inlineSchema, config).validate(data);
  }

  /** 读取 classpath 上的 JSON 资源。 */
  public JsonNode readClasspathJson(String path) {
    try (InputStream in = SchemaValidator.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalArgumentException("classpath resource not found: " + path);
      }
      return mapper.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read " + path, e);
    }
  }

  public ObjectMapper mapper() {
    return mapper;
  }
}
