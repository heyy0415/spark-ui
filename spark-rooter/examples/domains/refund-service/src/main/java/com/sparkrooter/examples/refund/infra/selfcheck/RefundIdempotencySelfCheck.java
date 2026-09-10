package com.sparkrooter.examples.refund.infra.selfcheck;

import com.sparkrooter.examples.refund.infra.RefundTools;
import com.sparkrooter.spi.SelfCheck;
import com.sparkrooter.spi.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 幂等自检：直接调用 RefundTools（不经 Gateway、不产生审计行），固定使用订单 10003（spec §2.2）； 同一 idempotencyKey 调用两次，refundId
 * 必须相同且该订单退款记录恰 1 条。10001 / 10002 保留给验收链路。
 */
@Component
public class RefundIdempotencySelfCheck implements SelfCheck {

  private static final Logger log = LoggerFactory.getLogger(RefundIdempotencySelfCheck.class);
  private static final String ORDER = "10003";

  private final RefundTools tools;

  public RefundIdempotencySelfCheck(RefundTools tools) {
    this.tools = tools;
  }

  @Override
  public String name() {
    return "refund.create idempotent";
  }

  @Override
  public void run() {
    ToolContext ctx =
        new ToolContext("run_selfcheck", "tc_selfcheck", "selfcheck-refund-10003", null);
    RefundTools.CreateIn in = new RefundTools.CreateIn(ORDER, "1.00", RefundTools.Reason.DAMAGED);
    RefundTools.CreateOut first = tools.create(in, ctx);
    RefundTools.CreateOut second = tools.create(in, ctx);
    if (!first.refundId().equals(second.refundId())) {
      throw new IllegalStateException("idempotency violated: different refundId on replay");
    }
    int count = tools.status(new RefundTools.OrderIdIn(ORDER)).refunds().size();
    if (count != 1) {
      throw new IllegalStateException("idempotency violated: expected 1 refund, got " + count);
    }
    log.info("selfcheck: refund.create idempotent OK");
  }
}
