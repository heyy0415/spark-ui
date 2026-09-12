package com.example.demo.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.model.ToolSearch;
import com.sparkrooter.runtime.application.ToolDisplayNames;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.infra.llm.PlanDraft;
import com.sparkrooter.runtime.infra.llm.PlanValidator;
import com.sparkrooter.spi.ConfirmationRecheck;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * e2e 专用的确定性规划器：模型输出的替身，让端到端验收不依赖真实模型。
 *
 * <p><b>为什么在宿主工程而不是内核</b>：它必须认识示例领域的动词与工具（「退款」→ {@code refund.*}），而内核被
 * {@code check-module-deps} 的 DOMAIN_WORDS 红线禁止出现这些词。领域语义只能待在宿主侧。
 *
 * <p><b>不是捷径</b>：本类只负责「原话 + 上下文 → {@link PlanDraft}」这一段（即真模型的职责），产出的草案一律交给
 * {@link PlanValidator#decide} 做校验与派发，与 {@code LlmPlanner} 走完全相同的路径。因此 e2e 验证的仍是
 * 校验边界、编排、网关与领域实现，而非绕过它们。
 *
 * <p>仅在 {@code e2e} / {@code e2e-ttl} profile 装配；默认与 Docker 启动下不存在，规划器仍是真模型或
 * {@code UnavailablePlanner}。规则表只覆盖 {@code e2e-backend.sh} 实际发送的消息形态，不追求通用理解能力。
 */
@Component
@Profile({"e2e", "e2e-ttl"})
public class FakeLlmPlanner implements LlmClient {

  private static final Logger log = LoggerFactory.getLogger(FakeLlmPlanner.class);

  /** 订单号：带「订单」前缀，或独立的 5 位数字（前后非数字、非 `-`，避免命中 P-1003 与更长数字）。 */
  private static final Pattern ORDER_ID = Pattern.compile("订单\\s*(\\d{5})(?!\\d)");

  private static final Pattern BARE_ORDER_ID = Pattern.compile("(?<![\\d-])(\\d{5})(?![\\d-])");

  /** 商品号：形如 P-1003。 */
  private static final Pattern PRODUCT_ID = Pattern.compile("(P-\\d{4})");

  /** 「最近 N 单 / 条 / 个」里的条数。 */
  private static final Pattern COUNT = Pattern.compile("(\\d+)\\s*[单条个]");

  /** 序数指代：「第二个」→ 索引 1。仅覆盖 e2e 用到的「第二个」。 */
  private static final Pattern ORDINAL = Pattern.compile("第([一二三四五六七八九十]|\\d+)个?");

  private static final Map<String, Integer> CN_NUMERALS =
      Map.of("一", 1, "二", 2, "三", 3, "四", 4, "五", 5, "六", 6, "七", 7, "八", 8, "九", 9, "十", 10);

  /** 订单状态中文名 → 枚举值（与 OrderTools.ListIn.status 的 @SparkParam.aliases 一致）。 */
  private static final Map<String, String> STATUS_ALIASES =
      Map.of(
          "已支付", "PAID",
          "已发货", "SHIPPED",
          "已完成", "COMPLETED",
          "已退款", "REFUNDED",
          "已取消", "CANCELLED");

  private final ToolDisplayNames displayNames;
  private final ToolMetaRegistry meta;
  private final SchemaValidator validator;
  private final ObjectProvider<ConfirmationRecheck> rechecks;

  public FakeLlmPlanner(
      ToolDisplayNames displayNames,
      ToolMetaRegistry meta,
      SchemaValidator validator,
      ObjectProvider<ConfirmationRecheck> rechecks) {
    this.displayNames = displayNames;
    this.meta = meta;
    this.validator = validator;
    this.rechecks = rechecks;
    log.warn("e2e 假规划器已装配（profile=e2e/e2e-ttl），仅供测试；生产 profile 下不存在");
  }

  @Override
  public Decision plan(PlanRequest req) {
    PlanDraft draft = draft(req);
    // 与真模型同一条校验 / 派发路径：草案不得绕过 PlanValidator
    return PlanValidator.decide(draft, req, displayNames, meta, trustedOnlyArgs(), validator);
  }

  /** 需确认步骤中规划器不得填写的参数名（各领域 ConfirmationRecheck 声明的并集），口径与 starter 装配真模型时一致。 */
  private Set<String> trustedOnlyArgs() {
    Set<String> trusted = new HashSet<>();
    rechecks.orderedStream().forEach(r -> trusted.addAll(r.trustedArgKeys()));
    return trusted;
  }

  /**
   * 原话 + 会话上下文 → 草案。动词决定目标工具，实体来自原话或上下文；两者都拿不到时给 clarify。
   *
   * <p>动词判定顺序固定：当前消息自带动词优先，只有当前消息无动词时才继承 {@code pendingMessage}（上一轮挂起的原话）。
   * 否则「删除订单」出澄清屏后用户说「第二个的物流」会被错误地拼回「删除」。
   */
  private PlanDraft draft(PlanRequest req) {
    String msg = req.message() == null ? "" : req.message();
    Context ctx = req.context();

    // 商品链路：与订单无关，先判
    Optional<String> productId = find(PRODUCT_ID, msg);
    if (productId.isPresent() && msg.contains("详情")) {
      return plan(step("product.detail.get", Map.of("productId", productId.get())));
    }
    if (msg.contains("商品")) {
      return plan(step("product.list.search", Map.of()));
    }

    // 动词：当前消息优先，其次继承上一轮挂起的原话
    String verb = verbOf(msg);
    if (verb == null) {
      verb = ctx.pendingMessage().map(this::verbOf).orElse(null);
    }

    // 订单列表：无动词且提到订单 / 最近，或动词就是「列表」
    if (verb == null) {
      if (msg.contains("订单") || msg.contains("最近")) {
        return plan(step("order.list.search", listArgs(msg, req.candidates())));
      }
      return none("当前没有可用能力处理该请求");
    }
    if ("list".equals(verb)) {
      return plan(step("order.list.search", listArgs(msg, req.candidates())));
    }

    // 需要订单号的动词：原话 → 序数指代 → 记忆实体
    Optional<String> orderId = resolveOrderId(msg, ctx);
    if (orderId.isEmpty()) {
      // 缺实体：missing.entity 用英文类型名，normalizeEntityType 才能命中
      return clarify(verb);
    }
    return plan(stepsFor(verb, orderId.get()));
  }

  /**
   * 消息里的动词。返回 null 表示未命中；{@code "list"} 表示列表查询。
   *
   * <p>顺序有意义：「删除」「退款」等强动词先判，避免「删除订单」被「订单」误判成列表。
   */
  private String verbOf(String msg) {
    if (msg.contains("物流")) {
      return "logistics";
    }
    if (msg.contains("详情")) {
      return "detail";
    }
    if (msg.contains("删除")) {
      return "delete";
    }
    if (msg.contains("退款") || msg.contains("退钱") || msg.contains("钱要回来")) {
      return "refund";
    }
    if (msg.contains("售后")) {
      return "aftersale";
    }
    if (msg.contains("最近") || msg.contains("看看我的订单") || msg.contains("查看最近")) {
      return "list";
    }
    return null;
  }

  /** 动词 → 步骤链。需确认工具带上注解声明的只读前置步骤，顺序与 @SparkPrerequisite 一致。 */
  private List<PlanDraft.DraftStep> stepsFor(String verb, String orderId) {
    Map<String, String> idArg = Map.of("orderId", orderId);
    return switch (verb) {
      case "logistics" -> List.of(step("order.logistics.get", idArg));
      case "detail" -> List.of(step("order.detail.get", idArg));
      case "delete" -> List.of(step("order.detail.get", idArg), step("order.delete", idArg));
      case "refund" ->
          List.of(
              step("refund.eligibility.check", idArg),
              step("refund.preview", idArg),
              step("refund.create", idArg));
      case "aftersale" ->
          List.of(step("aftersale.list.get", idArg), step("aftersale.create", idArg));
      default -> List.of(step("order.list.search", Map.of()));
    };
  }

  /** 缺实体时的澄清草案：steps 给目标工具（args 缺 orderId），missing 显式声明实体类型，两者互为冗余。 */
  private PlanDraft clarify(String verb) {
    List<PlanDraft.DraftStep> steps = stepsFor(verb, "");
    List<PlanDraft.DraftStep> stripped =
        steps.stream().map(s -> new PlanDraft.DraftStep(s.toolId(), Map.of())).toList();
    return new PlanDraft(
        "clarify", stripped, List.of(new PlanDraft.Missing("order", "缺少订单号")), "请指定要操作的订单");
  }

  /**
   * 订单列表的参数：状态别名 + 条数。
   *
   * <p>条数按候选 inputSchema 的 {@code maximum} / {@code minimum} 夹紧——真模型受 prompt 约束会遵守 schema，
   * 替身必须模仿这一行为，否则「最近 100 单」会因超出 {@code max = 50} 被 PlanValidator 拒（替身没有真模型的二次重试）。
   */
  private Map<String, String> listArgs(String msg, List<ToolSearch.ToolCandidate> candidates) {
    Map<String, String> args = new LinkedHashMap<>();
    STATUS_ALIASES.forEach(
        (cn, code) -> {
          if (msg.contains(cn)) {
            args.put("status", code);
          }
        });
    find(COUNT, msg)
        .ifPresent(n -> args.put("limit", clampToSchema("order.list.search", "limit", n, candidates)));
    return args;
  }

  /** 按候选 inputSchema 的数值边界夹紧整数参数；取不到 schema 时原样返回。 */
  private String clampToSchema(
      String toolId, String argName, String raw, List<ToolSearch.ToolCandidate> candidates) {
    JsonNode prop =
        candidates.stream()
            .filter(c -> c.toolId().equals(toolId))
            .findFirst()
            .map(c -> c.inputSchema().path("properties").path(argName))
            .orElse(null);
    if (prop == null || prop.isMissingNode()) {
      return raw;
    }
    try {
      long v = Long.parseLong(raw);
      if (prop.has("maximum")) {
        v = Math.min(v, prop.path("maximum").asLong());
      }
      if (prop.has("minimum")) {
        v = Math.max(v, prop.path("minimum").asLong());
      }
      return String.valueOf(v);
    } catch (NumberFormatException e) {
      return raw;
    }
  }

  /** 订单号解析：原话 → 序数指代（查最近列表行）→ 记忆实体。 */
  private Optional<String> resolveOrderId(String msg, Context ctx) {
    Optional<String> fromMessage = find(ORDER_ID, msg).or(() -> find(BARE_ORDER_ID, msg));
    if (fromMessage.isPresent()) {
      return fromMessage;
    }
    Optional<String> byOrdinal = ordinalOf(msg).flatMap(i -> nth(ctx.lastRowIds(), i));
    if (byOrdinal.isPresent()) {
      return byOrdinal;
    }
    return Optional.ofNullable(ctx.entities().get("order"));
  }

  /** 「第二个」→ 0-based 索引 1；未命中返回 empty。 */
  private Optional<Integer> ordinalOf(String msg) {
    Matcher m = ORDINAL.matcher(msg);
    if (!m.find()) {
      return Optional.empty();
    }
    String token = m.group(1);
    Integer cn = CN_NUMERALS.get(token);
    if (cn != null) {
      return Optional.of(cn - 1);
    }
    try {
      return Optional.of(Integer.parseInt(token) - 1);
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static Optional<String> nth(List<String> ids, int index) {
    return ids != null && index >= 0 && index < ids.size()
        ? Optional.of(ids.get(index))
        : Optional.empty();
  }

  private static Optional<String> find(Pattern p, String s) {
    Matcher m = p.matcher(s);
    return m.find() ? Optional.of(m.group(1)) : Optional.empty();
  }

  private static PlanDraft.DraftStep step(String toolId, Map<String, String> args) {
    return new PlanDraft.DraftStep(toolId, args);
  }

  private static PlanDraft plan(PlanDraft.DraftStep single) {
    return plan(List.of(single));
  }

  private static PlanDraft plan(List<PlanDraft.DraftStep> steps) {
    return new PlanDraft("plan", new ArrayList<>(steps), List.of(), null);
  }

  private static PlanDraft none(String reply) {
    return new PlanDraft("none", List.of(), List.of(), reply);
  }

  @Override
  public String name() {
    return "fake-e2e";
  }
}
