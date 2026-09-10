package com.sparkrooter.spi.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在 In record 的组件上，把该组件开放进 inputSchema。<b>未标注的组件不进 inputSchema</b>（默认不开放，宿主显式声明模型可填什么）。 组件类型决定
 * JSON Schema 类型；本注解补充约束与抽取提示。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
public @interface SparkParam {

  /** 给模型看的参数说明。 */
  String description() default "";

  /** 允许的枚举值（字符串组件用；Java enum 组件自动推导，不必填）。 */
  String[] enums() default {};

  /** 枚举值的中文别名，形如 "SHIPPED=已发货"；抽取器据此把用户原话映射为枚举值。 */
  String[] aliases() default {};

  /** 数量参数的单位词（如 "单" / "条" / "个"）；抽取器把「最近 5 单」中的 5 填到含该单位词的整型参数，截断到 max。 */
  String[] unit() default {};

  /** 整型下界；Long.MIN_VALUE 表示不限。 */
  long min() default Long.MIN_VALUE;

  /** 整型上界；Long.MAX_VALUE 表示不限。 */
  long max() default Long.MAX_VALUE;

  /** 字符串最小长度；-1 表示不限。 */
  int minLength() default -1;

  /** 字符串最大长度；-1 表示不限。 */
  int maxLength() default -1;

  ParamFormat format() default ParamFormat.NONE;

  /** 标记为实体 ID 参数（订单号 / 商品编号）：值只能来自用户原话或会话记忆，规划器不得编造。 */
  EntityType entity() default EntityType.NONE;
}
