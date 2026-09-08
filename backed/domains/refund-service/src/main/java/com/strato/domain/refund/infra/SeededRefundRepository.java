package com.strato.domain.refund.infra;

import com.strato.domain.refund.domain.Refund;
import com.strato.domain.refund.domain.RefundRepository;
import com.strato.spi.SeedLoader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/** 从 data/refunds.json 装配的内存退款仓储。幂等键 → 退款单，putIfAbsent 保证同键只写一次。 */
@Repository
public class SeededRefundRepository implements RefundRepository {

  private static final Logger log = LoggerFactory.getLogger(SeededRefundRepository.class);

  /** refunds.json 行。 */
  record Row(
      String refundId,
      String orderId,
      String tenantId,
      BigDecimal amount,
      String currency,
      String reason,
      String status,
      String idempotencyKey,
      Instant createdAt) {}

  private final Map<String, Refund> byIdemKey = new ConcurrentHashMap<>();

  public SeededRefundRepository() {
    List<Row> rows = SeedLoader.load("data/refunds.json", Row.class);
    for (Row r : rows) {
      Refund f =
          new Refund(
              r.refundId(),
              r.tenantId(),
              r.orderId(),
              r.amount(),
              r.currency(),
              r.reason(),
              Refund.RefundStatus.valueOf(r.status()),
              r.idempotencyKey(),
              r.createdAt());
      byIdemKey.put(key(f.tenantId(), f.idempotencyKey()), f);
    }
    log.info("refund seed loaded refunds={}", rows.size());
  }

  private static String key(String tenantId, String idem) {
    return tenantId + "/" + idem;
  }

  @Override
  public Optional<Refund> findByIdempotencyKey(String tenantId, String idempotencyKey) {
    return Optional.ofNullable(byIdemKey.get(key(tenantId, idempotencyKey)));
  }

  @Override
  public List<Refund> findByOrder(String tenantId, String orderId) {
    return byIdemKey.values().stream()
        .filter(r -> r.tenantId().equals(tenantId) && r.orderId().equals(orderId))
        .sorted((a, b) -> a.createdAt().compareTo(b.createdAt()))
        .toList();
  }

  @Override
  public Refund saveIfAbsent(Refund refund) {
    Refund existing =
        byIdemKey.putIfAbsent(key(refund.tenantId(), refund.idempotencyKey()), refund);
    return existing != null ? existing : refund;
  }
}
