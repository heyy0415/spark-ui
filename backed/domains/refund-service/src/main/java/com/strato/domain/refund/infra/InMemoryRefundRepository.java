package com.strato.domain.refund.infra;

import com.strato.domain.refund.domain.Refund;
import com.strato.domain.refund.domain.RefundRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** 内存退款仓储。幂等键 → 退款单，putIfAbsent 保证同键只写一次。 */
@Repository
public class InMemoryRefundRepository implements RefundRepository {

  private final Map<String, Refund> byIdemKey = new ConcurrentHashMap<>();

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
