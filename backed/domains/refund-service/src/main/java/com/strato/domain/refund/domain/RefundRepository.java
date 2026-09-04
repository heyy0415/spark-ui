package com.strato.domain.refund.domain;

import java.util.List;
import java.util.Optional;

/** 退款仓储端口；无框架依赖。 */
public interface RefundRepository {

  /** 按幂等键查找已存在的退款单（去重）。 */
  Optional<Refund> findByIdempotencyKey(String tenantId, String idempotencyKey);

  List<Refund> findByOrder(String tenantId, String orderId);

  /** 若该幂等键已存在返回已有记录，否则保存并返回新记录（原子）。 */
  Refund saveIfAbsent(Refund refund);
}
