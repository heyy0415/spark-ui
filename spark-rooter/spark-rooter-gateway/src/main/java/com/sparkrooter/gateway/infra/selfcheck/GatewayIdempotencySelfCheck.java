package com.sparkrooter.gateway.infra.selfcheck;

import com.sparkrooter.contracts.model.SseEvent;
import com.sparkrooter.contracts.model.ToolInvoke;
import com.sparkrooter.gateway.domain.IdempotencyStore;
import com.sparkrooter.gateway.domain.IdempotencyStore.Claim;
import com.sparkrooter.spi.SelfCheck;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 幂等占位自检：只测 IdempotencyStore（不经 InvokeToolUseCase，不产生审计行）。三条断言：
 *
 * <ol>
 *   <li>A claim → Owner；B 在 A complete 之前 claim → Awaiting；A complete 后 B 拿到同一 Response
 *   <li>另一 key：A claim 后 release；B 的 Awaiting 收到异常后重新 claim → Owner
 *   <li>A complete 后再 release 为 no-op，find 仍返回结果
 * </ol>
 *
 * 用 CountDownLatch 保证 B 的 claim 确定落在 A 的占位期内，而不是靠调度巧合。
 */
public class GatewayIdempotencySelfCheck implements SelfCheck {

  private static final Logger log = LoggerFactory.getLogger(GatewayIdempotencySelfCheck.class);
  private static final String SCOPE = "selfcheck";
  private static final long WAIT_MS = 2000;

  private final IdempotencyStore store;
  private final ExecutorService executor;

  public GatewayIdempotencySelfCheck(IdempotencyStore store, ExecutorService toolExecutor) {
    this.store = store;
    this.executor = toolExecutor;
  }

  @Override
  public String name() {
    return "gateway idempotency claim";
  }

  @Override
  public void run() {
    try {
      ownerThenAwaitingGetSameResult();
      releaseLetsWaiterReclaim();
      releaseAfterCompleteIsNoop();
      log.info("selfcheck: gateway idempotency claim OK");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("selfcheck interrupted", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("gateway idempotency selfcheck failed", e);
    }
  }

  private void ownerThenAwaitingGetSameResult()
      throws InterruptedException, ExecutionException, TimeoutException {
    String key = "sc-idem-1";
    ToolInvoke.Response result = response("tc_sc_a");
    if (!(store.claim(SCOPE, key) instanceof Claim.Owner)) {
      throw new IllegalStateException("first claim must be Owner");
    }
    CountDownLatch bClaimed = new CountDownLatch(1);
    Future<ToolInvoke.Response> b =
        executor.submit(
            () -> {
              Claim c = store.claim(SCOPE, key);
              bClaimed.countDown();
              if (!(c instanceof Claim.Awaiting aw)) {
                throw new IllegalStateException("second claim during hold must be Awaiting");
              }
              return aw.future().get(WAIT_MS, TimeUnit.MILLISECONDS);
            });
    if (!bClaimed.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
      throw new IllegalStateException("B did not claim in time");
    }
    store.complete(SCOPE, key, result);
    ToolInvoke.Response seen = b.get(WAIT_MS, TimeUnit.MILLISECONDS);
    if (!result.toolCallId().equals(seen.toolCallId())) {
      throw new IllegalStateException("Awaiting must receive Owner's response");
    }
    if (!(store.claim(SCOPE, key) instanceof Claim.Replay)) {
      throw new IllegalStateException("claim after complete must be Replay");
    }
  }

  private void releaseLetsWaiterReclaim()
      throws InterruptedException, ExecutionException, TimeoutException {
    String key = "sc-idem-2";
    if (!(store.claim(SCOPE, key) instanceof Claim.Owner)) {
      throw new IllegalStateException("first claim must be Owner");
    }
    Claim second = store.claim(SCOPE, key);
    if (!(second instanceof Claim.Awaiting aw)) {
      throw new IllegalStateException("second claim during hold must be Awaiting");
    }
    store.release(SCOPE, key);
    CompletableFuture<ToolInvoke.Response> f = aw.future();
    if (!f.isCompletedExceptionally()) {
      throw new IllegalStateException("release must complete waiter's future exceptionally");
    }
    if (!(store.claim(SCOPE, key) instanceof Claim.Owner)) {
      throw new IllegalStateException("claim after release must be Owner again");
    }
    store.release(SCOPE, key);
  }

  private void releaseAfterCompleteIsNoop() {
    String key = "sc-idem-3";
    store.claim(SCOPE, key);
    store.complete(SCOPE, key, response("tc_sc_c"));
    store.release(SCOPE, key);
    if (store.find(SCOPE, key).isEmpty()) {
      throw new IllegalStateException("release after complete must not drop the result");
    }
  }

  private static ToolInvoke.Response response(String toolCallId) {
    return new ToolInvoke.Response(toolCallId, SseEvent.ToolStatus.succeeded, 0, null, null);
  }
}
