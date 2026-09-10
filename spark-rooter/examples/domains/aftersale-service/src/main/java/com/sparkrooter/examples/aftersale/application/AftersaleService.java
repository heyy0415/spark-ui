package com.sparkrooter.examples.aftersale.application;

import com.sparkrooter.examples.aftersale.domain.Aftersale;
import com.sparkrooter.examples.aftersale.domain.AftersalePolicy;
import com.sparkrooter.examples.aftersale.domain.AftersaleRepository;
import com.sparkrooter.spi.OrderSnapshot;
import com.sparkrooter.spi.OrderSnapshotProvider;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 售后域用例；两个 ToolHandler 都委托到这里。订单只读经 spi OrderSnapshotProvider，不 import order-service。 */
@Service
public class AftersaleService {

  private static final Logger log = LoggerFactory.getLogger(AftersaleService.class);

  private final AftersaleRepository aftersales;
  private final OrderSnapshotProvider orders;

  public AftersaleService(AftersaleRepository aftersales, OrderSnapshotProvider orders) {
    this.aftersales = aftersales;
    this.orders = orders;
  }

  public List<Aftersale> list(String tenantId, Optional<String> orderId) {
    return orderId
        .map(id -> aftersales.findByOrder(tenantId, id))
        .orElseGet(() -> aftersales.findByTenant(tenantId));
  }

  /** 订单摘要（供 aftersale.list.get 输出 order 字段）；不存在 → 业务错误。 */
  public OrderSnapshot order(String tenantId, String orderId) {
    return orders
        .snapshot(tenantId, orderId)
        .orElseThrow(() -> new IllegalArgumentException("order not found: " + orderId));
  }

  /** 幂等创建：同 (tenantId, idempotencyKey) 返回首次结果，不新增记录。 */
  public Aftersale create(
      String tenantId, String orderId, Aftersale.Type type, String reason, String idempotencyKey) {
    return aftersales
        .findByIdempotencyKey(tenantId, idempotencyKey)
        .orElseGet(
            () -> {
              OrderSnapshot snap = order(tenantId, orderId);
              AftersalePolicy.reject(snap.status(), aftersales.findByOrder(tenantId, orderId))
                  .ifPresent(
                      reason0 -> {
                        throw new IllegalStateException(reason0);
                      });
              Aftersale created =
                  new Aftersale(
                      "AS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                      orderId,
                      tenantId,
                      type,
                      Aftersale.Status.SUBMITTED,
                      reason,
                      Instant.now());
              Aftersale saved = aftersales.saveIfAbsent(idempotencyKey, created);
              log.info(
                  "aftersale created aftersaleId={} orderId={} tenant={}",
                  saved.aftersaleId(),
                  orderId,
                  tenantId);
              return saved;
            });
  }
}
