package com.sparkrooter.gateway.infra;

import com.sparkrooter.contracts.model.ToolInvoke;
import com.sparkrooter.gateway.domain.IdempotencyStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 内存幂等表：值是 CompletableFuture，未完成 = 占位中，已完成 = 最终结果。 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

  private final Map<String, CompletableFuture<ToolInvoke.Response>> store =
      new ConcurrentHashMap<>();

  private static String key(String tenantId, String idem) {
    return tenantId + "/" + idem;
  }

  @Override
  public Claim claim(String tenantId, String idempotencyKey) {
    CompletableFuture<ToolInvoke.Response> mine = new CompletableFuture<>();
    CompletableFuture<ToolInvoke.Response> existing =
        store.putIfAbsent(key(tenantId, idempotencyKey), mine);
    if (existing == null) {
      return new Claim.Owner();
    }
    if (existing.isDone() && !existing.isCompletedExceptionally()) {
      return new Claim.Replay(existing.join());
    }
    return new Claim.Awaiting(existing);
  }

  @Override
  public void complete(String tenantId, String idempotencyKey, ToolInvoke.Response response) {
    CompletableFuture<ToolInvoke.Response> f = store.get(key(tenantId, idempotencyKey));
    if (f != null) {
      f.complete(response);
    }
  }

  @Override
  public void release(String tenantId, String idempotencyKey) {
    String k = key(tenantId, idempotencyKey);
    CompletableFuture<ToolInvoke.Response> f = store.get(k);
    // 只释放未完成的占位；已 complete 的结果必须保留，否则重放失效
    if (f != null && !f.isDone()) {
      store.remove(k, f);
      f.completeExceptionally(new IllegalStateException("idempotency claim released"));
    }
  }

  @Override
  public Optional<ToolInvoke.Response> find(String tenantId, String idempotencyKey) {
    CompletableFuture<ToolInvoke.Response> f = store.get(key(tenantId, idempotencyKey));
    if (f == null || !f.isDone() || f.isCompletedExceptionally()) {
      return Optional.empty();
    }
    return Optional.of(f.join());
  }
}
