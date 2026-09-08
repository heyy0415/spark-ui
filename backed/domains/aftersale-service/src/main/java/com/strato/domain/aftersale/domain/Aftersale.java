package com.strato.domain.aftersale.domain;

import java.time.Instant;
import java.util.Set;

/** 售后单聚合。每个订单同时最多一个进行中（SUBMITTED / APPROVED）售后单。 */
public record Aftersale(
    String aftersaleId,
    String orderId,
    String tenantId,
    Type type,
    Status status,
    String reason,
    Instant createdAt) {

  /** 售后类型。 */
  public enum Type {
    /** 退货。 */
    RETURN,
    /** 换货。 */
    EXCHANGE,
    /** 维修。 */
    REPAIR
  }

  /** 售后状态。 */
  public enum Status {
    /** 已提交待审核。 */
    SUBMITTED,
    /** 审核通过处理中。 */
    APPROVED,
    /** 审核驳回。 */
    REJECTED,
    /** 已完成。 */
    COMPLETED,
    /** 用户取消。 */
    CANCELLED;

    /** 进行中集合 = {SUBMITTED, APPROVED}（spec §2.1）。 */
    public static final Set<Status> ACTIVE = Set.of(SUBMITTED, APPROVED);

    public boolean active() {
      return ACTIVE.contains(this);
    }
  }
}
