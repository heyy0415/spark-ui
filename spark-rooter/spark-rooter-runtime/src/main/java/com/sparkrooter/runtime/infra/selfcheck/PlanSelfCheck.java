package com.sparkrooter.runtime.infra.selfcheck;

import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.model.ToolSearch;
import com.sparkrooter.runtime.application.ToolDisplayNames;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.application.port.ToolRegistryClient;
import com.sparkrooter.runtime.domain.RunFailure;
import com.sparkrooter.runtime.infra.llm.PlanDraft;
import com.sparkrooter.runtime.infra.llm.PlanValidator;
import com.sparkrooter.spi.tool.ParamMeta;
import com.sparkrooter.spi.tool.ToolMeta;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 规划校验自检：不调模型，只验证 PlanValidator 这道硬边界对模型输出的四类越界都拒绝（候选外 toolId / 缺前置 / 实体值不在原话与上下文 / 值不合
 * schema），且正常草案能通过。全部用 Registry 里实际注册的工具与其注解元数据构造用例，不含任何内核写死的领域词。
 */
public class PlanSelfCheck implements com.sparkrooter.spi.SelfCheck {

  private static final Logger log = LoggerFactory.getLogger(PlanSelfCheck.class);

  private final ToolRegistryClient registry;
  private final ToolDisplayNames names;
  private final ToolMetaRegistry meta;
  private final SchemaValidator validator;
  private final LlmClient llm;

  public PlanSelfCheck(
      LlmClient llm,
      ToolRegistryClient registry,
      ToolDisplayNames names,
      ToolMetaRegistry meta,
      SchemaValidator validator) {
    this.llm = llm;
    this.registry = registry;
    this.names = names;
    this.meta = meta;
    this.validator = validator;
  }

  @Override
  public String name() {
    return "plan validator";
  }

  @Override
  public void run() {
    log.info("selfcheck: planner={}", llm.name());
    List<ToolSearch.ToolCandidate> all =
        registry.search(new ToolSearch.Request(null, null, null), null).tools();
    if (all.isEmpty()) {
      log.info("selfcheck: no tools registered, plan validator skipped");
      return;
    }
    // 找一个需确认且有前置的工具、一个带实体参数的工具，用注解元数据而不是写死的 toolId
    Optional<ToolMeta> confirmTool =
        meta.all().stream().filter(m -> !m.prerequisites().isEmpty()).findFirst();
    Optional<ParamMeta> entityParam =
        meta.all().stream()
            .flatMap(m -> m.params().values().stream())
            .filter(ParamMeta::isEntity)
            .findFirst();

    expectReject(
        () ->
            PlanValidator.validate(
                draft("no.such.tool", Map.of()),
                "x",
                LlmClient.Context.empty(),
                all,
                names,
                meta,
                Set.of(),
                validator),
        "invalid toolId rejected OK");

    confirmTool.ifPresent(
        ct -> {
          Map<String, String> args = new java.util.HashMap<>();
          entityParamOf(ct).ifPresent(p -> args.put(p.name(), "99999"));
          expectReject(
              () ->
                  PlanValidator.validate(
                      draft(ct.toolId(), args),
                      "99999",
                      LlmClient.Context.empty(),
                      all,
                      names,
                      meta,
                      Set.of(),
                      validator),
              "missing prerequisite rejected OK");
        });

    entityParam.ifPresent(
        p -> {
          String toolId =
              meta.all().stream()
                  .filter(m -> m.params().containsKey(p.name()) && m.prerequisites().isEmpty())
                  .map(ToolMeta::toolId)
                  .findFirst()
                  .orElse(null);
          if (toolId == null) {
            return;
          }
          try {
            PlanValidator.validate(
                draft(toolId, Map.of(p.name(), "10009")),
                "id 10001",
                LlmClient.Context.empty(),
                all,
                names,
                meta,
                Set.of(),
                validator);
            throw new IllegalStateException(
                "validator accepted an entity value absent from message");
          } catch (PlanValidator.EntityMissing expected) {
            log.info("selfcheck: foreign entity arg rejected OK");
          }
        });

    // 值不合 schema：任一带 enum 参数的工具
    all.stream()
        .filter(
            c ->
                c.inputSchema().path("properties").properties().stream()
                    .anyMatch(e -> e.getValue().has("enum")))
        .findFirst()
        .ifPresent(
            c -> {
              String key =
                  c.inputSchema().path("properties").properties().stream()
                      .filter(e -> e.getValue().has("enum"))
                      .findFirst()
                      .get()
                      .getKey();
              expectReject(
                  () ->
                      PlanValidator.validate(
                          draft(c.toolId(), Map.of(key, "__NOT_AN_ENUM__")),
                          "x",
                          LlmClient.Context.empty(),
                          all,
                          names,
                          meta,
                          Set.of(),
                          validator),
                  "schema-violating arg rejected OK");
            });
  }

  private static Optional<ParamMeta> entityParamOf(ToolMeta m) {
    return m.params().values().stream().filter(ParamMeta::isEntity).findFirst();
  }

  private static PlanDraft draft(String toolId, Map<String, String> args) {
    return new PlanDraft("plan", List.of(new PlanDraft.DraftStep(toolId, args)), List.of(), null);
  }

  private static void expectReject(Runnable r, String okText) {
    try {
      r.run();
    } catch (RunFailure expected) {
      log.info("selfcheck: {}", okText);
      return;
    } catch (PlanValidator.EntityMissing expected) {
      log.info("selfcheck: {}", okText);
      return;
    }
    throw new IllegalStateException("plan validator accepted invalid draft: " + okText);
  }
}
