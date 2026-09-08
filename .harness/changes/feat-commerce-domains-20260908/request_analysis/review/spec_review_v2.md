# Spec Review v2 — feat-commerce-domains-20260908

| 字段 | 值 |
|---|---|
| mode | plan |
| 日期 | 2026-09-08 |
| 轮次 | 2 |
| 评审对象 | `request_analysis/spec.md` v2、`request_analysis/tasks.md` v2 |
| 依据 | `review/spec_review_v1.md`；rules/{agent-safety, contracts, backend-standard, project-structure}.md；expert-reviewer plan 6 项；现状代码：`RunOrchestrator` / `EntityRequirementCheck` / `DomainResolver` / `DomainRouter` / `ToolDisplayNames` / `RuleBasedLlmClient` / `ToolSelectionValidator` / `PromptBuilder` / `PlanSelfCheck` / `StartupManifestRegistrar` / `GatewayExceptionHandler` / `InvokeToolUseCase` / `LogAuditSink` / `SelfCheckRunner` / `LlmConfiguration` / `platform-spi` 全部文件与 pom / `contracts-java` pom / `ui-schema.schema.json` / core `index.ts` `types.ts` `SchemaRenderer.tsx` / `verify-pack.mjs` / `e2e-backend.sh` / `e2e-frontend.mjs` / `deploy-verify.sh` / `harness-doctor.mjs` / `application.yml` |
| **verdict** | **REVISION REQUIRED**（MUST FIX 5 条，SHOULD 6 条，LOW 7 条，INFO 5 条） |

> 独立性说明：只看 v2 产出物与仓库现状，未读编码 Agent 自评。本轮只评 v2 增量与上轮闭环质量；v1 已确认无误的部分不重复。

---

## 0. 上轮意见闭环表

| 编号 | v1 要点 | v2 闭环证据 | 状态 |
|---|---|---|---|
| M1 | 规则规划器按排序碰运气 | §2.4.3 动词表 + 前置依赖表；`refund.status.get` 不进计划；§2.4.1「结果屏 = 最后成功步骤」；§7 第 2 行改写为「表与候选脱节」；⑭ 写死 `TOOL_SELECTION_INVALID`；`PlanSelfCheck` 5 条消息 | ✓ 闭环（新问题见 S-A） |
| M2 | 实体抽取在拦截之后 | §2.4.2 路由后 / 拦截前，多类型 map；T07a 目标与验收 | ✓ |
| M3 | 路由顺序与关键词冲突 | §2.1 写死 `refund → aftersale → order → product`，去单字，`DomainDescriptions` 同步 | ✓ |
| M4 | spi 签名用 `UiSchema` 违红线 | §2.4.1 全 `JsonNode` + `ScreenContext` / `OrderSnapshot` spi 自有；T05 `check-module-deps` 两条新规则 + 植入负例 | ✓ |
| M5 | 重校验语义矛盾 | §2.4.4 `ConfirmationRecheck` 契约；⑬ `CONFIRMATION_REJECTED` + ⑬' `HANDLER_ERROR` 各测各的 | ✓ |
| M6 | 订单数三处矛盾 | §2.2 `createdAt desc`；⑦ `items 20 / total 30`；§4.1「20 行」；T03 `{status:"DELETED"}` → 400 | ✓（但引出新矛盾 M-C） |
| M7 | task 超时 / T06 缺 T04 | T06 / T07 / T08 / T10 已拆 a/b，共 15 task | ◐ 部分：v2 头部宣称「T06 依赖补 T04」，但 T06b 的 **依赖字段仍只写 T06a**，T04 只出现在依赖图括注里（见 S-E） |
| S1 | displayName / summaryOf 硬编码 | §2.4.1 `ToolNameSink` 回填 + `ScreenBuilder.summary` | ✓（实现细节见 S-F） |
| S2 | 既有断言同步清单 | §2.6「既有断言同步清单」、T10a | ✓（数字自相矛盾见 M-D） |
| S3 | verify-pack 基线 | §2.5 `--write-baseline` + 记录前后 KB；T08b | ✓ |
| S4 | 确认屏经 spi 直读 | §2.4.1 取 (b)：`aftersale.list.get` 带 `order` 摘要；§6.2 grep `OrderSnapshotProvider` 在 `infra/screen` 0 行 | ✓（摘要字段缺口见 M-E） |
| S5 | user_002 权限 | §2.1 四项只读含 `refund:read` | ✓ |
| S6 | e2e-frontend 依赖排序 / 10002 售后占用 | §2.2 售后挂 10006+；§2.5 `data-intent`；§2.6 顺序放 M2 幂等之后 | ✓（10007 定位见 M-C） |
| S7 | DDL 解析 / SeedLoader 映射 | §2.2 DDL 硬格式；`SeedLoader` 自建 ObjectMapper + `SNAKE_CASE` | ✓（时间类型见 S-D） |
| S8 | hint 白名单 / 文案 | §2.1 末句 | ✓ |
| S9 | label ↔ intent 机械保障 | §2.6 `InlineActionSelfCheck`；§2.3 映射表进 `contracts.md` | ✓ |
| S10 | README 两列 | §2.5 | ✓ |
| S11 | 种子脚本生成 | §2.2 `gen-seed.mjs` + 四项额外校验 | ✓ |
| S12 | 非目标补项 | §3 末条四项 | ✓ |
| L1 | 正则兼容无空格 | §2.4.2 `订单\s*(\d{5})` / `商品\s*(P-\d{4})` | ✓ |
| L2 | `Table` 标注仅 playground | §2.3 提「后端不再使用」，但 `wiki/api-contracts.md` 标注未列入 T10b | ◐ |
| L3 | 06-backend-module-spec 段落 | T10b 列出 | ✓ |
| L4 | check-module-deps 植入负例 | T05 验收「两条新规则各植入 → 红」 | ✓ |
| L5 | `RECHECK_TOOL` 版本硬编码 | `ConfirmationRecheck.recheckToolId()` 无版本；spec 未说 runtime 从哪取 recheck 工具的 version | ✗ 未吸收（并入 S-F） |

**闭环统计**：MUST 7 → 6 闭环 / 1 部分；SHOULD 12 → 12 闭环；LOW 5 → 3 闭环 / 1 部分 / 1 未吸收。

---

## 1. plan 模式必查 6 项

| # | 项 | 结果 | 说明 |
|---|---|---|---|
| 1 | 「非目标」存在且非空 | ✓ | §3 十二条 |
| 2 | 每条验收可被命令 / 断言校验 | ✗ | 自检数三处不一致（M-D）；⑮「与 §6.2.8 完全一致」在 e2e 顺序下不成立（M-B）；⑦ 首行 / e2e-frontend `10007` 与排序约束矛盾（M-C） |
| 3 | 风险章节 ≥1 失败模式 + 缓解 | ✓ | §7 八行；第 3 行承认「退货」取舍 |
| 4 | task 标所属端，contracts 在前 | ✓ | T01 无依赖；T06a / T08a 依赖 T01 |
| 5 | 跨端 task 列契约文件 | ✓ | T01 / T06a / T08a 指向 `ui-schema.schema.json` |
| 6 | 每个 task ≤ 0.5 天 | ◐ | T02 明显超（S-E）；T03 / T06a 贴边 |

---

## 2. 重点项推演结论（用户指定 1–9）

### 2.1 动词表 + 前置依赖表 逐条推演（按 §2.1 路由顺序、§2.4.2 抽取、§2.4.3 表顺序）

| 消息 | 路由 | 实体 | 动词 | toolId 序列 | 与 spec 断言 |
|---|---|---|---|---|---|
| 帮我把这个订单退款 + {order:10001} | refund（退款） | order | 退款 | eligibility.check → preview → create | ✓ |
| 看看我的订单 | order（订单） | 无 | 无（不含「看看这个」） | order.list.search | ✓ ⑦ |
| 查看订单 10002 的物流 | order（refund / aftersale 均未命中） | 10002 | 物流 | order.logistics.get | ✓ ⑧ |
| 有什么商品 | product（商品） | 无 | 无 | product.list.search | ✓ ⑨ |
| 查看商品 P-1003 的详情 | product | P-1003 | 详情 | product.detail.get | ✓ ⑩ |
| 订单 10002 申请售后 | aftersale（售后先于订单） | 10002 | 售后 | aftersale.list.get → aftersale.create | ✓ ⑪ |
| 删除订单 10005 / 10001 | order | 10005 / 10001 | 删除 | order.detail.get → order.delete | ✓ ⑫ ⑬ |
| user_002 删除订单 10005 | order | 10005 | 删除 → 目标不在候选（user_002 无 `order:delete`） | `TOOL_SELECTION_INVALID` | ✓ ⑭ |
| 订单 10001 退款（无 pageContext） | refund | 10001 | 退款 | 3 步 | 起始序列 ✓；**确认阶段 ✗**（M-B） |
| 订单 10002（只有实体） | order | 10002 | 无 → detail.get | order.detail.get → OrderCard + Timeline | 合理 |

「退货」两表同归 aftersale，§7 第 3 行明确承认取舍；`aftersale.create.type` 含 `RETURN`（退货）语义自洽，接受。

### 2.2 实体抽取 × `EntityRequirementCheck`
- 「订单 10002 申请售后」：aftersale 候选 `list.get`（orderId 可选）→ 不是「全部候选都需实体」→ 放行；即便全需，map 已含 order。✓
- 「有什么商品」：`product.list.search` 无必填 → 放行。✓
- 「退钱」（e2e ①）：refund 全部候选需 orderId、map 空 → 仍拦截，既有用例不变。✓
- **反例**：「删除订单」「申请售后」不带号码 → order / aftersale 候选含无实体工具 → 放行 → 动词命中目标缺必填 → 按 §2.4.3 第 3 条 `TOOL_SELECTION_INVALID`。spec 称「理论不可达」是**错的**（S-A）。

### 2.3 `ScreenBuilder` / `ToolNameSink` 依赖方向
- `ScreenContext`、`OrderSnapshot` 均写明在 spi。✓
- `ToolNameSink` 放 spi，runtime 提供实现 Bean（`ToolDisplayNames`），registry `StartupManifestRegistrar` 注入 `List<ToolNameSink>` 调用 → 方向 registry → spi ← runtime，无反向依赖。可行 ✓。但 §2.4.1 那句残留了被放弃方案的半句话（L-1），且 `LlmConfiguration` 现以 `ToolDisplayNames.all()` **构造期快照**传入两个 LlmClient，注册表改可变后必须传活引用（S-F）。

### 2.4 `ConfirmationRecheck`
- `shownUi` 为整屏 `JsonNode`：`RefundRecheck` 遍历 `components[]` 找 `type == RefundConfirmCard` 取 `props.amount`，与现 `shownRefundAmount`（`lastUi.components().stream().filter(RefundConfirmCard).props.amount`）等价。✓
- `trustedArgs` 覆盖顺序 fixedArgs → formData → trustedArgs，与现 `args.put("amount", trustedAmount)` 等价；`TRUSTED_ONLY_ARGS` 仍在 `ToolSelectionValidator` 挡模型、token 白名单挡 formData（e2e §6.2.12b 不变）。✓
- `order.detail.get` 1.1.0 输出「订单全字段」含 `status`（现 1.0.0 已含），`DeletionPolicy` 可用。✓
- 缺：无注册 recheck 的需确认工具如何处置（S-C）；recheck 工具 version 来源（S-F / L5）；策略拒绝时的用户文案（S-B）。

### 2.5 种子
- `check-seed` 按硬格式解析 DDL 可实现 ✓；`SNAKE_CASE` 映射 record（Jackson 2.19 原生 record 支持）✓；时间字段若为 `Instant` 需 jsr310 模块，spi pom 现仅 `jackson-databind`（S-D）。
- **夹具约束互斥**：见 M-C。

### 2.6 e2e 顺序与期望
- ⑦（20/30）→ ⑫（29）编号顺序即执行顺序，可接受；§6.2「单独 Gateway total == 30」应标注「在 ⑫ 之前或独立启动」（L-5）。
- ⑬' 期望 HTTP 502 + `INTERNAL_ERROR`：`GatewayExceptionHandler` 把 `HANDLER_ERROR` 映射 `BAD_GATEWAY` + `ErrorResponse.Code.INTERNAL_ERROR`；`InvokeToolUseCase` catch 后审计 `failed:HANDLER_ERROR` 再 rethrow。**一致** ✓。
- ⑮：**不成立**（M-B）。

### 2.7 前端
- `FormComponentHandlers` / `RenderedComponentProps` 未从 `index.ts` 导出（已实测 `index.ts` 清单），改名不动公共 API；`FormValues` 保留导出。✓
- `data-intent` 含中文与空格：HTML 属性值无字符限制，puppeteer `[data-intent="查看订单 10007 的物流"]` 属性选择器带引号即可。✓
- antd-mobile 5.42.3 `es/components/steps` 存在，`Steps direction="vertical"` 承载时间线可行。✓

### 2.8 tasks
- 15 task 六要素齐 ✓；无环 ✓；依赖字段与依赖图不一致（S-E）。

### 2.9 上轮 LOW
- L1 ✓ 吸收；L4 ✓ 吸收；L5 ✗；L2 ◐（见闭环表）。

---

## 3. MUST FIX

### M-A 「空 `Form`」违反 ui-schema 契约（§2.4.1 表「`order.delete` 确认」行、`FallbackScreenBuilder` 描述、e2e ⑫ 断言 `[OrderCard, ConfirmationCard, Form]`）
**问题**：`ui-schema.schema.json` `formProps.fields` 为 `minItems: 1`，前端 `FormPropsSchema = z.object({ fields: z.array(FormFieldSchema).min(1).max(16) })`。后端 runtime 对屏做 `SchemaValidator.assertValid("ui-schema")` → 空 `Form` 直接 `INTERNAL_ERROR`；即使放过，前端 Zod 失败 → `UnknownComponent`。⑫ 按现 spec 写的断言必红。
**建议**：二选一并三处同步（§2.4.1 表、Fallback 描述、⑫ / e2e-frontend 断言）：(a) 删除确认屏 **不含 Form**：`[OrderCard, ConfirmationCard]`，token `allowedFormKeys = {}`，`formKeys(ui)` 对无 Form 屏返回空集（现实现天然如此），前端 ActionBar 提交 `formData: {}`；(b) 改契约 `minItems: 0`——不推荐（Form 无字段无意义，且要同步 Zod / 示例）。倾向 (a)。`FallbackScreenBuilder` 确认屏同样去掉「空 Form」。

### M-B e2e ⑮ 使用 10001，但 §6.2.8 / §6.2.9 已对 10001 完成退款 → 确认阶段必是 `CONFIRMATION_REJECTED`，「与首期事件序列完全一致」不成立（§2.6 ⑮、§4.5、T07a 验收）
**问题**：e2e 顺序：§6.2.8 起 Run（`intent-request.example.json` = 10001）→ §6.2.9 确认成功 → §6.2.13 断言 `refunds for 10001 == 1`。⑮ 放在 M2 幂等用例之后，此时 `refund.eligibility.check 10001` 返回 `eligible=false`（`EligibilityPolicy`「订单已存在退款单」）。起始三帧序列仍相同（preview 不抛错、返回 0.00），但确认后 `RefundRecheck` 拒绝 → `run.failed CONFIRMATION_REJECTED`，与 §4.5「三步 + 确认 + 重校验 + 结果，与首期事件序列完全一致」矛盾；T07a 验收「事件序列 == §6.2.8」若实现者顺手做确认也红。可用夹具已耗尽：10002 被 M2 退款、10004 被 ④ 退款、10003 归自检。
**建议**：新增一个 e2e 专用 PAID 夹具（如 `10006 PAID`，同时把「售后 / 退款种子挂 10006+」改为 `10008+`，或把 ⑮ 夹具定为夹具区最后一个编号并在 §2.2 夹具表写死）；⑮ 断言起始序列 == §6.2.8 **且**确认后序列 == §6.2.9、`ResultCard`、`refunds for <夹具> == 1`。§4.5 与 T07a 验收同步引用该夹具。

### M-C 「10007 = created_at 最新的 SHIPPED」与「同表 `created_at` 严格递增」「夹具 10001–10005 最早」「默认 20 行 `createdAt desc`」不能同时满足；e2e-frontend 点击 `[data-intent="查看订单 10007 的物流"]` 会找不到元素（§2.2、§2.6 e2e-frontend 步骤、§8 假设 4、T02）
**问题**：`created_at` 在 `orders.json` 内严格递增且 10001–10005 最早 ⇒ 时间序与编号序一致 ⇒ 默认列表 20 行 = `10030 … 10011`。10007 不在其中，「点行内按钮 → 新 Run」步骤无法执行。若为让 10007 成为「最新 SHIPPED」而把 10008–10030 全设为非 SHIPPED，则默认列表**没有任何** SHIPPED 行、没有「查看物流 / 申请售后」按钮，演示价值归零。⑦「首行 orderId 为 created_at 最新的种子单」也未写死数值，不可直接断言。
**建议**：在 §2.2 写一张**夹具与可见区状态表**：例如 `10030 = SHIPPED（最新，e2e-frontend 点它的「查看物流」）`、`10029 = COMPLETED`（默认列表首屏有「删除订单」按钮）、10011–10030 状态分布保证 PAID / SHIPPED / COMPLETED / REFUNDED / CANCELLED 各 ≥ 2；⑦ 首行写死 `10030`；e2e-frontend 选择器改为 `data-intent="查看订单 10030 的物流"`；`gen-seed.mjs` 把这些写成断言而非随机结果；`check-seed` 增「10030 为最大 created_at 且 SHIPPED」校验。§8 假设 4 同步。

### M-D 启动自检数量三处不一致：§2.6「deploy-verify.sh selfcheck 数 5 不变」、§6.4「deploy-verify 12/12（自检 5）」 vs §6.2「自检 6/6 … deploy-verify.sh / e2e-backend.sh selfcheck 期望同步为 6」、T10a「selfcheck 数 → 6（deploy-verify.sh 同）」
**问题**：现状 5 个 `SelfCheck` Bean（contracts / refund idempotency / plan / token / gateway idempotency），`deploy-verify.sh` 以 `grep -c 'SelfCheckRunner.*selfcheck: .* OK'` 断言 **5**。新增 `InlineActionSelfCheck` 后为 6；`PlanSelfCheck` 改 5 条消息仍是 1 个 Bean（不影响计数）。§2.6 与 §6.4 写 5 是错的，与 §6.2 / T10a 直接冲突——这是上轮 M6 同款「同一数字多处不一致」。
**建议**：统一为 **6**；§2.6 那句改为「`deploy-verify.sh` selfcheck 期望 5 → 6（新增 InlineActionSelfCheck）」；§6.4 改「deploy-verify 12/12（自检 6）」。

### M-E `OrderSnapshot{orderId, status, amount, currency, createdAt}` 无 `productName`，但 `aftersale.list.get.order` 摘要要 `productName`、`AftersaleScreens.confirmation` 的 `OrderCard` 要 `productName`（Zod `min(1)` 必填）（§2.4.1、§2.1 aftersale 行、T05）
**问题**：T05 写「`order{orderId, productName, amount, currency, status}` 摘要（经 `OrderSnapshotProvider`）」，而 §2.4.1 定义的 spi record 没有 `productName`，aftersale-service 又不得 import order 模块，无处取值。按 spec 字面实现 ⑪ 确认屏 `OrderCard` 缺 `productName` → 前端 Zod 失败 → `UnknownComponent`，⑪ 断言 `[OrderCard, Form]` 类型仍过但页面为占位，e2e-frontend 视觉与 console.error 0 断言红。
**建议**：`OrderSnapshot` 增 `productName`（多商品取首行商品名 +「等 N 件」由屏层拼），或摘要改为 `OrderCard` 所需全部字段的显式清单并让 `OrderSnapshotProvider` 返回它；§2.4.1 record 与 T02 / T05 同步。

---

## 4. SHOULD

### S-A 「缺必填 → `TOOL_SELECTION_INVALID`（理论不可达）」对 order / aftersale 领域不成立（§2.4.3 第 3 条）
「删除订单」「申请售后」「查看物流」不带号码、无 pageContext：领域候选含无实体工具 → `EntityRequirementCheck` 放行 → 动词命中目标缺 orderId → `run.failed TOOL_SELECTION_INVALID`，用户看到「暂时无法为该请求制定可执行的方案」；同样情形在 refund 领域（「退钱」）却是友好提示「请先在页面上选择一个订单」。行为不一致且 spec 论断错误。建议：规划器在「动词命中但目标必填实体缺失」时抛专用信号，由 `RunOrchestrator` 走 `EntityRequirementCheck` 同款 `message.delta` 提示并 COMPLETED（不算失败）；或在 §2.4.3 明说接受 `TOOL_SELECTION_INVALID` 并把「理论不可达」删掉，同时加一条 e2e（「删除订单」无号码）把行为钉死。

### S-B 策略拒绝复用 `CONFIRMATION_REJECTED`，用户文案「确认已过期或已被使用，请重新发起」误导（§2.4.4、⑬）
`RunOrchestrator.userMessage("CONFIRMATION_REJECTED")` 只有一句。⑬ 场景是「订单状态不允许删除」，用户会被引导「重新发起」并再次失败。建议 spec 规定：`run.failed.code` 仍 `CONFIRMATION_REJECTED`（不动 sse 契约），但 `message` 按内部原因分两类文案（令牌 / 并发 → 现文案；重校验策略拒绝 → 「订单状态已变化，本次操作未执行」），`ConfirmationRecheck.reject` 返回的原因只进日志。

### S-C 需确认工具无注册 `ConfirmationRecheck` 时的处置未定义（§2.4.4）
agent-safety §3 要求确认时重新校验订单状态。本 change 三个高风险工具都有 recheck，但 runtime 通用化后若某工具查不到 recheck，spec 没说是拒绝还是直接执行。建议：fail-closed —— 查不到 → `INTERNAL_ERROR` 并结束 Run；启动自检（可并入 `PlanSelfCheck` 或 `InlineActionSelfCheck`）断言「Registry 中所有 `confirmation=required` 工具都有 `ConfirmationRecheck` 与 `ScreenBuilder.confirmToolIds()` 覆盖」，把红线机械化（Hashimoto）。

### S-D `SeedLoader` 时间字段类型与 spi 依赖（§2.2、T02）
spec 说 record 内金额为 `BigDecimal`，但未说 `created_at` 等时间字段在 record 内是 `Instant` 还是 `String`。`Instant` 反序列化需要 `jackson-datatype-jsr310`，`platform-spi` pom 现只有 `jackson-databind`；全仓 pom 均未见 jsr310。建议写死：spi 增 `jackson-datatype-jsr310`（仍非 Spring，不违红线）并在自建 `ObjectMapper` 注册 `JavaTimeModule`；或 seed record 时间用 `String` 由仓储 `Instant.parse`。T02 输出随之列出 pom 改动。

### S-E tasks 依赖字段与依赖图不一致；T02 超 0.5 天（tasks.md T02 / T06b / T07a / T07b / 依赖图）
- T06b 依赖字段只写 `T06a`，输入写 `T03–T05`，图注写「T06b 依赖 T04」；T04 不在 T06a 的传递闭包内（T06a ← T01, T05 ← T03 ← T02），漏写就是真漏。
- 图画 `T06b → T07a`，T07a 依赖字段却只写 `T06a`；T07b 验收要跑 §4 五条消息（含 product / aftersale 屏），依赖字段只写 `T07a`。
- T02 = 生成器（5 张表 + 夹具约束）+ 4 份 DDL + 4 份 README + `SeedLoader` + `OrderSnapshotProvider` / `OrderSnapshot` + `check-seed`（8 项校验 + DDL 解析）+ ci / doctor 接入，> 0.5 天。
建议：T06b 依赖 `T06a, T04`；T07a 依赖 `T06b`（或明确说 T07a 可与 T06b 并行、T07b 依赖 `T07a, T06b`）；T02 拆 T02a（gen-seed + json + DDL + README）/ T02b（spi `SeedLoader` + `OrderSnapshot` + `check-seed` + ci / doctor）。

### S-F `ToolDisplayNames` 改可变注册表的实现约束未写；recheck 工具 version 来源未写（§2.4.1、§2.4.4；上轮 L5）
- `LlmConfiguration` 现以 `ToolDisplayNames.all()`（不可变 Map）在 Bean 构造期传给 `RuleBasedLlmClient` / `SpringAiLlmClient`，回填发生在 `ApplicationReadyEvent`，快照永远为空 → `Step.displayName` 退化为 toolId。spec 需写明：LlmClient / `ToolSelectionValidator` 持有注册表引用（或函数 `Function<String,String>`），不持有快照。
- `ConfirmationRecheck.recheckToolId()` 无版本；runtime 调 Gateway 必须带 `toolVersion`。现状硬编码 `"1.2.0"`。建议：runtime 从当前 Run 计划中同 toolId 的前置步骤取 `version`（三个 recheck 工具都恰是各自的前置只读步骤），找不到 → `INTERNAL_ERROR`；写进 §2.4.4 与 T06a。

---

## 5. LOW

- **L-1** §2.4.1「`displayName` 不再硬编码：runtime 启动时经 `ToolSearchPort.names(principal?)`… **不**改 tool-search 契约，改为…」是被放弃方案的残句，删掉前半句只留 `ToolNameSink` 方案。
- **L-2** `OrderList.emptyText` 被用作「共 N 单，仅展示最近 M 单」截断提示，语义是空态文案；建议单独 `footerText?` 或 `note?`，契约与 Zod 同步。
- **L-3** ⑬ / §6.2「`order.delete` 审计 0 行」需按该 Run 的 `runId` 作用域 grep（⑫ 已产生 1 行 `toolId=order.delete … status=succeeded`），否则断言恒红。
- **L-4** `order.detail.get` 对已软删（`DELETED`）订单的行为未定义（404 类 `HANDLER_ERROR`？仍返回并 status=DELETED？）；输出 `status` enum 若含 `DELETED` 与 inputSchema enum 不含要在 manifest 里写清。
- **L-5** §6.2「单独 Gateway `order.list.search {} → total == 30`」在 ⑫ 之后为 29；标注「独立启动或在 ⑫ 之前」。
- **L-6** `trustedArgs` 键与确认屏 `Form.fields[].name` 应互斥（否则用户填写被静默覆盖）；建议 runtime 加一条不变量校验（违反 → `INTERNAL_ERROR`），并写进 §2.4.4。
- **L-7** 上轮 L2：`wiki/api-contracts.md` 标注 `Table`「仅 playground」未列入 T10b；T02 输出把 `data/**` 写进尚不存在的 product / aftersale 模块目录，注明「先建目录，pom 由 T04 / T05 补」。

---

## 6. INFO（已核实、无需修改）

- **I-1** ⑬' 期望与 `GatewayExceptionHandler` 一致：`HANDLER_ERROR → 502 + INTERNAL_ERROR`，`InvokeToolUseCase` 审计 `failed:HANDLER_ERROR` 后 rethrow。
- **I-2** `ComponentHandlers` 改名不影响公共 API：`FormComponentHandlers` / `RenderedComponentProps` 未在 `index.ts` 导出；`SchemaRendererProps.onIntent?` 为可选扩展；`TYPE_EXPORTS` 10 → 11（`IconName`）与 verify-pack 机制一致。
- **I-3** `data-intent` 含中文 / 空格在 HTML 属性与 CSS 属性选择器（带引号）中均合法；antd-mobile 5.42.3 含 `Steps`。
- **I-4** `ToolNameSink` 放 spi 的依赖方向成立：registry → spi、runtime → spi，Spring 在 app 装配，无 registry → runtime。
- **I-5** ⑪ 可行性：M2 用例对 10002 创建退款不改订单状态（refund-service 无订单写权限），10002 仍 SHIPPED 且种子无售后单 → `AftersalePolicy` 通过。

---

## 7. 结论

**verdict: REVISION REQUIRED** — 5 条 MUST FIX（M-A 空 Form 违契约；M-B ⑮ 夹具已被退款；M-C 10007 与排序 / 可见区矛盾；M-D 自检数 5 / 6 不一致；M-E `OrderSnapshot` 缺 `productName`）。上轮 7 条 MUST 已闭环 6 条、M7 部分闭环（依赖字段）。第 3 轮重点复核：§2.2 夹具 / 可见区状态表与 ⑦ / e2e-frontend / gen-seed / check-seed 四处数字一致；删除确认屏组件清单三处一致；自检数全文统一为 6；⑮ 夹具与 §4.5 / T07a 同步；tasks 依赖字段与图一致。
