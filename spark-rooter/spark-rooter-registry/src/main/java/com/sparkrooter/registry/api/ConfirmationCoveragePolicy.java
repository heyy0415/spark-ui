package com.sparkrooter.registry.api;

import com.sparkrooter.contracts.model.ToolManifest;

/**
 * 需确认工具的「覆盖齐备」判定，在<b>注册时</b>而非仅启动时执行。
 *
 * <p>为什么必须在注册时：hub 的 {@code ConfirmationCoverageSelfCheck} 跑在 {@code
 * ApplicationReadyEvent}，遍历当时已注册的工具，要求每个需确认工具都有确认屏与 {@code ConfirmationRecheck}。但远程 Manifest 是
 * provider 在<b>它自己的</b> {@code ApplicationReadyEvent} 才推来的——两个进程，顺序无保证。于是一个高风险远程工具通常 在 hub
 * 自检通过之后才注册，<b>完全绕过那道检查</b>，只在用户点确认时才 fail-closed （{@code INTERNAL_ERROR}）。
 *
 * <p>那样虽不会执行未校验的高危操作（fail-closed 仍成立），但故障点从「启动失败、立刻可见」 退化为「用户走到确认步骤才失败」——排查成本与用户影响都高得多。本策略把它拉回注册时。
 *
 * <p>放在 {@code api} 包：runtime 与 registry 之间只经对方 api 包的接口通信（project-structure §2）。 实现在 runtime（那里才有
 * {@code RecheckRegistry} 与 {@code ScreenRegistry}），registry 只认接口， 不反向依赖
 * runtime。默认实现全放行，保持单体形态零行为变化。
 */
public interface ConfirmationCoveragePolicy {

  /** 覆盖不齐备。注册端点映射为 4xx，provider 的推送日志里能直接看到原因。 */
  final class NotCovered extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NotCovered(String message) {
      super(message);
    }
  }

  /**
   * 校验一个待注册的 Manifest。
   *
   * @param manifest 已通过契约校验的 Manifest
   * @throws NotCovered 该工具需要确认，但本 hub 缺少对应的确认屏或重校验实现
   */
  void check(ToolManifest manifest);

  /** 默认全放行：单体形态由启动自检覆盖，无需在注册时重复判定。 */
  static ConfirmationCoveragePolicy permitAll() {
    return manifest -> {};
  }
}
