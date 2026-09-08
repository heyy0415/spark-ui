# Tasks: feat-commerce-domains-20260908

> v1。所属端 contracts / backed / fronted / harness。编码顺序：契约 → 数据与 spi → 领域服务 → runtime 屏与规划器 → 前端 → Harness。每个 task ≤ 0.5 天。移动 / 重命名与逻辑分开提交。

## Phase A — 契约与数据形态

### T01 ui-schema 契约扩展
- **目标**：`componentType` enum +3（`OrderList`、`ProductList`、`LogisticsTimeline`）；`$defs`：`iconName`（`^[a-z0-9-]{1,32}$`）、`inlineAction{label ≤32, intent ≤200 且不含 `://` / `<`}`、`orderListProps`、`productListProps`、`logisticsTimelineProps`；`OrderCard` props 增 `quantity? / items?[] / logisticsStatus?`；`if/then` 对三新组件做 props 结构约束；5 个新示例；`contracts.md` §4 增「intent 是自然语言」一条。
- **所属端**：contracts
- **输入**：`.harness/contracts/ui-schema.schema.json`、spec §2.3 / §5
- **输出**：schema + `examples/ui-schema.{order-list,product-list,logistics,aftersale-confirm,delete-confirm}.example.json` + `contracts.md`
- **验收**：`check-contracts` 9 schema / 25 example ✓；植入 `intent: "http://x"` 到示例 → 红
- **依赖**：—

### T02 mock 数据与 DDL
- **目标**：三领域 `resources/data/`：`schema.sql` + json（products 20 / orders 30 / order_items / logistics_events / aftersales 4 / refunds 3）+ `README.md`（导入命令）。`platform-spi` 新增 `SeedLoader`（Jackson 读 classpath json → `List<T>`，无 Spring）与 `OrderSnapshotProvider` 接口。`.harness/scripts/check-seed.mjs` 并纳入 `ci.mjs`、doctor 必需文件。
- **所属端**：backed / harness
- **输入**：spec §2.2；现 `InMemoryOrderRepository` 种子（10001–10004 语义保留）
- **输出**：`backed/domains/{order,product,aftersale,refund}-service/src/main/resources/data/**`、`backed/platform-spi/src/main/java/com/strato/spi/{SeedLoader,OrderSnapshotProvider}.java`、`.harness/scripts/check-seed.mjs`
- **验收**：`node .harness/scripts/check-seed.mjs` 0；植入外键悬空 → 红；植入金额不等 → 红；`jq length` 各表数量符合 spec
- **依赖**：—

## Phase B — 领域服务

### T03 order-service 扩展
- **目标**：`Order` 增 `items[]`、`address`、`logistics?`；`OrderItem`、`LogisticsEvent` 实体；仓储改为 `SeedLoader` 加载 json（加载时校验金额和）；新 handler `OrderLogisticsGetHandler`、`OrderDeleteHandler`（`DeletionPolicy` 纯函数；软删 `DELETED`）；`order.list.search` / `order.detail.get` 输出扩字段（版本 1.1.0）；实现 `OrderSnapshotProvider`；Manifest 4 个；权限 `order:delete`。
- **所属端**：backed
- **输入**：T02、现 order-service
- **输出**：`backed/domains/order-service/**`
- **验收**：`mvn verify` 0；单独 `curl` Gateway `order.list.search {}` 返回 `total == 30`（含 DELETED 之外）；`order.delete 10001` → `HANDLER_ERROR`；`order.delete 10005` → `deleted:true` 且列表 `total == 29`
- **依赖**：T02

### T04 product-service 新建
- **目标**：新模块 `domains/product-service`（pom、`Product` 实体、`SeedLoader` 仓储、`ProductListSearchHandler`、`ProductDetailGetHandler`、`ProductManifestSource`、2 Manifest）；`app` pom 加依赖；权限 `product:read`。
- **所属端**：backed
- **输入**：T02、`backed/pom.xml` modules、`app/pom.xml`
- **输出**：`backed/domains/product-service/**`、两处 pom、`application.yml` 权限
- **验收**：`mvn verify` 0；`check-module-deps` 0；启动日志注册数 +2；`product.list.search {keyword:"耳机"}` 返回 ≥ 1
- **依赖**：T02

### T05 aftersale-service 新建 + refund-service 改造
- **目标**：新模块 `domains/aftersale-service`（`Aftersale` 实体、策略 `AftersalePolicy`、2 handler、Manifest、seed）；`refund-service` 删 `SeededOrderLookup`，`OrderLookup` 实现改为委托 `OrderSnapshotProvider`（spi）；refund 种子从 json 加载；`check-module-deps` 新规则「`domains/*` 之间不得互相 import」（只经 spi）。
- **所属端**：backed / harness
- **输入**：T02、T03、现 refund-service
- **输出**：`backed/domains/aftersale-service/**`、refund-service 改动、`check-module-deps.mjs`
- **验收**：`mvn verify` 0；`grep -rn "com.strato.domain.order" backed/domains/refund-service backed/domains/aftersale-service` 0；植入 → check-module-deps 红；启动注册 11 tools from 4 sources；e2e 首期用例（退款链路）仍绿
- **依赖**：T03

## Phase C — Runtime

### T06 ScreenBuilder 抽象与领域屏
- **目标**：`platform-spi.ScreenBuilder` 接口；runtime `ScreenRegistry`（按 toolId 查表）+ `FallbackScreenBuilder`（Card / ConfirmationCard + 空 Form）；`UiSchemaBuilder` 拆成 `refund-service/infra/screen/RefundScreens`、`order-service/infra/screen/OrderScreens`（OrderList 行内按钮按状态、OrderCard 多商品、LogisticsTimeline、删除确认）、`product-service/.../ProductScreens`、`aftersale-service/.../AftersaleScreens`（确认屏经 `OrderSnapshotProvider` 取订单）；`RunOrchestrator` 的 `waitForConfirmation` / 结果屏改为查 `ScreenRegistry`；令牌白名单 = 该确认屏 `Form.fields[]`（现有 `formKeys` 逻辑通用化）。
- **所属端**：backed
- **输入**：T01、T03–T05、现 `UiSchemaBuilder` / `RunOrchestrator`
- **输出**：spi 接口、runtime 2 类、4 个领域 `infra/screen/*`
- **验收**：`mvn verify` 0；`grep -rn "refundConfirmation\|refundResult" backed/agent-runtime/src` 0；首期 e2e 退款链路事件序列与 UI components 完全不变
- **依赖**：T01、T05

### T07 规划器通用化与实体提取
- **目标**：`RuleBasedLlmClient` 删 `WITH_ENTITY / WITHOUT_ENTITY`，改通用规则（只读优先、按 riskLevel、需确认最后且最多 1 个、缺必填实体跳过、全跳过 → `TOOL_SELECTION_INVALID`）；`EntityExtractor`（`订单 (\d{5})`、`商品 (P-\d{4})`，与 pageContext 合并，消息优先；只记录 ID 不记录原文）；`EntityRequirementCheck.ENTITY_ARGS` 增 `productId ↔ product`；`DomainRouter` 关键词表增 product / aftersale，refund 删「退货」；`DomainDescriptions` +2；`PromptBuilder` 用户段附「消息中识别到的实体」；`order.delete` / `aftersale.create` 确认后重校验路径（复用 `executeConfirmed` 的 recheck 结构，recheck 工具由 `ScreenBuilder` 声明 `recheckToolId()`）。
- **所属端**：backed
- **输入**：T06、现 `RuleBasedLlmClient` / `DomainRouter` / `EntityRequirementCheck` / `RunOrchestrator`
- **输出**：上述改动 + `application/EntityExtractor.java`
- **验收**：`mvn verify` 0；规则模式 `curl` 五条消息（spec §4.1–4.5）事件序列各自符合；首期 + 上一 change e2e 全绿
- **依赖**：T06

## Phase D — 前端

### T08 core 三新组件 + OrderCard 扩展 + onIntent
- **目标**：`schema/uiSchema.ts` 与 `registry/types.ts` 同步契约（10 类型、3 组 props、`IconName`）；desktop / mobile 各 3 个新实现；`OrderCard` 多商品 + 物流状态；`icons.ts` 白名单映射；`SchemaRenderer` 增 `onIntent` 并透传到列表组件；`FormComponentHandlers` 扩为 `ComponentHandlers{onChange?, onIntent?}`；`index.ts` 类型导出 +1（`IconName`）；README 更新。
- **所属端**：fronted
- **输入**：T01、现 core
- **输出**：`packages/core/src/**`、`scripts/verify-pack.mjs` 清单（类型 11）
- **验收**：`pnpm -C fronted run ci` 0；check-registry 10；verify-pack 17 + 11；`/dev/schema?example=order-list|product-list|logistics` 三例渲染，1280 / 375 各 console.error 0
- **依赖**：T01

### T09 chat 应用：意图按钮、连续对话、示例 chip
- **目标**：`AgentChatPanel` 接 `onIntent` → 显示用户消息 + `start.mutate`；连续对话：新 Run 期间保留上一屏直到新 `ui.replace`；消息区渲染用户 / 助手两类消息；演示页示例 chip 三条；`schema-playground` 三个新示例入口。
- **所属端**：fronted
- **输入**：T08、现 `apps/chat`
- **输出**：`apps/chat/src/**`
- **验收**：`pnpm -C fronted run ci` 0；手动 / e2e：点行内按钮后消息区出现该文本且新 Run 开始
- **依赖**：T08

## Phase E — Harness、文档、验收

### T10 e2e 与文档
- **目标**：`e2e-backend.sh` 新增 ⑦–⑭；`e2e-frontend.mjs` 新增 ≥ 8 项（列表 → 行内按钮 → 新屏，两条链）+ 3 张截图；`wiki/domain-model.md`、`wiki/api-contracts.md`、`backed/README.md`、`packages/core/README.md`、`project-structure.md` §2、`06-backend-module-spec.md` 同步；doctor 必需文件加 `check-seed.mjs`。
- **所属端**：harness
- **输入**：T07、T09
- **输出**：脚本 + 文档
- **验收**：规则 / LIVE 两模式 e2e-backend 全绿；e2e-frontend 全绿；doctor 0
- **依赖**：T07、T09

### T11 全链路验收
- **目标**：`rm -rf */dist && pnpm -C .harness run ci`（含 check-seed）；deploy-verify；截图与产物冻结；coding_report。
- **所属端**：harness
- **输入**：T10
- **输出**：`deployment/**`、`coding/coding_report_v1.md`
- **验收**：spec §6 全部为真
- **依赖**：T10

## 依赖图

```
T01 ──────────────┬→ T06 → T07 ─┐
T02 → T03 → T05 ──┘              ├→ T10 → T11
T02 → T04 ────────┘              │
T01 → T08 → T09 ─────────────────┘
```
