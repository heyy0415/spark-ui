package com.strato.runtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.strato.contracts.model.IntentRequest;
import com.strato.contracts.model.ToolSearch;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 规划前拦截（spec §2.2）：领域内**全部**候选工具都要求某个实体参数（首期只有 orderId ↔ 页面选中 type=order），
 * 而页面上下文没有该实体时，直接给用户一句提示并结束 Run，不进规划、不调 Gateway。 只要有一个候选不需要实体（如
 * order.list.search）就放行，由规划器选择不需实体的工具。纯函数。
 */
public final class EntityRequirementCheck {

  /** 必填参数名 → 满足它所需的页面实体类型。 */
  static final Map<String, String> ENTITY_ARGS = Map.of("orderId", "order");

  private static final Map<String, String> NEED_ENTITY_TEXT =
      Map.of(
          "refund", "请先在页面上选择一个订单，再发起退款",
          "order", "请先在页面上选择一个订单");
  private static final String DEFAULT_TEXT = "请先在页面上选择相关对象";

  private EntityRequirementCheck() {}

  /** 返回提示文案表示应拦截；empty 表示放行。 */
  public static Optional<String> check(
      String domain,
      List<ToolSearch.ToolCandidate> candidates,
      IntentRequest.SelectedEntity selected) {
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    for (Map.Entry<String, String> e : ENTITY_ARGS.entrySet()) {
      String arg = e.getKey();
      String type = e.getValue();
      boolean allRequire = candidates.stream().allMatch(c -> requires(c, arg));
      boolean satisfied = selected != null && type.equals(selected.type());
      if (allRequire && !satisfied) {
        return Optional.of(NEED_ENTITY_TEXT.getOrDefault(domain, DEFAULT_TEXT));
      }
    }
    return Optional.empty();
  }

  private static boolean requires(ToolSearch.ToolCandidate c, String arg) {
    JsonNode required = c.inputSchema().path("required");
    if (!required.isArray()) {
      return false;
    }
    for (JsonNode n : required) {
      if (arg.equals(n.asText())) {
        return true;
      }
    }
    return false;
  }
}
