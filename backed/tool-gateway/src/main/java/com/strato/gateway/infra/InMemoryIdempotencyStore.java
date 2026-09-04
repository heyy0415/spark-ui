package com.strato.gateway.infra;

import com.strato.contracts.model.ToolInvoke;
import com.strato.gateway.domain.IdempotencyStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 内存幂等表。 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

  private final Map<String, ToolInvoke.Response> store = new ConcurrentHashMap<>();

  private static String key(String tenantId, String idem) {
    return tenantId + "/" + idem;
  }

  @Override
  public Optional<ToolInvoke.Response> find(String tenantId, String idempotencyKey) {
    return Optional.ofNullable(store.get(key(tenantId, idempotencyKey)));
  }

  @Override
  public ToolInvoke.Response putIfAbsent(
      String tenantId, String idempotencyKey, ToolInvoke.Response response) {
    ToolInvoke.Response existing = store.putIfAbsent(key(tenantId, idempotencyKey), response);
    return existing != null ? existing : response;
  }
}
