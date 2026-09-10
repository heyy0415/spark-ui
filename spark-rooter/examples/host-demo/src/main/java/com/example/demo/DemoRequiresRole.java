package com.example.demo;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 宿主自己的方法级权限注解（示例）；由 {@link DemoRoleAspect} 执行。标在 @SparkTool 方法上时，spark 经代理调用会触发它。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DemoRequiresRole {
  String value();
}
