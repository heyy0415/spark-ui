package com.sparkrooter.contracts.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 契约 intent-request：前端 → Runtime。pageContext 为不可信输入，principal 来自请求头。 */
public record IntentRequest(
    @NotBlank String conversationId,
    @NotBlank String message,
    @Valid PageContext pageContext,
    @NotNull @Valid ClientCapabilities clientCapabilities) {

  /** 页面上下文（可空）。 */
  public record PageContext(@NotBlank String page, @Valid SelectedEntity selectedEntity) {}

  /** 页面上选中的业务实体。 */
  public record SelectedEntity(@NotBlank String type, @NotBlank String id) {}

  /** 客户端可渲染的组件能力。 */
  public record ClientCapabilities(
      @NotBlank String uiSchemaVersion, @NotNull List<String> components) {}
}
