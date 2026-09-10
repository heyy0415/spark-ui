package com.sparkrooter.spi.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具风险与执行策略；缺省等价于只读低风险工具。约束：level=HIGH ⇒ confirmation=REQUIRED；sideEffect=true ⇒
 * idempotency=REQUIRED（违反则启动失败）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SparkRisk {

  RiskLevel level() default RiskLevel.LOW;

  Confirmation confirmation() default Confirmation.NEVER;

  Idempotency idempotency() default Idempotency.NONE;

  /** 是否产生副作用（写操作）。 */
  boolean sideEffect() default false;

  /** 副作用是否可撤销。 */
  boolean reversible() default true;

  /** Gateway 单次调用超时。 */
  long timeoutMs() default 3000;

  /** Gateway 失败重试次数（有副作用的工具建议 0）。 */
  int maxRetries() default 1;
}
