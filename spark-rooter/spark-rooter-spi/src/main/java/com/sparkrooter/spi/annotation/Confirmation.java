package com.sparkrooter.spi.annotation;

/** 是否需要用户确认，映射到 Manifest risk.confirmation。 */
public enum Confirmation {
  /** 自动执行。 */
  NEVER,
  /** 生成确认屏 + 后端令牌，用户确认后重校验再执行。 */
  REQUIRED
}
