package com.sparkrooter.runtime.application.meta;

import com.sparkrooter.spi.tool.ParamMeta;
import com.sparkrooter.spi.tool.ToolMeta;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具元数据注册表：@SparkTool / @SparkParam 扫描出的前置步骤、实体参数（类型名 / ID 格式 / 中文名）、同义动词、缺省值、澄清候选源。
 * 规划器与校验器只读它——内核不含任何领域默认表，实体类型名是宿主自定义字符串。starter 启动期写入，之后只读。
 *
 * <p>数据载体 {@link ToolMeta} / {@link ParamMeta} 在 spi（纯数据，两侧共用）；本容器留在 runtime，因为只有规划链路读它 —— provider
 * 不做规划，无需这个容器。
 */
public final class ToolMetaRegistry {

  private final Map<String, ToolMeta> byId = new ConcurrentHashMap<>();

  /** 参数名 → 实体类型名，register 时增量维护；同名不同类型 → 启动失败。 */
  private final Map<String, String> entityArgs = new ConcurrentHashMap<>();

  /** 同 toolId 二次注册 → 启动失败。 */
  public void register(ToolMeta meta) {
    if (byId.putIfAbsent(meta.toolId(), meta) != null) {
      throw new IllegalStateException("duplicate @SparkTool id: " + meta.toolId());
    }
    for (ParamMeta p : meta.params().values()) {
      if (!p.isEntity()) {
        continue;
      }
      String prev = entityArgs.putIfAbsent(p.name(), p.entity());
      if (prev != null && !prev.equals(p.entity())) {
        throw new IllegalStateException(
            "@SparkParam.entity conflict for parameter '"
                + p.name()
                + "': "
                + prev
                + " vs "
                + p.entity()
                + " ("
                + meta.toolId()
                + "); same parameter name must map to one entity type");
      }
    }
  }

  public Optional<ToolMeta> find(String toolId) {
    return Optional.ofNullable(byId.get(toolId));
  }

  public Collection<ToolMeta> all() {
    return Collections.unmodifiableCollection(byId.values());
  }

  /** 目标工具的前置只读步骤（注解声明），无则空。 */
  public List<String> prerequisites(String toolId) {
    ToolMeta m = byId.get(toolId);
    return m == null ? List.of() : m.prerequisites();
  }

  /** 参数名 → 实体类型名。 */
  public Map<String, String> entityArgs() {
    return Collections.unmodifiableMap(entityArgs);
  }

  /** 参数名对应的实体类型名，非实体参数返回 null。 */
  public String entityTypeOf(String argName) {
    return entityArgs.get(argName);
  }

  /** 某工具某参数的元数据；不存在返回 null。 */
  public ParamMeta param(String toolId, String argName) {
    ToolMeta m = byId.get(toolId);
    return m == null ? null : m.params().get(argName);
  }

  /** 某实体类型的用户可读名：取任一声明了该类型的参数的 label；没有则用类型名。 */
  public String entityLabel(String entityType) {
    for (ToolMeta m : byId.values()) {
      for (ParamMeta p : m.params().values()) {
        if (entityType.equals(p.entity()) && p.label() != null && !p.label().isBlank()) {
          return p.label();
        }
      }
    }
    return entityType;
  }

  /** 某实体类型的 ID 格式正则：取任一声明；没有返回 null。 */
  public String entityPattern(String entityType) {
    for (ToolMeta m : byId.values()) {
      for (ParamMeta p : m.params().values()) {
        if (entityType.equals(p.entity()) && p.pattern() != null && !p.pattern().isBlank()) {
          return p.pattern();
        }
      }
    }
    return null;
  }

  /** 把模型给出的实体标识规范化为已注册的类型名：先按类型名精确匹配，再按 label 匹配（模型常把中文 label 当类型名写回来）。 都不中返回 empty。 */
  public Optional<String> normalizeEntityType(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String v = raw.trim();
    for (ToolMeta m : byId.values()) {
      for (ParamMeta p : m.params().values()) {
        if (p.isEntity() && p.entity().equalsIgnoreCase(v)) {
          return Optional.of(p.entity());
        }
      }
    }
    for (ToolMeta m : byId.values()) {
      for (ParamMeta p : m.params().values()) {
        if (p.isEntity() && v.equals(p.label())) {
          return Optional.of(p.entity());
        }
      }
    }
    return Optional.empty();
  }

  /** 可作某实体类型澄清候选源的工具（@SparkTool(clarifiesEntity=…)）；无则 empty。 */
  public Optional<ToolMeta> clarifierFor(String entityType) {
    return byId.values().stream()
        .filter(ToolMeta::clarifies)
        .filter(m -> m.clarifiesEntity().equals(entityType))
        .findFirst();
  }
}
