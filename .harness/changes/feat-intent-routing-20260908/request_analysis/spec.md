# Spec: feat-intent-routing-20260908

> v1 — 阶段 1 产出。用户已确认三项决策：领域路由采用「规则优先 + 模型补位 + 代码兜底」；用户未选中实体就发起需要实体的操作时「规划前拦截并提示」；并入前端与后端两组评审遗留项。

## 1. 背景

首期领域路由是纯关键词匹配（`DomainRouter.defaultRules()`：`退款 / 退钱 / 退货 / refund → refund`，`订单 / order / 物流 / 发货 → order`）。生产上两个问题已经在联调中出现：

1. **召回率低**："我想把钱要回来"、"这单不想要了" 进不了 refund 领域，用户得到"当前没有可用能力处理该请求"。
2. **缺实体上下文时拿空参数去执行**：用户直接打开 `/` 不带 `entityId` 说"退款"，规则规划器给 `refund.eligibility.check` 的 `args` 为 `{}`，Gateway 按 inputSchema 拒绝（`INPUT_INVALID`），用户看到的是"执行过程中工具调用失败，请稍后重试"，实际是缺一个订单号。

同时前两个 change 的评审留下 8 项小修（4 前端 / 4 后端），本 change 一并收口。

## 2. 范围（In Scope）

### 2.1 后端：分层领域路由（`agent-runtime`）

```
用户消息 + pageContext
  │
  ├─ 第 1 层 规则快路径     DomainRouter（现有关键词表）命中 → domain，routeSource = rule
  │
  ├─ 第 2 层 模型分类       未命中且 LLM 已配置 → IntentClassifier.classify(message, entityType?) → {domain ∈ {refund, order, none}}
  │                          输出经代码校验：必须在 KNOWN_DOMAINS ∪ {none} 内，否则视为 none；routeSource = model
  │
  └─ 第 3 层 代码兜底       none / 未配置 LLM → message.delta(NO_CAPABILITY_TEXT) + run.completed（现状不变）
```

- 新端口 `application/port/IntentClassifier`：`Optional<String> classify(String message, Optional<String> entityType)`；返回 `Optional.empty()` 表示 none。
- 实现：`infra/llm/SpringAiIntentClassifier`（结构化输出 record `{domain}`，`temperature 0`，`internalToolExecutionEnabled(false)`，Prompt 只给领域名与一句话说明，不给工具列表）；`infra/llm/NoopIntentClassifier`（无 key 时的回退，恒返回 empty，日志不重复 WARN）。
- `DomainRouter` 保持纯函数不变；新增 `application/DomainResolver` 组合两层并产出 `RouteDecision(domain, source)`，`RunOrchestrator.start` 改为调用它。`RouteDecision.source` 只进日志（`route runId=… domain=… source=rule|model|none`），**不进 SSE**（契约不变）。
- **安全边界（agent-safety §2 更新）**：分类结果只决定「去 Registry 查哪个领域的候选」；Registry 仍按 tenant / permission 过滤，Gateway 仍逐次鉴权。模型不能发明领域（枚举校验），不能跳过 Registry。`KNOWN_DOMAINS` 来自 Registry 当前已注册的领域集合（`ToolRegistryClient.domains()` 新方法），不硬编码。
- 页面上下文作为强信号：`entityType = order` 时，分类 Prompt 附带「用户正在查看一个订单」；规则层也把 `entityType` 加入匹配（`order` 实体 + 无关键词 → 不直接判定，仍交给模型；避免把"今天天气怎么样"路由进 order）。

### 2.2 后端：缺实体上下文的规划前拦截（`agent-runtime`）

- 在 `DomainResolver` 之后、`llm.plan` 之前新增 `application/EntityRequirementCheck`：取该领域候选工具的 `inputSchema.required`，若**全部**候选都要求某个实体字段（首期只有 `orderId`）而 `pageContext.selectedEntity` 缺失或 `type` 不匹配，则 `message.delta(NEED_ENTITY_TEXT[domain])` + `run.completed`，不进入规划、不调 Gateway。
- 文案表：`refund → "请先在页面上选择一个订单，再发起退款"`，`order → "请先在页面上选择一个订单"`。文案不含用户原文。
- 只有当至少一个候选工具**不需要**实体（如 `order.list.search`）时才放行进规划，由规划器自行选择不需实体的工具。
- 规则规划器 `RuleBasedLlmClient` 同步修正：`orderId` 缺失时**不生成**需要 `orderId` 的步骤（而不是生成空参数步骤）；若模板步骤全部被跳过 → `TOOL_SELECTION_INVALID`（这条在拦截之后理论不可达，作为纵深防御）。

### 2.3 后端评审遗留项（首期 change N4 / N6）

- **N6 Gateway 幂等改「先占位后填充」**：`IdempotencyStore` 增 `Optional<ToolInvoke.Response> claim(tenantId, key)`：原子放入 `PENDING` 占位；已存在且为最终结果 → 返回它（重放）；已存在且为占位 → 等待至多 `execution.timeoutMs` 后返回结果或 `TIMEOUT`。执行成功 `complete(tenantId, key, resp)`；失败 `release(tenantId, key)` 让下一次重试可执行。`InMemoryIdempotencyStore` 用 `ConcurrentHashMap<String, CompletableFuture<Response>>` 实现。并发同 key 两次调用只执行 handler 一次（自检与 e2e 证明）。
- **N4 Registry / Gateway 抽接口层**：`tool-registry` 新增 `api/ToolSearchPort`（`ToolSearch.Response search(ToolSearch.Request)`），`tool-gateway` 新增 `api/ToolInvokePort`（`ToolInvoke.Response invoke(ToolInvoke.Request)`，失败以 `Response.failed` 返回）；两个 `application` 用例分别实现；`agent-runtime` 的 `InProcess*Client` 改为只依赖这两个接口。`check-module-deps.mjs` 增加规则：runtime 不得 import `com.strato.(registry|gateway).application.`。`project-structure.md` §2「api 包公开接口」措辞恢复为字面成立，`backend-standard.md` §4 去掉「或 application 用例类（过渡）」。

### 2.4 前端评审遗留项（`@strato-ui/core` / `apps/chat` / 脚本）

- `Object.freeze(PROPS_SCHEMAS)`（与两张注册表同级保证）。
- Form 字段 schema 去重：`registry/types.ts` 的 `FormFieldPropsSchema` / `FormComponentPropsSchema` 删除，改为 `import { FormFieldSchema, FormPropsSchema } from '../schema/uiSchema'` 并 re-export 为 `FormComponentProps` 类型；`ActionBarProps` 只在 `renderer/ActionBar.tsx` 定义一处，desktop / mobile 实现 `import type` 它。公共 API 清单不变（verify-pack (c)(e) 仍 17 + 10）。
- 空标题 a11y：`SchemaRenderer` 的 `<h2>` 已受 `ui.title` 守卫；补 `apps/chat` `RouteErrorBoundary` 的 `title` 为空串时回退 `'页面出错了'`（现状 `isRouteErrorResponse` 分支可能产出 ` · ` 空白）。
- verify-pack (f) 基线缺失时**不再静默写入**：改为 `✗ baseline missing — run with --write-baseline`；新增 `--write-baseline` 参数显式写入。
- doctor 新增「L1 硬约束一致性」检查：`CLAUDE.md`、`AGENTS.md`、`.harness/agents/platform-owner.md` 三文件中 antd import 位置这一条必须包含 `packages/core/src/components/**`，且不得再出现 `shared/ui/**`（上一 change 唯一 MUST FIX 的 Hashimoto 落点）。

### 2.5 Harness

- `e2e-backend.sh` 新增 5 条用例：① 无 `pageContext` 的「退款」→ 事件恰为 `run.started message.delta run.completed`，`message.delta.text` 含「选择一个订单」，audit 行数不变（未调 Gateway）；② `entityType = order` 但消息「今天天气怎么样」→ 无能力路径（规则不误路由）；③ 真模型模式下「我想把钱要回来」+ 订单上下文 → 事件序列与首期主链路一致（`source=model` 出现在日志）；规则模式下该用例期望无能力路径并跳过（LIVE_LLM 判定）；④ 并发同 idempotencyKey 两次 `POST /internal/tool-gateway/invoke refund.create` → 审计 `status=succeeded` 恰 1 条、两次响应 `refundId` 相同；⑤ `grep -c "source=" backend.log` ≥ 1。
- `harness-doctor.mjs` 新检查如 §2.4。
- `check-module-deps.mjs` 新规则如 §2.3。
- 文档：`agent-safety.md` §2 第 1 条改为「规则优先、模型补位（只输出领域枚举、经代码校验、不参与鉴权）、代码兜底」；`wiki/architecture.md` 运行链路第 2 步与框图「领域路由（规则）」改「领域路由（规则 → 模型分类）」；`backend-standard.md` §7 增加「分类器与规划器共用同一组 LLM 环境变量」；`backed/README.md` 端点速查下补「意图分类」一节；`06-backend-module-spec.md`（若存在 agent-runtime 段）同步。

## 3. 非目标（Out of Scope）

- **不**把工具选择交给模型之外的任何"自由规划"；模型仍只在候选内选工具。
- **不**新增领域、工具、契约字段；`sse-events` / `run-summary` 零变化，`RouteDecision.source` 不进 SSE。
- **不**做多轮对话 / 追问（"你要退哪个订单？"）——首期用一次性提示 + `run.completed`。
- **不**做真实 IdP、外置存储、HTTP 适配（Registry / Gateway 仍进程内，本 change 只抽接口）。
- **不**改 7 个白名单组件视觉；**不**发包；**不**改 catalog 版本。
- **不**引入分类结果缓存 / 向量检索。

## 4. 核心场景

### 4.1 规则命中（现状不变）
「帮我把这个订单退款」+ `order/10001` → rule → refund → 候选 4 → 规划 3 步 → 确认屏。日志 `route … domain=refund source=rule`。

### 4.2 模型补位（真模型）
「我想把钱要回来」+ `order/10001` → 规则未命中 → `SpringAiIntentClassifier` → `{domain:"refund"}` → 校验在 KNOWN_DOMAINS 内 → 同 4.1。日志 `source=model`。无 key 时 → `NoopIntentClassifier` → none → 无能力路径（与首期一致）。

### 4.3 缺实体拦截
「退款」+ 无 `pageContext` → rule → refund → `EntityRequirementCheck`：4 个候选 `required` 全含 `orderId`，上下文无实体 → `message.delta("请先在页面上选择一个订单，再发起退款")` + `run.completed`。不调 Registry 之后的任何东西，audit 行数为 0。

### 4.4 模型输出越界
分类器返回 `{domain:"payment"}` → 不在 KNOWN_DOMAINS → 视为 none → 无能力路径；日志 `WARN classifier returned unknown domain`（不含用户原文）。

### 4.5 幂等并发
两个线程同时 `refund.create` 同 `idempotencyKey`：A `claim` 成功执行，B `claim` 拿到占位后等待 A 完成，两者返回同一 `Response`；handler 只执行一次；审计 A `succeeded`、B `succeeded`（重放不另记审计，与现状 `idempotent replay` 日志一致）。

## 5. 契约影响

**NONE**。9 个 schema 与 20 个示例不变。`message.delta.text` 复用现有字段。

## 6. 验收标准

### 6.1 后端
- [ ] `node .harness/scripts/mvn.mjs -q -B verify` 退出码 0；`pnpm -C .harness run check-module-deps` 0，且植入 `import com.strato.registry.application.SearchToolsUseCase` 到 runtime 任一文件 → 红。
- [ ] `grep -rn "com.strato.registry.application\|com.strato.gateway.application" backed/agent-runtime/src` 输出 0 行。
- [ ] 无 LLM 变量：`bash .harness/scripts/e2e-backend.sh` 全部通过，含新增 ①②④⑤；③ 跳过并打印 `skipped (rule mode)`。
- [ ] 有 LLM 变量：同脚本全部通过，含 ③；`grep -c "source=model" $DEPLOY/backend.log` ≥ 1。
- [ ] 用例 ①：`grep -c "audit runId=<该 runId>" backend.log` 为 0；`message.delta.text` 不含用户原文（grep 「退款」在 text 中出现是文案自身，改用 ①' 的消息「退钱」验证原文不回显）。
- [ ] 用例 ④：`grep -c 'toolId=refund.create .*status=succeeded' backend.log`（该订单）恰 1；两响应 `output.refundId` 相等。
- [ ] 新增 `IdempotencySelfCheck`（gateway 层）：并发两次同 key 调用只执行一次 handler → 启动日志 `selfcheck: gateway idempotency claim OK`；`SelfCheckRunner` 计数 4 → 5，e2e 期望同步。
- [ ] `grep -rn "System.out\|printStackTrace" backed --include=*.java` 0（沿用）。

### 6.2 前端
- [ ] `rm -rf fronted/*/dist && pnpm -C fronted run ci` 0；verify-pack 仍 17 + 10 导出。
- [ ] `grep -n "FormFieldPropsSchema\|FormComponentPropsSchema = z" fronted/packages/core/src/registry/types.ts` 0 行；`grep -rn "interface ActionBarProps" fronted/packages/core/src | wc -l` 为 1。
- [ ] `grep -n "Object.freeze(" fronted/packages/core/src/registry/types.ts` ≥ 1。
- [ ] `mv fronted/scripts/verify-pack.baseline.json /tmp && pnpm -C fronted run verify-pack` → 退出码 1 且输出含 `baseline missing`；`--write-baseline` 后恢复绿；还原文件。
- [ ] `node .harness/scripts/e2e-frontend.mjs` 21/21（前端行为不变）。

### 6.3 Harness / 文档
- [ ] `pnpm -C .harness run doctor` 0；植入 `CLAUDE.md` 硬约束行改回 `shared/ui/**` → 红，还原后绿。
- [ ] `grep -n "领域路由（规则）" .harness/wiki/architecture.md` 0 行；`agent-safety.md` §2 含「模型补位」。
- [ ] `pnpm -C .harness run ci` 四段 0。

## 7. 风险与权衡

| 风险 | 影响 | 缓解 |
|---|---|---|
| 模型分类被 prompt 注入引到高风险领域 | 拉出 `refund.create` 候选 | 分类只决定候选领域；Registry 权限过滤 + Gateway 鉴权 + confirmationToken 三层不变；枚举校验拒绝未知领域；e2e 4.4 |
| 每条未命中规则的 Run 多一跳模型调用（+3~8s） | 体验变慢 | 规则优先，命中即 0 延迟；分类 Prompt 极短（无工具列表）；`temperature 0` |
| 规则把 `entityType=order` 当强信号误路由 | "今天天气"进 order | 规则层不因实体单独判定领域；e2e ② |
| 拦截判定依赖 `inputSchema.required` 全集 | 新工具不要求实体时放行到规划，规划器仍可能选需实体工具 | 规则规划器不生成缺参步骤；真模型输出经 `ToolSelectionValidator`，缺必填由 Gateway INPUT_INVALID 兜底（现状） |
| 幂等占位在 handler 异常时未释放 | 同 key 永久 PENDING | `finally` 中 `release`；等待方超时返回 `TIMEOUT`；自检覆盖异常路径 |
| 抽接口后 Spring 装配歧义（接口只有一个实现） | 启动失败 | 接口由用例类 `implements`，构造注入按接口类型；`mvn verify` + e2e 启动即验证 |
| verify-pack 基线行为变化让 CI 首次红 | 阻塞 | 本 change 内基线文件已存在，只影响缺失场景 |

## 8. 假设（非阻塞）
- `KNOWN_DOMAINS` 取自 Registry 已注册 manifest 的 `domain` 去重集合（首期 = {order, refund}）。
- 分类器与规划器共用同一 `ChatClient` Bean 与三个环境变量。
- 需实体字段首期只识别 `orderId`；`EntityRequirementCheck` 用 `selectedEntity.type == "order"` 作为满足条件，映射表可扩展但本 change 只有一条。
