package com.sparkrooter.runtime.application;

import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.registry.api.ConfirmationCoveragePolicy;
import com.sparkrooter.runtime.application.screen.ScreenRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 注册时的确认覆盖判定：需确认工具必须在本 hub 有确认屏与 {@code ConfirmationRecheck}。
 *
 * <p>与 {@code ConfirmationCoverageSelfCheck} 同一规则、不同时机——自检在启动时遍历已注册工具， 本策略在每次注册时逐个判定。远程 Manifest
 * 只会被后者拦到（provider 推送晚于 hub 启动）。
 *
 * <p>只判「有没有」，不构造探针屏：那是启动自检的职责（它还顺带校验 trustedArgs 与 Form 字段互斥）。 注册路径要快且不能因领域屏构造失败而拒绝注册。
 */
public class RegistryConfirmationCoverage implements ConfirmationCoveragePolicy {

  private static final Logger log = LoggerFactory.getLogger(RegistryConfirmationCoverage.class);

  private final ScreenRegistry screens;
  private final RecheckRegistry rechecks;

  public RegistryConfirmationCoverage(ScreenRegistry screens, RecheckRegistry rechecks) {
    this.screens = screens;
    this.rechecks = rechecks;
  }

  @Override
  public void check(ToolManifest manifest) {
    boolean needsConfirmation =
        manifest.risk().confirmation() == ToolManifest.Confirmation.required
            || manifest.risk().level() == ToolManifest.RiskLevel.high;
    if (!needsConfirmation) {
      return;
    }
    String toolId = manifest.toolId();
    if (!screens.coversConfirmation(toolId)) {
      log.warn("register_rejected toolId={} reason=no_confirmation_screen", toolId);
      throw new NotCovered(
          "tool "
              + toolId
              + " requires confirmation but this hub has no confirmation screen for it");
    }
    if (rechecks.find(toolId).isEmpty()) {
      log.warn("register_rejected toolId={} reason=no_confirmation_recheck", toolId);
      throw new NotCovered(
          "tool "
              + toolId
              + " requires confirmation but this hub has no ConfirmationRecheck for it");
    }
  }
}
