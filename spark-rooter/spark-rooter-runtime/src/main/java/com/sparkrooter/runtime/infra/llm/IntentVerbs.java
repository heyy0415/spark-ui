package com.sparkrooter.runtime.infra.llm;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 规则规划器的确定性表（spec §2.4.3）：动词 → 目标工具；目标工具 → 前置只读步骤。按声明顺序匹配首个命中的动词。 `<domain>` 占位在解析时替换为路由出的领域。
 * 真模型模式的 prompt 也附这张表，两种模式行为一致。表内 toolId 在启动时经 IntentVerbsSelfCheck 校验都已注册。
 */
public final class IntentVerbs {

  /** 一条动词规则：任一关键词命中 → 目标工具。 */
  public record Verb(List<String> keywords, String target) {}

  /** 顺序敏感：先具体动词（删除 / 物流），后泛化（详情）。 */
  public static final List<Verb> VERBS =
      List.of(
          new Verb(List.of("删除", "删掉"), "order.delete"),
          new Verb(List.of("物流", "到哪", "快递"), "order.logistics.get"),
          new Verb(List.of("售后", "换货", "维修", "退货"), "aftersale.create"),
          new Verb(List.of("退款", "退钱"), "refund.create"),
          new Verb(List.of("详情", "看看这个", "查看商品"), "<domain>.detail.get"));

  /** 目标工具 → 必须在它之前执行的只读步骤（有序）。 */
  public static final Map<String, List<String>> PREREQUISITES =
      Map.of(
          "refund.create", List.of("refund.eligibility.check", "refund.preview"),
          "order.delete", List.of("order.detail.get"),
          "aftersale.create", List.of("aftersale.list.get"));

  /** 无动词时按领域的默认：有实体 → detail，无实体 → list。 */
  private static final Map<String, String> LIST_TOOL =
      Map.of(
          "order", "order.list.search",
          "product", "product.list.search",
          "aftersale", "aftersale.list.get",
          "refund", "refund.status.get");

  /** 有实体但无动词时的领域详情工具；没有 detail 工具的领域（refund / aftersale）落到其查询工具。 */
  private static final Map<String, String> DETAIL_TOOL =
      Map.of(
          "order", "order.detail.get",
          "product", "product.detail.get",
          "aftersale", "aftersale.list.get",
          "refund", "refund.status.get");

  /** 目标工具 → 澄清屏行内按钮文案（与 contracts.md §4 label ↔ intent 绑定表一致）。 */
  private static final Map<String, String> TARGET_LABEL =
      Map.of(
          "order.delete", "删除订单",
          "order.logistics.get", "查看物流",
          "aftersale.create", "申请售后",
          "refund.create", "退款",
          "product.detail.get", "查看商品",
          "order.detail.get", "查看详情");

  /** 用户原动词对应的按钮文案；无动词时按实体类型给「选择」。 */
  public static String verbLabel(String message, String domain) {
    return target(message, domain).map(t -> TARGET_LABEL.getOrDefault(t, "选择")).orElse("选择");
  }

  private IntentVerbs() {}

  /**
   * 动词命中的目标工具（已替换领域占位）；无动词 → empty。先只看路由出的领域自己的动词（「退货退款」路由到 refund 就选 refund.create，而不是表里更靠前的
   * aftersale.create），再退到全表顺序，使动词表顺序不与 DomainRouter 顺序打架。
   */
  public static Optional<String> target(String message, String domain) {
    if (message == null) {
      return Optional.empty();
    }
    for (Verb v : VERBS) {
      String t = resolve(v.target(), domain);
      if (!t.startsWith(domain + ".")) {
        continue;
      }
      for (String k : v.keywords()) {
        if (message.contains(k)) {
          return Optional.of(t);
        }
      }
    }
    for (Verb v : VERBS) {
      for (String k : v.keywords()) {
        if (message.contains(k)) {
          return Optional.of(resolve(v.target(), domain));
        }
      }
    }
    return Optional.empty();
  }

  /** `<domain>.detail.get` 占位按 DETAIL_TOOL 解析（refund / aftersale 没有 detail 工具，落到其查询工具）。 */
  private static String resolve(String target, String domain) {
    if (target.contains("<domain>")) {
      return DETAIL_TOOL.getOrDefault(domain, target.replace("<domain>", domain));
    }
    return target;
  }

  /** 无动词时的默认目标。 */
  public static String fallbackTarget(String domain, boolean hasDomainEntity) {
    if (hasDomainEntity) {
      return DETAIL_TOOL.getOrDefault(domain, domain + ".detail.get");
    }
    return LIST_TOOL.getOrDefault(domain, domain + ".list.search");
  }

  public static List<String> prerequisites(String target) {
    return PREREQUISITES.getOrDefault(target, List.of());
  }

  /** 表内出现的全部 toolId（不含领域占位那条），供启动校验。 */
  public static List<String> referencedToolIds() {
    List<String> ids = new java.util.ArrayList<>();
    VERBS.stream().map(Verb::target).filter(t -> !t.contains("<domain>")).forEach(ids::add);
    PREREQUISITES.forEach(
        (k, v) -> {
          ids.add(k);
          ids.addAll(v);
        });
    LIST_TOOL.values().forEach(ids::add);
    DETAIL_TOOL.values().forEach(ids::add);
    return ids;
  }
}
