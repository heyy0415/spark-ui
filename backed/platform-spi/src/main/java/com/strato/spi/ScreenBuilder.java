package com.strato.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;

/**
 * 领域模块提供的屏生成器：把工具输出 / 待确认参数变成 UI Schema（以 JsonNode 形态返回，runtime 统一按 ui-schema 契约校验）。 确认屏只能使用同一 Run
 * 内经 Gateway 得到的 previousOutputs 与 fixedArgs，不得直读领域数据。
 */
public interface ScreenBuilder {

  /** 成功输出后由本 builder 出结果屏的工具集合。 */
  Set<String> resultToolIds();

  /** 需确认时由本 builder 出确认屏的工具集合。 */
  Set<String> confirmToolIds();

  JsonNode result(String toolId, JsonNode output, ScreenContext ctx);

  /**
   * @param previousOutputs 同 Run 内此前各只读步骤的输出（toolId → output）
   * @param token 后端签发的 confirmationToken，放入 submit action
   */
  JsonNode confirmation(
      String toolId,
      Map<String, String> fixedArgs,
      Map<String, JsonNode> previousOutputs,
      String token,
      ScreenContext ctx);

  /** tool.completed.summary 的用户可读一句话；null 表示无。 */
  default String summary(String toolId, JsonNode output) {
    return null;
  }
}
