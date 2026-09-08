# Tasks: feat-commerce-domains-20260908

> v3 — 响应 `review/spec_review_v2.md`：T02 拆 T02a（生成器 / json / DDL / README）与 T02b（spi SeedLoader + jsr310 / OrderSnapshot / check-seed / ci）；T06a 增 `ConfirmationCoverageSelfCheck`、recheck version 取前置步骤、`ToolDisplayNames` 引用注入、拒绝文案分类；T06b 删除确认屏无 Form；T07b 增 `MissingEntity` 信号；T10a 用例 ⑮ 用 10006、⑯ 新增、selfcheck 7、e2e-frontend 点 10030；依赖字段与图一致。16 task。
> v2 — 响应 `review/spec_review_v1.md`：T06 / T07 / T08 / T10 各拆 a/b（M7）；T06 依赖补 T04；T02 增 `gen-seed.mjs` 与 DDL 硬格式；T03 增排序与 `order` 摘要；T05 增 `OrderSnapshot` spi record 与 aftersale.list.get 摘要；T07a 含实体抽取前移与路由表；T07b 含动词表与 `ConfirmationRecheck`；T10a 含既有断言同步清单。15 task。
> v1。所属端 contracts / backed / fronted / harness。编码顺序：契约 → 数据与 spi → 领域服务 → runtime → 前端 → Harness。每个 task ≤ 0.5 天。移动 / 重命名与逻辑分开提交。

## Phase A — 契约与数据形态

### T01 ui-schema 契约扩展
- **目标**：`componentType` enum +3；`$defs`：`iconName`、`inlineAction{label ≤32, intent ≤200, pattern 不含 "://" 与 "<"}`、`orderListProps`、`productListProps`、`logisticsTimelineProps`；`OrderCard` props 增 `quantity? / items?[] / logisticsStatus?`；`if/then` 对三新组件做 props 约束（与首期 Form 写法一致：`then` 内显式 `type:object` + `properties`）；5 个新示例（示例中 inlineAction 满足「intent 含 ID + label↔动词」）；`contracts.md` §4 增两条（intent 是自然语言；label↔动词映射表）。
- **所属端**：contracts
- **输入**：`.harness/contracts/ui-schema.schema.json`、spec §2.3 / §5
- **输出**：schema + 5 示例 + `contracts.md`
- **验收**：`check-contracts` 9 schema / 25 example ✓；植入 `intent: "http://x"` 与 `intent: "<b>"` → 红；植入 `OrderList.props.items[0]` 缺 `orderId` → 红
- **依赖**：—

### T02a 种子生成器、json、DDL、README
- **目标**：`.harness/scripts/gen-seed.mjs`（固定随机种子；夹具与可见区状态表以断言写死：10001–10006 状态与最早 created_at、售后 4 / 退款 3 只挂 10007–10010、10011–10028 五状态各 ≥ 2、10029 COMPLETED、10030 SHIPPED 最新；products 20 / orders 30 / order_items / logistics_events）→ 写入三领域 `resources/data/*.json`（product / aftersale 目录先建，pom 由 T04 / T05 补）；每领域 `schema.sql`（spec §2.2 硬格式）与 `README.md`（导入命令）。
- **所属端**：harness / backed
- **输入**：spec §2.2 夹具表
- **输出**：`gen-seed.mjs`、`backed/domains/{order,product,aftersale,refund}-service/src/main/resources/data/**`
- **验收**：`node gen-seed.mjs` 两次输出字节相同；`jq length` 各表数量符合；`jq '.[0].order_id' orders.json` 等抽查夹具表 5 行
- **依赖**：—

### T02b SeedLoader、OrderSnapshot、check-seed
- **目标**：`platform-spi` pom 增 `jackson-datatype-jsr310`；`SeedLoader`（自建 ObjectMapper + JavaTimeModule，SNAKE_CASE，`load(resource, Class<T>)`）、`OrderSnapshotProvider` + `OrderSnapshot{orderId, status, amount, currency, productName, quantity, createdAt}`、`ToolNameSink`、`ScreenBuilder` / `ScreenContext` / `ConfirmationRecheck` 接口（JsonNode 签名；接口先落，T06a 用）；`.harness/scripts/check-seed.mjs`（键 == DDL 列、金额和、外键、状态-物流一致、created_at 递增、手机号、夹具表、DDL 格式）纳入 `ci.mjs` 与 doctor 必需文件；`check-module-deps` 新规则「platform-spi pom 不得含任何 `com.strato` artifact」。
- **所属端**：backed / harness
- **输入**：T02a、`backed/platform-spi/**`、`.harness/scripts/{ci,harness-doctor,check-module-deps}.mjs`
- **输出**：spi 7 个文件 + pom、`check-seed.mjs`、三个 harness 脚本改动
- **验收**：`mvn -pl platform-spi verify` 0；`node check-seed.mjs` 0；植入外键悬空 / 金额不等 / PAID 带物流 / DDL 行尾注释 / 10030 非 SHIPPED 各 → 红；spi pom 植入 `contracts-java` 依赖 → check-module-deps 红
- **依赖**：T02a

## Phase B — 领域服务

### T03 order-service 扩展
- **目标**：`Order` 增 `items[]`、`address`、`logistics?`；`OrderItem`、`LogisticsEvent`；仓储用 `SeedLoader`（加载时校验金额和，不一致启动失败）；`order.list.search` 默认 `createdAt desc`（description 写明）、`limit` 默认 20、排除 DELETED、输出扩字段（1.1.0）；`order.detail.get` 输出扩（1.1.0）；新 handler `OrderLogisticsGetHandler`、`OrderDeleteHandler`（`DeletionPolicy` 纯函数；软删）；实现 `OrderSnapshotProvider`；4 Manifest；权限 `order:delete`；`user_002` 权限改为四项只读。
- **所属端**：backed
- **输入**：T02、现 order-service、`application.yml`
- **输出**：`backed/domains/order-service/**`、`application.yml`
- **验收**：`mvn verify` 0；Gateway `order.list.search {}` → `total 30 / items 20 / items[0]` 最新；`{status:"DELETED"}` → 400；`order.delete 10001` → 502 `HANDLER_ERROR`；`order.delete 10005` → `deleted:true`，之后 `total 29`；`user_002` search refund 候选仍 3
- **依赖**：T02b

### T04 product-service 新建
- **目标**：新模块（pom、`Product`、`SeedLoader` 仓储、2 handler、`ProductManifestSource`、2 Manifest）；`backed/pom.xml` modules、`app/pom.xml`；权限 `product:read`。
- **所属端**：backed
- **输入**：T02b
- **输出**：`backed/domains/product-service/**`、两处 pom、`application.yml`
- **验收**：`mvn verify` 0；`check-module-deps` 0；注册 +2；`product.list.search {keyword:"耳机"}` ≥ 1；`product.detail.get P-1003` 含 `specs`
- **依赖**：T02b

### T05 aftersale-service 新建 + refund-service 改造 + 模块红线
- **目标**：新模块 aftersale-service（`Aftersale`、`AftersalePolicy`、`aftersale.list.get` 带 `orderId` 时输出 `order{orderId, productName, quantity, amount, currency, status}` 摘要（经 `OrderSnapshotProvider`）、`aftersale.create`、Manifest、seed）；refund-service 删 `SeededOrderLookup`，`OrderLookup` 委托 `OrderSnapshotProvider`，refunds 从 json 加载；`check-module-deps` 新规则「`domains/*` 之间不得 import `com.strato.domain.<other>`」（spi 规则已在 T02b）。
- **所属端**：backed / harness
- **输入**：T02、T03、现 refund-service、`check-module-deps.mjs`
- **输出**：`backed/domains/aftersale-service/**`、refund-service 改动、脚本
- **验收**：`mvn verify` 0；`grep -rn "com.strato.domain.order" backed/domains/refund-service backed/domains/aftersale-service` 0；新规则植入 → 红；注册 11 tools from 4 sources；首期退款 e2e 用例仍绿
- **依赖**：T03、T04

## Phase C — Runtime

### T06a runtime 屏 / 重校验注册表 + refund 搬迁
- **目标**：runtime `ScreenRegistry`、`FallbackScreenBuilder`（确认屏无 Form）、`RecheckRegistry`；`ToolDisplayNames` 改为实现 `ToolNameSink` 的可变注册表 Bean，`LlmConfiguration` 把它按引用注入两个 LlmClient；Registry `StartupManifestRegistrar` 回填；删 `summaryOf` 改 `ScreenBuilder.summary`；`RunOrchestrator`：确认屏 / 结果屏查表、`executeConfirmed` 通用化（recheck version 取计划前置步骤；无 recheck → fail-closed `INTERNAL_ERROR`；拒绝文案分令牌 / 策略两类）；`ConfirmationCoverageSelfCheck`；`UiSchemaBuilder` 搬到 `refund-service/infra/screen/RefundScreens` + `RefundRecheck`。
- **所属端**：backed
- **输入**：T01、T02b（spi 接口）、T05、现 `UiSchemaBuilder` / `RunOrchestrator` / `ToolDisplayNames` / `LlmConfiguration`
- **输出**：runtime 4 类 + 改动、refund-service `infra/screen/` 2 类
- **验收**：`mvn verify` 0；`grep -rn "refundConfirmation\|refundResult\|summaryOf\|RefundConfirmCard\|\"1.2.0\"" backed/agent-runtime/src` 0；自检 `confirmation coverage OK`；首期 e2e 退款链路事件序列、UI components、金额比对拒绝用例全部不变；`tool.selected.displayName` 与 manifest name 一致
- **依赖**：T01、T02b、T05

### T06b order / product / aftersale 屏与重校验
- **目标**：`OrderScreens`（OrderList 行内按钮按状态、`emptyText` 截断提示、OrderCard 多商品 + 物流状态、LogisticsTimeline、删除确认屏 `[OrderCard, ConfirmationCard]` 无 Form）+ `OrderDeleteRecheck`（`DeletionPolicy`）；`ProductScreens`；`AftersaleScreens`（确认屏用 `aftersale.list.get.order` 摘要）+ `AftersaleRecheck`；`InlineActionSelfCheck`（种子全部订单的 inlineAction：intent 含 orderId、label↔动词）。
- **所属端**：backed
- **输入**：T06a、T03、T04、T05
- **输出**：三领域 `infra/screen/**` + 自检
- **验收**：`mvn verify` 0；自检日志 `selfcheck: inline actions OK`；`grep -rn "OrderSnapshotProvider" backed/domains/*/src/main/java/*/infra/screen` 0；Gateway 直调后手工构造 `previousOutputs` 走 `ScreenRegistry` 的单元级验证记入 coding_report（或经 T07b e2e 覆盖）
- **依赖**：T06a、T04

### T07a 实体抽取前移与路由表
- **目标**：`EntityExtractor`（`订单\s*(\d{5})`、`商品\s*(P-\d{4})`）→ `Map<String,String>`，与 `pageContext.selectedEntity` 合并（消息优先）；`RunOrchestrator.start` 顺序：路由 → 抽取 → `EntityRequirementCheck`（改用 map）→ search → 规划；`LlmClient.PlanRequest.entity` 改为 map；`PromptBuilder` 段名改「已识别实体」；`DomainRouter` 顺序表 + 关键词表（spec §2.1）；`DomainDescriptions` 四条；`ENTITY_HINT_WHITELIST {order, product}`；`EntityRequirementCheck` 映射 + 文案扩。
- **所属端**：backed
- **输入**：T06a、现 `RunOrchestrator` / `DomainRouter` / `EntityRequirementCheck` / `DomainResolver` / `PromptBuilder`
- **输出**：`application/EntityExtractor.java` + 5 处改动
- **验收**：`mvn verify` 0；无 pageContext 的「订单 10001 退款」事件序列 == 带 pageContext 的 §6.2.8；「订单 10002 申请售后」路由日志 `domain=aftersale source=rule`；「有什么商品」`domain=product`；日志无用户原文
- **依赖**：T06b

### T07b 规则规划器动词表与前置依赖
- **目标**：`RuleBasedLlmClient` 删 `WITH_ENTITY / WITHOUT_ENTITY`；`IntentVerbs` 表 + `Prerequisites` 表（spec §2.4.3）；目标工具不在候选 → `TOOL_SELECTION_INVALID`；动词命中但目标必填实体缺失 → `MissingEntity` 信号，`RunOrchestrator` 走友好提示 + `run.completed`；`ToolSelectionValidator` 增「需确认步骤前置齐全且在前」校验；`PlanSelfCheck` 改 5 条消息断言（日志「plan 5 messages OK」）；`PromptBuilder.system` 附动词表与前置依赖说明；启动时校验 `IntentVerbs` / `Prerequisites` 引用的 toolId 都已注册（`ToolNameSink` 回填后）。
- **所属端**：backed
- **输入**：T07a、现 `RuleBasedLlmClient` / `PlanSelfCheck` / `ToolSelectionValidator`
- **输出**：`infra/llm/{IntentVerbs,RuleBasedLlmClient,PromptBuilder,ToolSelectionValidator}.java`、`infra/selfcheck/PlanSelfCheck.java`
- **验收**：`mvn verify` 0；启动自检 `plan 5 messages OK`；`curl` 五条核心消息（spec §4）事件序列各自符合；`user_002` 「删除订单 10005」→ `TOOL_SELECTION_INVALID`；「删除订单」无号码 → `message.delta` 提示 + `run.completed`
- **依赖**：T07a

## Phase D — 前端

### T08a core：契约投影、OrderCard 扩展、图标表、onIntent
- **目标**：`schema/uiSchema.ts` / `registry/types.ts` 同步契约（10 类型、三组 props、`IconName`、`InlineAction`）；`icons.ts` 白名单 12 → `@ant-design/icons`；`ComponentHandlers{onChange?, onIntent?}`；`SchemaRenderer` 增 `onIntent` 透传；`OrderCard` 两端多商品 + 物流状态；`index.ts` 类型 +`IconName`；verify-pack 类型清单 11；README 两列 + `onIntent`。
- **所属端**：fronted
- **输入**：T01、现 core
- **输出**：`packages/core/src/**`、`scripts/verify-pack.mjs`、`packages/core/README.md`
- **验收**：`pnpm -C fronted run typecheck` 0；check-registry 暂红（缺 3 实现）可接受，T08b 后绿
- **依赖**：T01

### T08b core：三新组件两端实现 + 基线
- **目标**：desktop `OrderList`（antd List + 行内 Button，`data-intent`）/ `ProductList` / `LogisticsTimeline`（antd Timeline）；mobile 对应（antd-mobile List / Card / Steps）；`pnpm run verify-pack -- --write-baseline` 重写基线并记录前后 KB。
- **所属端**：fronted
- **输入**：T08a
- **输出**：6 个组件文件、`verify-pack.baseline.json`
- **验收**：`rm -rf */dist && pnpm -C fronted run ci` 0；check-registry 10；verify-pack 17 + 11；`/dev/schema?example=order-list|product-list|logistics` 1280 / 375 各 console.error 0；`grep -rn "http\|href=" packages/core/src` 0
- **依赖**：T08a

### T09 chat 应用：意图按钮、连续对话、示例 chip
- **目标**：`AgentChatPanel` 接 `onIntent` → 显示用户消息 + `start.mutate`；新 Run 期间保留上一屏直到新 `ui.replace`；消息区区分用户 / 助手；演示页示例 chip（「看看我的订单」「有什么商品」「查看订单 10007 的物流」）；playground 三个新示例入口。
- **所属端**：fronted
- **输入**：T08b
- **输出**：`apps/chat/src/**`
- **验收**：`pnpm -C fronted run ci` 0；e2e-frontend（T10a）覆盖
- **依赖**：T08b

## Phase E — Harness、文档、验收

### T10a e2e 脚本与既有断言同步
- **目标**：`e2e-backend.sh` 新增 ⑦–⑯ + ⑬'（放 M2 幂等用例之后；⑮ 用 10006）；既有断言同步：自检「20 examples」→ 25、plan 自检文案、新增两条自检名、selfcheck 数 → 7（`deploy-verify.sh` 同）；`e2e-frontend.mjs` 新增 ≥ 8 项（`data-intent="查看订单 10030 的物流"` 等精确定位）+ 3 张截图。
- **所属端**：harness
- **输入**：T07b、T09、现两脚本
- **输出**：两脚本
- **验收**：规则模式 e2e-backend 全绿；LIVE 模式全绿（⑦–⑫、⑮ 真模型需同序列；失败记录为 prompt 缺陷）；e2e-frontend 全绿；deploy-verify 12/12（自检 7）
- **依赖**：T07b、T09

### T10b 文档同步
- **目标**：`wiki/domain-model.md`（四领域实体与状态机、删除 / 售后 / 退款策略）、`wiki/api-contracts.md`（11 工具；`Table` 标注仅 playground）、`backed/README.md`、`packages/core/README.md`（10 组件、`onIntent`）、`project-structure.md` §2（`ScreenBuilder` / `ConfirmationRecheck` 归领域模块；domains 互不 import）、`agent-safety.md` §3（重校验契约化）、`06-backend-module-spec.md`、`backed/domains/*/README.md`。
- **所属端**：harness
- **输入**：T06b、T07b、T09
- **输出**：上述文档
- **验收**：doctor 0（含文档路径检查）；`grep -n "UiSchemaBuilder" .harness/rules .harness/wiki backed/README.md` 0
- **依赖**：T10a

### T11 全链路验收
- **目标**：`rm -rf */dist && pnpm -C .harness run ci`（含 check-seed）；deploy-verify；产物冻结；coding_report。
- **所属端**：harness
- **输入**：T10b
- **输出**：`deployment/**`、`coding/coding_report_v1.md`
- **验收**：spec §6 全部为真
- **依赖**：T10b

## 依赖图

```
T02a → T02b → T03 → T05 ─┐
T02b → T04 ──────────────┼→ T06a → T06b → T07a → T07b ─┐
T01 ─────────────────────┘                              ├→ T10a → T10b → T11
T01 → T08a → T08b → T09 ────────────────────────────────┘
（T06a 依赖 T01 / T02b / T05；T06b 依赖 T06a / T04；T07a 依赖 T06b）
```
