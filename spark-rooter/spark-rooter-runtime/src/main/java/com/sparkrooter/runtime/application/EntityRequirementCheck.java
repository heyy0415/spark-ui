package com.sparkrooter.runtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparkrooter.contracts.model.ToolSearch;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 规划前拦截（spec §2.2）：领域内**全部**候选工具都要求某个实体参数（orderId ↔ order、productId ↔ product）， 而已识别实体（消息 + 页面上下文，见
 * EntityExtractor）里没有时，直接给用户一句提示并结束 Run，不进规划、不调 Gateway。 只要有一个候选不需要实体（如
 * order.list.search）就放行，由规划器决定；动词命中但目标缺实体的情况由规划器抛 MissingEntity。纯函数。
 */
public final class EntityRequirementCheck {

  /** 必填参数名 → 满足它所需的页面实体类型。 */
  public static final Map<String, String> ENTITY_ARGS =
      Map.of("orderId", "order", "productId", "product");

  private static final Map<String, String> NEED_ENTITY_TEXT =
      Map.of(
          "refund", "请先选择一个订单，再发起退款",
          "aftersale", "请先选择一个订单，再申请售后",
          "order", "请先选择一个订单",
          "product", "请先选择一个商品");
  private static final String DEFAULT_TEXT = "请先选择相关对象";

  /** 领域的提示文案（MissingEntity 信号也用它）。 */
  public static String text(String domain) {
    return NEED_ENTITY_TEXT.getOrDefault(domain, DEFAULT_TEXT);
  }

  private EntityRequirementCheck() {}

  /** 返回提示文案表示应拦截；empty 表示放行。 */
  public static Optional<String> check(
      String domain, List<ToolSearch.ToolCandidate> candidates, Map<String, String> entities) {
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    for (Map.Entry<String, String> e : ENTITY_ARGS.entrySet()) {
      String arg = e.getKey();
      String type = e.getValue();
      boolean allRequire = candidates.stream().allMatch(c -> requires(c, arg));
      boolean satisfied = entities.containsKey(type);
      if (allRequire && !satisfied) {
        return Optional.of(text(domain));
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
