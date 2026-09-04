package com.strato.gateway.domain;

import com.strato.contracts.model.ToolInvoke;
import java.util.Optional;

/** 幂等端口：(tenantId, idempotencyKey) → 首次响应。有副作用工具的重放直接返回首次结果。 */
public interface IdempotencyStore {
  Optional<ToolInvoke.Response> find(String tenantId, String idempotencyKey);

  /** 已存在返回已有值，否则写入并返回本次值（原子）。 */
  ToolInvoke.Response putIfAbsent(
      String tenantId, String idempotencyKey, ToolInvoke.Response response);
}
