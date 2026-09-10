package com.sparkrooter.contracts.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 契约 intent-request：前端 → Runtime。只有自然语言 message，无页面上下文、无身份字段（身份由宿主解析为 sessionId）。 */
public record IntentRequest(
    @NotBlank String conversationId,
    @NotBlank String message,
    @NotNull @Valid ClientCapabilities clientCapabilities) {

  /** 客户端可渲染的组件能力。 */
  public record ClientCapabilities(
      @NotBlank String uiSchemaVersion, @NotNull List<String> components) {}
}
