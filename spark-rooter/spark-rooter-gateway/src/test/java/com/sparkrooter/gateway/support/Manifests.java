package com.sparkrooter.gateway.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.model.ToolManifest;

/** 测试用 Manifest：从契约副本示例读取再改字段（示例：refund.eligibility.check@1.2.0，输入 orderId，输出 eligible 等）。 */
public final class Manifests {

  public static final SchemaValidator VALIDATOR = new SchemaValidator(new ObjectMapper());

  private Manifests() {}

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

  /**
   * 覆盖 risk.sideEffect / execution.idempotency / execution.maxRetries；不经契约校验（RetryPolicy
   * 边界用例需要非法组合）。
   */
  public static ToolManifest withExecution(
      boolean sideEffect, ToolManifest.Idempotency idempotency, int maxRetries) {
    ToolManifest m = example();
    return new ToolManifest(
        m.toolId(),
        m.version(),
        m.domain(),
        m.name(),
        m.description(),
        m.protocol(),
        m.provider(),
        m.inputSchema(),
        m.outputSchema(),
        new ToolManifest.Risk(
            m.risk().level(), sideEffect, m.risk().reversible(), m.risk().confirmation()),
        m.authorization(),
        new ToolManifest.Execution(m.execution().timeoutMs(), maxRetries, idempotency),
        m.owner(),
        m.status());
  }

  /** 改 timeoutMs / maxRetries / idempotency（sideEffect 保持示例的 false，契约合法）。 */
  public static ObjectNode jsonWithExecution(int timeoutMs, int maxRetries, String idempotency) {
    ObjectNode j = exampleJson();
    ((ObjectNode) j.get("execution"))
        .put("timeoutMs", timeoutMs)
        .put("maxRetries", maxRetries)
        .put("idempotency", idempotency);
    return j;
  }

  /** 合法输出（满足示例 outputSchema：orderId / eligible / refundableAmount / currency）。 */
  public static ObjectNode okOutput() {
    return VALIDATOR
        .mapper()
        .createObjectNode()
        .put("orderId", "10001")
        .put("eligible", true)
        .put("refundableAmount", "128.00")
        .put("currency", "CNY");
  }
}
