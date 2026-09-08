package com.strato.domain.refund.infra;

import com.strato.domain.refund.domain.EligibilityPolicy;
import com.strato.domain.refund.domain.OrderLookup;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 首期：退款域自己持有一份订单快照（与 order-service 的 seed 数据一致），避免领域模块互相依赖。 真实系统中此处为对订单服务的 RPC / 事件订阅。 */
@Component
public class SeededOrderLookup implements OrderLookup {

  private record Row(String status, String amount, Instant createdAt) {}

  private static final Map<String, Row> SEED =
      Map.of(
          "tenant_001/10001", new Row("PAID", "128.00", Instant.parse("2026-08-30T02:15:00Z")),
          "tenant_001/10002", new Row("SHIPPED", "299.00", Instant.parse("2026-09-01T09:40:00Z")),
          "tenant_001/10003", new Row("PAID", "1.00", Instant.parse("2026-09-02T00:00:00Z")),
          "tenant_001/10004", new Row("PAID", "59.00", Instant.parse("2026-09-03T00:00:00Z")));

  @Override
  public Optional<EligibilityPolicy.Snapshot> snapshot(String tenantId, String orderId) {
    Row r = SEED.get(tenantId + "/" + orderId);
    if (r == null) {
      return Optional.empty();
    }
    return Optional.of(
        new EligibilityPolicy.Snapshot(orderId, r.status(), new BigDecimal(r.amount())));
  }
}
