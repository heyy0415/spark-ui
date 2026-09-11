package com.sparkrooter.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.util.List;

/** 契约 tool-search：Runtime ↔ Registry。请求不带身份（内核不识别用户）；响应项只含六字段（agent-safety §2）。 */
public final class ToolSearch {

  private ToolSearch() {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  /** domain 可空 = 全部可发现工具（内核不按领域筛，由模型在全部候选里选）。 */
  public record Request(String domain, String intent, @Valid Context context) {}

  public record Context(String entityType) {}

  public record Response(List<ToolCandidate> tools) {}

  /** 返回给模型的候选工具，字段集合固定为六个，不得增加。 */
  public record ToolCandidate(
      String toolId,
      String version,
      String description,
      JsonNode inputSchema,
      ToolManifest.RiskLevel riskLevel,
      ToolManifest.Confirmation confirmation) {

    public static ToolCandidate from(ToolManifest m) {
      return new ToolCandidate(
          m.toolId(),
          m.version(),
          m.description(),
          m.inputSchema(),
          m.risk().level(),
          m.risk().confirmation());
    }
  }
}
