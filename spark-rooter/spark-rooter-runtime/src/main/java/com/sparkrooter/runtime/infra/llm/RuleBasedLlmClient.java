package com.sparkrooter.runtime.infra.llm;

import com.sparkrooter.contracts.model.ToolSearch;
import com.sparkrooter.runtime.application.ArgumentExtractor;
import com.sparkrooter.runtime.application.ToolDisplayNames;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.domain.Plan;
import com.sparkrooter.runtime.domain.RunFailure;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 无 key 时的确定性回退（spec §2.4.3）：动词表定目标工具、前置表补只读步骤、参数从已识别实体填。 目标不在候选 →
 * TOOL_SELECTION_INVALID（如无权用户说「删除」）；动词命中但目标必填实体缺失 → MissingEntity（编排器走友好提示）。 不理解自然语言，只保证链路确定、可验收。
 */
public final class RuleBasedLlmClient implements LlmClient {

  private final ToolDisplayNames displayNames;
  private final ToolMetaRegistry meta;
  private final Clock clock;

  public RuleBasedLlmClient(ToolDisplayNames displayNames, ToolMetaRegistry meta, Clock clock) {
    this.displayNames = displayNames;
    this.meta = meta;
    this.clock = clock;
  }

  @Override
  public Plan plan(PlanRequest req) {
    Map<String, ToolSearch.ToolCandidate> available = new LinkedHashMap<>();
    req.candidates().forEach(c -> available.put(c.toolId(), c));

    ToolSelectionValidator.preflight(
        req.message(), req.domain(), req.candidates(), req.entities(), meta);
    // 目标工具：动词命中优先；否则按「有该领域实体 → detail，无 → list」
    String target =
        IntentVerbs.target(req.message(), req.domain())
            .orElseGet(
                () ->
                    IntentVerbs.fallbackTarget(
                        req.domain(), req.entities().containsKey(req.domain())));
    if (!available.containsKey(target)) {
      throw new RunFailure("TOOL_SELECTION_INVALID", "target tool not in candidates: " + target);
    }

    List<String> order = new ArrayList<>(meta.prerequisites(target));
    order.add(target);
    List<LlmPlanDraft.DraftStep> steps = new ArrayList<>();
    for (String toolId : order) {
      ToolSearch.ToolCandidate c = available.get(toolId);
      if (c == null) {
        throw new RunFailure("TOOL_SELECTION_INVALID", "prerequisite not in candidates: " + toolId);
      }
      steps.add(
          new LlmPlanDraft.DraftStep(
              toolId, argsFor(c, req.message(), req.entities(), meta, clock)));
    }
    return ToolSelectionValidator.validate(
        new LlmPlanDraft(steps),
        req.domain(),
        req.candidates(),
        displayNames,
        req.entities(),
        meta);
  }

  /**
   * 参数三层（spec §2.6）：① 实体类参数（orderId / productId）从已识别实体填（可选参数也填，如 aftersale.list.get 的 orderId），必填缺失
   * → MissingEntity；② 枚举别名 / 数量 / 相对时间由 ArgumentExtractor 从消息抽取；③ 其余有 @SparkDefault 的参数填默认值（写进 args
   * 使 argsDigest 确定）。非实体类必填参数（如 refund.create 的 amount / reason）由确认屏 Form 与重校验填，规划器不填。
   */
  static Map<String, String> argsFor(
      ToolSearch.ToolCandidate c,
      String message,
      Map<String, String> entities,
      ToolMetaRegistry meta,
      Clock clock) {
    Map<String, String> args = new LinkedHashMap<>();
    ToolMetaRegistry.ToolMeta tm = meta.find(c.toolId()).orElse(null);
    if (tm != null) {
      args.putAll(ArgumentExtractor.extractArgs(message, tm, clock));
      tm.params()
          .values()
          .forEach(
              p -> {
                if (p.defaultValue() != null) {
                  args.putIfAbsent(p.name(), p.defaultValue());
                }
              });
    }
    java.util.Set<String> required = new java.util.HashSet<>();
    c.inputSchema().path("required").forEach(n -> required.add(n.asText()));
    c.inputSchema()
        .path("properties")
        .fieldNames()
        .forEachRemaining(
            arg -> {
              String type = meta.entityTypeOf(arg);
              if (type == null) {
                return;
              }
              String id = entities.get(type);
              if (id != null) {
                args.put(arg, id);
              } else if (required.contains(arg)) {
                throw new MissingEntity(type);
              }
            });
    return args;
  }

  @Override
  public String name() {
    return "rule-based";
  }
}
