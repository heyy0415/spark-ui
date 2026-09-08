# Spec Review v2 — feat-intent-routing-20260908

| 字段 | 值 |
|---|---|
| mode | plan |
| 日期 | 2026-09-08 |
| 轮次 | 2 |
| 评审对象 | `request_analysis/spec.md`（v2）、`request_analysis/tasks.md`（v2） |
| 依据 | `review/spec_review_v1.md`（逐条核对闭环）；`rules/{agent-safety,backend-standard,project-structure,dev-workflow}.md`；`skills/expert-reviewer/SKILL.md` plan 必查 6 项；现状代码（`tool-gateway` IdempotencyStore / InMemoryIdempotencyStore / InvokeToolUseCase、`tool-registry` SearchToolsUseCase / DiscoveryPolicy / ToolRegistryRepository / InMemoryToolRegistryRepository、`agent-runtime` RunOrchestrator / RuleBasedLlmClient / ToolSelectionValidator / LlmConfiguration、`app` SelfCheckRunner / InMemoryPrincipalPermissionResolver、6 份 manifest、`order-service` / `refund-service` 种子与 handler）；脚本 `e2e-backend.sh` / `deploy-verify.sh` / `check-module-deps.mjs` / `fronted/scripts/check-registry.mjs`；前端 `runView.ts` / `AgentChatPanel.tsx` / `registry/types.ts` / `index.ts` |
| 实测 | (a) sealed 接口 + record + 模式 switch 在 JDK 21.0.11、`javac --release 21 -Xlint:all -Werror` 下编译通过；(b) v2 提议的 `PROPS_SCHEMAS` 正则对 `= {…} as const;`、`= Object.freeze({…} as const);`、`= Object.freeze({…});` 三种形态均解析出键；(c) ajv 2020：`order.list.search.inputSchema` 对 `{}` 返回 valid，对 `{orderId:"1"}` 返回 invalid（`additionalProperties:false`） |
| 独立性 | 未阅读任何编码 Agent 自评；只看产出物与仓库现状 |

**verdict：APPROVED** — MUST FIX 0 条，SHOULD 6 条（S-1 … S-6），LOW 7 条，INFO 7 条。

v1 的 5 条 MUST FIX 在 v2 中全部闭环且方案经现状代码 / 实测验证可行；8 条 SHOULD 全部吸收。剩余意见集中在 N6 幂等并发路径的两处边界措辞、T03 的一处输入遗漏、⑥ 在 LIVE 模式的期望、以及 v1 LOW 有 4 条未动。均可在阶段 3 开工前以 spec/tasks v2.1 小修落地（不需再开评审轮次），并在 coding_report 中标注。

---

## 一、上轮意见闭环核对

| # | v1 分级 | v1 要点 | v2 落点（引用） | 结论 |
|---|---|---|---|---|
| M1 | MUST | order 领域拦截失效，规则规划器全跳过 → `TOOL_SELECTION_INVALID` | spec §2.2「order：有 `orderId` → `order.detail.get`；无 → `order.list.search`（args `{}`）」「refund 因拦截不可达，order 因 `order.list.search` 回退不可达」；§2.5 ⑥；§6.1 用例 ⑥；tasks T04b | ✓ 闭环（实测 `{}` 通过 inputSchema） |
| M2 | MUST | `domains()` 不带 principal | spec §2.1「`ToolRegistryClient.domains(principal)` → `repo` 全部 manifest 经 `DiscoveryPolicy.filter(…, perms)` 后取 `domain` 去重。**带 principal**」；tasks T03「接口 Javadoc 写明『发现面两个只读查询均按 principal 过滤』」 | ✓ 闭环；但 §8 假设 1 未同步（L-1），repo 缺 `findAll` 未列入 task（S-3） |
| M3 | MUST | 自检无法注入人工 handler；4→5 漏改 `deploy-verify.sh` | spec §2.3「自检 **只测 `IdempotencyStore`**…`CountDownLatch`…`deploy-verify.sh` 的期望 4 同步改 5」；§6.1 倒数第 2 条；tasks T02「`deploy-verify.sh:29`」 | ✓ 闭环；「`e2e-backend.sh` selfcheck 期望 4 → 5」措辞仍不对应脚本机制（S-6） |
| M4 | MUST | ④ 无可退订单 / 重放审计 2 行 / 并发不可保证 / 无法按订单过滤 | spec §2.3b 种子 10004；§2.3「`Replay` → 审计 `status=replayed`」；§2.5 ④「顺序即可，不要求并发」「`runId=run_e2eidem`…不同 `toolCallId`」；§4.5「路径级断言由自检用 latch 确定性证明，e2e 只断言结果」；§6.1 ④ | ✓ 闭环 |
| M5 | MUST | `Object.freeze` 让 `check-registry` 红 | spec §2.4「正则改为与 `keysOf` 同款 `(?:Object\.freeze\()?\{…\}(?: as const)?\)?;`…验收含负例」；tasks T05 输入含 `fronted/scripts/check-registry.mjs`，验收含负例 | ✓ 闭环（实测三形态可解析） |
| S1 | SHOULD | `release` 后等待方白等 | spec §2.3「`release` 触发的 `completeExceptionally` → 重新 claim 一次」；§7 倒数第 3 行；自检覆盖 release → 重新 claim | ✓ 闭环；「一次」的边界见 S-1 |
| S2 | SHOULD | 规则层 entityType 措辞矛盾；进 Prompt 前白名单 | spec §2.1「落在实体白名单（首期 `{order}`）时…不在白名单则忽略；规则层**不**使用 entityType」；tasks T03 | ✓ 闭环 |
| S3 | SHOULD | 领域说明来源；闲聊判 none；② LIVE 不稳 | spec §2.1「说明表硬编码在 runtime `application/DomainDescriptions`…明示闲聊输出 none」；§2.5 ②「LIVE 模式下此断言依赖模型判 none（spec §7 承认）」；§7 倒数第 2 行 | ✓ 闭环 |
| S4 | SHOULD | ③ 分类与主链路绑死 | spec §2.5 ③a / ③b；tasks T07b | ✓ 闭环 |
| S5 | SHOULD | `ActionBarProps` 收敛到 renderer 成环 | spec §2.4「只在叶子模块 `registry/types.ts` 定义一处…`index.ts` 改路径」；tasks T05 | ✓ 闭环（`registry/types.ts` → `schema/uiSchema.ts` → zod，无环） |
| S6 | SHOULD | 非目标漏三项；拦截终态未写 | spec §3 末条「不新增 `RunFailureCode` / `RunState`…不改 `NO_CAPABILITY_TEXT`…不改任何路径的 SSE 事件顺序」；§2.2「Run 终态 = `COMPLETED`…无 `failureCode`」；§2.5 ①「`state == COMPLETED`」 | ✓ 闭环 |
| S7 | SHOULD | T04 / T07 超 0.5 天 | tasks T04a / T04b、T07a / T07b，共 11 task | ✓ 闭环 |
| S8 | SHOULD | `putIfAbsent` / `find` 取舍 | spec §2.3「保留 `find(tenantId, key)` 供自检断言终态；**删除** `putIfAbsent`」；tasks T02 验收 `grep -n putIfAbsent … 0 行` | ✓ 闭环 |
| L1 | LOW | ⑤ `source=` 过泛 | spec §2.5 ⑤ `grep -c "route runId=.* source="` | ✓ 闭环 |
| L2 | LOW | T01 验收 grep 靠大小写 | tasks T01 验收仍为 `grep -rn "\.application\." … \| grep "registry\|gateway"` | ✗ 未闭环（L-2） |
| L3 | LOW | WARN 不含模型原始输出 | spec §4.4「不含用户原文」；T03「WARN 不含原文」——未明示「亦不含模型原始输出」 | △ 部分（L-2） |
| L4 | LOW | 「若存在」两处都是必改 | spec §2.5「`06-backend-module-spec.md`（若存在 agent-runtime 段）」；T08「`backed/agent-runtime/README.md`（若存在）、`06-backend-module-spec.md`（若含 agent-runtime 段）」——两文件实测均存在（`backed/agent-runtime/README.md` 1869 B；`.harness/skills/coding-skill/specs/06-backend-module-spec.md:41`「## Agent Runtime 专项」） | ✗ 未闭环（L-2） |
| L5 | LOW | doctor 第一条负例 | tasks T07a 验收只有「改回 `shared/ui/**` → 红」 | ✗ 未闭环（L-2） |
| I1–I4 | INFO | Form 去重安全 / `--write-baseline` argv / Spring 装配 / `classifierMs` | I2 已体现在 spec §2.4「argv 含 `--`，实现用 `process.argv.includes`」；I3 体现在 §7 末行；I4 未采纳（可选） | — |

闭环统计：MUST 5/5，SHOULD 8/8，LOW 1/5（L1 ✓；L2 / L4 / L5 未动，L3 部分）。

---

## 二、plan 模式必查 6 项

| # | 必查项 | 结论 | 备注 |
|---|---|---|---|
| 1 | 「非目标」章节存在且非空 | ✓ | §3 共 7 条，含 v1 S6 补入的三项 |
| 2 | 每条验收标准都可被命令或断言校验 | ✓（有条件） | §6 全部为 grep / curl / 退出码；⑥ 在 LIVE 模式的 `toolId == order.list.search` 依赖模型行为但 spec 未承认（S-4）；「`e2e-backend.sh` selfcheck 期望改 5」在脚本里没有落点（S-6） |
| 3 | 风险章节 ≥1 失败模式与缓解 | ✓ | §7 共 8 行；幂等一行的缓解「等待方重新 claim」需补边界（S-1、S-2） |
| 4 | task 标注所属端；contracts task 排前 | ✓ | 11/11 有所属端；§5 NONE，无 contracts task |
| 5 | 跨端结构 task 列出契约文件 | ✓ | 无新跨端结构；§3 已封死 `RunFailureCode` / SSE 顺序 |
| 6 | 每个 task ≤ 0.5 天 | ✓（T03 偏满） | T03 = 4 新类 + 4 改动 + Registry 侧 `domains` 实现 + `ChatClient` 抽 Bean；建议把 Registry 侧部分前移到 T01（S-3） |

依赖图：T01→T03→T04a→T04b→T07b；T02→T07b；T07a、T05、T06、T07b→T08→T09。无环。文字依赖与图有一处不一致（L-7）。

---

## 三、新意见（只评 v2 增量）

### SHOULD

#### S-1 — `Awaiting` 收到异常后「重新 claim 一次」的结果未穷尽；应改为有 deadline 的循环而非固定一次
- **位置**：spec §2.3 N6「`release` 触发的 `completeExceptionally` → **重新 claim 一次**（此时能拿到 `Owner` 自己执行）」；tasks T02「Awaiting 异常重新 claim 一次」
- **问题**：「此时能拿到 Owner」是对并发的乐观假设。A 失败 `release` 后，B 与第三方 C 同时被唤醒并 claim，只有一个拿到 `Owner`，另一个再次拿到 `Awaiting`。spec 没有定义第二次 `Awaiting` 的行为；按字面「一次」实现，编码 Agent 只能选择抛 `INTERNAL`、返回 `TIMEOUT` 或再等一次——三者语义都不在 spec 里。`Claim` 是 sealed 三分支，switch 必须穷尽，这个分支不可能被「忘掉」，只能被「拍脑袋」。
- **建议**：把「重新 claim 一次」改为「以首次进入第 4 步的时刻起算一个 deadline（= `execution.timeoutMs`），循环：`claim` → `Owner` 执行 / `Replay` 返回 / `Awaiting` 等到 future 完成或 deadline；future 正常完成 → 返回并审计 `replayed`；异常完成 → 回到循环头；deadline 到 → `TIMEOUT`」。这样不存在「第 N 次」的特殊分支，自检用例「release → 重新 claim 为 Owner」仍成立。§7 对应行同步。
- **分级**：SHOULD

#### S-2 — 「成功 `complete`，任何异常 `finally release`」按字面实现会把已完成的占位一并删掉，重放路径失效
- **位置**：spec §2.3 N6「`Owner` → 执行，成功 `complete`，任何异常 `finally release`」；tasks T02「`release` = `completeExceptionally` + remove」
- **问题**：`release` 定义为「remove」。若编码为 `try { … complete(); return resp; } finally { release(); }`，成功路径也会走 `release`，把刚 `complete` 的 future 从表里移走 → 第二次同 key 调用拿到 `Owner` 再次执行 handler。e2e ④ 能抓到（`status=succeeded` 变 2、`replayed` 为 0），但它是本 change 最容易写错的一行，spec 措辞「任何异常 finally」恰好诱导这种写法。
- **建议**：spec 写成「`release` 只在**未** `complete` 的异常退出路径调用（`catch` 中调用，或 `finally` 中以 `completed` 标志守卫）；`release` 对已 `complete` 的 key 必须是 no-op」，并把「complete 后 release 不得抹掉结果」作为自检第三条断言（`complete` → `release` → `find` 仍返回 Response）。
- **分级**：SHOULD

#### S-3 — `domains(principal)` 的 Registry 侧实现需要 `ToolRegistryRepository` 新增全量读取，T03 输入 / 输出未列；且更适合放在 T01
- **位置**：spec §2.1「`repo` 全部 manifest 经 `DiscoveryPolicy.filter(…, perms)`」；tasks T03 输入 / 输出
- **问题**：`ToolRegistryRepository`（`tool-registry/domain/ToolRegistryRepository.java`）只有 `putIfAbsent / find / findByDomain / findVersions`，没有「全部 manifest」的读取方法；`InMemoryToolRegistryRepository` 同样没有。实现 `domains(principal)` 必须给 domain 端口加 `findAll()`（或 `domains()`）并改内存实现，这两个文件不在 T03 的输入 / 输出里。另外 T01 已经在创建 `ToolSearchPort`，T03 再回头给同一接口加第二个方法，等于同一接口被两个 task 各改一次；而 T03 本身已偏满（6 项）。`DiscoveryPolicy.filter(List<ToolManifest>, Set<String>)` 签名可直接复用，无需改动。
- **建议**：把「`ToolSearchPort.domains(Principal)` + `SearchToolsUseCase` 实现 + `ToolRegistryRepository.findAll()` + 内存实现」整体前移到 T01（接口层一次成形），T03 只保留 runtime 侧的 `ToolRegistryClient.domains` 与 `InProcessToolRegistryClient` 转发。T01 / T03 输入 / 输出对应补齐。
- **分级**：SHOULD

#### S-4 — 用例 ⑥ 在 LIVE 模式下的期望与 §6.1「有 LLM 变量：同脚本全部通过」冲突
- **位置**：spec §2.5 首句「用例 ①②④⑤⑥ 两种模式都跑」vs ⑥「「订单」+ 无 `pageContext`（**规则模式**）」；§6.1「用例 ⑥：…`toolId == order.list.search`」
- **问题**：「订单」命中规则 → order → 候选 `{order.detail.get, order.list.search}` → 不拦截 → 进规划。LIVE 模式由真模型规划，模型可能选 `order.detail.get` 并编造 `orderId`（`ToolSelectionValidator` 只校验键名，不校验值），Gateway 返回 `HANDLER_ERROR` / 空结果 → `run.failed` 或 `toolId != order.list.search`。⑥ 的 `toolId` 断言只对规则规划器成立，而首句把它归入两模式都跑，§6.1 又要求 LIVE 全绿。这是 v1 S3 / S4 同类问题在新用例上的复现。
- **建议**：⑥ 明确「仅规则模式」（LIVE 打印 `skipped (live LLM)`），或在 LIVE 模式放宽为「终态 `run.completed`、无 `run.failed`、`tool.selected` 的 toolId ∈ order 候选」并在 §7「用例 ② ③ 在 LIVE 模式依赖模型判断」一行加入 ⑥。
- **分级**:SHOULD

#### S-5 — order 无实体链路 `run.completed` 但无 `ui.replace`，用户只看到「已完成」；spec 应把这个产品决策与精确事件序列写明
- **位置**:spec §2.2 order 回退；§2.5 ⑥「事件含 `tool.selected`…且到 `run.completed`」；§6.1 ⑥
- **问题**:`UiSchemaBuilder` 只有 `refundConfirmation` / `refundResult` 两块屏，没有 order 领域的屏；`RunOrchestrator.runSteps` 执行完非确认步骤直接 `complete`，不发 `ui.replace`；`summaryOf` 对 `order.list.search` 返回 null。前端 `AgentChatPanel.tsx:120`：`phase === 'completed' && !view.ui` → 渲染「已完成」。所以用户在 `/` 输入「订单」得到的是：进度条「搜索订单 ✓」+「已完成」，看不到任何订单。这比 `INPUT_INVALID` 好，但 spec 只写了事件到 `run.completed`，没有说「首期不生成 order 结果屏」，也没有把 ⑥ 的期望写成精确序列。`order.detail.get` 有实体时同样无屏（首期已如此），本条不是回归，是未写明。
- **建议**:§3 非目标加一条「不新增 order 领域 UI 屏（Table）；order 链路首期只有工具进度 + `run.completed`，前端显示『已完成』」；§2.5 / §6.1 ⑥ 期望改为精确序列 `run.started tool.selected tool.started tool.completed run.completed`（与现有 check 风格一致，同时排除 `ui.replace` 与 `run.failed`）。若产品不接受「已完成」无内容，则应作为独立 change 提出，不在本 change 顺手加屏。
- **分级**:SHOULD

#### S-6 — 「`e2e-backend.sh` 的 selfcheck 期望改 5」在脚本里没有落点；④ 的放置位置须在 §6.2.12 之后
- **位置**:spec §2.3 末句、§6.1 倒数第 2 条；tasks T02、T07b「selfcheck 期望 4 → 5」
- **问题**:(1) `e2e-backend.sh:33-37` 的自检断言是**按名字逐条** `check "selfcheck: $s" 1 …`，没有任何「4」；v1 M3 已指出这一点，v2 仍写「期望改 5」。编码 Agent 会去找一个不存在的数字。正确动作是把 `"gateway idempotency claim OK"` 追加进 `for s in …` 列表，并（可选）加 `check "running 5 checks" 1 "$(grep -c 'selfcheck: running 5 checks' …)"`。(2) 已核实 §6.2.12 `refund.create succeeded audit lines = 1`（`e2e-backend.sh:102`）不受 `replayed` 影响：该用例第二次调用在 Runtime 被 `CONFIRMATION_REJECTED`，不到 Gateway，无审计行。但该 grep 是**全文件**计数，若新用例 ④ 被插到第 102 行之前，计数变 2。§6.2.14 `audit fields = 9` 取 `-m1` 首行（§6.2.7 的 `failed:FORBIDDEN`），不受影响。
- **建议**:spec / T02 / T07b 把「期望改 5」改成「`e2e-backend.sh` 自检列表追加 `gateway idempotency claim OK`；`deploy-verify.sh:29` 4 → 5」；T07b 写明「新用例 ①–⑥ 追加在 `--- 其他` 段之前、§6.2.12 之后」，或把 §6.2.12 的 grep 收窄为 `runId=$RUNID`。
- **分级**:SHOULD

### LOW

- **L-1** spec §8 假设 1 仍写「`KNOWN_DOMAINS` 取自 Registry 已注册 manifest 的 `domain` 去重集合」，与 §2.1「该用户可见的领域集合」不一致；改为「按 principal 过滤后的可见领域集合（user_001 / user_002 首期均 = {order, refund}）」。同时写明 `domains(principal)` **只在模型路径**（规则未命中且分类器非 Noop）调用，规则模式零额外查询。
- **L-2** v1 LOW 未闭环四项：L2（T01 验收 grep 统一为 spec §6.1 的 `grep -rn "com.strato.registry.application\|com.strato.gateway.application"`）；L3（§4.4 / T03 WARN「不含用户原文」补「亦不含模型原始输出」，结构化输出解析失败时最自然的写法就是把 raw text 打进 WARN）；L4（`backed/agent-runtime/README.md` 与 `06-backend-module-spec.md:41` 都存在，去掉两处「若存在 / 若含」）；L5（T07a 验收补第一条负例：把 `packages/core/src/components/**` 改错拼写 → 红）。
- **L-3** `Awaiting(future)` 直接暴露可写的 `CompletableFuture`，等待方拿到后可以 `complete()` 它污染他人结果；建议暴露 `CompletionStage<Response>` 或在 `Awaiting` 上提供 `Response await(long ms)`。另外等待上限 `execution.timeoutMs` 没有考虑 Owner 的 `callWithRetry` 最多跑 `(maxRetries+1) × timeoutMs`（`InvokeToolUseCase.java:204-243`）；首期 `refund.create` maxRetries=0 不受影响，spec 可注一句「等待上限按 `(maxRetries+1) × timeoutMs`」以免将来白等失配。
- **L-4** `GatewayIdempotencySelfCheck` 的两条路径其实可以**单线程**完成：同线程 A `claim` → Owner；再 `claim` → Awaiting（future 未完成）；A `complete` → `awaiting.future().getNow(null)` == 同一 Response。不需要 latch 与第二线程。若坚持双线程，用注入的 `toolExecutor`（`GatewayExecutorConfiguration`）而不是 `new Thread`。
- **L-5** e2e ④ 未写 `refund.create` 的 `arguments`。10004 金额 59.00，`RefundService.create` 要求 `amount ≤ refundableAmount`；建议固定 `{"orderId":"10004","amount":"59.00","reason":"DAMAGED"}`，避免编码时拷 §6.2.7 的 `"1.00"`（能过但与「幂等验收专用商品」金额不一致，读日志时会困惑）。
- **L-6** §6.2.15 只 grep `帮我把这个订单退款`。新增路径引入了新的用户原文（①「退钱」；③「我想把钱要回来」会进分类 Prompt）。建议 ① 加 `grep -c '退钱' backend.log` = 0，LIVE 模式 ③ 加 `grep -c '我想把钱要回来' backend.log` = 0，把「分类器不落原文」也变成可执行断言（对应 T03 的静态 grep 只查了日志语句，查不到 Prompt 被 DEBUG 级别打出来的情况）。
- **L-7** tasks 依赖图把 T05 / T06 汇入 T08，但 T08 文字「依赖：T07a、T07b」未列 T05 / T06；统一为图示（T08 依赖 T05、T06、T07a、T07b）。T04b 把「种子 10004」与「规则规划器模板」放在一起可以接受（都 ≤ 0.5 天且互不阻塞），但种子的真正消费方是 T07b ④ 与 T02 的语义，语义上更贴近 Phase A；若 T02 需要一个端到端的手工验证，可把两处种子挪到 T02。二选一即可，不强制。

### INFO

- **I-1** `Claim` sealed 接口 + 三个 record + 模式 switch：已在 JDK 21.0.11 用 `javac --release 21 -Xlint:all -Werror` 编译最小样例通过，无 exhaustiveness / preview 警告；google-java-format 1.24.0（`~/.m2` 现有版本）支持 sealed / record / pattern switch。`CompletableFuture` 属 `java.util.concurrent`，`check-module-deps.mjs` 对 domain 包只禁 `org.springframework.` 与 `com.fasterxml.`，`IdempotencyStore` 已 import `com.strato.contracts.model.ToolInvoke`，新增 JDK 类型不违反「domain 无框架」。
- **I-2** v2 提议的 `check-registry` 正则 `/export const PROPS_SCHEMAS = (?:Object\.freeze\()?\{([\s\S]*?)\n\}(?: as const)?\)?;/` 已实测：`= {…} as const;`、`= Object.freeze({…} as const);`、`= Object.freeze({…});` 三形态均解析出 `['Form','Card']`。T05 的负例可直接用第一形态。`Object.freeze({...} as const)` 的类型为 `Readonly<…>`，`SchemaRenderer.tsx:43-44` 的 `keyof typeof PROPS_SCHEMAS` 索引写法不受影响。
- **I-3** `order.list.search` inputSchema 实测：`{}` valid；`{orderId:"1"}` invalid（`additionalProperties:false`）。因此 v2「有 `orderId` → `order.detail.get`；无 → `order.list.search {}`」二选一模板是唯一正确形态——现状 `RuleBasedLlmClient.java:43-45` 对每一步都无条件塞 `orderId`，若模板改成两步都生成会被 Gateway 以 `INPUT_INVALID` 拒绝。T04b 实现时注意 `orderId` 只进需要它的步骤。
- **I-4** 审计 `status=replayed`：`execute()` 用 `resp.status().name()` 记审计（`InvokeToolUseCase.java:111`），`pipeline()` 只返回 `Response`。要让 `Replay` / `Awaiting` 路径落 `replayed`，`pipeline` 需要向 `execute` 传出「是否重放」（返回一个小 record 或在 `pipeline` 内直接 `audit.record`）。契约 `tool-invoke.response.status` 仍只有 `succeeded | failed`，`replayed` 只存在于审计字符串，与 spec「仅日志口径」一致。
- **I-5** `EntityRequirementCheck` 对 refund 领域用 user_002 仍成立：`application.yml:21` user_002 = `order:read, refund:read`，refund 候选 = `eligibility.check / preview / status.get`（e2e §6.2.5 断言 3），三者 `required` 均含 `orderId`（manifest 已核）→「全部候选需实体」→ 拦截。候选为空（无任何 refund 权限）走现有 `found.tools().isEmpty()` → `NO_CAPABILITY_TEXT`，在 Check 之前，顺序与 T04a「resolve → 空则无能力 → search → Check → plan」一致。
- **I-6** §6.2.12 / §6.2.14 复核结论见 S-6：前者在 ④ 追加于其后时不受影响；后者 `-m1` 取首条审计行（`failed:FORBIDDEN`），`replayed` 行同样 9 字段（`LogAuditSink` 固定 9 个 `key=`）。§6.2.15 与「ERROR lines 0」不受新增 WARN / route 日志影响。
- **I-7** §8 假设 2「分类器与规划器共用同一 `ChatClient` Bean」：现状 `LlmConfiguration.llmClient()` 在方法内构造 `ChatClient`，并非 Bean。T03 需要把 `OpenAiApi` / `ChatClient` 抽成 `@Bean`（无 key 时不注册）再分别注入两个实现；`@Bean` 条件化后 `SpringAiIntentClassifier` / `NoopIntentClassifier` 的二选一可以复用同一判定。属实现细节，不改 spec。

---

## 四、tasks.md 机械核对

| 项 | 结论 |
|---|---|
| 六要素（目标 / 所属端 / 输入 / 输出 / 验收 / 依赖） | 11/11 齐全 |
| ≤ 0.5 天 | 11/11；T03 偏满（S-3 建议前移 Registry 侧到 T01） |
| 依赖无环 | ✓ |
| 验收可命令化 | 11/11；T02 / T07b 的「selfcheck 期望 4 → 5」需按 S-6 改措辞；T01 验收 grep 见 L-2 |
| 输入 / 输出与 spec 一致 | T03 漏 `ToolRegistryRepository` / `InMemoryToolRegistryRepository`（S-3）；T08 依赖与图不一致（L-7） |

## 五、结论

**APPROVED**。v1 的 13 条 MUST / SHOULD 全部闭环，v2 新引入的三个机制（sealed `Claim`、`domains(principal)`、`order.list.search` 回退）均经现状代码与实测验证可行。请在进入阶段 3 前以 spec / tasks v2.1 吸收 S-1 … S-6（均为措辞与任务清单级修改，不改变设计方向），并顺手清掉 L-2 中四条上轮遗留；v2.1 不需再开评审轮次，在 coding_report 中列出对应改动即可。
