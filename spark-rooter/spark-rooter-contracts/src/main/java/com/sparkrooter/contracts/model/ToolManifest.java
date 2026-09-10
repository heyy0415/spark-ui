package com.sparkrooter.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** 契约 tool-manifest：领域服务 → Registry。toolId@version 不可变。description 视为不可信文本。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolManifest(
    String toolId,
    String version,
    String domain,
    String name,
    String description,
    Protocol protocol,
    JsonNode inputSchema,
    JsonNode outputSchema,
    Risk risk,
    Authorization authorization,
    Execution execution,
    Owner owner,
    Status status) {

  public enum Protocol {
    IN_PROCESS("in-process"),
    HTTP("http"),
    MCP("mcp");

    private final String wire;

    Protocol(String wire) {
      this.wire = wire;
    }

    @com.fasterxml.jackson.annotation.JsonValue
    public String wire() {
      return wire;
    }
  }

  public record Risk(
      RiskLevel level, boolean sideEffect, boolean reversible, Confirmation confirmation) {}

  public enum RiskLevel {
    low,
    medium,
    high
  }

  public enum Confirmation {
    never,
    required
  }

  /** permission 可选：内核不做鉴权，仅供宿主 ToolAccessPolicy 参考；@SparkTool 推导为空对象。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Authorization(String permission) {}

  public record Execution(int timeoutMs, int maxRetries, Idempotency idempotency) {}

  public enum Idempotency {
    none,
    required
  }

  public record Owner(String team) {}

  public enum Status {
    draft,
    canary,
    active,
    deprecated
  }

  /** toolId@version，Registry 的唯一键。 */
  public String key() {
    return toolId + "@" + version;
  }
}
