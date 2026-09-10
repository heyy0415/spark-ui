package com.sparkrooter.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 契约 tool-invoke：Runtime ↔ Gateway。 */
public final class ToolInvoke {

  private ToolInvoke() {}

  public record Request(
      @NotBlank String toolId,
      @NotBlank String toolVersion,
      @NotNull JsonNode arguments,
      @NotNull @Valid ExecutionContext executionContext) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ExecutionContext(
      @NotBlank String runId,
      @NotBlank String toolCallId,
      @NotBlank String sessionId,
      @NotBlank String idempotencyKey,
      String traceId) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Response(
      String toolCallId,
      SseEvent.ToolStatus status,
      long durationMs,
      JsonNode output,
      Error error) {

    public static Response succeeded(String toolCallId, long durationMs, JsonNode output) {
      return new Response(toolCallId, SseEvent.ToolStatus.succeeded, durationMs, output, null);
    }

    public static Response failed(
        String toolCallId, long durationMs, ErrorCode code, String message) {
      return new Response(
          toolCallId, SseEvent.ToolStatus.failed, durationMs, null, new Error(code, message));
    }
  }

  public record Error(ErrorCode code, String message) {}

  /** Gateway 失败码（与 tool-invoke.schema.json response.error.code enum 一致）。 */
  public enum ErrorCode {
    INPUT_INVALID,
    OUTPUT_INVALID,
    FORBIDDEN,
    TOOL_NOT_FOUND,
    TIMEOUT,
    HANDLER_ERROR
  }
}
