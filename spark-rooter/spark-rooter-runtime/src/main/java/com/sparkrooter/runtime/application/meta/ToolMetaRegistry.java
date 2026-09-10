package com.sparkrooter.runtime.application.meta;

import com.sparkrooter.spi.annotation.EntityType;
import com.sparkrooter.spi.annotation.ParamFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具元数据注册表：@SparkTool 扫描出的前置步骤、实体参数、枚举别名、单位词、缺省值、澄清候选源。规划器 / 校验器 / 抽取器读它而不再读内核硬编码表； 手写
 * Manifest（迁移期）没有元数据，回落到构造时注入的内核默认表（IntentVerbs.PREREQUISITES /
 * EntityRequirementCheck.ENTITY_ARGS）。 starter 启动期写入，之后只读。
 */
public final class ToolMetaRegistry {

  /**
   * 一个 inputSchema 参数的元数据。
   *
   * @param aliases 别名 → 枚举值（如 已发货 → SHIPPED）
   * @param units 数量参数的单位词
   * @param min 整型下界，null 不限
   * @param max 整型上界，null 不限
   * @param defaultValue @SparkDefault 字面量，null 表示无
   * @param integer 是否整型参数
   */
  public record ParamMeta(
      String name,
      EntityType entity,
      Map<String, String> aliases,
      List<String> units,
      Long min,
      Long max,
      ParamFormat format,
      String defaultValue,
      boolean integer) {
    public ParamMeta {
      aliases = Map.copyOf(aliases);
      units = List.copyOf(units);
    }
  }

  /** 一个工具的元数据；params 按 inputSchema 参数名。 */
  public record ToolMeta(
      String toolId,
      String version,
      String domain,
      List<String> prerequisites,
      EntityType clarifiesEntity,
      Map<String, ParamMeta> params) {
    public ToolMeta {
      prerequisites = List.copyOf(prerequisites);
      params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
  }

  private final Map<String, ToolMeta> byId = new ConcurrentHashMap<>();
  private final Map<String, List<String>> defaultPrerequisites;
  private final Map<String, String> defaultEntityArgs;

  /**
   * @param defaultPrerequisites 内核默认前置表（toolId → 前置 toolId 列表），供手写 Manifest 工具
   * @param defaultEntityArgs 内核默认实体参数表（参数名 → 实体类型小写）
   */
  public ToolMetaRegistry(
      Map<String, List<String>> defaultPrerequisites, Map<String, String> defaultEntityArgs) {
    this.defaultPrerequisites = Map.copyOf(defaultPrerequisites);
    this.defaultEntityArgs = Map.copyOf(defaultEntityArgs);
  }

  /** 同 toolId 二次注册 → 启动失败（注解来源与手写来源冲突、两方法同 id 都落在这里）。 */
  public void register(ToolMeta meta) {
    if (byId.putIfAbsent(meta.toolId(), meta) != null) {
      throw new IllegalStateException("duplicate @SparkTool id: " + meta.toolId());
    }
  }

  public Optional<ToolMeta> find(String toolId) {
    return Optional.ofNullable(byId.get(toolId));
  }

  public Collection<ToolMeta> all() {
    return Collections.unmodifiableCollection(byId.values());
  }

  /** 目标工具的前置只读步骤：注解声明优先，否则内核默认表，否则空。 */
  public List<String> prerequisites(String toolId) {
    ToolMeta m = byId.get(toolId);
    if (m != null) {
      return m.prerequisites();
    }
    return defaultPrerequisites.getOrDefault(toolId, List.of());
  }

  /** 参数名 → 实体类型（小写）；全部已注册工具的 @SparkParam.entity 与内核默认表合并（同名参数语义一致是约定）。 */
  public Map<String, String> entityArgs() {
    Map<String, String> merged = new LinkedHashMap<>(defaultEntityArgs);
    for (ToolMeta m : byId.values()) {
      for (ParamMeta p : m.params().values()) {
        if (p.entity() != EntityType.NONE) {
          merged.put(p.name(), typeName(p.entity()));
        }
      }
    }
    return merged;
  }

  /** 参数名对应的实体类型（小写），非实体参数返回 null。 */
  public String entityTypeOf(String argName) {
    return entityArgs().get(argName);
  }

  /** 可作某实体类型澄清候选源的工具（@SparkTool(clarifiesEntity=…)）；无则 empty。 */
  public Optional<ToolMeta> clarifierFor(String entityType) {
    return byId.values().stream()
        .filter(m -> m.clarifiesEntity() != EntityType.NONE)
        .filter(m -> typeName(m.clarifiesEntity()).equals(entityType))
        .findFirst();
  }

  public static String typeName(EntityType type) {
    return type.name().toLowerCase(Locale.ROOT);
  }
}
