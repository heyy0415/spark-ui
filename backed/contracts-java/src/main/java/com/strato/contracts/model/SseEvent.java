package com.strato.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * 契约 sse-events：单帧 {event, data}。这里用一个通用 record 承载十种事件，data 由各静态工厂构造并保证只含契约允许的字段。 序列化前由
 * SchemaValidator 校验。
 */
public record SseEvent(String event, JsonNode data) {

  public static final String RUN_STARTED = "run.started";
  public static final String MESSAGE_DELTA = "message.delta";
  public static final String TOOL_SELECTED = "tool.selected";
  public static final String TOOL_STARTED = "tool.started";
  public static final String TOOL_COMPLETED = "tool.completed";
  public static final String UI_REPLACE = "ui.replace";
  public static final String UI_PATCH = "ui.patch";
  public static final String CONFIRMATION_REQUIRED = "confirmation.required";
  public static final String RUN_COMPLETED = "run.completed";
  public static final String RUN_FAILED = "run.failed";

  /** 十个事件名，顺序与契约 oneOf 一致。 */
  public static final List<String> EVENT_NAMES =
      List.of(
          RUN_STARTED,
          MESSAGE_DELTA,
          TOOL_SELECTED,
          TOOL_STARTED,
          TOOL_COMPLETED,
          UI_REPLACE,
          UI_PATCH,
          CONFIRMATION_REQUIRED,
          RUN_COMPLETED,
          RUN_FAILED);

  /** tool.completed 的 status 取值。 */
  public enum ToolStatus {
    succeeded,
    failed
  }

  // ---- 以下为各事件 data 的强类型载体，供 Runtime 构造后经 ObjectMapper 转为 JsonNode ----

  public record RunStartedData(String runId, String conversationId, Instant at) {}

  public record MessageDeltaData(String runId, String text) {}

  public record ToolSelectedData(
      String runId, String toolCallId, String toolId, String version, String displayName) {}

  public record ToolStartedData(String runId, String toolCallId, Instant at) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ToolCompletedData(
      String runId, String toolCallId, ToolStatus status, long durationMs, String summary) {}

  public record UiReplaceData(String runId, UiSchema ui) {}

  public record UiPatchData(String runId, String screenId, List<UiSchema.Component> components) {}

  public record ConfirmationRequiredData(String runId, String actionId, Instant expiresAt) {}

  public record RunCompletedData(String runId, Instant at) {}

  public record RunFailedData(String runId, RunFailureCode code, String message, Instant at) {}
}
