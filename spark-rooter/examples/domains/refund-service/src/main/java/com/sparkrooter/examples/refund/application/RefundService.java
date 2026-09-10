package com.sparkrooter.examples.refund.application;

import com.sparkrooter.examples.refund.domain.EligibilityPolicy;
import com.sparkrooter.examples.refund.domain.OrderLookup;
import com.sparkrooter.examples.refund.domain.Refund;
import com.sparkrooter.examples.refund.domain.RefundRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 退款域用例集合；四个 ToolHandler 都委托到这里。 */
@Service
public class RefundService {

  private static final Logger log = LoggerFactory.getLogger(RefundService.class);
  private static final int ESTIMATED_DAYS = 3;

  private final RefundRepository refunds;
  private final OrderLookup orders;

  public RefundService(RefundRepository refunds, OrderLookup orders) {
    this.refunds = refunds;
    this.orders = orders;
  }

  /** 资格判定 + 订单快照（确认屏用快照渲染订单摘要，不再让屏层猜默认值）。 */
  public record Eligibility(EligibilityPolicy.Result result, EligibilityPolicy.Snapshot order) {}

  public Eligibility eligibility(String tenantId, String orderId) {
    EligibilityPolicy.Snapshot snap =
        orders
            .snapshot(tenantId, orderId)
            .orElseThrow(() -> new IllegalArgumentException("order not found: " + orderId));
    boolean already = !refunds.findByOrder(tenantId, orderId).isEmpty();
    return new Eligibility(EligibilityPolicy.evaluate(snap, already), snap);
  }

  public EligibilityPolicy.Result checkEligibility(String tenantId, String orderId) {
    return eligibility(tenantId, orderId).result();
  }

  public record Preview(String orderId, BigDecimal amount, int estimatedDays) {}

  public Preview preview(String tenantId, String orderId) {
    EligibilityPolicy.Result r = checkEligibility(tenantId, orderId);
    return new Preview(orderId, r.refundableAmount(), ESTIMATED_DAYS);
  }

  /** 幂等创建：同 (tenantId, idempotencyKey) 返回首次结果，不新增记录。 */
  public Refund create(
      String tenantId, String orderId, BigDecimal amount, String reason, String idempotencyKey) {
    return refunds
        .findByIdempotencyKey(tenantId, idempotencyKey)
        .orElseGet(
            () -> {
              EligibilityPolicy.Result r = checkEligibility(tenantId, orderId);
              if (!r.eligible()) {
                throw new IllegalStateException(
                    "order not eligible for refund: " + r.reason().orElse(""));
              }
              if (amount.compareTo(r.refundableAmount()) > 0) {
                throw new IllegalArgumentException("amount exceeds refundable amount");
              }
              Refund created =
                  new Refund(
                      "rf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                      tenantId,
                      orderId,
                      amount,
                      "CNY",
                      reason,
                      Refund.RefundStatus.SUBMITTED,
                      idempotencyKey,
                      Instant.now());
              Refund saved = refunds.saveIfAbsent(created);
              log.info(
                  "refund created refundId={} orderId={} tenant={}",
                  saved.refundId(),
                  orderId,
                  tenantId);
              return saved;
            });
  }

  public List<Refund> status(String tenantId, String orderId) {
    return refunds.findByOrder(tenantId, orderId);
  }
}
