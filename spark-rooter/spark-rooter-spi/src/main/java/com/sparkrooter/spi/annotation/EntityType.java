package com.sparkrooter.spi.annotation;

/** 实体类型：抽取器识别的业务对象种类，也是澄清屏与会话记忆的键。与 IntentVerbs / ArgumentExtractor 的内核表同源。 */
public enum EntityType {
  /** 不是实体参数（默认）。 */
  NONE,
  /** 订单：ID 形态 5 位数字，正则「订单\s*(\d{5})」。 */
  ORDER,
  /** 商品：ID 形态 P-4 位数字，正则「商品\s*(P-\d{4})」。 */
  PRODUCT
}
