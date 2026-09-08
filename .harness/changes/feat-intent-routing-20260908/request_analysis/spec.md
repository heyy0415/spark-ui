# Spec: feat-intent-routing-20260908

> v2 — 响应 `review/spec_review_v1.md`（REVISION REQUIRED，M1–M5 / S1–S8）：M1 规则规划器 order 模板增 `order.list.search` 回退；M2 `domains(principal)` 经 DiscoveryPolicy 过滤；M3 幂等自检只测 store + latch；M4 新增种子订单 10004、审计 `replayed` 口径、结果断言；M5 check-registry 正则兼容 freeze；S1 release → completeExceptionally + 重新 claim；S2 entityType 白名单；S3 领域说明表硬编码 + 闲聊判 none；S4 用例 ③ 拆断言；S5 ActionBarProps 放 registry/types；S6 非目标补三项；S7 T04/T07 拆分；S8 IdempotencyStore 删 putIfAbsent 保留 find（自检用）。
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
- 实现：`infra/llm/SpringAiIntentClassifier`（结构化输出 record `{domain}`，`temperature 0`，`internalToolExecutionEnabled(false)`，Prompt 只给领域名与一句话说明（说明表硬编码在 runtime `application/DomainDescriptions`，Registry 返回的领域若无说明则只给名字），明示「与任何领域无关的请求（闲聊、天气、问候等）输出 none」，不给工具列表）；`infra/llm/NoopIntentClassifier`（无 key 时的回退，恒返回 empty，日志不重复 WARN）。
- `DomainRouter` 保持纯函数不变；新增 `application/DomainResolver` 组合两层并产出 `RouteDecision(domain, source)`，`RunOrchestrator.start` 改为调用它。`RouteDecision.source` 只进日志（`route runId=… domain=… source=rule|model|none`），**不进 SSE**（契约不变）。
- **安全边界（agent-safety §2 更新）**：分类结果只决定「去 Registry 查哪个领域的候选」；Registry 仍按 tenant / permission 过滤，Gateway 仍逐次鉴权。模型不能发明领域（枚举校验），不能跳过 Registry。`KNOWN_DOMAINS` 来自 Registry：`ToolRegistryClient.domains(principal)` → Registry 侧 `repo` 全部 manifest 经 `DiscoveryPolicy.filter(…, perms)` 后取 `domain` 去重。**带 principal**（agent-safety §2 红线），即「该用户可见的领域集合」；无权领域对该用户直接判 none，不再多一跳 search。
- 页面上下文只作为**分类提示**：`selectedEntity.type` 落在实体白名单（首期 `{order}`）时，分类 Prompt 附带「用户正在查看一个订单」；不在白名单则忽略；规则层**不**使用 entityType（避免把"今天天气怎么样"路由进 order）。pageContext 仍不参与任何鉴权决策（agent-safety §4）。

### 2.2 后端：缺实体上下文的规划前拦截（`agent-runtime`）

- 在 `DomainResolver` 之后、`llm.plan` 之前新增 `application/EntityRequirementCheck`：取该领域候选工具的 `inputSchema.required`，若**全部**候选都要求某个实体字段（首期只有 `orderId`）而 `pageContext.selectedEntity` 缺失或 `type` 不匹配，则 `message.delta(NEED_ENTITY_TEXT[domain])` + `run.completed`，不进入规划、不调 Gateway。
- 文案表：`refund → "请先在页面上选择一个订单，再发起退款"`，`order → "请先在页面上选择一个订单"`。文案不含用户原文。
- 只有当至少一个候选工具**不需要**实体（如 `order.list.search`）时才放行进规划，由规划器选择不需实体的工具。
- 规则规划器 `RuleBasedLlmClient` 同步修正：模板改为**按实体有无二选一**——refund：有 `orderId` → 三步（现状）；无 → 不可达（refund 四个工具全需 `orderId`，已被拦截）。order：有 `orderId` → `order.detail.get`；无 → `order.list.search`（args `{}`）。任一步骤缺必填实体则**不生成**该步；模板全部被跳过 → `TOOL_SELECTION_INVALID`（纵深防御，refund 因拦截不可达，order 因 `order.list.search` 回退不可达）。
- 拦截路径 Run 终态 = `COMPLETED`，`GET /agent/runs/{runId}` 无 `failureCode`；SSE 事件顺序与现有无能力路径完全一致。

### 2.3 后端评审遗留项（首期 change N4 / N6）

- **N6 Gateway 幂等改「先占位后填充」**：`IdempotencyStore` 接口重定义为 `Claim claim(tenantId, key)`（`Claim` 为 sealed：`Owner`（本次拿到执行权）/ `Replay(response)`（已有最终结果）/ `Awaiting(future)`（他人执行中））、`complete(tenantId, key, resp)`、`release(tenantId, key)`、保留 `find(tenantId, key)` 供自检断言终态；**删除** `putIfAbsent`（无外部实现者）。`InMemoryIdempotencyStore` 用 `ConcurrentHashMap<String, CompletableFuture<Response>>`。`InvokeToolUseCase` 第 4 步：`Owner` → 执行，成功 `complete`，任何异常 `finally release`；`Replay` → 直接返回，审计 `status=replayed`（仅日志口径，非契约）；`Awaiting` → 等待至多 `execution.timeoutMs`：正常完成 → 返回并审计 `replayed`；`release` 触发的 `completeExceptionally` → **重新 claim 一次**（此时能拿到 `Owner` 自己执行）；超时 → `TIMEOUT`。自检 `GatewayIdempotencySelfCheck` **只测 `IdempotencyStore`**（不经 `InvokeToolUseCase`，避免 handler 表注入问题）：线程 A claim → Owner，`CountDownLatch` 保证线程 B 在 A `complete` 前 claim → Awaiting；A complete 后 B 拿到同一 Response；另一 key：A claim 后 `release`，B 的 Awaiting 收到异常后重新 claim → Owner。日志 `selfcheck: gateway idempotency claim OK`；`SelfCheckRunner` 计数 4 → 5，`deploy-verify.sh` 的期望 4 同步改 5。
- **N4 Registry / Gateway 抽接口层**：`tool-registry` 新增 `api/ToolSearchPort`（`ToolSearch.Response search(ToolSearch.Request)`），`tool-gateway` 新增 `api/ToolInvokePort`（`ToolInvoke.Response invoke(ToolInvoke.Request)`，失败以 `Response.failed` 返回）；两个 `application` 用例分别实现；`agent-runtime` 的 `InProcess*Client` 改为只依赖这两个接口。`check-module-deps.mjs` 增加规则：runtime 不得 import `com.strato.(registry|gateway).application.`。`project-structure.md` §2「api 包公开接口」措辞恢复为字面成立，`backend-standard.md` §4 去掉「或 application 用例类（过渡）」。

### 2.3b 种子数据
- `order-service` `InMemoryOrderRepository` 与 `refund-service` `SeededOrderLookup` 各新增订单 `10004`（PAID，`59.00`，「幂等验收专用商品」），专供 e2e 幂等并发用例；10001 / 10002 / 10003 用途不变。

### 2.4 前端评审遗留项（`@strato-ui/core` / `apps/chat` / 脚本）

- `Object.freeze(PROPS_SCHEMAS)`（与两张注册表同级保证）；`scripts/check-registry.mjs` 的 `PROPS_SCHEMAS` 正则改为与 `keysOf` 同款 `(?:Object\.freeze\()?\{…\}(?: as const)?\)?;`，两种写法都可解析（验收含负例：改回非 freeze 形态仍绿）。
- Form 字段 schema 去重：`registry/types.ts` 的 `FormFieldPropsSchema` / `FormComponentPropsSchema` 删除，改为 `import { FormFieldSchema, FormPropsSchema } from '../schema/uiSchema'` 并 re-export 为 `FormComponentProps` 类型；`ActionBarProps` 只在叶子模块 `registry/types.ts` 定义一处（与 `RenderedComponentProps` 同处），renderer 与 desktop / mobile 实现都 `import type` 它，避免 renderer ↔ components 类型环；`index.ts` 的 `export type { ActionBarProps }` 改路径。公共 API 清单不变（verify-pack (c)(e) 仍 17 + 10）。
- 空标题 a11y：`SchemaRenderer` 的 `<h2>` 已受 `ui.title` 守卫；补 `apps/chat` `RouteErrorBoundary` 的 `title` 为空串时回退 `'页面出错了'`（现状 `isRouteErrorResponse` 分支可能产出 ` · ` 空白）。
- verify-pack (f) 基线缺失时**不再静默写入**：改为 `✗ baseline missing — run with --write-baseline`；新增 `--write-baseline` 参数显式写入（`pnpm run verify-pack -- --write-baseline` 透传后 argv 含 `--`，实现用 `process.argv.includes`）。
- doctor 新增「L1 硬约束一致性」检查：`CLAUDE.md`、`AGENTS.md`、`.harness/agents/platform-owner.md` 三文件中 antd import 位置这一条必须包含 `packages/core/src/components/**`，且不得再出现 `shared/ui/**`（上一 change 唯一 MUST FIX 的 Hashimoto 落点）。

### 2.5 Harness

- `e2e-backend.sh` 新增用例（用例 ①②④⑤⑥ 两种模式都跑；③ 仅 LIVE）：
  ① 无 `pageContext` 的「退钱」→ 事件恰为 `run.started message.delta run.completed`；`message.delta.text` 含「选择一个订单」且不含「退钱」（原文不回显）；`grep -c "audit runId=<该 runId>"` 为 0；`GET /agent/runs/{runId}` `state == COMPLETED` 且无 `failureCode`。
  ② `entityType = order` + 「今天天气怎么样」→ 事件恰为无能力路径三件。LIVE 模式下此断言依赖模型判 none（spec §7 承认；失败视为 Prompt 缺陷）。
  ③（LIVE 才跑，否则打印 `skipped (rule mode)`）「我想把钱要回来」+ `order/10001` → ③a `grep "route runId=<runId> domain=refund source=model"` 恰 1；③b 事件序列 == 首期主链路发起段。
  ④ 订单 10004：两次 `POST /internal/tool-gateway/invoke refund.create`，同 `idempotencyKey=e2e-idem-10004`、`runId=run_e2eidem`、不同 `toolCallId`（`tc_idem1` / `tc_idem2`），第二次紧随第一次（顺序即可，不要求并发）→ 两响应 `output.refundId` 相等；`grep -c 'runId=run_e2eidem .*status=succeeded'` 恰 1；`grep -c 'runId=run_e2eidem .*status=replayed'` 恰 1；`refund.status.get 10004` `refunds.length == 1`。
  ⑤ `grep -c "route runId=.* source=" backend.log` ≥ 3（每条 Run 一行）。
  ⑥ 「订单」+ 无 `pageContext`（规则模式）→ 事件含 `tool.selected`（`order.list.search`）且到 `run.completed`，不出现 `run.failed`（M1 的 order 回退）。
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
- **不**新增 `RunFailureCode` / `RunState` 枚举值；**不**改 `NO_CAPABILITY_TEXT` 与现有 `userMessage` 文案；**不**改任何路径的 SSE 事件顺序。

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
两次 `refund.create` 同 `idempotencyKey`（订单 10004）：A `claim → Owner` 执行；B `claim → Awaiting` 等 A 完成后拿到同一 `Response`；handler 只执行一次。审计：A `status=succeeded`，B `status=replayed`；`refund.status.get 10004` 恰 1 条。路径级断言（阻塞、重新 claim）由自检用 latch 确定性证明，e2e 只断言结果。

## 5. 契约影响

**NONE**。9 个 schema 与 20 个示例不变。`message.delta.text` 复用现有字段。

## 6. 验收标准

### 6.1 后端
- [ ] `node .harness/scripts/mvn.mjs -q -B verify` 退出码 0；`pnpm -C .harness run check-module-deps` 0，且植入 `import com.strato.registry.application.SearchToolsUseCase` 到 runtime 任一文件 → 红。
- [ ] `grep -rn "com.strato.registry.application\|com.strato.gateway.application" backed/agent-runtime/src` 输出 0 行。
- [ ] 无 LLM 变量：`bash .harness/scripts/e2e-backend.sh` 全部通过，含新增 ①②④⑤⑥；③ 跳过并打印 `skipped (rule mode)`；`selfcheck all OK` 期望 5。
- [ ] 有 LLM 变量：同脚本全部通过，含 ③；`grep -c "source=model" $DEPLOY/backend.log` ≥ 1。
- [ ] 用例 ①：`grep -c "audit runId=<该 runId>" backend.log` 为 0；`message.delta.text` 含「选择一个订单」且不含「退钱」；summary `COMPLETED` 无 `failureCode`。
- [ ] 用例 ④：按 `runId=run_e2eidem` 过滤，`status=succeeded` 恰 1、`status=replayed` 恰 1；两响应 `output.refundId` 相等；`refund.status.get 10004` 恰 1 条。
- [ ] 用例 ⑥：「订单」无上下文 → `tool.selected` 的 `toolId == order.list.search`，终态 `run.completed`。
- [ ] 新增 `GatewayIdempotencySelfCheck`（只测 store，latch 控时序）：Owner / Awaiting → 同 Response；release → 重新 claim 为 Owner；启动日志 `selfcheck: gateway idempotency claim OK`；`SelfCheckRunner` `running 5 checks`；`deploy-verify.sh` 与 `e2e-backend.sh` 的 selfcheck 期望改 5。
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
| 幂等占位在 handler 异常时未释放 / 等待方白等 | 同 key 永久 PENDING 或等满 timeout | `finally release` 且 `completeExceptionally` 唤醒等待方，等待方重新 claim；自检用 latch 覆盖两条路径 |
| 用例 ② ③ 在 LIVE 模式依赖模型判断 | 偶发红 | Prompt 明示 none 语义、`temperature 0`；③ 拆为分类断言 + 链路断言以定位层次；失败按 Prompt 缺陷处理 |
| 抽接口后 Spring 装配歧义（接口只有一个实现） | 启动失败 | 接口由用例类 `implements`，构造注入按接口类型；`mvn verify` + e2e 启动即验证 |
| verify-pack 基线行为变化让 CI 首次红 | 阻塞 | 本 change 内基线文件已存在，只影响缺失场景 |

## 8. 假设（非阻塞）
- `KNOWN_DOMAINS` 取自 Registry 已注册 manifest 的 `domain` 去重集合（首期 = {order, refund}）。
- 分类器与规划器共用同一 `ChatClient` Bean 与三个环境变量。
- 需实体字段首期只识别 `orderId`；`EntityRequirementCheck` 用 `selectedEntity.type == "order"` 作为满足条件，映射表可扩展但本 change 只有一条。
