package com.sparkrooter.examples.order.infra;

import com.sparkrooter.examples.order.domain.DeletionPolicy;
import com.sparkrooter.examples.order.domain.LogisticsEvent;
import com.sparkrooter.examples.order.domain.LogisticsStatus;
import com.sparkrooter.examples.order.domain.Order;
import com.sparkrooter.examples.order.domain.OrderRepository;
import com.sparkrooter.examples.support.DemoUserContext;
import com.sparkrooter.spi.ToolContext;
import com.sparkrooter.spi.annotation.Confirmation;
import com.sparkrooter.spi.annotation.EntityType;
import com.sparkrooter.spi.annotation.Idempotency;
import com.sparkrooter.spi.annotation.RiskLevel;
import com.sparkrooter.spi.annotation.SparkDefault;
import com.sparkrooter.spi.annotation.SparkParam;
import com.sparkrooter.spi.annotation.SparkPrerequisite;
import com.sparkrooter.spi.annotation.SparkRisk;
import com.sparkrooter.spi.annotation.SparkTool;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 订单域的四个工具（@SparkTool 形态，替代原 ToolHandler + tool-manifests/*.json）。输出 record 字段与 change 4 手写
 * Manifest 一一对应 （ManifestParitySelfCheck 逐字段 diff）。租户来自示例宿主的 DemoUserContext；真实宿主换成自己的登录态。
 */
@Service
public class OrderTools {

  private static final Logger log = LoggerFactory.getLogger(OrderTools.class);
  private static final String CNY = "CNY";

  private final OrderRepository orders;

  public OrderTools(OrderRepository orders) {
    this.orders = orders;
  }

  // ---- 输入 / 输出 record

  /** 订单状态（对外枚举，不含软删 DELETED）。 */
  public enum Status {
    PAID,
    SHIPPED,
    COMPLETED,
    REFUNDED,
    CANCELLED
  }

  /** 物流状态（含未发货）。 */
  public enum ShipStatus {
    NOT_SHIPPED,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED
  }

  /** 已发货订单的物流概要状态。 */
  public enum ShippedStatus {
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED
  }

  public enum Currency {
    CNY
  }

  public record ListIn(
      @SparkParam(
              description = "按订单状态筛选",
              aliases = {
                "PAID=已支付",
                "SHIPPED=已发货",
                "COMPLETED=已完成",
                "REFUNDED=已退款",
                "CANCELLED=已取消"
              })
          Optional<Status> status,
      @SparkParam(
              description = "返回条数",
              unit = {"单", "条", "个"},
              min = 1,
              max = 50)
          @SparkDefault("20")
          Integer limit) {}

  public record ListItem(
      String orderId,
      String productId,
      String productName,
      Optional<String> thumbnail,
      @SparkParam(min = 1) int quantity,
      BigDecimal amount,
      Currency currency,
      Status status,
      Instant createdAt) {}

  public record ListOut(List<ListItem> items, @SparkParam(min = 0) int total) {}

  public record OrderIdIn(
      @SparkParam(description = "订单号", entity = EntityType.ORDER, minLength = 1, maxLength = 64)
          String orderId) {}

  public record Item(
      String productId,
      String productName,
      BigDecimal unitPrice,
      @SparkParam(min = 1) int quantity,
      BigDecimal amount) {}

  public record Address(
      String receiver,
      @SparkParam(pattern = "^1\\d{2}\\*{4}\\d{4}$") String phoneMasked,
      String region) {}

  public record Logistics(String carrier, String trackingNo, ShippedStatus status) {}

  public record DetailOut(
      String orderId,
      String productId,
      String productName,
      Optional<String> thumbnail,
      @SparkParam(min = 1) int quantity,
      BigDecimal amount,
      Currency currency,
      Status status,
      Instant createdAt,
      @SparkParam(minItems = 1) List<Item> items,
      Address address,
      Optional<Logistics> logistics) {}

  public record Event(Instant time, String location, String description) {}

  public record LogisticsOut(
      String orderId, String carrier, String trackingNo, ShipStatus status, List<Event> events) {}

  public record DeleteOut(
      String orderId, @SparkParam(constant = "true") boolean deleted, Instant deletedAt) {}

  // ---- 工具

  @SparkTool(
      id = "order.list.search",
      version = "1.1.0",
      domain = "order",
      name = "搜索订单",
      description =
          "按状态筛选当前租户下的订单列表，按下单时间倒序（最新在前），默认返回前 20 条（limit 最大 50）；已删除订单不返回。total 为筛选后总数。只读，无副作用。",
      clarifiesEntity = EntityType.ORDER)
  public ListOut list(ListIn in) {
    List<Order> matched =
        orders.findByTenant(DemoUserContext.tenantId()).stream()
            .filter(o -> o.status() != Order.OrderStatus.DELETED)
            .filter(o -> in.status().map(s -> o.status().name().equals(s.name())).orElse(true))
            .toList();
    int limit = in.limit() == null ? 20 : in.limit();
    return new ListOut(
        matched.stream().limit(limit).map(OrderTools::listItem).toList(), matched.size());
  }

  @SparkTool(
      id = "order.detail.get",
      version = "1.1.0",
      domain = "order",
      name = "查询订单详情",
      description = "按订单号查询订单全部信息：商品行、金额、状态、收货地址（手机号脱敏）以及物流概要（已发货时）。只读，无副作用。")
  public DetailOut detail(OrderIdIn in) {
    Order o = require(in.orderId());
    Optional<Logistics> lg =
        o.logistics().isEmpty()
            ? Optional.empty()
            : Optional.of(
                new Logistics(
                    o.logistics().get(0).carrier(),
                    o.logistics().get(0).trackingNo(),
                    ShippedStatus.valueOf(o.logisticsStatus().name())));
    return new DetailOut(
        o.orderId(),
        o.items().get(0).productId(),
        o.productName(),
        Optional.ofNullable(o.thumbnail()),
        o.quantity(),
        o.amount(),
        Currency.CNY,
        Status.valueOf(o.status().name()),
        o.createdAt(),
        o.items().stream()
            .map(
                i ->
                    new Item(
                        i.productId(),
                        i.productName(),
                        i.unitPrice(),
                        i.quantity(),
                        i.lineAmount()))
            .toList(),
        new Address(o.address().receiver(), o.address().phoneMasked(), o.address().region()),
        lg);
  }

  @SparkTool(
      id = "order.logistics.get",
      version = "1.0.0",
      domain = "order",
      name = "查询订单物流",
      description = "按订单号查询物流轨迹：承运商、运单号、当前状态与事件列表。未发货订单返回 NOT_SHIPPED 与空事件列表，不报错。只读，无副作用。")
  public LogisticsOut logistics(OrderIdIn in) {
    Order o = require(in.orderId());
    List<LogisticsEvent> ev = o.logistics();
    LogisticsStatus st = o.logisticsStatus();
    return new LogisticsOut(
        o.orderId(),
        ev.isEmpty() ? "" : ev.get(0).carrier(),
        ev.isEmpty() ? "" : ev.get(0).trackingNo(),
        ShipStatus.valueOf(st.name()),
        ev.stream().map(e -> new Event(e.eventTime(), e.location(), e.description())).toList());
  }

  /** 软删；handler 自身再过一次 DeletionPolicy 作第二道保险（第一道是 runtime 确认后的重校验）；幂等由 Gateway 保证。 */
  @SparkTool(
      id = "order.delete",
      version = "1.0.0",
      domain = "order",
      name = "删除订单",
      description =
          "删除一个已完成 / 已取消 / 已退款的订单（软删，列表不再显示）。进行中的订单（已支付、已发货）不允许删除。有副作用、不可逆，必须经用户确认，调用需携带幂等键。")
  @SparkRisk(
      level = RiskLevel.HIGH,
      confirmation = Confirmation.REQUIRED,
      idempotency = Idempotency.REQUIRED,
      sideEffect = true,
      reversible = false,
      timeoutMs = 5000,
      maxRetries = 0)
  @SparkPrerequisite({"order.detail.get"})
  public DeleteOut delete(OrderIdIn in, ToolContext ctx) {
    String tenantId = DemoUserContext.tenantId();
    Order o =
        orders
            .find(tenantId, in.orderId())
            .orElseThrow(() -> new IllegalArgumentException("order not found: " + in.orderId()));
    DeletionPolicy.reject(o.status())
        .ifPresent(
            reason -> {
              throw new IllegalStateException(reason);
            });
    Instant now = Instant.now();
    orders.save(o.withStatus(Order.OrderStatus.DELETED));
    log.info("order deleted orderId={} tenant={} runId={}", in.orderId(), tenantId, ctx.runId());
    return new DeleteOut(in.orderId(), true, now);
  }

  /** 不存在或已软删都按业务错误抛出（Gateway 统一映射 HANDLER_ERROR），不泄露已删除内容。 */
  private Order require(String orderId) {
    Order o =
        orders
            .find(DemoUserContext.tenantId(), orderId)
            .orElseThrow(() -> new IllegalArgumentException("order not found: " + orderId));
    if (o.status() == Order.OrderStatus.DELETED) {
      throw new IllegalStateException("订单已删除: " + orderId);
    }
    return o;
  }

  static ListItem listItem(Order o) {
    return new ListItem(
        o.orderId(),
        o.items().get(0).productId(),
        o.productName(),
        Optional.ofNullable(o.thumbnail()),
        o.quantity(),
        o.amount(),
        Currency.CNY,
        Status.valueOf(o.status().name()),
        o.createdAt());
  }
}
