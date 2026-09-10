package com.sparkrooter.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话记忆端口：每个 Run 成功结束后写入该会话最近的领域、实体与最近一次列表的行 ID，供下一轮省略实体时补位（「删除它」「第二个」）。 只存 ID，不存业务数据、不存用户。默认内存 +
 * TTL。
 */
public interface ConversationMemory {

  /**
   * @param entities 实体类型（小写，如 order）→ ID
   * @param lastTable 最近一次列表屏：工具 ID 与行 ID 顺序（序数指代用）；无则 null
   */
  record Memory(String domain, Map<String, String> entities, LastTable lastTable, Instant at) {}

  record LastTable(String toolId, List<String> rowIds) {}

  void put(String conversationId, Memory memory);

  Optional<Memory> find(String conversationId);
}
