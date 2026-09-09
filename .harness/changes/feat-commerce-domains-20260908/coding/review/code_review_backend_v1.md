# Code Review（backend + contracts + harness）— feat-commerce-domains-20260908

- mode: execution
- 评审对象：`git diff d988063..HEAD -- backed .harness/contracts .harness/scripts .harness/rules`
- 独立性：未阅读 `coding_report_v1.md` 与 summary.md「经验沉淀」；仅对照 spec v3.2 / tasks.md 与产出物本身。
- 依据：backend-standard / contracts / agent-safety / project-structure / expert-reviewer execution checklist；公司级规范（BigDecimal 只从字符串构造、无空 catch、日志无密钥与用户原文、domain/ 不依赖 Spring/Jackson、领域互不 import）。

## 0. 机械校验（本次评审实际执行）

| 项 | 结果 |
|---|---|
| `backed/./mvnw -q -B verify` | 退出码 0 |
| `node .harness/scripts/check-module-deps.mjs` | 0（含新增「领域互不引用」规则） |
| `node .harness/scripts/check-seed.mjs` | 0，7 组不变量全过 |
| `node .harness/scripts/check-contracts.mjs` | 9 schemas / 26 examples OK |
| 冻结产物 `deployment/e2e-backend-rule.out` / `-live.out` | 107 / 109 passed，0 failed |
| `grep -rIn -E 'sssaiapi\|sk-sssaicode\|gpt-5\.6'`（排除 node_modules/target/dist/.git） | **1 个文件命中**（见 M-2），不为 0 |
| `deployment/backend.log` 含用户原文「包装破损」/「帮我把这个订单退款」 | 0 |
| domain/ 包 Spring / Jackson 引用 | 0（脚本 + 人工抽查 DeletionPolicy / AftersalePolicy / LogisticsStatus / EligibilityPolicy） |
| `new BigDecimal(double)` | 0；金额均从字符串（SeedLoader / `args.asText()`）构造 |
| 空 catch | 0 |

## 1. 发现

| # | 位置 | 问题 | 建议 | 分级 |
|---|---|---|---|---|
| M-1 | `backed/domains/refund-service/src/main/java/com/strato/domain/refund/infra/screen/RefundScreens.java:44-57` | 退款确认屏的订单 Card 读 `previousOutputs.get("order.detail.get")`，但退款计划固定为 `[refund.eligibility.check, refund.preview, refund.create]`（IntentVerbs.PREREQUISITES / PromptBuilder），`order.detail.get` 永远不在退款 Run 内 → `detail` 恒为 null，屏上「商品」退化为「订单 10002」、「状态」**硬编码回退 "PAID"**。冻结产物 `deployment/run2_events.log`（订单 10002，种子状态 SHIPPED）的确认屏实际为 `{"状态":"PAID"}`，即高风险确认屏向用户展示了**虚构的订单状态**。agent-safety §3 要求用户确认的是真实计划与真实状态；虽属首期逻辑「原样迁出」，但本 change 引入 SHIPPED 可退订单后缺陷已可观测。 | 二选一：(a) `refund.eligibility.check` / `refund.preview` 输出增加 `orderStatus` / `productName` 字段（改 manifest outputSchema），屏从中取值；(b) 屏上未知即不展示该行（去掉 `"PAID"` / `"订单 xxx"` 兜底），并在 RefundRecheck 中比对状态。禁止任何硬编码业务状态默认值。 | MUST FIX |
| M-2 | `.harness/changes/feat-commerce-domains-20260908/coding/coding_report_v1.md:43` | 全仓 `grep -E 'sssaiapi\|sk-sssaicode\|gpt-5\.6'` 应为 0，实际命中 1 处（该行同时含三个模式）。评审按独立性原则未阅读该文件内容，无法判断是真实网关地址 / 密钥 / 模型名，还是把 grep 命令本身写进了报告；无论哪种，冻结产物红线（e2e-backend.sh:277 注释「LLM 网关地址与密钥不得出现在日志 / 冻结产物」）当前不成立。 | 改写该行：用环境变量名（`STRATO_LLM_BASE_URL` / `STRATO_LLM_API_KEY` / `STRATO_LLM_MODEL`）指代，不写字面值；并把这条 grep 加进 `deploy-verify.sh` 或 `harness-doctor.mjs`（对 `.harness/changes/**` 也扫）成为门禁。 | MUST FIX |
| S-1 | `backed/agent-runtime/.../infra/selfcheck/ConfirmationCoverageSelfCheck.java:93-94` | 「trustedArgs 键与确认屏 Form 字段互斥」用 `rc.trustedArgs(mapper.createObjectNode())` 取键：`RefundRecheck.trustedArgs` 在 `refundableAmount` 为空时返回 `Map.of()`，于是对 refund.create 该断言**空集恒过**（把 `RefundScreens` 的 Form 加一个 `amount` 字段自检仍绿）。运行期只靠 `args.putAll(trustedArgs)` 的顺序兜底。 | `ConfirmationRecheck` 增加 `Set<String> trustedArgKeys()`（常量声明），自检与 `executeConfirmed` 都用它；或自检用带 `refundableAmount:"1.00"` 的合成输出探测。 | SHOULD |
| S-2 | `.harness/contracts/ui-schema.schema.json`（`tableRow.cells.description`）vs `backed/agent-runtime/.../screen/ScreenRegistry.java` | 契约写明 `cells` 键 ⊆ `columns[].key`「跨字段约束由 runtime ScreenRegistry 检查」，ScreenRegistry 没有任何 columns / cells 相关代码；前端 Zod（`uiSchema.ts:81-83`）也只限 ≤16 键。契约声明了不存在的校验点。 | 在 `ScreenRegistry.toUi` 增加 Table 组件的 cells ⊆ columns 断言（违反 → IllegalStateException，与 duplicate builder 同级）；或删除契约里的这句话。 | SHOULD |
| S-3 | `backed/agent-runtime/.../domain/DomainRouter.java:22-25` 与 `infra/llm/IntentVerbs.java:17-23, 58-63` | 领域路由按 refund → aftersale → order → product 顺序，动词表按 删除 → 物流 → 售后(含「退货」) → 退款 顺序，两表顺序不一致。具体输入「订单 10002 退货退款」：路由命中 refund，动词命中 `aftersale.create` → 候选内无该工具 → `TOOL_SELECTION_INVALID`，Run FAILED，用户看到「暂时无法为该请求制定可执行的方案」。另 `fallbackTarget("refund", true)` 返回 `refund.detail.get`（不存在的工具，`referencedToolIds()` 因含占位符而未被 PlanSelfCheck 校验）：输入「refund 订单 10001」→ 同样 FAILED。 | 让动词表与路由表共用同一顺序（或让 IntentVerbs.target 只在「目标工具属于已路由领域」的动词组内匹配）；`fallbackTarget` 用显式 `DETAIL_TOOL` 映射（refund → `refund.status.get`），并把展开后的 toolId 纳入 `referencedToolIds()`。给 PlanSelfCheck 加「退货退款」一例。 | SHOULD |
| S-4 | `backed/agent-runtime/.../application/EntityExtractor.java:15-16` | `订单\s*(\d{5})` 无右侧数字边界：「订单 100021 的物流」抽出 `10002`，命中真实存在的另一单；写路径有确认屏兜底（屏上显示 10005），读路径会直接展示错单。 | 改为 `订单\s*(\d{5})(?!\d)`、`商品\s*(P-\d{4})(?!\d)`。 | SHOULD |
| S-5 | `backed/agent-runtime/.../infra/llm/ToolSelectionValidator.java:82-89, 102-107` | LIVE 模式只校验 args 键 ⊆ inputSchema.properties 与「需确认步骤实体参数存在」，不校验实体参数**值**等于 `entities` 中抽取的 ID。模型可把 `orderId` 换成同租户任意订单（PromptBuilder 只是「提醒」不要发明）。读步骤会直接返回错单数据；确认步骤靠屏兜底。 | `validate` 增加 `PlanRequest.entities` 入参：凡 `ENTITY_ARGS` 中的键，其值必须等于 `entities.get(type)`，否则 `TOOL_SELECTION_INVALID`（重试一次）。 | SHOULD |
| S-6 | `.harness/scripts/e2e-backend.sh:24`；`.harness/scripts/deploy-verify.sh:11-13` | `pkill -f "app/target/app.jar"`（deploy-verify 还有 `pkill -f "vite preview"`）无条件执行，随后才检查 `$PORT` 占用。设置 `STRATO_PORT=8091` 本意是避开用户在 8080 的实例，但脚本一开始就把它杀掉了；「端口可覆盖」的承诺不成立。 | 只 kill 本脚本启动的 PID（`$!` / pidfile），或仅当 `PORT` 为默认 8080 且占用者命令行含 `app.jar` 时才 pkill；deploy-verify 的 `cleanup` 同理。 | SHOULD |
| S-7 | `backed/agent-runtime/.../infra/llm/SpringAiLlmClient.java:63-67` | `.entity(LlmPlanDraft.class)` 在模型输出非 JSON / 结构不符时抛 RuntimeException，被当作「传输错误」→ 直接 `INTERNAL_ERROR`，不进 `MAX_ATTEMPTS` 重试。backend-standard §3：「LLM 输出按预期 Schema 校验，失败重试一次」。 | 把 `.call().content()` 与 `mapper.readValue(..., LlmPlanDraft.class)` 拆开：解析失败抛 `RunFailure("TOOL_SELECTION_INVALID", "planner output not parseable")` 进入重试；仅网络 / HTTP 异常走 transport 路径。 | SHOULD |
| S-8 | `backed/agent-runtime/.../application/RunOrchestrator.java:86, 397-430, 566-571` | `stepOutputs` 只在 `confirm()` 终态时移除；⑦⑧⑨⑩ 这类无确认的 Run 在 `runSteps → complete()` 后条目永久保留（本 change 新增的四个只读工具让泄漏成为主路径）。`lastUi` 供 GET 使用可接受，但同样无上限。 | `complete()` / `fail()` 内 `stepOutputs.remove(runId)`；`lastUi` 加 TTL 或与 `RunRepository` 同生命周期。 | SHOULD |
| L-1 | `backed/domains/aftersale-service/.../infra/screen/AftersaleRecheck.java:41-50` | 用 `Aftersale.Type.valueOf(asText("RETURN"))` / `Status.valueOf(asText("CANCELLED"))` 把 recheck 输出重建成完整聚合，缺失字段默认值向「非进行中」方向放行（CANCELLED 不算 active）。当前因 Gateway 已按 outputSchema enum 校验、字段 required，实际不可达；未知值会抛 IAE → runtime 兜成 INTERNAL_ERROR（fail-closed，可接受）。但用哑字段（tenantId ""、Instant.EPOCH）拼聚合只为拿 status，是误用信号。 | `AftersalePolicy.reject(String orderStatus, Collection<String> existingStatusNames)` 以状态名判定；缺失 status 直接 `Optional.of("recheck output missing status")` 拒绝。 | LOW |
| L-2 | `OrderScreens.detail:160-176`、`logistics:204-209`；`AftersaleScreens.orderCard`；`ProductScreens.detail:112-115` | 多处 Card item value / Timeline label 直接放工具输出，不 `truncate`（契约 value ≤200、label ≤80）。数据来自领域自身，种子安全；但一条长 `description` / `productName` 会让整屏 ui-schema 校验失败 → INTERNAL_ERROR。 | 在 `UiNodes.labelValue` / Timeline item 构造处统一按契约上限截断（UiNodes 已有 `truncate`）。 | LOW |
| L-3 | `AftersaleListGetHandler.handle:45`；`AftersaleScreens.list` | `aftersale.list.get` 无 limit，`findByTenant` 全量返回；Table `rows.maxItems=50`。每次 ⑪ 都新增一单，长时间运行（≥47 次创建）后「售后记录」屏校验失败。 | manifest 加 `limit`（≤50，默认 20）或屏内 `rows` 截断 + `total`/`emptyText`。 | LOW |
| L-4 | `SeededAftersaleRepository:49` | 种子幂等键占位 `"seed-<aftersaleId>"` 与运行期 Gateway 透传的 idempotencyKey 同一命名空间；直连 Gateway 传 `seed-AS-0001` 会命中种子记录而「幂等返回」旧单。 | 用不可能由外部传入的前缀（如 ` seed:`）或单独的 `Map<String,Aftersale> seeds`。 | LOW |
| L-5 | `OrderScreens.detail:186-188` | 详情屏放一个空 Timeline，`emptyText` 写「点击「查看物流」查看完整轨迹」，但详情屏没有任何行内按钮 / 动作——文案指向不存在的控件。 | 去掉该 Timeline，或在 Card 加 inlineAction 需契约支持（当前 Card 无 actions）→ 直接删。 | LOW |
| L-6 | `.harness/scripts/e2e-backend.sh:236, 225-237` | ⑬' 断言名为 `audit failed:HANDLER_ERROR`，实际只 grep `status=failed`，任一失败码都通过。spec §6.2 的「`startup registration done: 12 tools from 4 sources`」与「`{status:"DELETED"}` 被 enum 拒绝 → 400」两条验收未出现在脚本中。 | grep 加 `HANDLER_ERROR`；补两条 check。 | LOW |
| L-7 | `RunOrchestrator.waitForConfirmation` / `OrderScreens.confirmation` | ⑬ 对 PAID 订单也照常出「确认删除」屏，确认后才被策略拒绝，用户文案「订单状态已变化」与事实不符（状态从未变化）。spec 明确如此设计（双保险各测各的），故不判违规。 | 后续可让 `OrderScreens.confirmation` 读 detail.status 在屏上标 danger「当前状态不可删除」并仍走确认（保持 e2e ⑬ 语义）。 | LOW |
| L-8 | `ToolSelectionValidator.validate:80, 86-87`；`SpringAiLlmClient:58-62` | `TOOL_SELECTION_INVALID` 原因串拼接模型产出的 `toolId` / arg 键名并进 WARN 日志；模型可能回显用户原文（SpringAiIntentClassifier 已按此思路只记长度）。 | 与分类器一致：只记长度或白名单化后的值。 | LOW |
| L-9 | `OrderDeleteHandler.handle:56`；`AftersaleService.create:65`；`RefundService.create:71` | `Instant.now()` 直取系统时钟；runtime 已注入 `Clock`，领域侧不一致，测试不可控。 | 领域侧注入 `Clock`（spi 或 Spring Bean）。 | LOW |
| L-10 | `FallbackScreenBuilder.confirmation` | `waitForConfirmation` 先 `coversConfirmation` fail-closed，`ScreenRegistry.confirmation` 的 fallback 分支运行期不可达，只有 `ConfirmationCoverageSelfCheck` 覆盖后才调用；方法成死路径但注释仍描述其行为。 | 让 `ScreenRegistry.confirmation` 对无 builder 的 toolId 直接抛 IllegalStateException，删掉 fallback 的 confirmation 实现。 | LOW |
| I-1 | `RunOrchestrator.executeConfirmed:332-334, 353-355` | Gateway FORBIDDEN 映射为 `CONFIRMATION_REJECTED`，默认文案「确认已过期或已被使用」——权限问题被描述成令牌问题。首期 Registry 已按权限过滤候选，实际难触达。 | 可给 `RunFailure.withUserText` 一条「无权执行该操作」。 | INFO |
| I-2 | `backed/app/src/main/resources/application.yml`；`orders.json` | 所有订单 `user_id=user_001`；`user_002` 同租户 `order:read` 可读全部订单——用户级归属未建模，与 spec 非目标一致。 | 记录为后续 change。 | INFO |
| I-3 | 契约 / Zod 交叉 | `ui-schema.schema.json` 五组件 if/then、`inlineAction.intent` pattern、`labelValue.tone` enum、`tableRow.cells` ≤16 与 `uiSchema.ts` 逐项一致（抽查 16/25/49/58-62/81-83 行）；`UiSchema.ComponentType` 五枚举一致；8 个示例 + run-summary / sse 示例经 check-contracts 通过。 | — | INFO |
| I-4 | 安全边界逐项 | §1 四面：runtime 只经 `ToolGatewayClient` / `ToolRegistryClient`，`InlineActionSelfCheck` 亦走 Gateway ✓；§2 候选六字段、description 转义限长 ✓、模型 toolId 必在候选 ✓；§3 令牌绑定 runId/actionId/stepSeq/argsDigest/formKeys、一次性、10 分钟 ✓，两遍生成并断言屏稳定 ✓，formData 白名单 + trustedArgs 覆盖 ✓，缺 recheck / 屏 fail-closed ✓；§5 Gateway 幂等 / 审计（9 字段）✓；§6 SSE 无堆栈无参数原文 ✓。 | — | INFO |
| I-5 | 分层 / 依赖 | 四个领域 pom 只依赖 spi + contracts + spring-context；refund / aftersale 经 `OrderSnapshotProvider` 读订单；`check-module-deps` 新增规则可机械捕获 `com.strato.domain.<other>.`；`platform-spi` 只依赖 Jackson（UiNodes / SeedLoader），无 Spring ✓。`ToolNameSink` 方向 registry → spi ← runtime ✓。 | — | INFO |
| I-6 | 数据 / 种子 | `SeededOrderRepository` 加载时校验 Σ line_amount == amount、seq 连续、至少一行；`check-seed` 覆盖 spec §2.2 全部不变量且每条都有对应失败分支（非空集断言：订单 30 / 商品 20 / 售后 4 / 退款 3 写死）。 | — | INFO |

## 2. 与 spec 的偏差记录

- spec §6.2 两条验收（`12 tools from 4 sources` 日志、`{status:"DELETED"}` → 400）未进 `e2e-backend.sh`（L-6）。
- spec §2.4.4「trustedArgs 与 Form 字段互斥由 ConfirmationCoverageSelfCheck 校验」——实现存在但对 refund 空集恒过（S-1）。
- 契约文本声称的 `cells ⊆ columns` runtime 校验未实现（S-2）。
- 其余（屏映射表、动词表、前置表、MissingEntity 路径、双保险 ⑬/⑬'、`TRUSTED_ONLY_ARGS`、fail-closed）与 spec 一致。

## 3. verdict

**verdict: REVISION REQUIRED**（MUST FIX 2 条：M-1 退款确认屏虚构订单状态；M-2 冻结产物 secret grep 非 0）
