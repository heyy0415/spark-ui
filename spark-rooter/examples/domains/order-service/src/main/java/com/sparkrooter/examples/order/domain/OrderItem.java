package com.sparkrooter.examples.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 订单商品行。`lineAmount == unitPrice × quantity`，Σ lineAmount == 订单金额（仓储加载时校验）。 */
public record OrderItem(
    String itemId,
    String productId,
    String productName,
    BigDecimal unitPrice,
    int quantity,
    BigDecimal lineAmount) {

  public String unitPriceText() {
    return unitPrice.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  public String lineAmountText() {
    return lineAmount.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }
}
