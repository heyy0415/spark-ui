package com.sparkrooter.runtime.infra.llm;

import static com.sparkrooter.runtime.support.TestFixtures.ENTITY;
import static com.sparkrooter.runtime.support.TestFixtures.VALIDATOR;
import static com.sparkrooter.runtime.support.TestFixtures.confirmSchema;
import static com.sparkrooter.runtime.support.TestFixtures.entityParam;
import static com.sparkrooter.runtime.support.TestFixtures.idSchema;
import static com.sparkrooter.runtime.support.TestFixtures.listSchema;
import static com.sparkrooter.runtime.support.TestFixtures.meta;
import static com.sparkrooter.runtime.support.TestFixtures.names;
import static com.sparkrooter.runtime.support.TestFixtures.needsConfirm;
import static com.sparkrooter.runtime.support.TestFixtures.plainParam;
import static com.sparkrooter.runtime.support.TestFixtures.readOnly;
import static com.sparkrooter.runtime.support.TestFixtures.registry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparkrooter.contracts.model.ToolSearch;
import com.sparkrooter.runtime.application.ToolDisplayNames;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.domain.Plan;
import com.sparkrooter.runtime.domain.RunFailure;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 模型输出的通用校验边界（agent-safety §2 / §3）。工具全部中性命名：demo.item.list（只读，可澄清）、demo.item.get（只读，实体参数）、
 * demo.item.close（需确认，前置 demo.item.get，可信参数 amount）。
 */
final class PlanValidatorTest {

  private static final String LIST = "demo.item.list";
  private static final String GET = "demo.item.get";
  private static final String CLOSE = "demo.item.close";

  private final List<ToolSearch.ToolCandidate> candidates =
      List.of(
          readOnly(LIST, listSchema()),
          readOnly(GET, idSchema()),
          needsConfirm(CLOSE, confirmSchema()));

  private final ToolMetaRegistry meta =
      registry(
          meta(
              LIST,
              List.of(),
              ENTITY,
              plainParam("status", null, false),
              plainParam("limit", "20", true)),
          meta(GET, List.of(), null, entityParam("itemId", "^\\d{5}$")),
          meta(
              CLOSE,
              List.of(GET),
              null,
              entityParam("itemId", "^\\d{5}$"),
              plainParam("amount", null, false)));

  private final ToolDisplayNames displayNames = names(LIST, "列出条目", GET, "查看条目", CLOSE, "关闭条目");

  // ---------------------------------------------------------------- 通过路径

  @Test
  void validDraftBecomesPlanWithDefaultsFilled() {
    Plan plan = validate(draft(step(LIST, Map.of("status", "OPEN"))), "看看打开的条目", ctx());
    assertThat(plan.domain()).isEqualTo("demo");
    assertThat(plan.steps()).hasSize(1);
    assertThat(plan.steps().get(0).toolId()).isEqualTo(LIST);
    assertThat(plan.steps().get(0).displayName()).isEqualTo("列出条目");
    assertThat(plan.steps().get(0).requiresConfirmation()).isFalse();
    // @SparkDefault 补齐进 fixedArgs，使 argsDigest 确定
    assertThat(plan.steps().get(0).fixedArgs())
        .containsEntry("status", "OPEN")
        .containsEntry("limit", "20");
  }

  @Test
  void entityValuePresentInMessagePasses() {
    Plan plan = validate(draft(step(GET, Map.of("itemId", "10001"))), "看看 10001 的详情", ctx());
    assertThat(plan.steps().get(0).fixedArgs()).containsEntry("itemId", "10001");
  }

  @Test
  void entityValueFromMemoryOrLastRowsPasses() {
    LlmClient.Context memory =
        new LlmClient.Context(Map.of(ENTITY, "10002"), List.of(), Optional.empty());
    assertThat(validate(draft(step(GET, Map.of("itemId", "10002"))), "它的详情", memory).steps())
        .hasSize(1);

    LlmClient.Context rows =
        new LlmClient.Context(Map.of(), List.of("10003", "10004"), Optional.empty());
    assertThat(validate(draft(step(GET, Map.of("itemId", "10004"))), "第二个", rows).steps())
        .hasSize(1);
  }

  @Test
  void confirmationStepWithPrerequisiteInOrderPasses() {
    Plan plan =
        validate(
            draft(step(GET, Map.of("itemId", "10001")), step(CLOSE, Map.of("itemId", "10001"))),
            "关闭 10001",
            ctx());
    assertThat(plan.steps()).extracting(s -> s.toolId()).containsExactly(GET, CLOSE);
    assertThat(plan.steps().get(1).requiresConfirmation()).isTrue();
    assertThat(plan.steps().get(0).seq()).isEqualTo(1);
    assertThat(plan.steps().get(1).seq()).isEqualTo(2);
  }

  // ---------------------------------------------------------------- TOOL_SELECTION_INVALID

  @Test
  void emptyStepsIsSelectionInvalid() {
    assertSelectionInvalid(
        () -> validate(new PlanDraft("plan", List.of(), List.of(), null), "x", ctx()), "no steps");
    assertSelectionInvalid(
        () -> validate(new PlanDraft("plan", null, null, null), "x", ctx()), "no steps");
  }

  @Test
  void toolOutsideCandidatesIsSelectionInvalid() {
    assertSelectionInvalid(
        () -> validate(draft(step("demo.other.run", Map.of())), "x", ctx()), "not in candidates");
  }

  @Test
  void argNotInInputSchemaIsSelectionInvalid() {
    assertSelectionInvalid(
        () -> validate(draft(step(LIST, Map.of("colour", "red"))), "x", ctx()),
        "arg not in inputSchema");
  }

  @Test
  void argViolatingEnumIsSelectionInvalid() {
    assertSelectionInvalid(
        () -> validate(draft(step(LIST, Map.of("status", "PENDING"))), "x", ctx()),
        "violates inputSchema");
  }

  @Test
  void integerArgNotParsableIsSelectionInvalid() {
    assertSelectionInvalid(
        () -> validate(draft(step(LIST, Map.of("limit", "many"))), "x", ctx()), "is not a integer");
  }

  @Test
  void integerArgOutOfRangeIsSelectionInvalid() {
    assertSelectionInvalid(
        () -> validate(draft(step(LIST, Map.of("limit", "500"))), "x", ctx()),
        "violates inputSchema");
  }

  @Test
  void confirmationStepMissingPrerequisiteIsSelectionInvalid() {
    assertSelectionInvalid(
        () -> validate(draft(step(CLOSE, Map.of("itemId", "10001"))), "关闭 10001", ctx()),
        "missing prerequisite");
  }

  @Test
  void plannerMustNotFillTrustedOnlyArgOnConfirmationStep() {
    // amount 由重校验结果覆盖（ConfirmationRecheck.trustedArgKeys），模型填了就拒绝
    assertSelectionInvalid(
        () ->
            PlanValidator.validate(
                draft(
                    step(GET, Map.of("itemId", "10001")),
                    step(CLOSE, Map.of("itemId", "10001", "amount", "1.00"))),
                "关闭 10001",
                ctx(),
                candidates,
                displayNames,
                meta,
                Set.of("amount"),
                VALIDATOR),
        "trusted-only");
  }

  // ---------------------------------------------------------------- EntityMissing

  @Test
  void entityValueAbsentFromMessageAndContextIsEntityMissing() {
    assertEntityMissing(
        () -> validate(draft(step(GET, Map.of("itemId", "10009"))), "看看 10001", ctx()));
  }

  @Test
  void entityValueViolatingPatternIsEntityMissing() {
    assertEntityMissing(() -> validate(draft(step(GET, Map.of("itemId", "abc"))), "看看 abc", ctx()));
  }

  @Test
  void requiredEntityArgOmittedIsEntityMissing() {
    assertEntityMissing(() -> validate(draft(step(GET, Map.of())), "看看详情", ctx()));
  }

  @Test
  void entityMissingCarriesEntityType() {
    assertThatThrownBy(() -> validate(draft(step(GET, Map.of())), "看看详情", ctx()))
        .isInstanceOf(PlanValidator.EntityMissing.class)
        .satisfies(
            e -> assertThat(((PlanValidator.EntityMissing) e).entityType()).isEqualTo(ENTITY));
  }

  // ---------------------------------------------------------------- missingEntity()

  @Test
  void missingEntityPrefersDeclaredTypeNormalized() {
    // 模型把中文 label 当类型名写回来 → 规范化到已注册类型
    PlanDraft d =
        new PlanDraft(
            "clarify",
            List.of(step(GET, Map.of())),
            List.of(new PlanDraft.Missing("条目", "缺")),
            null);
    assertThat(PlanValidator.missingEntity(d, candidates, meta)).contains(ENTITY);
    PlanDraft exact =
        new PlanDraft("clarify", List.of(), List.of(new PlanDraft.Missing("ITEM", "缺")), null);
    assertThat(PlanValidator.missingEntity(exact, candidates, meta)).contains(ENTITY);
  }

  @Test
  void missingEntityFallsBackToTargetToolRequiredEntityArg() {
    PlanDraft d = new PlanDraft("clarify", List.of(step(GET, Map.of())), List.of(), "请问哪一条？");
    assertThat(PlanValidator.missingEntity(d, candidates, meta)).contains(ENTITY);
  }

  @Test
  void missingEntityEmptyWhenNothingToInfer() {
    PlanDraft unknownType =
        new PlanDraft("clarify", List.of(), List.of(new PlanDraft.Missing("galaxy", "?")), null);
    assertThat(PlanValidator.missingEntity(unknownType, candidates, meta)).isEmpty();
    PlanDraft noSteps = new PlanDraft("clarify", List.of(), List.of(), null);
    assertThat(PlanValidator.missingEntity(noSteps, candidates, meta)).isEmpty();
    PlanDraft filled =
        new PlanDraft("clarify", List.of(step(GET, Map.of("itemId", "10001"))), List.of(), null);
    assertThat(PlanValidator.missingEntity(filled, candidates, meta)).isEmpty();
    PlanDraft foreignTool =
        new PlanDraft("clarify", List.of(step("demo.other.run", Map.of())), List.of(), null);
    assertThat(PlanValidator.missingEntity(foreignTool, candidates, meta)).isEmpty();
  }

  // ---------------------------------------------------------------- helpers

  private Plan validate(PlanDraft draft, String message, LlmClient.Context ctx) {
    return PlanValidator.validate(
        draft, message, ctx, candidates, displayNames, meta, Set.of(), VALIDATOR);
  }

  private static LlmClient.Context ctx() {
    return LlmClient.Context.empty();
  }

  private static PlanDraft draft(PlanDraft.DraftStep... steps) {
    return new PlanDraft("plan", List.of(steps), List.of(), null);
  }

  private static PlanDraft.DraftStep step(String toolId, Map<String, String> args) {
    return new PlanDraft.DraftStep(toolId, args);
  }

  private static void assertSelectionInvalid(Runnable r, String messagePart) {
    assertThatThrownBy(r::run)
        .isInstanceOf(RunFailure.class)
        .satisfies(e -> assertThat(((RunFailure) e).code()).isEqualTo("TOOL_SELECTION_INVALID"))
        .hasMessageContaining(messagePart);
  }

  private static void assertEntityMissing(Runnable r) {
    assertThatThrownBy(r::run).isInstanceOf(PlanValidator.EntityMissing.class);
  }
}
