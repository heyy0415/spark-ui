package com.sparkrooter.runtime.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.PlatformMapper;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.registry.api.ConfirmationCoveragePolicy;
import com.sparkrooter.runtime.application.screen.ScreenRegistry;
import com.sparkrooter.runtime.support.Fakes;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 注册时的确认覆盖判定（feat-provider-http-transport 评审 M-3 的实际缺口）。
 *
 * <p>缺口来自两个进程的启动顺序：hub 的 {@code ConfirmationCoverageSelfCheck} 在自己的 {@code ApplicationReadyEvent}
 * 遍历当时已注册的工具；provider 在<b>它自己的</b> {@code ApplicationReadyEvent} 才推 Manifest。于是高风险远程工具通常在 hub
 * 自检通过之后注册， 完全绕过那道检查——虽仍 fail-closed（确认时 INTERNAL_ERROR），但故障点从「启动即失败」 退化为「用户走到确认步骤才失败」。本策略把它拉回注册时。
 */
final class RegistryConfirmationCoverageTest {

  private static final ObjectMapper MAPPER = PlatformMapper.create();
  private static final SchemaValidator VALIDATOR = new SchemaValidator(MAPPER);
  private static final String TOOL = "demo.thing.create";

  /** 复用既有测试夹具（Fakes），不手写匿名实现——接口签名变动时只需改一处。 */
  private ConfirmationCoveragePolicy policy(boolean withScreen, boolean withRecheck) {
    ScreenRegistry screens =
        new ScreenRegistry(
            withScreen ? List.of(new Fakes.FakeScreens(Set.of(TOOL), Set.of(TOOL))) : List.of(),
            VALIDATOR);
    RecheckRegistry rechecks =
        new RecheckRegistry(
            withRecheck ? List.of(new Fakes.FakeRecheck(TOOL, "demo.thing.check")) : List.of());
    return new RegistryConfirmationCoverage(screens, rechecks);
  }

  private static ToolManifest manifest(String confirmation, String riskLevel) {
    ObjectNode m = MAPPER.createObjectNode();
    m.put("toolId", TOOL)
        .put("version", "1.0.0")
        .put("domain", "demo")
        .put("name", "创建")
        .put("description", "写操作")
        .put("protocol", "http");
    m.putObject("provider").put("serviceName", "demo-service").put("baseUrl", "http://demo:8080");
    m.putObject("inputSchema").put("type", "object");
    m.putObject("outputSchema").put("type", "object");
    m.putObject("risk")
        .put("level", riskLevel)
        .put("sideEffect", true)
        .put("reversible", false)
        .put("confirmation", confirmation);
    m.putObject("authorization");
    m.putObject("execution")
        .put("timeoutMs", 3000)
        .put("maxRetries", 0)
        .put("idempotency", "required");
    m.putObject("owner").put("team", "demo-team");
    m.put("status", "active");
    return MAPPER.convertValue(m, ToolManifest.class);
  }

  // ---------------------------------------------------------------- 需确认工具

  /** confirmation=required 且覆盖齐备 → 放行。 */
  @Test
  void coveredConfirmationToolIsAccepted() {
    assertThatCode(() -> policy(true, true).check(manifest("required", "high")))
        .doesNotThrowAnyException();
  }

  /**
   * 缺重校验 → <b>注册即拒绝</b>，而不是等到用户点确认。
   *
   * <p>这是本类的核心断言：没有它，一个高风险远程工具能注册成功、能被规划、能渲染确认屏， 只在用户点下「确认」那一刻才 INTERNAL_ERROR。
   */
  @Test
  void confirmationToolWithoutRecheckIsRejectedAtRegistration() {
    assertThatThrownBy(() -> policy(true, false).check(manifest("required", "high")))
        .isInstanceOf(ConfirmationCoveragePolicy.NotCovered.class)
        .hasMessageContaining("no ConfirmationRecheck");
  }

  /** 缺确认屏 → 同样注册即拒绝。 */
  @Test
  void confirmationToolWithoutScreenIsRejectedAtRegistration() {
    assertThatThrownBy(() -> policy(false, true).check(manifest("required", "high")))
        .isInstanceOf(ConfirmationCoveragePolicy.NotCovered.class)
        .hasMessageContaining("no confirmation screen");
  }

  /** risk=high 即使 confirmation 字段为 never 也算需确认（契约的 if/then 本不允许，双保险）。 */
  @Test
  void highRiskIsTreatedAsNeedingConfirmation() {
    assertThatThrownBy(() -> policy(false, false).check(manifest("never", "high")))
        .isInstanceOf(ConfirmationCoveragePolicy.NotCovered.class);
  }

  // ---------------------------------------------------------------- 无需确认的工具

  /** 低风险 + never → 不判定覆盖，照常注册（绝大多数只读工具走这条）。 */
  @Test
  void lowRiskToolNeedsNoCoverage() {
    assertThatCode(() -> policy(false, false).check(manifest("never", "low")))
        .doesNotThrowAnyException();
  }

  @Test
  void mediumRiskWithoutConfirmationNeedsNoCoverage() {
    assertThatCode(() -> policy(false, false).check(manifest("never", "medium")))
        .doesNotThrowAnyException();
  }

  /** 默认策略全放行：单体形态由启动自检覆盖，注册路径零行为变化。 */
  @Test
  void permitAllAcceptsEverything() {
    assertThatCode(() -> ConfirmationCoveragePolicy.permitAll().check(manifest("required", "high")))
        .doesNotThrowAnyException();
  }
}
