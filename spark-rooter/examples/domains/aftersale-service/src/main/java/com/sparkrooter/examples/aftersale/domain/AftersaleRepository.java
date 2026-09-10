package com.sparkrooter.examples.aftersale.domain;

import java.util.List;
import java.util.Optional;

/** 售后仓储端口；无框架依赖。 */
public interface AftersaleRepository {

  /** 按幂等键查找已存在的售后单（去重）。 */
  Optional<Aftersale> findByIdempotencyKey(String tenantId, String idempotencyKey);

  /** 某订单的全部售后单，按 createdAt 升序。 */
  List<Aftersale> findByOrder(String tenantId, String orderId);

  /** 租户全部售后单，按 createdAt 降序。 */
  List<Aftersale> findByTenant(String tenantId);

  /** 若该幂等键已存在返回已有记录，否则保存并返回新记录（原子）。 */
  Aftersale saveIfAbsent(String idempotencyKey, Aftersale aftersale);
}
