# Tasks: feat-commerce-domains-20260908

> v3.2 — 响应用户阶段 3 纠偏（spec v3.2）：组件白名单收敛为 5 个官方组件映射。新增 **T01b**（契约收敛 + 8 示例，替代已提交的 T01 增量）；T06a / T06b 屏投影按 spec §2.4.1 屏映射表重写（Card / Table / Result / Timeline）；T08a / T08b 合并重写为「core 收敛」：删 4 个业务组件、`ResultCard → Result`、新增 `Timeline`、`Table` 增 actions 列、`Card` 增 tone、`check-registry` 增 import 白名单与命名规则；T09 / T10a 断言按新组件 id 改；T02a–T05 不变（已完成）。17 task。
> v3.1 — 吸收评审 v3：T02a 夹具表含金额与售后 / 退款分布；T02b `check-seed` 增金额 / 售后 / 退款校验；T03 输入 T02b、`order.logistics.get` NOT_SHIPPED、`order.detail.get` DELETED 行为、`OrderSnapshotProvider` 实现挪到 T05 前置小步（L-3）；T06a 输出列 `RunFailure.userText` 与 `RuleBasedLlmClient` / `SpringAiLlmClient` / `ToolSelectionValidator` / `RunOrchestrator` 签名；T07a 验收改用 10006；T07b LIVE `MissingEntity` 映射；T09 chip 统一；T10a ⑫ / ⑬ 断言；依赖图补 `T04 → T05`。
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
- **状态**：已提交 `e854104`（v3.1 形态）；由 T01b 覆盖。

### T01b ui-schema 契约收敛为 5 个官方组件（spec v3.2）
- **目标**：`componentType` enum → `Form / Card / Table / Result / Timeline`；`$defs` 新增 `labelValue{label, value, tone?}`、`cardProps`、`tableProps{columns, rows[]{id, cells, actions?}, total?, emptyText?}`、`resultProps`、`timelineProps{items[]{time, label, description?}, emptyText?}`；删 `iconName / orderListItem / orderListProps / productListItem / productListProps / logisticsEvent / logisticsTimelineProps`；`if/then` 覆盖 5 个组件；示例重写为 8 个（spec §2.3 列表；删 `order-list / product-list` 两个，新增 `order-table / product-table / order-detail`，其余 5 个改写）；`contracts.md` 变更记录写「v3.2 破坏性收敛，无外部消费方」。
- **所属端**：contracts
- **输入**：spec v3.2 §2.3 / §2.4.1 屏映射表
- **输出**：schema + 8 示例 + `contracts.md`
- **验收**：`check-contracts` 9 schema / 23 example ✓；植入 `type:"OrderCard"`、`Table.rows[0]` 缺 `id`、`Card.items[0].tone:"red"`、`intent:"http://x"` 各 → 红；`ContractsSelfCheck` 日志「9 schemas, 23 examples」
- **依赖**：T01

### T02a 种子生成器、json、DDL、README
- **目标**：`.harness/scripts/gen-seed.mjs`（固定随机种子；夹具与可见区状态表以断言写死：10001–10006 状态、**金额**（128.00 / 299.00 / 1.00 / 59.00 / — / 88.00）与最早 created_at、退款 3 条各挂 10007–10009（REFUNDED）、售后 4 条（APPROVED 挂 10010 COMPLETED，其余 3 条非进行中挂 10007–10009）、10011–10028 五状态各 ≥ 2、10029 COMPLETED、10030 SHIPPED 最新；products 20 / orders 30 / order_items / logistics_events）→ 写入三领域 `resources/data/*.json`（product / aftersale 目录先建，pom 由 T04 / T05 补）；每领域 `schema.sql`（spec §2.2 硬格式）与 `README.md`（导入命令）。
- **所属端**：harness / backed
- **输入**：spec §2.2 夹具表
- **输出**：`gen-seed.mjs`、`backed/domains/{order,product,aftersale,refund}-service/src/main/resources/data/**`
- **验收**：`node gen-seed.mjs` 两次输出字节相同；`jq length` 各表数量符合；`jq '.[0].order_id' orders.json` 等抽查夹具表 5 行
- **依赖**：—

### T02b SeedLoader、OrderSnapshot、check-seed
- **目标**：`platform-spi` pom 增 `jackson-datatype-jsr310`；`SeedLoader`（自建 ObjectMapper + JavaTimeModule，SNAKE_CASE，`load(resource, Class<T>)`）、`OrderSnapshotProvider` + `OrderSnapshot{orderId, status, amount, currency, productName, quantity, createdAt}`、`ToolNameSink`、`ScreenBuilder` / `ScreenContext` / `ConfirmationRecheck` 接口（JsonNode 签名；接口先落，T06a 用）；`.harness/scripts/check-seed.mjs`（键 == DDL 列、金额和、外键、状态-物流一致、created_at 递增、手机号、夹具表含金额、每单进行中售后 ≤ 1、退款一单一条且挂 REFUNDED、DDL 格式）纳入 `ci.mjs` 与 doctor 必需文件；`check-module-deps` 新规则「platform-spi pom 不得含任何 `com.strato` artifact」。
- **所属端**：backed / harness
- **输入**：T02a、`backed/platform-spi/**`、`.harness/scripts/{ci,harness-doctor,check-module-deps}.mjs`
- **输出**：spi 7 个文件 + pom、`check-seed.mjs`、三个 harness 脚本改动
- **验收**：`mvn -pl platform-spi verify` 0；`node check-seed.mjs` 0；植入外键悬空 / 金额不等 / PAID 带物流 / DDL 行尾注释 / 10030 非 SHIPPED / 10004 金额 60.00 / 同单两条进行中售后 各 → 红；spi pom 植入 `contracts-java` 依赖 → check-module-deps 红
- **依赖**：T02a

## Phase B — 领域服务

### T03 order-service 扩展
- **目标**：`Order` 增 `items[]`、`address`、`logistics?`；`OrderItem`、`LogisticsEvent`；仓储用 `SeedLoader`（加载时校验金额和，不一致启动失败）；`order.list.search` 默认 `createdAt desc`（description 写明）、`limit` 默认 20、排除 DELETED、输出扩字段（1.1.0）；`order.detail.get` 输出扩（1.1.0）；新 handler `OrderLogisticsGetHandler`（无物流 → `status NOT_SHIPPED, events []`）、`OrderDeleteHandler`（`DeletionPolicy` 纯函数；软删；`order.detail.get` 对 DELETED → `HANDLER_ERROR`）；实现 `OrderSnapshotProvider`（含 `productName` / `quantity`）；4 Manifest；权限 `order:delete`；`user_002` 权限改为四项只读。
- **所属端**：backed
- **输入**：T02b、现 order-service、`application.yml`
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
- **输入**：T02b、T03、T04、现 refund-service、`check-module-deps.mjs`
- **输出**：`backed/domains/aftersale-service/**`、refund-service 改动、脚本
- **验收**：`mvn verify` 0；`grep -rn "com.strato.domain.order" backed/domains/refund-service backed/domains/aftersale-service` 0；新规则植入 → 红；注册 12 tools from 4 sources；首期退款 e2e 用例仍绿
- **依赖**：T03、T04

## Phase C — Runtime

### T06a runtime 屏 / 重校验注册表 + refund 搬迁
- **目标**：spi `UiNodes`（树构造小工具）；runtime `ScreenRegistry`（契约校验 + `Table.cells ⊆ columns` 检查 + 两遍生成取 submit action id）、`FallbackScreenBuilder`（结果 `Card`；确认 `Card` 无 Form）、`RecheckRegistry`；`contracts-java` `UiSchema.ComponentType` 收敛为 5；`ToolDisplayNames` 改为实现 `ToolNameSink` 的可变注册表 Bean，`LlmConfiguration` 把它按引用注入两个 LlmClient；Registry `StartupManifestRegistrar` 回填；删 `summaryOf` 改 `ScreenBuilder.summary`；`RunOrchestrator`：确认屏 / 结果屏查表、`executeConfirmed` 通用化（recheck version 取计划前置步骤；无 recheck → fail-closed `INTERNAL_ERROR`；拒绝文案分令牌 / 策略两类）；`ConfirmationCoverageSelfCheck`；`UiSchemaBuilder` 搬到 `refund-service/infra/screen/RefundScreens`（确认 `[order: Card, refund-summary: Card, refund-form: Form]`、结果 `[result: Result]`）+ `RefundRecheck`（金额取 `refund-summary.items[label=退款金额]`）。
- **所属端**：backed
- **输入**：T01b、T02b（spi 接口）、T05、现 `UiSchemaBuilder` / `RunOrchestrator` / `ToolDisplayNames` / `LlmConfiguration`
- **输出**：runtime 4 类 + `RunFailure`（增 `userText`）+ `RuleBasedLlmClient` / `SpringAiLlmClient` / `ToolSelectionValidator.validate` / `RunOrchestrator` 签名（`ToolDisplayNames` 由 `Map` 快照改为 Bean 引用）+ `LlmConfiguration`、refund-service `infra/screen/` 2 类
- **验收**：`mvn verify` 0；`grep -rn "refundConfirmation\|refundResult\|summaryOf\|RefundConfirmCard\|OrderCard\|ResultCard\|ConfirmationCard\|\"1.2.0\"" backed/*/src backed/domains/*/src` 0；自检 `confirmation coverage OK`；首期 e2e 退款链路事件序列不变，UI components 变为 `[Card, Card, Form]` / `[Result]`（断言在 T10a 同步），金额比对拒绝用例不变；`tool.selected.displayName` 与 manifest name 一致
- **依赖**：T01b、T02b、T05

### T06b order / product / aftersale 屏与重校验
- **目标**：按 spec §2.4.1 屏映射表（component id 写死）：`OrderScreens`（`orders: Table` 行内按钮按状态 + `emptyText`；详情 `[order: Card, logistics: Card, logistics-events: Timeline]`；物流 `[logistics: Card, logistics-events: Timeline]`；删除确认 `[order: Card]` 末项 danger、无 Form、submit `confirm-delete`；结果 `Result`）+ `OrderDeleteRecheck`（`DeletionPolicy`）；`ProductScreens`（`products: Table`、`product: Card`）；`AftersaleScreens`（`aftersales: Table`；确认 `[order: Card, aftersale-form: Form]` submit `confirm-aftersale`；结果 `Result`）+ `AftersaleRecheck`；`InlineActionSelfCheck`（种子全部订单 / 商品的 `Table.rows[].actions`：intent 含行 id、label↔动词）。
- **所属端**：backed
- **输入**：T06a、T03、T04、T05
- **输出**：三领域 `infra/screen/**` + 自检
- **验收**：`mvn verify` 0；自检日志 `selfcheck: inline actions OK`；`grep -rn "OrderSnapshotProvider" backed/domains/*/src/main/java/*/infra/screen` 0；每个屏经 `ScreenRegistry` 校验通过（启动自检 `ConfirmationCoverageSelfCheck` 覆盖三个确认屏；结果屏由 e2e ⑦–⑫ 覆盖）；Gateway 直调后手工构造 `previousOutputs` 走 `ScreenRegistry` 的单元级验证记入 coding_report（或经 T07b e2e 覆盖）
- **依赖**：T06a、T04

### T07a 实体抽取前移与路由表
- **目标**：`EntityExtractor`（`订单\s*(\d{5})`、`商品\s*(P-\d{4})`）→ `Map<String,String>`，与 `pageContext.selectedEntity` 合并（消息优先）；`RunOrchestrator.start` 顺序：路由 → 抽取 → `EntityRequirementCheck`（改用 map）→ search → 规划；`LlmClient.PlanRequest.entity` 改为 map；`PromptBuilder` 段名改「已识别实体」；`DomainRouter` 顺序表 + 关键词表（spec §2.1）；`DomainDescriptions` 四条；`ENTITY_HINT_WHITELIST {order, product}`；`EntityRequirementCheck` 映射 + 文案扩。
- **所属端**：backed
- **输入**：T06a、现 `RunOrchestrator` / `DomainRouter` / `EntityRequirementCheck` / `DomainResolver` / `PromptBuilder`
- **输出**：`application/EntityExtractor.java` + 5 处改动
- **验收**：`mvn verify` 0；无 pageContext 的「订单 10006 退款」发起序列 == 带 pageContext 的 §6.2.8；「订单 10002 申请售后」路由日志 `domain=aftersale source=rule`；「有什么商品」`domain=product`；日志无用户原文
- **依赖**：T06b

### T07b 规则规划器动词表与前置依赖
- **目标**：`RuleBasedLlmClient` 删 `WITH_ENTITY / WITHOUT_ENTITY`；`IntentVerbs` 表 + `Prerequisites` 表（spec §2.4.3）；目标工具不在候选 → `TOOL_SELECTION_INVALID`；动词命中但目标必填实体缺失 → `MissingEntity` 信号（`ToolSelectionValidator` 对 LIVE 模式输出同样映射），`RunOrchestrator` 走友好提示 + `run.completed`；`ToolSelectionValidator` 增「需确认步骤前置齐全且在前」校验；`PlanSelfCheck` 改 5 条消息断言（日志「plan 5 messages OK」）；`PromptBuilder.system` 附动词表与前置依赖说明；启动时校验 `IntentVerbs` / `Prerequisites` 引用的 toolId 都已注册（`ToolNameSink` 回填后）。
- **所属端**：backed
- **输入**：T07a、现 `RuleBasedLlmClient` / `PlanSelfCheck` / `ToolSelectionValidator`
- **输出**：`infra/llm/{IntentVerbs,RuleBasedLlmClient,PromptBuilder,ToolSelectionValidator}.java`、`infra/selfcheck/PlanSelfCheck.java`
- **验收**：`mvn verify` 0；启动自检 `plan 5 messages OK`；`curl` 五条核心消息（spec §4）事件序列各自符合；`user_002` 「删除订单 10005」→ `TOOL_SELECTION_INVALID`；「删除订单」无号码 → `message.delta` 提示 + `run.completed`
- **依赖**：T07a

## Phase D — 前端

### T08a core：契约投影收敛 + onIntent + 门禁
- **目标**：`schema/uiSchema.ts` 重写为 5 个组件的契约级 Zod（`.strict()`；`COMPONENT_TYPES` 5；`CardProps / TableProps / ResultProps / TimelineProps / InlineAction / LabelValue` 类型）；`registry/types.ts` 只 re-export，删 `OrderCard* / RefundConfirmCard* / ResultCard* / ConfirmationCard*`；`ComponentHandlers{onChange?, onIntent?}`（原 `FormComponentHandlers`）；`SchemaRenderer` 增 `onIntent` 透传；`index.ts` 导出同步；`verify-pack.mjs` 类型清单同步；`check-registry.mjs` 增两条规则：`components/**` 文件 import 只允许 `antd` / `antd-mobile` / `react` / `../../registry` / `../../schema`；`components/{desktop,mobile}/*.tsx` 文件名 ∈ 契约 enum ∪ {ActionBar}；`coding-standard.md` 写入该红线。
- **所属端**：fronted / harness
- **输入**：T01b、现 core
- **输出**：`packages/core/src/{schema,registry,renderer,index.ts}`、`scripts/{verify-pack,check-registry}.mjs`、`coding-standard.md`
- **验收**：`pnpm -C fronted run typecheck` 0（组件文件此时报错可接受，T08b 后绿）；`check-registry` 植入 `components/desktop/OrderCard.tsx`（含 `import { Tag } from 'antd'`）→ 红「业务命名」；植入 `import x from 'lodash'` → 红「import 白名单」
- **依赖**：T01b

### T08b core：5 个官方组件映射 + 基线
- **目标**：删 `components/{desktop,mobile}/{OrderCard,RefundConfirmCard,ConfirmationCard,ResultCard}.tsx`（8 个）；`ResultCard.tsx → Result.tsx`（antd `Result` + `Descriptions` / antd-mobile `Result` + `List`，`data-component-id`）；`Card` 增 `items[].tone`（antd `Typography.Text type` / antd-mobile `--adm-color-*`）；`Table` 增 `rows[].actions` 末列（antd `Button size="small"` / antd-mobile 每行 `List` 分组尾部 `Button size="mini"`，`data-intent`，点击 → `handlers.onIntent(intent)`）与 `total / emptyText` 表尾（antd `Table footer` / antd-mobile `List` 尾项）；新增 `Timeline.tsx` 两端（antd `Timeline items` / antd-mobile `Steps direction="vertical"`；空 items 显示 `emptyText`）；`componentRegistry.ts` 5 键；`pnpm run verify-pack -- --write-baseline` 重写基线并记录前后 KB；core README 白名单表改 5 行。
- **所属端**：fronted
- **输入**：T08a
- **输出**：`components/{desktop,mobile}/{Card,Table,Result,Timeline}.tsx`、`componentRegistry.ts`、`verify-pack.baseline.json`、README
- **验收**：`rm -rf */dist && pnpm -C fronted run ci` 0；check-registry 5 + 两条新规则 ✓；`ls components/desktop` == `ActionBar Card Form Result Table Timeline`；`grep -rn "Order\|Refund\|Product\|Logistics" packages/core/src/components` 0；`grep "@ant-design/icons" packages/core/package.json` 0；`/dev/schema?example=<8 个>` 1280 / 375 各 console.error 0；`grep -rn "http\|href=" packages/core/src` 0
- **依赖**：T08a

### T09 chat 应用：意图按钮、连续对话、示例 chip
- **目标**：`AgentChatPanel` 接 `onIntent` → 显示用户消息 + `start.mutate`；新 Run 期间保留上一屏直到新 `ui.replace`；消息区区分用户 / 助手；演示页示例 chip（「看看我的订单」「有什么商品」「查看订单 10030 的物流」「订单 10002 申请售后」）；playground `example` 参数扩为 8 个契约示例 + `unknown`。
- **所属端**：fronted
- **输入**：T08b
- **输出**：`apps/chat/src/**`
- **验收**：`pnpm -C fronted run ci` 0；e2e-frontend（T10a）覆盖
- **依赖**：T08b

## Phase E — Harness、文档、验收

### T10a e2e 脚本与既有断言同步
- **目标**：`e2e-backend.sh` 新增 ⑦–⑯ + ⑬'（spec v3.2 §2.6 断言：组件按 `[Card, Timeline]` / `[Table]` 等 type 列表与 component id；⑫ 确认屏 `[Card]` 末项 `tone==danger` 提交 `{}`；⑬ 审计按 runId 作用域）；既有断言同步清单（spec §2.6）：自检「20 examples」→ 23、§6.2.8 `['Card','Card','Form']`、§6.2.9 / M2 `['Result']`、`SHOWN` 取 `refund-summary.items[label=退款金额]`、plan 自检文案、新增两条自检名、selfcheck 数 → 7（`deploy-verify.sh` 同）、check-registry 5；`e2e-frontend.mjs` 新增 ≥ 8 项（`data-component-id="orders"`、`data-intent="查看订单 10030 的物流"` 等精确定位）+ 3 张截图。
- **所属端**：harness
- **输入**：T07b、T09、现两脚本
- **输出**：两脚本
- **验收**：规则模式 e2e-backend 全绿；LIVE 模式全绿（⑦–⑫、⑮ 真模型需同序列；失败记录为 prompt 缺陷）；e2e-frontend 全绿；deploy-verify 12/12（自检 7）
- **依赖**：T07b、T09

### T10b 文档同步
- **目标**：`wiki/domain-model.md`（四领域实体与状态机、删除 / 售后 / 退款策略）、`wiki/api-contracts.md`（12 工具）、`wiki/architecture.md`（组件白名单 5）、`backed/README.md`、`packages/core/README.md`（5 组件、`onIntent`）、`project-structure.md` §2（`ScreenBuilder` / `ConfirmationRecheck` 归领域模块；domains 互不 import；core 组件 == 官方组件映射）、`agent-safety.md` §3（重校验契约化）、`06-backend-module-spec.md`、`backed/domains/*/README.md`。
- **所属端**：harness
- **输入**：T06b、T07b、T09
- **输出**：上述文档
- **验收**：doctor 0（含文档路径检查）；`grep -rn "UiSchemaBuilder\|OrderCard\|RefundConfirmCard\|ResultCard\|ConfirmationCard" .harness/rules .harness/wiki backed/README.md fronted/packages/core/README.md` 0
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
T02b → T04 → T05 ────────┼→ T06a → T06b → T07a → T07b ─┐
T01 → T01b ──────────────┘                              ├→ T10a → T10b → T11
T01b → T08a → T08b → T09 ───────────────────────────────┘
（T06a 依赖 T01b / T02b / T05；T06b 依赖 T06a / T04；T07a 依赖 T06b）
已完成：T01（e854104）、T02a+T02b（c75ad6a）、T03（5c58938）、T04+T05（688021b）。T06a 已开工（ScreenRegistry / RecheckRegistry / ToolDisplayNames / RunFailure / ConfirmationCoverageSelfCheck / UiNodes / 注册回填已写，未提交），按 v3.2 继续。
```
