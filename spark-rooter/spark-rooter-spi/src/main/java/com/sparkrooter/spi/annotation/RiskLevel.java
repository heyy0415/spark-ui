package com.sparkrooter.spi.annotation;

/** 风险等级，映射到 Manifest risk.level（小写）。HIGH 必须配 Confirmation.REQUIRED。 */
public enum RiskLevel {
  /** 只读或可忽略的副作用。 */
  LOW,
  /** 有副作用但可撤销。 */
  MEDIUM,
  /** 不可逆或涉及资金，必须确认。 */
  HIGH
}
