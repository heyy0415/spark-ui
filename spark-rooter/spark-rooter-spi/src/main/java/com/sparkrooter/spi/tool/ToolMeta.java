package com.sparkrooter.spi.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个工具的元数据，由 {@code @SparkTool} / {@code @SparkPrerequisite} / {@code @SparkRisk} 等注解推导。
 *
 * <p>纯数据、无框架依赖，放在 spi 供 hub 与 provider 两侧共用（理由同 {@link ParamMeta}）。构造时对三个集合做不可变拷贝：启动期写入、之后只读。
 *
 * @param toolId 工具 ID，形如 {@code {domain}.{resource}.{verb}}
 * @param version semver
 * @param domain 领域名
 * @param prerequisites 前置步骤的 toolId 列表；规划器据此校验步骤顺序
 * @param clarifiesEntity 本工具可作为哪种实体的澄清候选源；null / 空 = 不作澄清源
 * @param verbs 同义动词，用于意图路由
 * @param params 参数元数据，键为 inputSchema 参数名，保持声明顺序
 * @param sideEffect 是否为写操作（{@code @SparkRisk.sideEffect}）。编排器据此决定客户端断开后能否提前终止：只读步骤可以，写步骤必须跑完
 */
public record ToolMeta(
    String toolId,
    String version,
    String domain,
    List<String> prerequisites,
    String clarifiesEntity,
    List<String> verbs,
    Map<String, ParamMeta> params,
    boolean sideEffect) {

  public ToolMeta {
    prerequisites = List.copyOf(prerequisites);
    verbs = List.copyOf(verbs);
    params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
  }

  /** 只读工具（sideEffect=false）的便捷构造，供测试与不关心风险的调用方。 */
  public ToolMeta(
      String toolId,
      String version,
      String domain,
      List<String> prerequisites,
      String clarifiesEntity,
      List<String> verbs,
      Map<String, ParamMeta> params) {
    this(toolId, version, domain, prerequisites, clarifiesEntity, verbs, params, false);
  }

  /** 是否可作为某种实体的澄清候选源。 */
  public boolean clarifies() {
    return clarifiesEntity != null && !clarifiesEntity.isBlank();
  }
}
