package com.sparkrooter.runtime.application;

import com.sparkrooter.contracts.model.IntentRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实体抽取（纯函数）：从消息正则抓 ID（订单 5 位数字、商品 P-4 位），再用 pageContext.selectedEntity 补位（消息优先）。 产出 {order:
 * "10002", product: "P-1003"} 形态的 map。ID 格式白名单，调用方日志只记录抓到的 ID 与类型，不记录原文。
 */
public final class EntityExtractor {

  // 尾部 (?!\\d) 防止「订单 100021」抓成 10002
  private static final Pattern ORDER = Pattern.compile("订单\\s*(\\d{5})(?!\\d)");
  private static final Pattern PRODUCT = Pattern.compile("商品\\s*(P-\\d{4})(?!\\d)");

  /** 页面实体类型白名单：只有这些类型会被采纳补位。 */
  private static final Map<String, Pattern> ID_FORMAT =
      Map.of("order", Pattern.compile("^\\d{5}$"), "product", Pattern.compile("^P-\\d{4}$"));

  private EntityExtractor() {}

  public static Map<String, String> extract(String message, IntentRequest.SelectedEntity selected) {
    Map<String, String> out = new LinkedHashMap<>();
    if (message != null) {
      Matcher o = ORDER.matcher(message);
      if (o.find()) {
        out.put("order", o.group(1));
      }
      Matcher p = PRODUCT.matcher(message);
      if (p.find()) {
        out.put("product", p.group(1));
      }
    }
    if (selected != null && selected.type() != null && selected.id() != null) {
      Pattern fmt = ID_FORMAT.get(selected.type());
      if (fmt != null && fmt.matcher(selected.id()).matches()) {
        out.putIfAbsent(selected.type(), selected.id());
      }
    }
    return out;
  }
}
