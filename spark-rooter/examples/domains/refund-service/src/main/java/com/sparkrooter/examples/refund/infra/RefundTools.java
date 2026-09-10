package com.sparkrooter.examples.refund.infra;

import com.sparkrooter.examples.refund.application.RefundService;
import com.sparkrooter.examples.refund.domain.EligibilityPolicy;
import com.sparkrooter.examples.refund.domain.Refund;
import com.sparkrooter.examples.support.DemoUserContext;
import com.sparkrooter.spi.ToolContext;
import com.sparkrooter.spi.annotation.Confirmation;
import com.sparkrooter.spi.annotation.EntityType;
import com.sparkrooter.spi.annotation.Idempotency;
import com.sparkrooter.spi.annotation.ParamFormat;
import com.sparkrooter.spi.annotation.RiskLevel;
import com.sparkrooter.spi.annotation.SparkParam;
import com.sparkrooter.spi.annotation.SparkPrerequisite;
import com.sparkrooter.spi.annotation.SparkRisk;
import com.sparkrooter.spi.annotation.SparkTool;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 退款域四个工具（@SparkTool 形态）。输出 record 与 change 4 手写 Manifest 一一对应。 */
@Service
public class RefundTools {

  private final RefundService service;

  public RefundTools(RefundService service) {
    this.service = service;
  }

  public enum Currency {
    CNY
  }

  /** 订单状态（退款资格输出的订单摘要）。 */
  public enum OrderStatus {
    PAID,
    SHIPPED,
    COMPLETED,
    REFUNDED,
    CANCELLED
  }

  public record OrderIdIn(
      @SparkParam(description = "订单号", entity = EntityType.ORDER, minLength = 1, maxLength = 64)
          String orderId) {}

  public record EligibilityOut(
      String orderId,
      boolean eligible,
      @SparkParam(pattern = "^-?\\d+(\\.\\d{1,2})?$") BigDecimal refundableAmount,
      Currency currency,
      @SparkParam(maxLength = 200) Optional<String> reason,
      OrderStatus orderStatus,
      String productName,
      @SparkParam(min = 1) int quantity,
      BigDecimal orderAmount) {}

  public record PreviewOut(
      String orderId,
      BigDecimal amount,
      Currency currency,
      @SparkParam(min = 0) int estimatedDays) {}

  public record CreateIn(
      @SparkParam(description = "订单号", entity = EntityType.ORDER, minLength = 1, maxLength = 64)
          String orderId,
      @SparkParam(description = "退款金额（由后端试算覆盖，模型不得填写）", format = ParamFormat.MONEY) String amount,
      @SparkParam(
              description = "退款原因",
              aliases = {"DAMAGED=商品损坏", "NOT_RECEIVED=未收到货", "CHANGED_MIND=不想要了"})
          Reason reason) {}

  public enum Reason {
    DAMAGED,
    NOT_RECEIVED,
    CHANGED_MIND
  }

  public record CreateOut(
      String refundId,
      String orderId,
      BigDecimal amount,
      Currency currency,
      Refund.RefundStatus status,
      Instant createdAt) {}

  public record RefundRow(String refundId, String amount, String status, Instant createdAt) {}

  public record StatusOut(String orderId, List<RefundRow> refunds) {}

  @SparkTool(
      id = "refund.eligibility.check",
      version = "1.3.0",
      domain = "refund",
      name = "检查退款资格",
      description = "检查指定订单当前是否满足退款条件，返回是否可退、可退金额与不可退原因。只读，无副作用；同时返回订单状态 / 商品 / 件数 / 金额摘要供确认屏展示。")
  public EligibilityOut eligibility(OrderIdIn in) {
    RefundService.Eligibility e = service.eligibility(DemoUserContext.tenantId(), in.orderId());
    EligibilityPolicy.Result r = e.result();
    return new EligibilityOut(
        in.orderId(),
        r.eligible(),
        r.refundableAmount(),
        Currency.CNY,
        r.reason(),
        OrderStatus.valueOf(e.order().status()),
        e.order().productName(),
        e.order().quantity(),
        e.order().amount());
  }

  @SparkTool(
      id = "refund.preview",
      version = "1.3.0",
      domain = "refund",
      name = "退款试算",
      description = "试算退款金额与预计到账时间，不产生任何变更。")
  public PreviewOut preview(OrderIdIn in) {
    RefundService.Preview p = service.preview(DemoUserContext.tenantId(), in.orderId());
    return new PreviewOut(p.orderId(), p.amount(), Currency.CNY, p.estimatedDays());
  }

  /** 高风险、有副作用；幂等键来自 ToolContext。 */
  @SparkTool(
      id = "refund.create",
      version = "2.1.0",
      domain = "refund",
      name = "创建退款",
      description = "为满足条件的订单创建退款单。有副作用、不可逆，必须经用户确认，调用需携带幂等键。")
  @SparkRisk(
      level = RiskLevel.HIGH,
      confirmation = Confirmation.REQUIRED,
      idempotency = Idempotency.REQUIRED,
      sideEffect = true,
      reversible = false,
      timeoutMs = 5000,
      maxRetries = 0)
  @SparkPrerequisite({"refund.eligibility.check", "refund.preview"})
  public CreateOut create(CreateIn in, ToolContext ctx) {
    Refund r =
        service.create(
            DemoUserContext.tenantId(),
            in.orderId(),
            new BigDecimal(in.amount()),
            in.reason().name(),
            ctx.idempotencyKey());
    return new CreateOut(
        r.refundId(), r.orderId(), r.amount(), Currency.CNY, r.status(), r.createdAt());
  }

  @SparkTool(
      id = "refund.status.get",
      version = "1.0.0",
      domain = "refund",
      name = "查询退款状态",
      description = "查询订单下的退款单列表与状态。只读。")
  public StatusOut status(OrderIdIn in) {
    List<RefundRow> rows =
        service.status(DemoUserContext.tenantId(), in.orderId()).stream()
            .map(r -> new RefundRow(r.refundId(), r.amountText(), r.status().name(), r.createdAt()))
            .toList();
    return new StatusOut(in.orderId(), rows);
  }
}
