package com.sparkrooter.runtime.application;

import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.spi.annotation.ParamFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 参数抽取（纯函数，表驱动；spec §2.6 抽取层）。三类结果：
 *
 * <ul>
 *   <li>实体 ID：正则抓订单 5 位数字、商品 P-4 位（尾边界防止「订单 100021」抓成 10002）→ {order: "10002"}
 *   <li>枚举别名：@SparkParam.aliases（「已发货」→ SHIPPED）
 *   <li>数量：「最近 5 单」「前 10 条」→ @SparkParam.unit 含该单位词的整型参数，截断到 max
 *   <li>相对时间：「最近一周」「最近 7 天」「这个月」「上个月」「9 月 1 日以后」→ format=DATE 参数的 ISO 日期（只产下界）
 * </ul>
 *
 * 前端只发自然语言，没有页面实体可补位；会话记忆补位在编排器（T12）。调用方日志只记类型与值，不记原文。
 */
public final class ArgumentExtractor {

  private static final Pattern ORDER = Pattern.compile("订单\\s*(\\d{5})(?!\\d)");

  /** 裸订单号：用户常只打「10030查看物流」；独立的 5 位数字（前后不是数字 / 连字符，避开 P-1003 与更长数字、金额）。 */
  private static final Pattern BARE_ORDER = Pattern.compile("(?<![\\d\\-.])(\\d{5})(?![\\d.])");

  private static final Pattern PRODUCT = Pattern.compile("商品\\s*(P-\\d{4})(?!\\d)");

  /** 数量：数字 + 单位词；单位词集合来自各参数的 unit 声明，运行期拼接。数字前不能紧跟「订单」等实体前缀（由实体正则先消费）。 */
  private static final String QUANTITY_TEMPLATE = "(?<![\\d-])(\\d{1,3})\\s*(%s)";

  private static final Pattern LAST_WEEK = Pattern.compile("最近(一|1)\\s*周");
  private static final Pattern LAST_DAYS = Pattern.compile("最近\\s*(\\d{1,3})\\s*天");
  private static final Pattern THIS_MONTH = Pattern.compile("这个月|本月");
  private static final Pattern LAST_MONTH = Pattern.compile("上个月|上月");
  private static final Pattern SINCE_DATE = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日以后");

  /** 序数指代：「第 2 个 / 第二单 / 最后一个 / 第一个」→ 最近列表的行下标。 */
  private static final Pattern ORDINAL_DIGIT = Pattern.compile("第\\s*(\\d{1,2})\\s*(个|单|条|件)");

  private static final Pattern ORDINAL_CN = Pattern.compile("第([一二三四五六七八九十])(个|单|条|件)");
  private static final Pattern LAST_ONE = Pattern.compile("最后一(个|单|条|件)");
  private static final String CN_DIGITS = "一二三四五六七八九十";

  /** 消息是否含序数指代（供路由：纯指代消息没有领域词，沿用记忆领域）。 */
  public static boolean isOrdinalReference(String message) {
    return message != null
        && (LAST_ONE.matcher(message).find()
            || ORDINAL_DIGIT.matcher(message).find()
            || ORDINAL_CN.matcher(message).find());
  }

  /** 序数指代 → 最近列表 rowIds 中的 ID（1 起；「最后一个」= 末项）；未命中或越界 empty。 */
  public static java.util.Optional<String> ordinalReference(String message, List<String> rowIds) {
    if (message == null || rowIds == null || rowIds.isEmpty()) {
      return java.util.Optional.empty();
    }
    if (LAST_ONE.matcher(message).find()) {
      return java.util.Optional.of(rowIds.get(rowIds.size() - 1));
    }
    int n = -1;
    Matcher d = ORDINAL_DIGIT.matcher(message);
    if (d.find()) {
      n = Integer.parseInt(d.group(1));
    } else {
      Matcher c = ORDINAL_CN.matcher(message);
      if (c.find()) {
        n = CN_DIGITS.indexOf(c.group(1)) + 1;
      }
    }
    if (n >= 1 && n <= rowIds.size()) {
      return java.util.Optional.of(rowIds.get(n - 1));
    }
    return java.util.Optional.empty();
  }

  private ArgumentExtractor() {}

  /** 从消息抓实体 ID：{order, product}（缺则无键）。 */
  public static Map<String, String> extractEntities(String message) {
    Map<String, String> out = new LinkedHashMap<>();
    if (message != null) {
      Matcher o = ORDER.matcher(message);
      if (o.find()) {
        out.put("order", o.group(1));
      } else {
        Matcher bare = BARE_ORDER.matcher(message);
        if (bare.find()) {
          out.put("order", bare.group(1));
        }
      }
      Matcher p = PRODUCT.matcher(message);
      if (p.find()) {
        out.put("product", p.group(1));
      }
    }
    return out;
  }

  /** 针对某个工具的参数元数据，从消息抽取枚举 / 数量 / 相对时间参数值（不含实体，实体由调用方按 entity 类型填）。 值一律字符串（Step.fixedArgs 口径）。 */
  public static Map<String, String> extractArgs(
      String message, ToolMetaRegistry.ToolMeta meta, Clock clock) {
    Map<String, String> out = new LinkedHashMap<>();
    if (message == null || meta == null) {
      return out;
    }
    for (ToolMetaRegistry.ParamMeta p : meta.params().values()) {
      // 枚举别名：按声明顺序首个命中
      for (Map.Entry<String, String> alias : p.aliases().entrySet()) {
        if (message.contains(alias.getKey())) {
          out.put(p.name(), alias.getValue());
          break;
        }
      }
      // 数量
      if (p.integer() && !p.units().isEmpty()) {
        Pattern q = Pattern.compile(String.format(QUANTITY_TEMPLATE, String.join("|", p.units())));
        Matcher m = q.matcher(message);
        if (m.find()) {
          long v = Long.parseLong(m.group(1));
          if (p.max() != null && v > p.max()) {
            v = p.max();
          }
          if (p.min() != null && v < p.min()) {
            v = p.min();
          }
          out.put(p.name(), Long.toString(v));
        }
      }
      // 相对时间：只产下界日期
      if (p.format() == ParamFormat.DATE) {
        relativeDate(message, LocalDate.now(clock.withZone(ZoneId.systemDefault())))
            .ifPresent(d -> out.put(p.name(), d.toString()));
      }
    }
    return out;
  }

  /** 相对时间表 → 下界日期；未命中 empty。 */
  static java.util.Optional<LocalDate> relativeDate(String message, LocalDate today) {
    if (LAST_WEEK.matcher(message).find()) {
      return java.util.Optional.of(today.minusDays(7));
    }
    Matcher d = LAST_DAYS.matcher(message);
    if (d.find()) {
      return java.util.Optional.of(today.minusDays(Long.parseLong(d.group(1))));
    }
    if (THIS_MONTH.matcher(message).find()) {
      return java.util.Optional.of(today.withDayOfMonth(1));
    }
    if (LAST_MONTH.matcher(message).find()) {
      return java.util.Optional.of(today.minusMonths(1).withDayOfMonth(1));
    }
    Matcher s = SINCE_DATE.matcher(message);
    if (s.find()) {
      int month = Integer.parseInt(s.group(1));
      int day = Integer.parseInt(s.group(2));
      if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
        LocalDate candidate = LocalDate.of(today.getYear(), month, 1).plusDays(day - 1L);
        // 未来日期视为去年的
        return java.util.Optional.of(
            candidate.isAfter(today) ? candidate.minusYears(1) : candidate);
      }
    }
    return java.util.Optional.empty();
  }

  /** 供日志：只记键与值，不记原文。 */
  public static String describe(Map<String, String> args) {
    return List.copyOf(args.entrySet()).toString();
  }
}
