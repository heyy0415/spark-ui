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
    Provider provider,
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

  /**
   * 远程提供方坐标，仅 {@code protocol=http} 时非 null（契约以 if/then 约束：http ⇒ 必填、in-process ⇒ 禁止出现）。
   *
   * @param serviceName 逻辑服务名，与调用方认证密钥绑定（防服务 A 注册服务 B 的工具）
   * @param baseUrl 可空；缺省时由宿主 ProviderEndpointResolver 按 serviceName 解析。允许 http:// 以便内网部署与本地联调，
   *     但传输未加密，生产应使用 HTTPS 或 mTLS；使用 http:// 时启动 WARN
   * @param instanceId 可空；仅用于审计与排障，不参与寻址
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Provider(String serviceName, String baseUrl, String instanceId) {}

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
