package com.example.demo;

import com.sparkrooter.examples.order.infra.OrderTools;
import com.sparkrooter.examples.support.DemoUserContext;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 宿主自己的端点（受 DemoInterceptorOnlyGuard 拦截）：e2e ⑭' 用 /demo/orders 证明「同一能力」经 Controller 被拦截器拒、经 spark 却能到——
 * 拦截器只保护 Controller，不保护 spark 的代理调用。
 */
@RestController
public class DemoController {

  private final OrderTools orders;

  public DemoController(OrderTools orders) {
    this.orders = orders;
  }

  /** 与 spark 工具 order.list.search 同一能力（直接调同一个 @SparkTool 方法）。 */
  @GetMapping("/demo/orders")
  public OrderTools.ListOut orders() {
    return orders.list(new OrderTools.ListIn(java.util.Optional.empty(), 20));
  }

  @GetMapping("/demo/whoami")
  public Map<String, String> whoami() {
    DemoUserContext.DemoUser u = DemoUserContext.currentOrDefault();
    return Map.of("viewer", u.userId(), "tenantId", u.tenantId());
  }
}
