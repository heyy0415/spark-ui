package com.sparkrooter.runtime.domain;

/** Run 状态机的六个状态（与 run-summary.state enum 一致）。 */
public enum RunState {
  CREATED,
  PLANNING,
  EXECUTING,
  WAITING_CONFIRMATION,
  COMPLETED,
  FAILED;

  /** 合法迁移表；迁移到自身视为幂等 no-op。 */
  public boolean canTransitionTo(RunState next) {
    if (next == this) {
      return true;
    }
    return switch (this) {
      case CREATED -> next == PLANNING || next == FAILED;
      case PLANNING -> next == EXECUTING || next == COMPLETED || next == FAILED;
      case EXECUTING -> next == WAITING_CONFIRMATION || next == COMPLETED || next == FAILED;
      case WAITING_CONFIRMATION -> next == EXECUTING || next == FAILED;
      case COMPLETED, FAILED -> false;
    };
  }

  public boolean terminal() {
    return this == COMPLETED || this == FAILED;
  }
}
