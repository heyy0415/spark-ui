package com.strato.runtime.application;

import java.util.Map;

/**
 * 工具的用户可读名称（tool.selected.displayName），与各 Manifest 的 name 一致。 放在 application 层：编排器与 LLM 装配都要用，且
 * application 不得反向依赖 infra。
 */
public final class ToolDisplayNames {

  static final Map<String, String> NAMES =
      Map.of(
          "order.detail.get", "查询订单详情",
          "order.list.search", "搜索订单",
          "refund.eligibility.check", "检查退款资格",
          "refund.preview", "退款试算",
          "refund.create", "创建退款",
          "refund.status.get", "查询退款状态");

  private ToolDisplayNames() {}

  public static Map<String, String> all() {
    return NAMES;
  }

  /** 未知 toolId 回退为 toolId 本身。 */
  public static String of(String toolId) {
    return NAMES.getOrDefault(toolId, toolId);
  }
}
