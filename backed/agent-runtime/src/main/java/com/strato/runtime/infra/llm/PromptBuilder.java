package com.strato.runtime.infra.llm;

import com.strato.contracts.model.ToolSearch;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 构造规划 prompt。候选 description 视为不可信文本（agent-safety §2）：截断到 500 字符并转义花括号 / 反引号 / 换行， 防止把模板占位符或指令注入进
 * prompt。
 */
public final class PromptBuilder {

  static final int MAX_DESCRIPTION = 500;

  private PromptBuilder() {}

  public static String system() {
    return """
        你是企业内部工具规划器。只能从给定的候选工具中选择，不得发明工具名。
        输出必须是 JSON 对象：{"steps":[{"toolId":"...","args":{...}}]}，不要输出任何其他文字。
        规则：先做只读检查与试算，再做有副作用的操作；有副作用的步骤放在最后。
        用户请求的是执行某个操作（如退款、删除、申请售后）时，计划必须包含完成该操作的那个有副作用的工具步骤，不能只做检查就结束；
        系统会在执行前向用户确认，你不需要为此省略该步骤。用户只是询问状态或信息时才只用只读工具。
        动词与目标工具的对应：删除/删掉 → order.delete；物流/到哪/快递 → order.logistics.get；售后/换货/维修/退货 → aftersale.create；
        退款/退钱 → refund.create；详情/看看这个/查看商品 → 该领域的 detail.get；没有动词时，有实体 → detail.get，无实体 → list.search（售后为 aftersale.list.get）。
        有副作用的目标工具必须带上它的前置只读步骤且放在前面：refund.create 前置 refund.eligibility.check、refund.preview；
        order.delete 前置 order.detail.get；aftersale.create 前置 aftersale.list.get。refund.status.get 不进退款计划。
        args 只能包含候选 inputSchema 中声明的字段，值一律为字符串；orderId / productId 从「已识别实体」取，没有就不要发明。
        需要用户确认的步骤不要填写 amount 等金额字段，金额由系统在确认后按试算结果填入。
        """;
  }

  public static String user(
      String message,
      String domain,
      List<ToolSearch.ToolCandidate> candidates,
      Map<String, String> entities) {
    String tools =
        candidates.stream()
            .map(
                c ->
                    "- toolId=%s version=%s risk=%s confirmation=%s\n  description: %s\n  inputSchema: %s"
                        .formatted(
                            c.toolId(),
                            c.version(),
                            c.riskLevel(),
                            c.confirmation(),
                            sanitize(c.description()),
                            sanitize(c.inputSchema().toString())))
            .collect(Collectors.joining("\n"));
    String ctx =
        entities.isEmpty()
            ? "(无)"
            : entities.entrySet().stream()
                .map(e -> e.getKey() + "=" + sanitize(e.getValue()))
                .collect(Collectors.joining(", "));
    return """
        领域：%s
        用户请求：%s
        已识别实体：%s
        候选工具：
        %s
        """
        .formatted(domain, sanitize(message), ctx, tools);
  }

  static String sanitize(String s) {
    if (s == null) {
      return "";
    }
    String t = s.length() > MAX_DESCRIPTION ? s.substring(0, MAX_DESCRIPTION) : s;
    return t.replace("{", "｛")
        .replace("}", "｝")
        .replace("`", "'")
        .replace("\r", " ")
        .replace("\n", " ");
  }
}
