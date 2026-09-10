package com.sparkrooter.spi.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 需确认工具的前置只读步骤（toolId 列表，按顺序执行）。规划器把它们排在目标工具之前，确认屏用它们的输出，确认后的重校验也复用首个前置工具。 引用的 toolId 必须已注册，否则启动失败。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SparkPrerequisite {
  String[] value();
}
