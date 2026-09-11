package com.sparkrooter.gateway.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparkrooter.contracts.model.SseEvent;
import com.sparkrooter.contracts.model.ToolInvoke;
import com.sparkrooter.gateway.domain.IdempotencyStore.Claim;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 内存幂等表（原 GatewayIdempotencySelfCheck 三条断言的可重复版本）：占位 / 等待 / 重放 / 释放语义。 */
final class InMemoryIdempotencyStoreTest {

  private static final String SCOPE = "sess";
  private static final long WAIT_MS = 2000;

  private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  @AfterEach
  void shutdown() {
    executor.shutdownNow();
  }

  @Test
  void ownerThenAwaitingReceiveSameResultThenReplay() throws Exception {
    String key = "k1";
    ToolInvoke.Response result = response("tc_a");
    assertThat(store.claim(SCOPE, key)).isInstanceOf(Claim.Owner.class);

    // 用 latch 保证 B 的 claim 确定落在 A 占位期内，而不是靠调度巧合
    CountDownLatch bClaimed = new CountDownLatch(1);
    Future<ToolInvoke.Response> b =
        executor.submit(
            () -> {
              Claim c = store.claim(SCOPE, key);
              bClaimed.countDown();
              assertThat(c).isInstanceOf(Claim.Awaiting.class);
              return ((Claim.Awaiting) c).future().get(WAIT_MS, TimeUnit.MILLISECONDS);
            });
    assertThat(bClaimed.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();

    store.complete(SCOPE, key, result);
    assertThat(b.get(WAIT_MS, TimeUnit.MILLISECONDS).toolCallId()).isEqualTo("tc_a");
    assertThat(store.claim(SCOPE, key)).isInstanceOf(Claim.Replay.class);
    assertThat(store.find(SCOPE, key)).map(ToolInvoke.Response::toolCallId).contains("tc_a");
  }

  @Test
  void releaseWakesWaiterExceptionallyAndAllowsReclaim() {
    String key = "k2";
    assertThat(store.claim(SCOPE, key)).isInstanceOf(Claim.Owner.class);
    Claim second = store.claim(SCOPE, key);
    assertThat(second).isInstanceOf(Claim.Awaiting.class);
    CompletableFuture<ToolInvoke.Response> f = ((Claim.Awaiting) second).future();

    store.release(SCOPE, key);

    assertThat(f.isCompletedExceptionally()).isTrue();
    assertThat(store.claim(SCOPE, key)).isInstanceOf(Claim.Owner.class);
    assertThat(store.find(SCOPE, key)).isEmpty();
  }

  @Test
  void releaseAfterCompleteIsNoop() {
    String key = "k3";
    store.claim(SCOPE, key);
    store.complete(SCOPE, key, response("tc_c"));
    store.release(SCOPE, key);
    assertThat(store.find(SCOPE, key)).isPresent();
    assertThat(store.claim(SCOPE, key)).isInstanceOf(Claim.Replay.class);
  }

  @Test
  void scopeIsolatesKeys() {
    assertThat(store.claim("s1", "same")).isInstanceOf(Claim.Owner.class);
    assertThat(store.claim("s2", "same")).isInstanceOf(Claim.Owner.class);
  }

  @Test
  void findIsEmptyWhileClaimedOrUnknown() {
    assertThat(store.find(SCOPE, "unknown")).isEmpty();
    store.claim(SCOPE, "held");
    assertThat(store.find(SCOPE, "held")).isEmpty();
  }

  private static ToolInvoke.Response response(String toolCallId) {
    return new ToolInvoke.Response(toolCallId, SseEvent.ToolStatus.succeeded, 0, null, null);
  }
}
