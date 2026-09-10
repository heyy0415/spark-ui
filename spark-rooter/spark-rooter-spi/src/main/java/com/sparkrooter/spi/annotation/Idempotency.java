package com.sparkrooter.spi.annotation;

/** 幂等要求，映射到 Manifest execution.idempotency。sideEffect=true 时必须 REQUIRED。 */
public enum Idempotency {
  /** 无幂等语义（只读）。 */
  NONE,
  /** Gateway 按 idempotencyKey 去重，同 key 重放返回首次结果。 */
  REQUIRED
}
