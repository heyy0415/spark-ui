package com.sparkrooter.runtime.infra.selfcheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.contracts.model.ToolSearch;
import com.sparkrooter.contracts.model.UiSchema;
import com.sparkrooter.runtime.application.RecheckRegistry;
import com.sparkrooter.runtime.application.port.ToolRegistryClient;
import com.sparkrooter.runtime.application.screen.ScreenRegistry;
import com.sparkrooter.spi.ConfirmationRecheck;
import com.sparkrooter.spi.ScreenContext;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 需确认工具覆盖自检：Registry 中所有 confirmation=required / risk=high
 * 的工具都必须同时有领域确认屏（ScreenBuilder.confirmToolIds） 与重校验契约（ConfirmationRecheck）；且 trustedArgs 的键与确认屏
 * Form 字段互斥（可信参数不能被表单覆盖）。 任一缺失启动即失败——运行期对应 fail-closed INTERNAL_ERROR，自检把它提前到启动。
 */
public class ConfirmationCoverageSelfCheck implements com.sparkrooter.spi.SelfCheck {

  private static final Logger log = LoggerFactory.getLogger(ConfirmationCoverageSelfCheck.class);

  /** 生成试探确认屏用的实体参数占位值：确认屏只用它拼标题，不查数据（数据来自 probeOutputs）。 */
  private static final String PROBE_ID = "probe";

  private final ToolRegistryClient registry;
  private final ScreenRegistry screens;
  private final RecheckRegistry rechecks;
  private final ObjectMapper mapper;

  public ConfirmationCoverageSelfCheck(
      ToolRegistryClient registry,
      ScreenRegistry screens,
      RecheckRegistry rechecks,
      ObjectMapper mapper) {
    this.registry = registry;
    this.screens = screens;
    this.rechecks = rechecks;
    this.mapper = mapper;
  }

  @Override
  public String name() {
    return "confirmation coverage";
  }

  @Override
  public void run() {
    int count = 0;
    ToolSearch.Response found = registry.search(new ToolSearch.Request(null, null, null), null);
    {
      for (ToolSearch.ToolCandidate c : found.tools()) {
        boolean confirm =
            c.confirmation() == ToolManifest.Confirmation.required
                || c.riskLevel() == ToolManifest.RiskLevel.high;
        if (!confirm) {
          continue;
        }
        Map<String, String> probe = new java.util.LinkedHashMap<>();
        c.inputSchema().path("required").forEach(n -> probe.put(n.asText(), PROBE_ID));
        verify(c.toolId(), probe);
        count++;
      }
    }
    log.info("selfcheck: confirmation coverage OK ({} tools)", count);
  }

  private void verify(String toolId, Map<String, String> probeArgs) {
    if (!screens.coversConfirmation(toolId)) {
      throw new IllegalStateException("no confirmation ScreenBuilder for " + toolId);
    }
    ConfirmationRecheck rc =
        rechecks
            .find(toolId)
            .orElseThrow(() -> new IllegalStateException("no ConfirmationRecheck for " + toolId));
    UiSchema ui =
        screens.confirmation(
            toolId,
            probeArgs,
            screens.probeOutputs(toolId),
            ScreenRegistry.PLACEHOLDER_TOKEN,
            new ScreenContext("run_selfcheck"));
    ScreenRegistry.submitActionId(ui);
    Set<String> overlap = new HashSet<>(ScreenRegistry.formKeys(ui));
    overlap.retainAll(rc.trustedArgKeys());
    if (!overlap.isEmpty()) {
      throw new IllegalStateException(
          "trustedArgs of " + toolId + " overlap confirmation Form fields: " + overlap);
    }
  }
}
