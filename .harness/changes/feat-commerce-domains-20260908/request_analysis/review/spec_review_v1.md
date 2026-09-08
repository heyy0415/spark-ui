# Spec Review v1 — feat-commerce-domains-20260908

| 字段 | 值 |
|---|---|
| mode | plan |
| 日期 | 2026-09-08 |
| 轮次 | 1 |
| 评审对象 | `request_analysis/spec.md` v1、`request_analysis/tasks.md` v1 |
| 依据 | rules/{agent-safety, contracts, backend-standard, project-structure, coding-standard}.md；expert-reviewer plan 6 项；00-contract-spec.md；现状代码（RunOrchestrator / RuleBasedLlmClient / EntityRequirementCheck / DomainRouter / UiSchemaBuilder / ToolSelectionValidator / InMemoryToolRegistryRepository / order & refund domains / core src / check-* 脚本 / e2e 脚本）；前三个 change 的经验沉淀 |
| **verdict** | **REVISION REQUIRED**（MUST FIX 7 条，SHOULD 12 条，LOW 5 条，INFO 4 条） |

> 独立性说明：只看产出物与仓库现状，未参考任何编码 Agent 自评。评审中含命令的判断均已实测（Ajv strict pattern / if-then；Registry 候选排序读源码确认）。

---

## 0. plan 模式必查 6 项

| # | 项 | 结果 | 说明 |
|---|---|---|---|
| 1 | 「非目标」存在且非空 | ✓ | §3 八条。漏项见 S12 |
| 2 | 每条验收可被命令 / 断言校验 | ✗ | §6.2 e2e ⑦ 与 §4.1 / T03 的数字互相矛盾（M6）；⑭「按实际行为写死其一」在实现前不可校验且与 M1 纠缠；§6.1 mysql 项标注可选可接受 |
| 3 | 风险章节 ≥1 失败模式 + 缓解 | ✓ | §7 七条。但「规则规划器通用化后 refund 顺序不变」这条缓解经核对是**错的**（M1） |
| 4 | 每个 task 标所属端，contracts task 在前 | ✓ | T01 contracts 无依赖，T06 / T08 依赖 T01 |
| 5 | 跨端 task 列出契约文件 | ✓ | T01 / T06 / T08 均指向 `ui-schema.schema.json` |
| 6 | 每个 task ≤ 0.5 天 | ✗ | T06 / T07 / T08 / T10 明显超（M7） |

---

## 1. MUST FIX

### M1 通用规则规划器对真实候选集合的结果与 spec 断言不符（§2.4 规划器段、§7 第 2 行、§4.1–4.3、⑭）
**位置**：spec §2.4「规划器：…通用规则」、§7「规则规划器通用化后 refund 三步顺序变化」、T07。
**问题**（按现状代码推演，不是感受）：
1. Registry 候选按 `toolId@version` 字典序返回（`InMemoryToolRegistryRepository.findByDomain` 有 `sorted(key)`）。refund 领域 user_001 可见 4 个：`refund.create / refund.eligibility.check / refund.preview / refund.status.get`。规则「只读在前、需确认最后、缺必填跳过」在有 orderId 时的自然结果是 **eligibility → preview → status.get → create，4 步**，不是 spec 说的 3 步。后果：`PlanSelfCheck`（断言 3 步）启动即失败；首期 e2e「event sequence」（确认前恰 2 组 tool.* 帧）、deploy-verify 同款断言全部红。spec 把「首期 e2e 全量保留作回归」当缓解，但按本 spec 实现回归必红。
2. order 领域 4 个候选 `order.delete / order.detail.get / order.list.search / order.logistics.get`。消息「查看订单 10002 的物流」抓到 10002 后，三个只读全部可填参 → 跑 detail + list + logistics 三次 Gateway，再因为「需确认最后且最多 1 个」把 `order.delete` 追加为确认步骤 → 用户点「查看物流」得到的是**删除订单的确认屏**。「订单 10002 申请售后」同理会在 `aftersale.list.get` 后追加 `aftersale.create`（这条恰好是想要的），而「订单 10002 有售后吗」也会得到创建售后的确认屏。规则规划器不理解语义是已知前提，但 spec 宣称的「快捷指令 → 对应工具」在此规则下**不成立**，这是设计硬伤，不是可接受的近似。
3. 多个只读步骤都执行时，spec 未规定结果屏取哪一个工具的 `ScreenBuilder.result`（`RunOrchestrator.runSteps` 目前只读步骤不发 `ui.replace`，只缓存输出）。「看看我的订单」如果 plan 只有 list.search 没问题；一旦 plan 含多个只读，屏选择未定义。
**建议**：
- 规则规划器补一层**确定性「意图动词 → 目标工具」表**（可放 manifest 扩展字段 `x-triggers` 或 runtime 常量）：`删除|删掉 → order.delete`、`物流|到哪 → order.logistics.get`、`售后|换货|维修 → aftersale.create`、`退款 → refund.create`、`详情 → *.detail.get`、无动词有实体 → detail，无实体 → list。目标工具确定后再按「目标工具的前置只读依赖」补步骤（refund.create 前置 = eligibility + preview，写在规则里而不是靠排序碰巧），**status.get 不进 refund 计划**。需确认工具只在动词命中时加入，绝不「顺手追加」。
- 明确「结果屏 = 计划最后一个成功步骤的 `ScreenBuilder.result`」，并写进 T06 验收。
- §7 该行改写为真实风险：「规则表与候选集合脱节」→ 缓解：`PlanSelfCheck` 扩为对 5 条核心消息各断言 toolId 序列（对应经验沉淀「按每个领域各举一例」）。
- ⑭ 随之可确定：user_002 候选无 delete、动词「删除」无目标 → `TOOL_SELECTION_INVALID` 或无能力提示，写死其一。

### M2 `EntityExtractor` 放在规划器里，但规划前的 `EntityRequirementCheck` 只看 `pageContext` → 行内按钮链路在无页面实体时全部被拦截（§2.4、§4.1/4.2/4.5、T07）
**位置**：spec §2.4「规则规划器对…快捷指令的实体提取」、T07 目标；现状 `RunOrchestrator.start` L149–157、`EntityRequirementCheck.check`。
**问题**：`RunOrchestrator` 在 `llm.plan` 之前先跑 `EntityRequirementCheck.check(domain, tools, selected)`，`selected` 只来自 `pageContext.selectedEntity`。首页 `/`（无 URL 实体）点「退款」发出「订单 10001 退款」：refund 全部候选 required orderId，`selected == null` → 直接回「请先在页面上选择一个订单」并结束 Run。§4.5、⑪（无 pageContext 的「订单 10002 申请售后」，aftersale.create / list.get 若 list.get 的 orderId 可选则放行，但 create 仍缺实体）、e2e-frontend「点行内按钮 → 新屏」都跑不通。LIVE 模式同样受影响（拦截在规划器之前，与规划器实现无关）。
**建议**：spec 明确写：**实体抽取在 `RunOrchestrator.start` 路由之后、`EntityRequirementCheck` 之前执行**，产出 `Map<entityType, id>`（多类型，如 `{order:10002, product:P-1003}`），`EntityRequirementCheck.check` 与 `LlmClient.PlanRequest.entity` 都改用这个 map（现状是单一 `type/id` 两键）；`pageContext.selectedEntity` 合并进 map，消息中的同类型实体优先。`PromptBuilder.user` 的「页面实体」段改名「已识别实体」。T07 验收加：无 pageContext 的「订单 10001 退款」事件序列与首期带 pageContext 的完全一致。

### M3 `DomainRouter` 关键词表新增两领域但未规定匹配顺序，且关键词有明显冲突（§2.1 领域路由）
**位置**：spec §2.1 最后一条；现状 `DomainRouter.defaultRules()` 「首个命中即返回，按声明顺序」。
**问题**：
- 「订单 10002 申请售后」同时含「订单」与「售后」；若 aftersale 声明在 order 之后 → 路由到 order → 按 M1 结果连售后候选都拿不到。spec 没写顺序。
- product 关键词「货」会命中 order 的「发货」与 aftersale 的「退货」；「买」会命中退款原因文案「买错了」类消息。
- refund 删掉「退货」后，`DomainDescriptions.refund` 仍写「退款、退货…」（模型分类器看的说明与规则表打架）。
**建议**：spec 写死顺序 `refund → aftersale → order → product`（写操作领域优先，通用词最后），product 关键词收敛为「商品 / product / 有什么卖」，去掉单字「货」「买」；`DomainDescriptions` 两条同步（refund 去「退货」，aftersale 加）。T07 验收加 5 条核心消息的 `route … domain=… source=rule` 日志断言。

### M4 `ScreenBuilder` 放 `platform-spi` 却以 `UiSchema`（contracts-java 类型）为签名 → 触发 project-structure §2「platform-spi ↛ 任何其他模块」红线（§2.4、T06）
**位置**：spec §2.4 第一段接口签名 `UiSchema result(...)` / `UiSchema confirmation(...)`；T06 输出「spi 接口」。
**问题**：`UiSchema` record 在 `contracts-java`；`platform-spi` 的 pom 目前只有 jackson。按 spec 字面实现要么 spi 依赖 contracts-java（红线 + Maven 依赖图变形），要么把 `UiSchema` 挪进 spi（把契约投影拆两处，违反 contracts.md §1「后端投影在 contracts-java」）。`check-module-deps` 当前只查五个模块的 pom 是否依赖 domains，不会抓这条，所以会静默通过。
**建议**：spi 接口一律用 `JsonNode`：`JsonNode result(String toolId, JsonNode output, ScreenContext ctx)` / `JsonNode confirmation(String toolId, JsonNode fixedArgs, Map<String,JsonNode> previousOutputs, String token)`；runtime 收到后 `mapper.treeToValue(node, UiSchema.class)` + `SchemaValidator.assertValid("ui-schema")`（校验仍在 runtime 一处）。`OrderSnapshotProvider` 返回值也要是 spi 自有 record（`OrderSnapshot{orderId,status,amount,currency,items…}`），不能复用 refund 的 `EligibilityPolicy.Snapshot`。顺带把 `check-module-deps` 加一条「platform-spi / contracts-java 的 pom `<dependencies>` 不得含任何 `com.strato` artifact」并用植入负例证明它会红。

### M5 确认后「重校验」路径的设计自相矛盾，且把领域策略隐含塞进 runtime（§2.4 末段、§4.3、⑬、T07）
**位置**：spec §2.4「`order.delete` / `aftersale.create` 确认后经 Gateway 重调只读工具（`order.detail.get`）校验状态仍满足策略」；§4.2「重校验（经 Gateway 调 `aftersale.list.get` …）」；§4.3 / ⑬ 期望 `run.failed TOOL_EXECUTION_FAILED`；T07「recheck 工具由 `ScreenBuilder` 声明 `recheckToolId()`」。
**问题**：
1. 「校验状态仍满足策略」需要 `DeletionPolicy` / `AftersalePolicy` 的判断，这些是领域模块的 domain 类，agent-runtime 不能 import（红线 5）。runtime 拿到 `order.detail.get` 输出后只能做「订单存在」这种无意义校验，或者复制一份策略到 runtime（双份真源）。
2. 同一类失败两套语义：refund 路径重校验不通过 → `CONFIRMATION_REJECTED`（现状）；delete 路径 spec 指望 handler 抛异常 → `HANDLER_ERROR` → `TOOL_EXECUTION_FAILED`。如果重校验按 1 真做了，⑬ 的期望就变成 `CONFIRMATION_REJECTED`；如果不做，§2.4 那句就是空话，而 agent-safety §3「确认时后端重新校验订单状态」对 delete / aftersale 未落实。
3. refund 的「金额只信重校验、须等于确认屏展示值」（`executeConfirmed` + `shownRefundAmount`，是首期评审 M3 的成果）在通用化后归谁？spec 只说「逻辑不变」，但 `RunOrchestrator` 已不再认识 `RefundConfirmCard`。
**建议**：在 spi 把重校验做成显式契约，例如 `ConfirmationRecheck { String recheckToolId(); Map<String,String> recheckArgs(fixedArgs); Optional<String> reject(JsonNode recheckOutput, JsonNode shownUi); Map<String,String> trustedArgs(JsonNode recheckOutput) }`，由各领域 `ScreenBuilder` 实现（refund：eligible + amount 比对并返回 `amount`；order.delete：`DeletionPolicy.allows(status)`；aftersale.create：`AftersalePolicy`）。runtime 只做：调 recheck 工具 → 调 `reject` → 非空则 `CONFIRMATION_REJECTED` → 合并 `trustedArgs` 覆盖 formData → 执行。⑬ 期望改为 `CONFIRMATION_REJECTED`（策略拒绝在重校验层），并**另加**一条「绕过重校验直接 Gateway 调 `order.delete 10001` → `HANDLER_ERROR`」证明 handler 自身也拒绝（双保险各测各的）。首期 M2 / M3 / 注入 e2e 断言保持不变作为回归。

### M6 e2e ⑦ 与 §4.1 / T03 对订单数量的断言互相矛盾（§6.2 ⑦、§4.1、T03 验收）
**位置**：⑦「`items.length` == 非 DELETED 订单数（≤ 20 由 limit 截断则断言 `total`）」；§4.1「`ui.replace{OrderList 25 行}`」；T03「`total == 30`（含 DELETED 之外）」。
**问题**：种子 30 单、默认 `limit 20` → `items.length` 恒为 20，永远不等于「非 DELETED 订单数」（30 或 29）；「25 行」不知从何而来（30 - 5 个夹具？但夹具也是订单）；T03 那句括号语义不通。同一件事三处三个数，实现时必然有人猜。这正是前三个 change 都踩过的「验收先实测」。此外 `OrderList` 展示 20 行而 `total` 30、又「不做分页 UI」，用户看不到另外 10 单——产品行为要写明（如 `emptyText` 位置显示「共 30 单，仅展示最近 20 单」）。
**建议**：写死：`order.list.search` 默认按 `createdAt desc`（或 `orderId asc`，必须选一个并写进 manifest description）；⑦ 断言 `items.length == 20 && total == 30`（在 ⑫ 之前执行）或把 e2e 消息改成「看看我最近 50 单」→ 无法用规则抓 limit，所以推荐前者；⑫ 之后追加 `total == 29`。§4.1 的「25 行」改为 20；T03 验收改为「`{}` → `total == 30`；`{status:"DELETED"}` 被 inputSchema enum 拒绝（DELETED 不在 enum）」。

### M7 T06 / T07 / T08 / T10 均超过 0.5 天，且 T06 依赖漏 T04（tasks.md）
**位置**：tasks.md T06、T07、T08、T10、依赖图。
**问题**：
- T06 = spi 接口 + `ScreenRegistry` + `FallbackScreenBuilder` + 拆 `UiSchemaBuilder` + 4 个领域屏（其中 `OrderScreens` 含 OrderList 按状态生成按钮、OrderCard 多商品、Timeline、删除确认四块）+ `RunOrchestrator` 两处改造 + 令牌白名单通用化。至少 1.5 天。
- T07 = 规划器重写 + M1 的动词表 + `EntityExtractor` + M2 的编排顺序改造 + 路由表 + 描述 + prompt + M5 的重校验通用化。至少 1.5 天。
- T08 = 6 个组件实现 + OrderCard 两端改造 + 图标表 + renderer / types / schema 三处 + verify-pack 清单。约 1 天。
- T10 = 8 条后端 e2e + ≥ 8 条前端 e2e + 3 截图 + 6 份文档。约 1 天。
- T06 的输入写了 T03–T05 但依赖只写 T01、T05；`ProductScreens` 需要 T04 的模块存在。
**建议**：T06 → T06a（spi 接口 + registry + fallback + refund 屏搬迁 + orchestrator 改造，首期 e2e 不变）/ T06b（order + product + aftersale 屏）；T07 → T07a（实体抽取 + 编排顺序 + 路由表）/ T07b（规划器动词表 + 重校验通用化）；T08 → T08a（契约投影 + OrderCard 扩展 + 图标表 + onIntent）/ T08b（三新组件 × 两端）；T10 → T10a（e2e 脚本）/ T10b（文档）。T06 依赖补 T04。

---

## 2. SHOULD

### S1 `ToolDisplayNames` / `RunOrchestrator.summaryOf` 硬编码 6 个工具，新增 5 个工具未提（§2.4、T07）
现状 `ToolDisplayNames.NAMES` 只有 6 条，`summaryOf` 只认 refund 三个。不改则「工具进度」显示裸 `order.logistics.get`，e2e-frontend 视觉验收不好看且 `tool.selected.displayName` 与 manifest `name` 不一致。建议：displayName 直接取 Registry 候选（`ToolCandidate` 六字段里没有 `name`，可在 Step 里带 manifest name——需 `ToolSearch.ToolCandidate` 加字段则动契约 `tool-search`，或让 runtime 启动时从 `registry.domains` 拉一次 name 表）；`summaryOf` 交给 `ScreenBuilder.summary(toolId, output)` 或删掉改为 null。写进 T06/T07 并在 §5 说明是否动 `tool-search` 契约。

### S2 既有断言字符串需随本 change 更新，spec 未列（§6.2、§6.4）
`e2e-backend.sh` 自检期望 `"contracts 9 schemas, 20 examples OK"` → 25；`PlanSelfCheck` 期望（见 M1）；`deploy-verify.sh`「selfcheck all OK 5」若 M1 建议的多消息自检拆成多行会变；`check-registry` 输出「7 component types」在 spec §6.3 写成 10 ✓。建议 T10 列出「需同步更新的既有断言清单」，避免实现时靠 e2e 红了再找。

### S3 `verify-pack (f)` 体积基线 40 KB × 1.1 = 44 KB，加 6 个组件 + 图标表几乎必超（§2.5、T08）
`verify-pack.baseline.json` 是 40 KB；`--write-baseline` 才能重写，缺失/超标即红。T08 验收应写「先 `pnpm -C fronted run verify-pack -- --write-baseline` 记录新基线，并在 coding_report 记录前后 KB」。否则 ci 必红一次。

### S4 确认屏经 spi 直读订单数据，绕过 Gateway 鉴权与审计，与 agent-safety §1「Runtime 不直连领域服务」表述冲突（§2.4、§4.2）
`AftersaleScreens.confirmation` 在 runtime 进程内经 `OrderSnapshotProvider` 读订单——数据读取没有经过 Gateway 的权限 / 审计 / 脱敏。首期 refund 确认屏用的是 Gateway 返回的 `order.detail.get` 缓存输出，是干净的。建议二选一并写进 spec：(a) 接受偏差，在 `agent-safety.md` §1 加注「`ScreenBuilder` 是领域模块提供的渲染代码，可经 spi 只读快照，不得写」；(b) 让 `aftersale.list.get` 输出带 `order{status,productName,amount}` 摘要，确认屏只用 `previousOutputs`。倾向 (b)，零新概念。

### S5 `user_002` 权限「只读三项」与现 e2e「user_002 refund tools == 3」冲突（§2.1 权限）
现状 user_002 = `order:read, refund:read`；e2e §6.2.5 断言 refund 候选 3。spec 写「只读三项」若指 order/product/aftersale 则 refund:read 被删，e2e 红。应写成四项：`order:read / product:read / aftersale:read / refund:read`。

### S6 e2e-frontend「第一条 SHIPPED 订单的查看物流」依赖列表排序与种子状态（§2.6）
排序未定（M6）；且现有 e2e-backend M2 对 10002 创建过退款（订单状态不变仍 SHIPPED），种子售后单若占用 10002 则 ⑪「10002 申请售后」会被 `AftersalePolicy`「无进行中售后」拒绝。建议：(1) 种子 4 条售后单只挂在 10006+ 的订单；(2) 行内按钮渲染 `data-intent="查看订单 10002 的物流"`，e2e 用属性精确定位，不用位置；(3) spec 写明 e2e-backend 步骤顺序（⑪ 在 M2 之后）与其可接受性。

### S7 `check-seed.mjs` 的 DDL 列解析要靠约束 DDL 书写格式才可实现（§2.2、T02）
自由格式 MySQL DDL 的解析很容易被 `PRIMARY KEY (…)`、`INDEX idx_x (…)`、`CONSTRAINT … FOREIGN KEY`、行尾注释打穿。spec 应把格式写成硬约束：每列独占一行、列名反引号、约束行以 `PRIMARY KEY|UNIQUE|KEY|INDEX|CONSTRAINT|FOREIGN` 开头、无行尾注释；`check-seed` 只按此格式解析并对不合格式的行报错。另写明 `SeedLoader` 的 snake_case → record 字段映射策略（`PropertyNamingStrategies.SNAKE_CASE`）以及 `ObjectMapper` 从何而来（spi 无 Spring，需自建）。

### S8 `DomainResolver.ENTITY_HINT_WHITELIST` 与 `EntityRequirementCheck.NEED_ENTITY_TEXT` 未随领域扩展（§2.1、T07）
现状 hint 白名单只有 `order`；提示文案只有 refund / order。product / aftersale 要补（「请先选择一个商品」「请先选择一个订单再申请售后」）。小但会漏。

### S9 行内按钮 label 与 intent 的语义绑定缺机械保障（§2.3、§7 第 1 行）
§7 的缓解只证明「无权用户点删除不会执行」，没有防「屏生成器 bug 把删除意图挂在『查看物流』标签下」——用户会看到删除确认屏，虽可取消但已是事故。建议 spec 加自检 / 单测：`OrderScreens` 生成的每个 `inlineAction` 必须满足「intent 含该行 orderId」且「label → 动词」映射表一致（`查看物流 ↔ 物流`、`删除订单 ↔ 删除`…）；`check-contracts` 新示例也按此写。

### S10 core README「安全边界」表需加 `onIntent` 一行（§2.5）
spec 提到 README 更新但没说加到哪。建议明确：随包走列「`onIntent` 只回调纯文本，core 不发请求、不解释文本」；留宿主列「把 `onIntent` 文本当用户输入原样提交，不拼接、不改写」。

### S11 30 单 / 20 品手工种子建议由脚本生成（§2.2、§7 第 6 行）
手工写 30 单 × 1~3 行 + 物流事件 + 金额一致，出错概率高；`check-seed` 能抓金额 / 外键，抓不到「状态与物流事件不匹配」「时间不单调」等。建议提交一个一次性 `gen-seed.mjs`（固定随机种子，可复现）并让 `check-seed` 额外校验：PAID 无物流、SHIPPED/COMPLETED 事件 ≥ 3、`created_at` 严格递增。

### S12 非目标漏项（§3）
建议补：不做 `tool.completed.summary` 语义化（若 S1 选删）；不做 `clientCapabilities.components` 交集过滤（runtime 现在不看它）；不解析「订单10002」无空格 / 「10002 这单」裸号形式（或 L1 放宽正则）；不改 `tool-search` 契约（若 S1 选拉 name 表）。

---

## 3. LOW

- **L1** `EntityExtractor` 正则建议 `订单\s*(\d{5})` / `商品\s*(P-\d{4})`，兼容无空格；商品 ID `P-\d{4}` 与 §2.2「20 品」一致（P-1001~P-1020），需在 §2.2 写死编号区间。
- **L2** `Table` 组件保留但无后端使用者 → 在 `wiki/api-contracts.md` 标注「仅 playground」。
- **L3** `06-backend-module-spec.md` 需增「领域模块可提供 `ScreenBuilder` / `OrderSnapshotProvider` 实现」段落，T10 已提但未列条目。
- **L4** `check-module-deps` 新规则「domains 之间不得互相 import」按 `com.strato.domain.<other>` 判定即可，`app` 不在 `domains/` 下不会误伤；建议顺手给规则加植入负例（沉淀已多次强调）。
- **L5** `order.list.search` 升 1.1.0 后 `order.detail.get` 也升 1.1.0，`RunOrchestrator.RECHECK_TOOL` 常量硬编码 `1.2.0` 的做法在通用化后应改为取候选版本。

---

## 4. INFO（已核实、无需修改）

- **I1 契约可行性**：在 Ajv 2020 strict 下实测 `inlineAction.intent` pattern `^(?!.*(://|<|>|\n))[^]*$`（或更保守的 `^[^<>\n]*$` + `not:{pattern:"://"}`）与 `iconName` `^[a-z0-9-]{1,32}$`：正例通过；`http://x`、`a<b>`、URL 缩略图均拒绝；`OrderList` 的 `if/then`（`then` 带 `type:object` + `properties.props.$ref`）与首期 Form 同款写法编译通过。`OrderCard` props 在契约里仍是自由 JSON，加可选字段不影响两个既有示例。
- **I2 前端公共 API**：`FormComponentHandlers` / `RenderedComponentProps` 未从 `index.ts` 导出，改名为 `ComponentHandlers` 不动公共清单；`SchemaRendererProps` 加可选 `onIntent` 为兼容性扩展；类型 +1 `IconName` → 11 与 spec 一致。`grep "http\|href="` 在 core src 现为 0，基线成立。
- **I3 行内按钮安全语义**：intent 文本走 `POST /agent/runs` 同一入口，principal 来自请求头，路由 / Registry 过滤 / 校验 / 确认全部重跑——与用户手打**等价**；前端伪造 intent 等价于手打，无提权面；后端不存在「信任自己生成的 intent」的路径（前提是 M2 修正后实体抽取仍只影响候选内参数，不影响鉴权）。LIVE 模式下 intent 进 prompt 与用户消息同等对待并经 `sanitize`。剩余风险只有 S9（标签与意图错配）。
- **I4 mobile `Timeline`**：antd-mobile 5 `Steps direction="vertical"` 可直接承载 events，可行。

---

## 5. 结论

**verdict: REVISION REQUIRED** — 7 条 MUST FIX 全部修正后进入第 2 轮。第 2 轮重点复核：M1 的动词表对 5 条核心消息的 toolId 序列是否逐条写出；M2 的编排顺序改动是否体现在 T07 输出与验收；M4 / M5 的 spi 签名与重校验契约是否成文；M6 数字是否三处一致。
