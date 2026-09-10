package com.sparkrooter.spi;

/**
 * 传给 @SparkTool 方法的可选第二参数：一次调用的标识信息，全部 String，<b>无用户字段</b>。宿主要用户就从自己经 RunContextPropagator
 * 恢复的上下文取。traceId 可空。
 */
public record ToolContext(String runId, String toolCallId, String idempotencyKey, String traceId) {

  public static ToolContext from(ExecutionContext ctx) {
    return new ToolContext(ctx.runId(), ctx.toolCallId(), ctx.idempotencyKey(), ctx.traceId());
  }
}
