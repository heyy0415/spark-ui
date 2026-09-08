# Spec Review v3 — feat-commerce-domains-20260908

| 字段 | 值 |
|---|---|
| mode | plan |
| 日期 | 2026-09-08 |
| 轮次 | 3（上限轮，仍有 MUST FIX → 转 HITL） |
| 评审对象 | `request_analysis/spec.md` v3（commit `9fa73c3`，头部「> v3 — 响应」已确认）、`request_analysis/tasks.md` v3 |
| 依据 | `review/spec_review_v2.md`；`.harness/skills/expert-reviewer/SKILL.md` plan 6 项；rules/{agent-safety, contracts, project-structure}.md；现状代码：`RunOrchestrator`（`executeConfirmed` / `fail` / `userMessage`）、`RunFailure`、`RuleBasedLlmClient` / `SpringAiLlmClient` 构造签名、`LlmConfiguration`、`ToolDisplayNames`、`ToolSelectionValidator.validate`、`EntityRequirementCheck`、`PlanSelfCheck`、`StartupManifestRegistrar` / `SelfCheckRunner` 的 `@Order`、`EligibilityPolicy`、`RefundIdempotencySelfCheck`、`TokenSelfCheck`、`InMemoryOrderRepository`、`e2e-backend.sh`、`deploy-verify.sh`、`e2e-frontend.mjs`、`action-request.schema.json` |
| **verdict** | **REVISION REQUIRED**（MUST FIX 3 条，SHOULD 5 条，LOW 7 条，INFO 4 条） |

> 独立性说明：只看 v3 产出物与仓库现状，未读编码 Agent 自评。本轮只评 v3 相对 v2 的增量与闭环质量。

---

## 0. 上轮意见闭环表

| 编号 | v2 要点 | v3 闭环证据 | 状态 |
|---|---|---|---|
| M-A | 空 `Form` 违契约 | §2.4.1「确认 → `ConfirmationCard` 列参数，**不含 Form**」「无 Form 的确认屏 token `allowedFormKeys = {}`」；表行「`order.delete` 确认 … `ConfirmationCard{…}`（无 Form）」；T06b「删除确认屏 `[OrderCard, ConfirmationCard]` 无 Form」 | ◐ **§2.6 ⑫ 仍写 `[OrderCard, ConfirmationCard, Form]`**（见 M-1）——上轮要求「三处同步」，第三处漏了 |
| M-B | ⑮ 夹具 10001 已被退款 | §2.2 表 `10006 PAID … ⑮ 无 pageContext 退款（不能复用 10001）`；§2.6 ⑮「订单 10006 退款 … `refund.status.get 10006` == 1」；§4.5「抓 `{order:10006}`（e2e ⑮）」；T10a「⑮ 用 10006」 | ✓（T07a 验收仍用 10001 只比发起序列，可行，见 L-7） |
| M-C | 10007 与排序 / 可见区矛盾 | §2.2 夹具与可见区状态表；⑦「`items[0].orderId == 10030`、`items[1]`（10029）含删除订单」；e2e-frontend `data-intent="查看订单 10030 的物流"`；`check-seed`「`10030` 为最大 `created_at` 且 SHIPPED」；§8 假设 4 | ✓（残留：§4.1 与 T09 示例 chip 仍用 10007，见 S-3） |
| M-D | 自检数 5 / 6 不一致 | §2.6「selfcheck 总数 **5 → 7**」；§6.2「自检 **7/7**」；§6.4「deploy-verify 12/12（自检 7）」；T10a「selfcheck 数 → 7」 | ✓ 正文四处一致为 7（v3 头部变更说明写「统一 6」是笔误，见 L-1） |
| M-E | `OrderSnapshot` 缺 `productName` | §2.4.1 `OrderSnapshot{orderId, status, amount, currency, productName, quantity, createdAt}`「多商品单 `productName` = 首行商品名，`quantity` = 总件数」；§2.1 aftersale 行摘要含 `productName, quantity`；T02b / T05 同步 | ✓ |
| S-A | 缺实体 → `TOOL_SELECTION_INVALID` 不一致 | §2.4.3 第 3 条 `MissingEntity(entityType)` 信号 → 与 `EntityRequirementCheck` 同款提示 + `run.completed`；⑯ 新增；T07b | ✓ |
| S-B | 策略拒绝文案误导 | §2.4.4「令牌 / 并发拒绝 → 现文案；重校验策略拒绝 → 『订单状态已变化，本次操作未执行』；内部原因只进日志」 | ✓ 需求层闭环（实现路径缺口见 S-1） |
| S-C | 无 recheck 的需确认工具 | §2.4.4 fail-closed `INTERNAL_ERROR` + `ConfirmationCoverageSelfCheck`；T06a | ✓ |
| S-D | 时间类型 / jsr310 | §2.2 `SeedLoader`「pom 增 `jackson-datatype-jsr310`，注册 `JavaTimeModule`」「时间 `Instant`」；T02b | ✓ |
| S-E | 依赖字段 / T02 超时 | T02 拆 T02a / T02b；T06b 依赖 `T06a, T04`；T07a 依赖 `T06b`；16 task | ✓（依赖图漏一条边，见 L-2） |
| S-F | `ToolDisplayNames` 快照 / recheck version | §2.4.1「`RuleBasedLlmClient` / `SpringAiLlmClient` 持有 `ToolDisplayNames` 引用而不是构造期 `all()` 快照」；§2.4.4「version 由 runtime 从当前计划中同 toolId 的前置只读步骤取；取不到 → `INTERNAL_ERROR`」；T06a | ✓（tasks 输出未列出签名变更，见 S-2） |
| L-1 | 被放弃方案残句 | §2.4.1 已删 | ✓ |
| L-2 | `emptyText` 语义 | §2.4.1 表仍「`emptyText = "共 N 单，仅展示最近 M 单"`」 | ✗ 未吸收 |
| L-3 | 审计 0 行按 runId 作用域 | §6.2「⑬ … `order.delete` 审计 0 行」无 runId 限定 | ✗ 未吸收（升级为 S-4：字面实现恒红） |
| L-4 | `order.detail.get` 对 DELETED 行为 | 未见 | ✗ |
| L-5 | 「单独 Gateway total == 30」时序 | §6.2 仍「`total == 30`」无「独立启动 / ⑫ 之前」标注 | ✗ |
| L-6 | `trustedArgs` ∩ Form 字段互斥 | 未见 | ✗ |
| L-7 | `Table` 标注 / T02 目录先建 | T10b「`Table` 标注仅 playground」；T02a「product / aftersale 目录先建，pom 由 T04 / T05 补」 | ✓ |

**闭环统计**：MUST 5 → 4 闭环 / 1 部分（M-A）；SHOULD 6 → 6 闭环（其中 S-B / S-F 各引出一条实现路径 SHOULD）；LOW 7 → 2 闭环 / 5 未吸收。v3 头部「L 项吸收」与事实不符。

---

## 1. plan 模式必查 6 项

| # | 项 | 结果 | 说明 |
|---|---|---|---|
| 1 | 「非目标」存在且非空 | ✓ | §3 |
| 2 | 每条验收可被命令 / 断言校验 | ✗ | ⑫ 组件清单与 §2.4.1 矛盾（M-1）；既有 e2e 断言 `shown amount 128.00`、幂等 ④ `amount 59.00` 依赖未在夹具表写死的金额（M-2）；⑬「审计 0 行」字面恒红（S-4） |
| 3 | 风险章节 ≥1 失败模式 + 缓解 | ✓ | §7 八行 |
| 4 | task 标所属端，contracts 在前 | ✓ | T01 无依赖；T06a / T08a 依赖 T01 |
| 5 | 跨端 task 列契约文件 | ✓ | T01 / T06a / T08a 指向 `ui-schema.schema.json` |
| 6 | 每个 task ≤ 0.5 天 | ◐ | 16 task 六要素齐；T03 / T06a 贴边（L-3），不阻塞 |

---

## 2. 本轮 7 项聚焦推演

### 2.1 夹具与可见区状态表（§2.2）自洽性
- **可见区**：10011–10028 = 18 单，5 状态各 ≥ 2 至少占 10，余 8 单自由分配 → 放得下 ✓。10029 COMPLETED → ⑦ `items[1]` 含「删除订单」✓；10030 SHIPPED → e2e-frontend「查看物流」✓；PAID 单无物流、SHIPPED / COMPLETED ≥ 3 条事件 ✓。
- **早区 10007–10010（4 单）**：退款 3 条须挂 3 个 **不同** REFUNDED 单（`EligibilityPolicy.alreadyRefunded` 一单一退），故 4 单只能是 3 REFUNDED + 1 COMPLETED（表写「混合」但实际被约束死，应写明）。售后 4 条挂这 4 单：
  - 若 4 条分散到 4 单各 1 条 → 「无进行中售后」不冲突；但 3 条落在 REFUNDED 单上，语义上要求「售后创建于订单 SHIPPED 期、随后退款」——历史数据可接受，但 spec 未说 `check-seed` 是否校验「售后所在订单 status ∈ {SHIPPED, COMPLETED, REFUNDED}」。
  - **spec 全文未定义售后单 status 枚举，也未定义「进行中」集合**（只出现 `SUBMITTED`（create 输出）与 `APPROVED`（种子含一条））。`AftersalePolicy`「无进行中售后」与 `check-seed`「夹具表每一行」都无法确定性实现；若实现者把 `APPROVED` 视为进行中而两条售后同挂一单则种子自违策略。→ **M-3**。
- e2e 写操作影响：⑫ 删 10005（早区，无售后 / 退款）、⑮ 退 10006、⑪ 售后 10002、④ / M2 退 10004 / 10002、§6.2.8 退 10001 —— 均不在默认 20 行 ✓；⑦ 在 ⑫ 之前 `total 30` ✓。
- **金额未写死**：现有 e2e `check "shown amount" 128.00`（10001）、幂等 ④ `refund.create 10004 amount 59.00`（`RefundService` 校验 `amount ≤ refundable`）、`RefundIdempotencySelfCheck` / `TokenSelfCheck` 用 10003 `amount 1.00`。`gen-seed.mjs`「固定随机种子」若不把这些金额钉住，spec 自称「首期 + 上一 change 全部用例仍绿」不成立。→ **M-2**。

### 2.2 `MissingEntity` 与 `EntityRequirementCheck` 分工；⑯ 可复现性
- 分工清楚：`EntityRequirementCheck` = 「领域**全部**候选需实体」拦截（现状不变）；`MissingEntity` = 「动词命中、目标工具必填实体缺失」（领域候选含无实体工具时放行后的兜底）。两者共用同一提示文案表与 `message.delta + run.completed` 出口 ✓。
- ⑯ 推演：「删除订单」→ 路由 order（关键词「订单」）→ 抽取空 → `EntityRequirementCheck`：order 候选含 `order.list.search` → 放行 → 动词「删除」→ `order.delete` 在 user_001 候选内 → 必填 `orderId` 缺 → `MissingEntity(order)` → 文案 `NEED_ENTITY_TEXT["order"]` = 「请先在页面上选择一个订单」含「选择一个订单」✓；事件 `run.started message.delta run.completed` ✓。可复现。
- ⑭ 与 ⑯ 的判定顺序（先「目标在候选内」再「实体齐全」）由 §2.4.3 第 1 → 3 条顺序隐含 ✓。LIVE 模式下 `MissingEntity` 由谁产生未说（⑯ 不在 LIVE 清单，见 L-5）。

### 2.3 拒绝文案分类（§2.4.4）实现路径
- 现状 `RunOrchestrator.fail()` → `userMessage(e.code())`，按 code 单射；`RunFailure(code, message, cause)` 无用户文案字段。spec 与 T06a 只说「拒绝文案分令牌 / 策略两类」，未说载体。实现者可能改 `userMessage` 按 `message` 字串匹配（脆弱）或新增 code（违 sse 契约）。→ **S-1**：写明 `RunFailure` 增可选 `userText`（或子类 `PolicyRejected`），`fail()` 优先取之；`ConfirmationRecheck.reject` 的返回值只进 `log.warn`。

### 2.4 `ToolDisplayNames` 引用注入
- 现状：`ToolDisplayNames` 是 `final class` + 私有构造 + 静态 `of()` / `all()`；`RunOrchestrator:422` 调静态 `of(toolId)`；`RuleBasedLlmClient(Map<String,String>)`、`SpringAiLlmClient(ChatClient, String, Map<String,String>)`、`ToolSelectionValidator.validate(…, Map<String,String> displayNames)`；`LlmConfiguration:65-66` 传 `ToolDisplayNames.all()`。改 Bean 后**四处签名都要变**，T06a 输出只写「runtime 4 类 + 改动」，未点名。→ **S-2**。
- 时序可行：`StartupManifestRegistrar` `@Order(MIN_VALUE)` 于 `ApplicationReadyEvent` 回填，`SelfCheckRunner` `@Order(MAX_VALUE)` 之后跑 → 自检读到的名字表已满 ✓。但首个用户请求前 Bean 已构造、名字表为空的窗口只存在于 ready 之前，无请求进入 ✓。
- T07b「启动时校验 `IntentVerbs` / `Prerequisites` 引用的 toolId 都已注册」若做成**新的 `SelfCheck` Bean**，`deploy-verify.sh` `grep -c 'SelfCheckRunner.*selfcheck: .* OK'` 将是 **8** 而不是 7 → 与 §2.6 / §6.2 / §6.4 / T10a 冲突。spec 未指定归属。→ 并入 S-2（明确并入 `PlanSelfCheck`）。

### 2.5 selfcheck 数
§2.6「5 → 7」、§6.2「7/7」、§6.4「自检 7」、T10a 目标「→ 7」与验收「（自检 7）」五处一致 ✓。现状 5 Bean（`ContractsSelfCheck` / `RefundIdempotencySelfCheck` / `PlanSelfCheck` / `TokenSelfCheck` / `GatewayIdempotencySelfCheck`）+ `InlineActionSelfCheck` + `ConfirmationCoverageSelfCheck` = 7 ✓。仅 v3 头部变更说明「M-D 自检数统一 6」为笔误（L-1）。

### 2.6 tasks
- 16 task（T01、T02a/b、T03–T05、T06a/b、T07a/b、T08a/b、T09、T10a/b、T11）六要素齐 ✓。
- 依赖字段：T02b←T02a、T03←T02b、T04←T02b、T05←T03,T04、T06a←T01,T02b,T05、T06b←T06a,T04、T07a←T06b、T07b←T07a、T08a←T01、T08b←T08a、T09←T08b、T10a←T07b,T09、T10b←T10a、T11←T10b。无环 ✓。
- 依赖图缺 `T04 → T05` 这条边（T05 字段有、图无）；T03 输入写「T02」应为「T02b」（L-2）。
- 工作量：T03（Order 模型三扩 + 2 新 handler + `DeletionPolicy` + `SeedLoader` 接入 + `OrderSnapshotProvider` + 4 Manifest + 权限）、T06a（3 注册表 + `ToolDisplayNames` 重构 + registrar 回填 + `executeConfirmed` 通用化 + 新自检 + refund 屏 / recheck 搬迁）均 > 0.5 天风险（L-3）。

### 2.7 v3 新引入的自相矛盾
- ⑫ 确认屏含 `Form`（M-1）。
- §2.1「三个领域服务」/ §6.1「3 个领域目录」 vs 实际 order / product / aftersale / refund 四个领域目录（T02a 输出 4 个、T10b「四领域」）（S-5）。
- §2.5 示例 chip「订单 10002 的物流」 vs T09「查看订单 10007 的物流」 vs §4.1 示例 10007；10007 在早区（REFUNDED / COMPLETED），若为 REFUNDED 是否有物流事件未定义，`order.logistics.get` 对无物流订单的行为未定义（S-3）。

---

## 3. MUST FIX

### M-1 §2.6 ⑫ 确认屏断言 `[OrderCard, ConfirmationCard, Form]` 与 §2.4.1（无 Form）、T06b 矛盾
**位置**：spec §2.6 ⑫（第 180 行）。
**问题**：上轮 M-A 要求「三处同步」，v3 改了 §2.4.1 表行、Fallback 描述、T06b，但 e2e ⑫ 仍含 `Form`。按 §2.4.1 实现则 ⑫ 断言必红；按 ⑫ 实现则空 Form 被 `assertValid` 拒（回到 M-A）。
**建议**：⑫ 改为「确认屏 `[OrderCard, ConfirmationCard]`；确认请求体 `{"confirmationToken": …, "formData": {}}`」（`action-request` 契约 `formData` 必填，空对象合法）。

### M-2 夹具表未写死金额，既有 e2e / 自检硬编码金额将随 `gen-seed` 随机化而红
**位置**：spec §2.2 夹具表；§2.6「既有断言同步清单」；T02a 目标。
**问题**：现状断言：`e2e-backend.sh:131` `check "shown amount" 128.00`（10001）；`:159` 幂等 ④ `refund.create 10004 amount 59.00`（`RefundService` 校验 `amount ≤ refundableAmount`，10004 金额 < 59.00 即 `HANDLER_ERROR`）；`RefundIdempotencySelfCheck` 10003 `amount 1.00`、`TokenSelfCheck` `amount 1.00`。spec 只钉状态与位置，声称「首期 + 上一 change 全部用例仍绿」但 `gen-seed.mjs` 随机金额无法保证。
**建议**：夹具表增「金额」列并由 `gen-seed` 断言、`check-seed` 校验：`10001 = 128.00`（单行 1 件）、`10004 = 59.00`、`10003 ≥ 1.00`、`10002 ≥ 1.00`（现状 `SeededOrderLookup` 值可直接沿用：128.00 / 299.00 / 1.00 / 59.00）；或在「既有断言同步清单」明确改 `e2e-backend.sh:131` 为从 `order.detail.get` 读取期望值。前者更小。

### M-3 售后单状态枚举与「进行中」集合未定义；10007–10010 容量约束未写死
**位置**：spec §2.1 aftersale 行与规则「该订单无进行中售后单」；§2.2 规模「售后单 4（含一条 APPROVED）」与夹具表 `10007–10010 混合`；§2.4.4 `AftersalePolicy`。
**问题**：全文只出现 `SUBMITTED` / `APPROVED` 两个值，无枚举、无终态、无「进行中」定义 → `AftersalePolicy`（T05）、`AftersaleRecheck`（T06b）、`check-seed` 夹具行校验（T02b）三处都要靪实现者猜。同时 3 条退款要求 3 个不同 REFUNDED 单（一单一退），4 单中只剩 1 个 COMPLETED；4 条售后如何分布、是否允许挂在 REFUNDED 单上、同单是否允许多条历史，均未说 → 种子可能与策略自相矛盾。
**建议**（最小）：§2.1 增一行「售后 `status ∈ {SUBMITTED, APPROVED, REJECTED, COMPLETED, CANCELLED}`；进行中 = `{SUBMITTED, APPROVED}`」；§2.2 夹具表把 `10007–10010` 行改为「`10007–10009` REFUNDED（各挂 1 条退款）、`10010` COMPLETED；售后 4 条：每单 ≤ 1 条进行中，`APPROVED` 那条挂 `10010`，其余为终态」并加入 `check-seed`（「每订单进行中售后 ≤ 1」「售后所在订单 status ∈ {SHIPPED, COMPLETED, REFUNDED}」）。`wiki/domain-model.md`（T10b）状态机随之。

---

## 4. SHOULD

### S-1 策略拒绝文案的载体未写（§2.4.4、T06a）
现状 `fail()` 用 `userMessage(code)` 单射，`RunFailure` 无用户文案字段。建议写死：`RunFailure` 增可选 `userText`（或子类 `PolicyRejected extends RunFailure`），`fail()` 有则用之、无则按 code；`ConfirmationRecheck.reject` 返回值只进日志。T06a 输出列 `RunFailure`。

### S-2 `ToolDisplayNames` 改 Bean 牵连的签名变更未进 T06a 输出；`IntentVerbs` 启动校验的归属未定（§2.4.1、§7 第 2 行、T06a、T07b）
- 需变更：`ToolDisplayNames`（去静态、实现 `ToolNameSink`、`@Component`）；`RuleBasedLlmClient(ToolDisplayNames)`、`SpringAiLlmClient(ChatClient, String, ToolDisplayNames)`；`ToolSelectionValidator.validate(…, ToolDisplayNames)` 或 `Function<String,String>`；`RunOrchestrator` 注入实例替代静态 `of()`；`PlanSelfCheck` 构造无需变（经 `LlmClient`）。T06a 输出应逐一列出。
- T07b「启动时校验 `IntentVerbs` / `Prerequisites` 引用的 toolId 已注册」**必须并入 `PlanSelfCheck`**（同一 Bean、同一行 `plan 5 messages OK` 前置），不得新增 `SelfCheck` Bean，否则 `deploy-verify` 计数为 8 与全文 7 冲突。

### S-3 示例 chip / §4.1 仍用 10007；`order.logistics.get` 对无物流订单的行为未定义（§2.5、§4.1、T09、§2.1）
§2.5 chip「订单 10002 的物流」、T09「查看订单 10007 的物流」、§4.1「查看订单 10007 的物流」三处不一致；10007 在早区且按 M-3 建议为 REFUNDED，REFUNDED / CANCELLED 单是否有物流事件、`order.logistics.get` 对无物流订单返回什么（`HANDLER_ERROR`？空 `events[]`？`LogisticsTimeline.events` 是否允许空）均未定。建议：chip 与示例统一为 10030 / 10002；§2.1 写明「无物流 → `HANDLER_ERROR`（业务原因『订单尚未发货』）」或「返回 `status: NOT_SHIPPED, events: []`」并同步 `LogisticsTimeline` 契约 `events minItems`。

### S-4 ⑬ / §6.2「`order.delete` 审计 0 行」字面恒红（上轮 L-3 未吸收）
⑫ 已产生一行 `toolId=order.delete … status=succeeded`。建议改为「该 Run 的 `runId` 作用域内 `order.delete` 审计 0 行」，T10a 用 `grep "runId=$RUNID" | grep -c order.delete`。

### S-5 领域 / 目录计数口径不一（§2.1「三个领域服务」、§6.1「3 个领域目录」 vs T02a 4 目录、T10b「四领域」、§2.1 表 4 个领域）
建议统一：「4 个领域服务（新增 product / aftersale 两个模块，order / refund 改造）、4 个 `data/` 目录」；§6.1 改「4 个领域目录」。

---

## 5. LOW

- **L-1** v3 头部变更说明「M-D 自检数统一 6」与正文 7 矛盾，改为 7。
- **L-2** 依赖图缺 `T04 → T05` 边；T03 输入「T02」应为「T02b」。
- **L-3** T03 / T06a 工作量 > 0.5 天风险；可将 T03 的 `OrderSnapshotProvider` 实现 + 权限改动挪到 T05 前一小步，或接受并在 coding_report 记录。
- **L-4** 上轮 L-2 / L-4 / L-5 / L-6 未吸收：`emptyText` 语义、`order.detail.get` 对 DELETED、§6.2「单独 Gateway total == 30」时序标注、`trustedArgs` ∩ Form 字段互斥不变量。
- **L-5** LIVE 模式下「删除订单」无号码由谁产生 `MissingEntity` 未说（模型可能产出 `order.delete` 空参数 → `ToolSelectionValidator` 应把「需确认工具必填实体缺失」也映射到 `MissingEntity`）；⑯ 不在 LIVE 清单可接受，但应写明。
- **L-6** §2.4.1 `FallbackScreenBuilder` 确认屏为 `ConfirmationCard` 单卡；v3 头部「删除 / Fallback 确认屏 = `[OrderCard, ConfirmationCard]`」把二者写成同构，Fallback 无 `OrderCard`，改述。
- **L-7** T07a 验收「无 pageContext 的『订单 10001 退款』事件序列 == §6.2.8」只比发起序列可成立（10001 已退，`eligibility` 返 false 不抛错），但改用 10006 更稳。

---

## 6. INFO（已核实、无需修改）

- **I-1** 回填时序：`StartupManifestRegistrar` `@Order(Integer.MIN_VALUE)`、`SelfCheckRunner` `@Order(Integer.MAX_VALUE)` 同在 `ApplicationReadyEvent`，`ToolNameSink` 回填先于自检 ✓。
- **I-2** ⑯ 在规则模式可复现（§2.2 推演），文案表复用 `EntityRequirementCheck.NEED_ENTITY_TEXT["order"]`。
- **I-3** 无 Form 确认屏：`action-request` `formData` 必填但可为 `{}`；`formKeys(ui)` 对无 Form 屏返回空集，与 `allowedFormKeys = {}` 一致。
- **I-4** recheck version 取自计划前置步骤：三个需确认工具的前置表首项恰为各自 recheck 工具（`refund.eligibility.check` / `order.detail.get` / `aftersale.list.get`），`Step.version` 现有字段可直接取 ✓。

---

## 7. 结论与 HITL 最小补丁清单

**verdict: REVISION REQUIRED** — 3 条 MUST FIX，均为局部文字补丁，不动架构：

| # | 补丁（最小） | 触及位置 |
|---|---|---|
| M-1 | ⑫ 确认屏改 `[OrderCard, ConfirmationCard]`，确认体 `formData: {}` | spec §2.6 ⑫ 一行 |
| M-2 | 夹具表增「金额」列：10001 = 128.00、10002 = 299.00、10003 = 1.00、10004 = 59.00（沿用现状值）；`gen-seed` 断言、`check-seed` 校验 | spec §2.2 表 + T02a 目标一句 |
| M-3 | 售后 status 枚举 `{SUBMITTED, APPROVED, REJECTED, COMPLETED, CANCELLED}`，进行中 = `{SUBMITTED, APPROVED}`；`10007–10009` REFUNDED 各 1 退款、`10010` COMPLETED；每单进行中售后 ≤ 1，`APPROVED` 挂 10010；两条约束进 `check-seed` | spec §2.1 一行、§2.2 表一行、T02b 校验清单一项 |

第 3 轮已达上限；建议 HITL 直接采纳上表三处补丁与 S-1 / S-2（两句话即可写清），随后进入阶段 3。
