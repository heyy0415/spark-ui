package com.sparkrooter.spi.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * In record 组件的缺省值（字符串形态，按组件类型转换）。写进 inputSchema 的 default，且该组件不进 required；规划器在用户未提供时填入， 使
 * argsDigest 确定。只对 @SparkParam 组件有意义。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
public @interface SparkDefault {
  String value();
}
