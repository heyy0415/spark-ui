package com.sparkrooter.spi.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把一个 public 方法声明为 Spark 工具。签名必须是 {@code Out method(In in)} 或 {@code Out method(In in, ToolContext
 * ctx)}，In / Out 为 record；Manifest（inputSchema / outputSchema / risk / execution）由 starter 从注解与
 * record 组件推导，并按 tool-manifest 契约校验。
 *
 * <p>调用约束（安全关键）：spark-rooter 经 Spring 代理反射调用该方法，宿主的方法级切面（@PreAuthorize / @Aspect）照常触发； Controller
 * 级拦截器对它无效。方法与所在类不得为 final、方法必须 public、不得声明在 {@code Configuration} 类上，否则启动失败。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SparkTool {

  /** 工具 ID，形如 {domain}.{resource}.{verb}，小写点分。 */
  String id();

  /** semver，同 id@version 已注册则启动失败。 */
  String version();

  /** 所属领域，与 id 首段一致。 */
  String domain();

  /** 用户可读名称（tool.selected.displayName）。 */
  String name();

  /** 给规划模型看的描述，≤ 500 字符，视为不可信文本。 */
  String description();

  /**
   * 该工具（须为无必填参数的列表工具）可作为某实体类型的澄清候选源：用户消息缺该实体时，Runtime 调它拿列表让用户点选。 值为宿主自定义实体类型名（与 @SparkParam.entity
   * 同一命名空间）；空 = 不作候选源。
   */
  String clarifiesEntity() default "";

  /** 给模型的同义动词 / 触发短语提示（同义表达列表）。纯提示，内核不据此做任何规则匹配。 */
  String[] verbs() default {};
}
