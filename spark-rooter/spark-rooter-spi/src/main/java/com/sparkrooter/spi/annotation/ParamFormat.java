package com.sparkrooter.spi.annotation;

/** 参数格式提示，映射到 JSON Schema format / pattern，并决定抽取器的处理方式。 */
public enum ParamFormat {
  /** 按 Java 类型映射，无额外格式。 */
  NONE,
  /** ISO 日期（yyyy-MM-dd）；抽取器把「最近一周」等相对时间填到这里。 */
  DATE,
  /** ISO 日期时间。 */
  DATE_TIME,
  /** 金额字符串 ^\d+(\.\d{1,2})?$（金额一律 string）。 */
  MONEY
}
