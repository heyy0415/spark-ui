package com.example.provider;

import com.sparkrooter.spi.annotation.SparkParam;
import com.sparkrooter.spi.annotation.SparkTool;
import org.springframework.stereotype.Service;

/**
 * 本示例 provider 自有的领域工具。
 *
 * <p>刻意用 hub 侧<b>没有</b>的领域（inventory），而不是复用 order-service：host-demo 自己已在进程内
 * 注册了 {@code order.*}，provider 再推同名 toolId@version 会被 Registry 按 409 拒绝（已发布版本不可变）。
 * 那反而证明了版本冲突保护有效，但测不到跨进程调用——所以这里给一个只存在于 provider 的工具。
 *
 * <p>真实微服务场景本就如此：每个服务暴露自己的领域能力，hub 不持有任何领域实现。
 */
@Service
public class InventoryTools {

  /** 入参：仓库 SKU。 */
  public record StockIn(
      @SparkParam(label = "商品编号", entity = "sku", pattern = "^[A-Z0-9-]{3,32}$") String skuId) {}

  /** 出参：可用库存与仓位。 */
  public record StockOut(String skuId, int available, String warehouse) {}

  @SparkTool(
      id = "inventory.stock.get",
      version = "1.0.0",
      domain = "inventory",
      name = "查询库存",
      description = "按商品编号查询可用库存与仓位。只读，无副作用。本工具只存在于 provider 进程，hub 经 HTTP 调用它。")
  public StockOut stock(StockIn in) {
    // 示例数据：不接数据库，保证 e2e 可离线跑
    int available = Math.abs(in.skuId().hashCode() % 100);
    return new StockOut(in.skuId(), available, "WH-SHANGHAI-01");
  }
}
