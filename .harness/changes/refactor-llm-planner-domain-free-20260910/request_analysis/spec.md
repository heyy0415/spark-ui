# Spec: refactor-llm-planner-domain-free-20260910

> 用户决策：内核**完全不含领域知识**，由大模型读 `@SparkTool` / `@SparkParam` 的上下文理解意图、选工具、填参数；**不配模型就不执行，不做规则兜底**。e2e 暂不跑（用户明示），以编译 + 自检 + 手工联调验收。

## 1. 背景

runtime 里有一批写死的领域知识：`DomainRouter` 关键词表、`IntentVerbs` 动词表 / 前置表 / 标签表、`ArgumentExtractor` 的实体正则 / 别名 / 数量 / 时间、`EntityRequirementCheck` / `ClarificationScreen` / `DomainDescriptions` 的中文文案、`PromptBuilder.system()` 里手写的领域动词映射、`InlineActionSelfCheck.LABEL_VERB`。它们让「宿主新增一个领域」必须改内核，也让「10030查看物流」这类写法一次一个坑。

## 2. 范围

### 2.1 spi 注解
- `EntityType` 枚举 → **删除**。`@SparkParam.entity` / `@SparkTool.clarifiesEntity` 改为 `String`（宿主自定义实体类型名，如 `"order"` / `"coupon"`；空串 = 无）。
- `@SparkParam` 增 `pattern`（实体 ID 格式正则，给复核用）、`label`（实体 / 参数的中文名，给澄清屏按钮拼文案）。
- `@SparkTool` 增 `verbs`（可选，给模型的同义动词提示，如 `{"物流","快递","到哪了"}`；纯提示，不进任何规则表）。

### 2.2 runtime：删除
`DomainRouter`、`DomainResolver`、`DomainDescriptions`、`IntentVerbs`、`ArgumentExtractor`（含数量 / 时间 / 别名）、`EntityRequirementCheck`、`RuleBasedLlmClient`、`IntentClassifier` 端口及两个实现、`ToolSelectionValidator.preflight`、`PromptBuilder` 里的领域动词段、`ToolMetaRegistry` 的内核默认表参数、`ClarificationScreen.label()`、`InlineActionSelfCheck.LABEL_VERB` 的领域词、`PlanSelfCheck` 的规则规划用例与 IntentVerbs 引用。

### 2.3 runtime：新增 / 改造
- **`LlmPlanner`**（替代 `LlmClient` 实现）：一次结构化调用，输入 = 全部可发现候选（不先按领域筛）+ 会话上下文 + 用户原话；输出：
  ```json
  { "action": "plan|clarify|none",
    "steps": [{ "toolId": "...", "args": { "k": "v" } }],
    "missing": [{ "entity": "order", "reason": "..." }],
    "reply": "给用户的一句话（clarify / none 时）" }
  ```
  system prompt 只含通用规则（候选内选择、schema 内填参、实体值必须出自原话或上下文、前置排前、不填可信参数、不确定就 clarify），零领域词。候选描述里的 `description / verbs / label / entity` 就是模型的全部领域知识。
- **`PlanValidator`**（原 `ToolSelectionValidator` 去 preflight）：① toolId ∈ 候选；② 键 ⊆ schema、值过 JSON Schema；③ **实体复核**：`entity` 参数的值必须在「用户原话 ∪ 记忆实体值 ∪ lastTable.rowIds」中出现，且匹配 `@SparkParam.pattern`（有则校）；不满足 → 转 `missing`；④ 需确认步骤前置齐全、可信参数不许填；⑤ `@SparkDefault` 补齐。校验失败（①②④）把错误喂回模型重试一次。
- **`RunOrchestrator.start`**：会话装载 → 候选发现（`registry.search(all)`，Request.domain 改为可空 = 全部）→ 模型规划 → 校验 → `clarify` / `none` / 执行。序数指代（「第二个」）不再由内核正则解析：把 `lastTable.rowIds` 作为上下文给模型，模型填出具体 ID，复核时 ID 在 rowIds 内即通过。
- **`ClarificationScreen`**：按钮文案 = 模型 `reply` 中的动词短语不可靠，改为 `@SparkParam.label` + 候选源工具 `name`（如「售后 · 订单 10029」）；intent = 「<用户原话> <label> <id>」（原话经 sanitize 去 `<` / `://` 并截断，id 永远在尾部保证不被截）。
- **无模型**：`LlmFactory` 缺配置 → starter 启动 **WARN 并注册 `UnavailablePlanner`**：任何 `/agent/runs` 直接 `run.failed{INTERNAL_ERROR, "未配置模型，无法理解请求"}`。不做任何规则规划。自检 `PlanSelfCheck` 在无模型时只跑校验器用例。
- **Registry**：`ToolSearch.Request.domain` 改可选；`search(null)` 返回全部可发现工具。契约 `tool-search.schema.json` 的 `required: ["domain"]` 改为 `[]`（契约先改）。

### 2.4 契约
- `tool-search.request.domain` 改可选。示例不变仍带 domain。`contracts.md` §5a 追加一行。

### 2.5 示例领域（注解补语义）
- `OrderTools` 等：`entity = "order", pattern = "\\d{5}", label = "订单"`；`entity = "product", pattern = "P-\\d{4}", label = "商品"`；`clarifiesEntity = "order"` / `"product"`；`@SparkTool(verbs = {...})` 给每个工具补同义动词。
- 领域 `ScreenBuilder` 不变。

### 2.6 门禁
- `check-module-deps` 增红线：平台 7 模块源码禁 `订单|商品|退款|售后|物流|order\.|product\.|refund\.|aftersale\.`（正则，排除注释以外的位置无法区分，全文匹配）。
- e2e / deploy-verify 暂不改（用户明示不跑）；自检名单：删 `plan N messages OK` / `intent verbs reference registered tools OK` / `foreign entity arg` 等规则模式用例，保留校验器用例。

## 3. 非目标
- 不做规则兜底；不做多轮追问以外的对话管理；不做工具向量检索（12 个工具直接全给）。

## 4. 验收
- `mvn -q install` 0；`host-demo` 启动（无模型）日志 WARN「未配置模型」，`/agent/runs` 返回 `run.failed INTERNAL_ERROR`。
- 配 `SPARK_LLM_*` 后手工联调：「看看我的订单」「10030查看物流」「第二个的物流」「申请售后」（澄清）→ 点选、「删除订单 10010」→ 确认 → 执行；观察 `planner=llm`。
- 平台模块 grep 领域词 0（`check-module-deps` 守）；宿主新增领域不改内核。

## 5. 风险
- 时延：每轮一次模型调用（本端点约 10～40 秒）。
- 不确定性：同句两次可能不同；temperature 0 + 结构化输出 + 校验器收敛。
- e2e 未跑：意图类回归靠手工。报告明确标注。
