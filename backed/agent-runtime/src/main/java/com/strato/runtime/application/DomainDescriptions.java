package com.strato.runtime.application;

import java.util.Map;

/** 领域 → 给分类器看的一句话说明（硬编码在 runtime；Registry 返回的领域若无说明则只给名字）。 */
public final class DomainDescriptions {

  private static final Map<String, String> DESCRIPTIONS =
      Map.of(
          "refund", "退款、退货、退钱、把钱要回来、不想要了等与退款相关的请求",
          "order", "查询订单详情、订单状态、物流、发货、搜索订单等与订单信息相关的请求");

  private DomainDescriptions() {}

  public static String of(String domain) {
    return DESCRIPTIONS.getOrDefault(domain, "");
  }
}
