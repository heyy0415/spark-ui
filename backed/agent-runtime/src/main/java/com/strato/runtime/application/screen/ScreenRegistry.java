package com.strato.runtime.application.screen;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.strato.contracts.SchemaValidator;
import com.strato.contracts.model.RunFailureCode;
import com.strato.contracts.model.UiSchema;
import com.strato.runtime.domain.RunFailure;
import com.strato.spi.ScreenBuilder;
import com.strato.spi.ScreenContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Component;

/**
 * 屏注册表：按 toolId 把「结果屏 / 确认屏」交给领域模块的 ScreenBuilder（spi Bean），查不到走 FallbackScreenBuilder。 领域返回
 * JsonNode，本类是唯一的 ui-schema 契约校验点（校验后再转 UiSchema）。同一 toolId 被两个 builder 声明视为装配错误。
 */
@Component
public class ScreenRegistry {

  /**
   * 两遍生成确认屏时第一遍用的占位令牌：令牌绑定 actionId，而 actionId 由屏决定，所以先用占位符生成一遍读出 action id 与 Form 字段，
   * 再签发令牌生成正式屏。占位符满足契约长度，但绝不下发。
   */
  public static final String PLACEHOLDER_TOKEN = "ct_" + "0".repeat(32);

  private final Map<String, ScreenBuilder> byResult = new HashMap<>();
  private final Map<String, ScreenBuilder> byConfirm = new HashMap<>();
  private final List<ScreenBuilder> builders;
  private final ScreenBuilder fallback;
  private final SchemaValidator validator;
  private final ObjectMapper mapper;

  public ScreenRegistry(List<ScreenBuilder> builders, SchemaValidator validator) {
    this.builders = List.copyOf(builders);
    this.validator = validator;
    this.mapper = validator.mapper();
    this.fallback = new FallbackScreenBuilder();
    for (ScreenBuilder b : builders) {
      for (String id : b.resultToolIds()) {
        if (byResult.putIfAbsent(id, b) != null) {
          throw new IllegalStateException("duplicate result ScreenBuilder for " + id);
        }
      }
      for (String id : b.confirmToolIds()) {
        if (byConfirm.putIfAbsent(id, b) != null) {
          throw new IllegalStateException("duplicate confirmation ScreenBuilder for " + id);
        }
      }
    }
  }

  /** 该需确认工具是否有领域提供的确认屏（没有则 runtime fail-closed，不用 fallback 放行）。 */
  public boolean coversConfirmation(String toolId) {
    return byConfirm.containsKey(toolId);
  }

  public UiSchema result(String toolId, JsonNode output, ScreenContext ctx) {
    return toUi(byResult.getOrDefault(toolId, fallback).result(toolId, output, ctx));
  }

  public UiSchema confirmation(
      String toolId,
      Map<String, String> fixedArgs,
      Map<String, JsonNode> previousOutputs,
      String token,
      ScreenContext ctx) {
    return toUi(
        byConfirm
            .getOrDefault(toolId, fallback)
            .confirmation(toolId, fixedArgs, previousOutputs, token, ctx));
  }

  /** tool.completed.summary：问遍所有 builder（摘要不限于出屏的工具），都不认识返回 null。 */
  public String summary(String toolId, JsonNode output) {
    if (output == null) {
      return null;
    }
    for (ScreenBuilder b : builders) {
      String s = b.summary(toolId, output);
      if (s != null) {
        return s;
      }
    }
    return null;
  }

  private UiSchema toUi(JsonNode node) {
    validator.assertValid("ui-schema", node);
    try {
      return mapper.treeToValue(node, UiSchema.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("screen builder produced a tree that is not a UiSchema", e);
    }
  }

  /** 确认屏中 Form 声明的字段名集合，作为 formData 键白名单；无 Form 的确认屏返回空集。 */
  public static Set<String> formKeys(UiSchema ui) {
    return ui.components().stream()
        .filter(c -> c.type() == UiSchema.ComponentType.Form)
        .flatMap(c -> StreamSupport.stream(c.props().path("fields").spliterator(), false))
        .map(f -> f.path("name").asText())
        .filter(s -> !s.isBlank())
        .collect(Collectors.toUnmodifiableSet());
  }

  /** 确认屏必须恰有一个 submit 动作；其 id 即令牌绑定的 actionId。 */
  public static String submitActionId(UiSchema ui) {
    List<String> ids =
        ui.actions().stream()
            .filter(a -> a.type() == UiSchema.ActionType.submit)
            .map(UiSchema.Action::id)
            .toList();
    if (ids.size() != 1) {
      throw new RunFailure(
          RunFailureCode.INTERNAL_ERROR.name(),
          "confirmation screen must have exactly one submit action, got " + ids.size());
    }
    return ids.get(0);
  }
}
