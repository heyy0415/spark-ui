package com.strato.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 契约 ui-schema：Runtime → 前端的声明式 UI。components[].type 只能是五个白名单值（antd / antd-mobile 官方组件映射）。 */
public record UiSchema(
    String schemaVersion,
    String screenId,
    String title,
    List<Component> components,
    List<Action> actions) {

  public static final String VERSION = "1.0";

  /** 白名单组件类型（与 ui-schema.schema.json 的 componentType enum 一致）。 */
  public enum ComponentType {
    Form,
    Card,
    Table,
    Result,
    Timeline
  }

  /** 一个组件实例；props 为自由 JSON（Form 除外，由 Schema 约束 fields[]）。 */
  public record Component(String id, ComponentType type, JsonNode props) {}

  /** 动作按钮；confirmationToken 为不透明字符串。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Action(
      String id, ActionType type, String label, ActionStyle style, String confirmationToken) {}

  public enum ActionType {
    submit,
    cancel
  }

  public enum ActionStyle {
    DEFAULT("default"),
    PRIMARY("primary"),
    DANGER("danger");

    private final String wire;

    ActionStyle(String wire) {
      this.wire = wire;
    }

    @com.fasterxml.jackson.annotation.JsonValue
    public String wire() {
      return wire;
    }
  }
}
