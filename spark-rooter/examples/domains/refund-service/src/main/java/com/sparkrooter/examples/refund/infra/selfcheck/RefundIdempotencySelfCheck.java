package com.sparkrooter.examples.refund.infra.selfcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.examples.refund.infra.RefundCreateHandler;
import com.sparkrooter.examples.refund.infra.RefundStatusGetHandler;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.Principal;
import com.sparkrooter.spi.SelfCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 幂等自检：直接调用 ToolHandler（不经 Gateway、不产生审计行），固定使用订单 10003（spec §2.2）； 同一 idempotencyKey 调用两次，refundId
 * 必须相同且该订单退款记录恰 1 条。10001 / 10002 保留给验收链路。
 */
@Component
public class RefundIdempotencySelfCheck implements SelfCheck {

  private static final Logger log = LoggerFactory.getLogger(RefundIdempotencySelfCheck.class);
  private static final String ORDER = "10003";

  private final RefundCreateHandler create;
  private final RefundStatusGetHandler status;
  private final ObjectMapper mapper;

  public RefundIdempotencySelfCheck(
      RefundCreateHandler create, RefundStatusGetHandler status, ObjectMapper mapper) {
    this.create = create;
    this.status = status;
    this.mapper = mapper;
  }

  @Override
  public String name() {
    return "refund.create idempotent";
  }

  @Override
  public void run() {
    ExecutionContext ctx =
        new ExecutionContext(
            "run_selfcheck",
            "tc_selfcheck",
            new Principal("selfcheck", "tenant_001"),
            "selfcheck-refund-10003",
            null);
    ObjectNode args = mapper.createObjectNode();
    args.put("orderId", ORDER);
    args.put("amount", "1.00");
    args.put("reason", "DAMAGED");
    JsonNode first = create.handle(args, ctx);
    JsonNode second = create.handle(args, ctx);
    if (!first.get("refundId").asText().equals(second.get("refundId").asText())) {
      throw new IllegalStateException("idempotency violated: different refundId on replay");
    }
    ObjectNode q = mapper.createObjectNode();
    q.put("orderId", ORDER);
    int count = status.handle(q, ctx).get("refunds").size();
    if (count != 1) {
      throw new IllegalStateException("idempotency violated: expected 1 refund, got " + count);
    }
    log.info("selfcheck: refund.create idempotent OK");
  }
}
