package com.sparkrooter.spi.tool;

import com.sparkrooter.spi.annotation.ParamFormat;

/**
 * 一个 inputSchema 参数的元数据，由 {@code @SparkParam} / {@code @SparkDefault} 推导。
 *
 * <p>纯数据、无框架依赖，放在 spi 供 hub 与 provider 两侧共用：hub 侧由 {@code ToolMetaRegistry} 收集给规划器与校验器读；provider
 * 侧只做 Manifest 推导、不做规划，拿到即丢弃。
 *
 * @param name 参数名（等于 inputSchema 的属性名）
 * @param entity 实体类型名（小写）；null / 空 = 非实体参数
 * @param label 用户可读名（澄清屏按钮 / 提示）；空则用参数名
 * @param pattern 实体 ID 格式正则（复核用）；null 不校
 * @param min 数值下界；null 不校
 * @param max 数值上界；null 不校
 * @param format 参数格式（日期 / 金额等）；null 为无特定格式
 * @param defaultValue {@code @SparkDefault} 字面量，null 表示无
 * @param integer true = 整数型参数
 */
public record ParamMeta(
    String name,
    String entity,
    String label,
    String pattern,
    Long min,
    Long max,
    ParamFormat format,
    String defaultValue,
    boolean integer) {

  /** 是否为实体参数（澄清链路据此决定能否向用户追问）。 */
  public boolean isEntity() {
    return entity != null && !entity.isBlank();
  }

  /** 展示用名称：未声明 label 时回落参数名。 */
  public String displayLabel() {
    return label == null || label.isBlank() ? name : label;
  }
}
