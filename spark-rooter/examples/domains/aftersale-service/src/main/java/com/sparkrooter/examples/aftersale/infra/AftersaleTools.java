package com.sparkrooter.examples.aftersale.infra;

import com.sparkrooter.examples.aftersale.application.AftersaleService;
import com.sparkrooter.examples.aftersale.domain.Aftersale;
import com.sparkrooter.examples.support.DemoUserContext;
import com.sparkrooter.examples.support.OrderSnapshot;
import com.sparkrooter.spi.ToolContext;
import com.sparkrooter.spi.annotation.Confirmation;
import com.sparkrooter.spi.annotation.EntityType;
import com.sparkrooter.spi.annotation.Idempotency;
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

/** 售后域两个工具（@SparkTool 形态）。输出 record 与 change 4 手写 Manifest 一一对应。 */
@Service
public class AftersaleTools {

  private final AftersaleService service;

  public AftersaleTools(AftersaleService service) {
    this.service = service;
  }

  public enum Currency {
    CNY
  }

  public record ListIn(
      @SparkParam(
              description = "订单号；给定时只返回该订单的售后单",
              entity = EntityType.ORDER,
              minLength = 1,
              maxLength = 64)
          Optional<String> orderId) {}

  public record Item(
      String aftersaleId,
      String orderId,
      Aftersale.Type type,
      Aftersale.Status status,
      String reason,
      Instant createdAt) {}

  /** 订单摘要（确认屏据此渲染订单 Card，不再直读订单域）。 */
  public record OrderSummary(
      String orderId,
      String productName,
      @SparkParam(min = 1) int quantity,
      BigDecimal amount,
      Currency currency,
      String status) {}

  public record ListOut(List<Item> items, Optional<OrderSummary> order) {}

  public record CreateIn(
      @SparkParam(description = "订单号", entity = EntityType.ORDER, minLength = 1, maxLength = 64)
          String orderId,
      @SparkParam(
              description = "售后类型",
              aliases = {"RETURN=退货", "EXCHANGE=换货", "REPAIR=维修"})
          Aftersale.Type type,
      @SparkParam(description = "申请原因", minLength = 1, maxLength = 500) String reason) {}

  @SparkTool(
      id = "aftersale.list.get",
      version = "1.0.0",
      domain = "aftersale",
      name = "查询售后单",
      description = "查询售后单列表；给定订单号时只返回该订单的售后单，并附带订单摘要（商品、件数、金额、状态）。只读，无副作用。")
  public ListOut list(ListIn in) {
    String tenantId = DemoUserContext.tenantId();
    List<Item> items =
        service.list(tenantId, in.orderId()).stream().map(AftersaleTools::item).toList();
    Optional<OrderSummary> order = in.orderId().map(id -> summary(service.order(tenantId, id)));
    return new ListOut(items, order);
  }

  /** 有副作用；幂等键由 Gateway 透传。 */
  @SparkTool(
      id = "aftersale.create",
      version = "1.0.0",
      domain = "aftersale",
      name = "申请售后",
      description = "为已发货或已完成的订单创建售后单（退货 / 换货 / 维修）。每个订单同时只能有一个进行中的售后单。有副作用，必须经用户确认，调用需携带幂等键。")
  @SparkRisk(
      level = RiskLevel.HIGH,
      confirmation = Confirmation.REQUIRED,
      idempotency = Idempotency.REQUIRED,
      sideEffect = true,
      reversible = false,
      timeoutMs = 5000,
      maxRetries = 0)
  @SparkPrerequisite({"aftersale.list.get"})
  public Item create(CreateIn in, ToolContext ctx) {
    return item(
        service.create(
            DemoUserContext.tenantId(),
            in.orderId(),
            in.type(),
            in.reason(),
            ctx.idempotencyKey()));
  }

  private static Item item(Aftersale a) {
    return new Item(a.aftersaleId(), a.orderId(), a.type(), a.status(), a.reason(), a.createdAt());
  }

  private static OrderSummary summary(OrderSnapshot s) {
    return new OrderSummary(
        s.orderId(), s.productName(), s.quantity(), s.amount(), Currency.CNY, s.status());
  }
}
