package com.sparkrooter.gateway.domain;

import com.sparkrooter.contracts.model.ToolInvoke;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 幂等端口（先占位后填充）：(scope, idempotencyKey) 只允许一个执行者；scope 由调用方决定（Gateway 传 sessionId）。
 *
 * <ul>
 *   <li>{@link #claim}：原子占位。返回 {@link Claim.Owner}（本次拿到执行权）、{@link Claim.Replay}（已有最终结果，直接重放）或
 *       {@link Claim.Awaiting}（他人执行中，持有可等待的 future）。
 *   <li>{@link #complete}：执行者写入最终结果并唤醒等待方。
 *   <li>{@link #release}：执行者失败时释放占位（只对未完成的占位生效；对已 complete 的 key 为 no-op），等待方收到异常后可重新 claim。
 * </ul>
 *
 * 纯 JDK 类型，无框架依赖。
 */
public interface IdempotencyStore {

  /** claim 的三种结果。 */
  sealed interface Claim permits Claim.Owner, Claim.Replay, Claim.Awaiting {
    /** 本次调用拿到执行权，执行后必须 complete 或 release。 */
    record Owner() implements Claim {}

    /** 已有最终结果。 */
    record Replay(ToolInvoke.Response response) implements Claim {}

    /** 他人正在执行；future 正常完成 = 结果，异常完成 = 执行者已 release。 */
    record Awaiting(CompletableFuture<ToolInvoke.Response> future) implements Claim {}
  }

  Claim claim(String scope, String idempotencyKey);

  void complete(String scope, String idempotencyKey, ToolInvoke.Response response);

  void release(String scope, String idempotencyKey);

  /** 只读查询最终结果（自检断言终态用）；占位中或不存在返回 empty。 */
  Optional<ToolInvoke.Response> find(String scope, String idempotencyKey);
}
