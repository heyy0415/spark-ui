# Spec: feat-commerce-domains-20260908

> v3.1 — 吸收 `review/spec_review_v3.md`（第 3 轮，3 MUST 均为文字补丁）：M-1 ⑫ 确认屏断言改 `[OrderCard, ConfirmationCard]`；M-2 夹具表增金额列（10001=128.00 / 10002=299.00 / 10003=1.00 / 10004=59.00 / 10006=88.00）；M-3 售后 status 枚举与「进行中」集合、10007–10010 分布写死；S-1 `RunFailure.userText`；S-2 `ToolDisplayNames` 引用注入牵连的签名列入 T06a；S-3 chip 与示例统一 10030 / 10002，无物流订单 `order.logistics.get` 返回 `status: NOT_SHIPPED, events: []`；S-4 ⑬ 审计按 runId 作用域；S-5 「4 个领域服务 / 4 个 data 目录」；L-1…L-7 吸收。进入 HITL ②。
> v3 — 响应 `review/spec_review_v2.md`（REVISION REQUIRED，M-A–M-E / S-A–S-F）：M-A 确认屏不再放空 Form（删除确认屏 = `[OrderCard, ConfirmationCard]`，Fallback 确认屏 = `[ConfirmationCard]`，token 白名单空集）；M-B 新增 e2e 专用 PAID 夹具 `10006`，种子售后 / 退款挂 `10008+`；M-C 夹具与可见区状态表写死（`10030` 最新 SHIPPED、`10029` COMPLETED），e2e-frontend 点 10030；M-D 自检数统一 7；M-E `OrderSnapshot` 增 `productName` / `quantity`；S-A 动词命中但缺实体 → 友好提示（`MissingEntity` 信号）；S-B 重校验拒绝用独立文案；S-C 需确认工具无 recheck → fail-closed + 自检；S-D spi 增 jsr310；S-E T02 拆 a/b、依赖字段修正；S-F recheck version 取自计划前置步骤、`ToolDisplayNames` 改可变注册表且 LlmClient 持引用不持快照；L 项吸收。
> v2 — 响应 `review/spec_review_v1.md`（REVISION REQUIRED，M1–M7 / S1–S12）：M1 规则规划器改「动词 → 目标工具 + 前置依赖」确定性表，`refund.status.get` 不进退款计划，结果屏 = 最后成功步骤；M2 实体抽取前移到路由后、拦截前，实体为多类型 map；M3 路由顺序 `refund → aftersale → order → product`，去单字关键词；M4 `ScreenBuilder` 签名用 `JsonNode`，spi 自有 `OrderSnapshot`；M5 spi `ConfirmationRecheck` 契约，⑬ 期望 `CONFIRMATION_REJECTED` + 另测 handler 双保险；M6 排序 `createdAt desc`、⑦ `items 20 / total 30`；M7 T06 / T07 / T08 / T10 各拆 a/b。S1 displayName 取 Registry name 表、`summaryOf` 交 ScreenBuilder；S2 既有断言同步清单；S3 verify-pack 重写基线；S4 确认屏只用 `previousOutputs`（aftersale.list.get 带订单摘要）；S5 user_002 四项只读；S6 售后种子挂 10006+、e2e 用 `data-intent` 定位；S7 DDL 硬格式；S8 hint / 文案随领域扩；S9 inlineAction 自检；S10 README 两列；S11 `gen-seed.mjs`；S12 非目标补四项。
> v1 — 阶段 1 产出。用户决策：① 纯自然语言驱动，行内按钮 = 自然语言快捷指令（点击即发送一条意图文本，走同一条 Runtime 链路，不预签 action）；② 删除订单 / 申请售后 / 退款三个写操作都走确认流；售后只到「创建售后单」；③ mock 数据 = 每领域 `data/*.json` 种子 + 配套 `schema.sql`（MySQL DDL，snake_case 列名 = json 键名），启动时由种子加载器读入内存；④ 单租户单用户（tenant_001 / user_001），商品全局可见。

## 1. 背景

首期只有「订单详情 + 退款」一条链路、3 条内存订单、7 个白名单组件里真正被后端使用的只有 4 个。要验证「纯自然语言驱动的实时 UI」这套基建是否成立，需要一组**贴近真实电商**的场景：用户在聊天里说「看看我的订单」「删掉那单」「这单的物流到哪了」「申请售后」「退款」「有什么商品」「看看这个商品」，后端理解并调对应工具，前端渲染出**可继续交互**的列表 / 详情 / 确认屏 / 结果屏。同时 mock 数据要能原样导入 Redis / MySQL，为后续外置存储 change 铺路。

## 2. 范围（In Scope）

### 2.1 领域与工具（后端 `backed/domains/`）

四个领域服务（order / refund 改造，product / aftersale 新建），共 **11 个工具**（原 6 个保留 4 个、改 2 个，新增 5 个）：

| 领域 | toolId | 版本 | 风险 | 确认 | 输入 | 输出（要点） |
|---|---|---|---|---|---|---|
| order | `order.list.search` | 1.1.0 | low | never | `status?`、`limit?`（≤ 50，默认 20） | `items[]{orderId, productId, productName, thumbnail?, quantity, amount, currency, status, createdAt}`、`total` |
| order | `order.detail.get` | 1.1.0 | low | never | `orderId` | 订单全字段 + `items[]`（多商品行） + `logistics?{carrier, trackingNo, status}` + `address{receiver, phoneMasked, region}` |
| order | `order.logistics.get` **新** | 1.0.0 | low | never | `orderId` | `carrier, trackingNo, status, events[]{time, location, description}` |
| order | `order.delete` **新** | 1.0.0 | **high** | required | `orderId` | `orderId, deleted:true, deletedAt` |
| product | `product.list.search` **新** | 1.0.0 | low | never | `keyword?`、`category?`、`limit?` | `items[]{productId, title, price, currency, stock, category, thumbnail?}`、`total` |
| product | `product.detail.get` **新** | 1.0.0 | low | never | `productId` | 商品全字段 + `specs[]{name, value}` + `salesCount` |
| aftersale | `aftersale.create` **新** | 1.0.0 | **high** | required | `orderId`、`type ∈ {RETURN, EXCHANGE, REPAIR}`、`reason` | `aftersaleId, orderId, type, status:SUBMITTED, createdAt` |
| aftersale | `aftersale.list.get` **新** | 1.0.0 | low | never | `orderId?` | `items[]{aftersaleId, orderId, type, status, createdAt}`；带 `orderId` 时另返回 `order{orderId, productName, quantity, amount, currency, status}` 摘要（供售后确认屏渲染 OrderCard） |
| refund | `refund.eligibility.check` | 1.2.0 | low | never | 不变 | 不变 |
| refund | `refund.preview` | 1.3.0 | low | never | 不变 | 不变 |
| refund | `refund.create` | 2.1.0 | high | required | 不变 | 不变 |
| refund | `refund.status.get` | 1.0.0 | low | never | 不变 | 不变 |

- `order.delete` 规则：只允许 `COMPLETED / CANCELLED / REFUNDED` 状态删除（软删，`status → DELETED`，列表默认不含）；`PAID / SHIPPED` 拒绝并返回业务原因（Gateway `HANDLER_ERROR` → 前端「执行失败」；`EligibilityPolicy` 风格的纯函数 `DeletionPolicy`）。
- 售后单 `status` 枚举 `{SUBMITTED, APPROVED, REJECTED, COMPLETED, CANCELLED}`；**进行中 = {SUBMITTED, APPROVED}**。`aftersale.create` 规则：订单 `status ∈ {SHIPPED, COMPLETED}` 且该订单无进行中售后单（每单进行中 ≤ 1）。
- `order.logistics.get` 对无物流的订单（PAID / CANCELLED 等）返回 `{orderId, carrier: "", trackingNo: "", status: "NOT_SHIPPED", events: []}`（不抛错）；`LogisticsTimeline.events` 契约允许空数组，前端显示「暂无物流信息」；`order.detail.get` 对 DELETED 订单返回 `HANDLER_ERROR`（业务原因「订单已删除」）。
- `refund` 领域的 `OrderLookup` 改为经 `order-service` 的 **`platform-spi` 端口**（新增 `spi/OrderSnapshotProvider`，order-service 实现，refund / aftersale 依赖 spi 接口而非 order 模块），删掉 `SeededOrderLookup` 双份种子。
- 权限：`order:read`、`order:delete`（新）、`product:read`（新）、`aftersale:read`、`aftersale:create`（新）、`refund:read`、`refund:create`。`user_001` 全部；`user_002` 四项只读：`order:read / product:read / aftersale:read / refund:read`（保留 `refund:read`，现有 e2e「user_002 refund 候选 == 3」不变）。
- 领域路由：`DomainRouter` 关键词表**按声明顺序匹配**，写死顺序 `refund → aftersale → order → product`（写操作领域优先、通用词最后）：`refund = [退款, 退钱, refund]`（删「退货」）、`aftersale = [售后, 换货, 维修, 退货]`、`order = [订单, order, 物流, 发货]`、`product = [商品, product, 有什么卖]`（不用单字「货」「买」）。`DomainDescriptions` 四条同步（refund 去「退货」）。`DomainResolver.ENTITY_HINT_WHITELIST` 扩为 `{order, product}`；`EntityRequirementCheck` 实体映射增 `productId ↔ product`，提示文案增 product（「请先选择一个商品」）与 aftersale（「请先在页面上选择一个订单，再申请售后」）。

### 2.2 mock 数据（`backed/domains/<svc>/src/main/resources/data/`）

四个领域各一个目录：

```
data/
├── schema.sql        # MySQL 8 DDL：CREATE TABLE IF NOT EXISTS，snake_case 列，主键、外键、索引；金额 DECIMAL(12,2)，时间 DATETIME(3)，ID VARCHAR(64)
├── products.json     # 数组；键 = 列名（snake_case）；值类型与 DDL 对齐（金额为字符串 "128.00"，时间 ISO-8601）
├── orders.json / order_items.json / logistics_events.json / aftersales.json / refunds.json
└── README.md         # 导入命令：mysql < schema.sql + jq 生成 INSERT；redis-cli HSET <table>:<pk> 的一行示例
```

- 规模：商品 **20**（4 类：数码 / 家居 / 服饰 / 食品，含库存 0 与库存充足、价格 9.90 ~ 6999.00）；订单 **30**（状态覆盖 PAID / SHIPPED / COMPLETED / REFUNDED / CANCELLED；每单 1~3 个商品行；SHIPPED / COMPLETED 有物流 3~6 条轨迹事件；地址手机号存脱敏形态 `138****1234`）；售后单 **4**（RETURN / EXCHANGE / REPAIR，含一条 APPROVED）；退款单 **3**（对应 REFUNDED 订单）。
- 时间：2026-08-01 ~ 2026-09-07，`created_at` 单调可读；金额 = Σ(单价 × 数量) 精确一致（seed 加载时校验，不一致启动失败）。
- 订单 ID `10001`–`10030`；商品 ID `P-1001`–`P-1020`。`order.list.search` 默认排序 **`createdAt desc`**（写进 manifest description），默认 20 行 = `10030` … `10011`。**夹具与可见区状态表**（`gen-seed.mjs` 以断言写死，`check-seed` 校验）：

| 订单 | 状态 | 金额 | created_at 位置 | 用途 |
|---|---|---|---|---|
| `10001` | PAID | **128.00** | 最早区 | 首期 e2e §6.2.8 退款主链路（会被退掉；`shown amount 128.00` 断言不变） |
| `10002` | SHIPPED | **299.00** | 最早区 | 上一 change M2 幂等 / 本 change ⑪ 售后（种子无售后单） |
| `10003` | PAID | **1.00** | 最早区 | 启动自检（`amount 1.00`） |
| `10004` | PAID | **59.00** | 最早区 | Gateway 幂等 e2e ④（`amount 59.00`） |
| `10005` | COMPLETED | 任意 | 最早区 | ⑫ 删除成功 |
| `10006` | PAID | **88.00** | 最早区 | ⑮ 无 pageContext 退款 |
| `10007`–`10009` | REFUNDED | 任意 | 早区 | 种子退款 3 条各挂 1 单（一单一退） |
| `10010` | COMPLETED | 任意 | 早区 | 种子售后 4 条：1 条 APPROVED 挂 10010；其余 3 条（REJECTED / COMPLETED / CANCELLED 各 1）挂 10007–10009，均非进行中 |
| `10011`–`10028` | 5 种状态各 ≥ 2 | 任意 | 可见区 | 默认列表主体 |
| `10029` | COMPLETED | 任意 | 次新 | 默认列表首屏有「删除订单」按钮 |
| `10030` | SHIPPED | 任意 | **最新** | ⑦ 首行；e2e-frontend 点 `data-intent="查看订单 10030 的物流"` |

e2e 对夹具区（10001–10006）的写操作都不影响默认 20 行列表断言；⑫ 删除 10005 后 `total 30 → 29`。
- 种子由一次性脚本 `.harness/scripts/gen-seed.mjs` 生成（固定随机种子，可复现；夹具表以断言写死），生成物提交进仓库；`check-seed` 除金额 / 外键外还校验：PAID 订单无物流事件、SHIPPED / COMPLETED 事件 ≥ 3、同表 `created_at` 严格递增、手机号 `^1\d{2}\*{4}\d{4}$`、夹具表每一行（状态 + 金额 + 相对位置）、`10030` 为最大 `created_at` 且 SHIPPED、每单进行中售后 ≤ 1、退款单一单一条且只挂 REFUNDED 单。
- **DDL 硬格式**（`check-seed` 只按此解析，不合格式报错）：每列独占一行、列名反引号包裹、约束行以 `PRIMARY KEY | UNIQUE | KEY | INDEX | CONSTRAINT | FOREIGN` 开头、无行尾注释、`CREATE TABLE IF NOT EXISTS \`snake_name\` (` 单独一行。
- `SeedLoader`（`platform-spi`）：自建 `ObjectMapper`（spi 无 Spring；pom 增 `jackson-datatype-jsr310`，注册 `JavaTimeModule`），`PropertyNamingStrategies.SNAKE_CASE` 把 json 键映射到 record 组件；`load(String resource, Class<T>) → List<T>`；金额 `BigDecimal`（json 字符串）、时间 `Instant`（json ISO-8601）。
- 加载：`platform-spi` 新增 `SeedLoader`（读 classpath `data/*.json` → record 列表，Jackson），各领域 `InMemory*Repository` 构造时调用；**加载器无 Spring 依赖**，后续 MySQL / Redis 实现只需换 Repository。
- `check-contracts` 不管这些 json；新增 `.harness/scripts/check-seed.mjs`：每个 json 的键集合 == 同名表 DDL 列集合；订单金额 = 行金额和；外键（order_items.order_id、logistics.order_id、aftersales.order_id、refunds.order_id、order_items.product_id）全部可解析。纳入 `ci.mjs`。

### 2.3 UI Schema 契约（`.harness/contracts/ui-schema.schema.json`）

`componentType` enum 从 7 扩到 **10**：新增 `OrderList`、`ProductList`、`LogisticsTimeline`。三者的 props 契约（后端生成 / 前端 Zod 同源）：

```
OrderList        { items[]{orderId, productName, thumbnail?, quantity, amount, currency, status, createdAt,
                            actions[]{label, intent}}, total, emptyText? }
ProductList      { items[]{productId, title, price, currency, stock, category, thumbnail?,
                            actions[]{label, intent}}, total, emptyText? }
LogisticsTimeline{ orderId, carrier, trackingNo, status, events[]{time, location, description} }
```

**`actions[].intent` 是一段自然语言文本**（≤ 200 字，无 URL / HTML），前端点击后原样作为新消息发送。例如订单行的 `[{label:"查看物流", intent:"查看订单 10002 的物流"}, {label:"申请售后", intent:"订单 10002 申请售后"}, {label:"删除订单", intent:"删除订单 10005"}, {label:"退款", intent:"订单 10001 退款"}]`；商品行 `[{label:"查看商品", intent:"查看商品 P-1003 的详情"}]`。后端按订单状态决定给哪些按钮（PAID → 退款；SHIPPED → 物流 + 售后；COMPLETED → 物流 + 售后 + 删除；CANCELLED / REFUNDED → 删除）。

- `thumbnail` 不是 URL：是白名单 emoji / 图标名（`^[a-z0-9-]{1,32}$`，如 `phone`、`headphones`），前端映射到 `@ant-design/icons`；契约「无 URL」约束不破。
- `OrderCard` props 增 `quantity?`、`items?[]{productName, quantity, amount}`（多商品）与 `logisticsStatus?`；`Table` 保留但本 change 不再由后端使用。
- 示例：`ui-schema.order-list.example.json`、`ui-schema.product-list.example.json`、`ui-schema.logistics.example.json`、`ui-schema.aftersale-confirm.example.json`、`ui-schema.delete-confirm.example.json`。
- `intent-request` / `sse-events` / `tool-search` / 其余契约**不变**（displayName 不动 `tool-search` 六字段）。
- `inlineAction.intent` 与 `label` 的绑定约束（写进 `contracts.md` §4 与 `check-contracts` 示例）：intent 必须含该行实体 ID；`label → 动词` 映射固定：`查看物流 → 物流`、`申请售后 → 售后`、`删除订单 → 删除`、`退款 → 退款`、`查看商品 → 查看商品`。

### 2.4 后端：屏生成、规划器、实体抽取、重校验（`agent-runtime` + `platform-spi`）

#### 2.4.1 `ScreenBuilder`（spi，签名只用 `JsonNode` 与 spi 自有类型）

```java
// platform-spi
public interface ScreenBuilder {
  Set<String> resultToolIds();                 // 哪些工具的成功输出由本 builder 出结果屏
  Set<String> confirmToolIds();                // 哪些需确认工具由本 builder 出确认屏
  JsonNode result(String toolId, JsonNode output, ScreenContext ctx);
  JsonNode confirmation(String toolId, Map<String,String> fixedArgs, Map<String,JsonNode> previousOutputs, String token, ScreenContext ctx);
  default String summary(String toolId, JsonNode output) { return null; }   // tool.completed.summary
}
public record ScreenContext(String runId, String userId, String tenantId) {}
```

- runtime `ScreenRegistry` 按 toolId 查表；查不到走 `FallbackScreenBuilder`（结果 → `Card` 列出输出键值；确认 → `ConfirmationCard` 列参数，**不含 Form**——契约 `Form.fields minItems 1`，空 Form 会被 `assertValid` 拒）。无 Form 的确认屏 token `allowedFormKeys = {}`，`formKeys(ui)` 对无 Form 屏返回空集，前端提交 `formData: {}`。runtime 收到 `JsonNode` 后 `treeToValue(UiSchema)` + `SchemaValidator.assertValid("ui-schema")`，校验仍只在 runtime 一处。
- **结果屏 = 计划中最后一个成功步骤的 `result`**；中间只读步骤不发 `ui.replace`（现状）。
- 确认屏只能用 `previousOutputs`（经 Gateway 的干净输出）与 `fixedArgs`，**不得**经 spi 直读领域数据（S4 取 (b)）：`aftersale.list.get` 输出增 `order{orderId, productName, amount, currency, status}` 摘要，`AftersaleScreens.confirmation` 用它渲染 `OrderCard`；`order.delete` 确认屏用同 Run 内 `order.detail.get` 的输出（规划器把它作为前置只读步骤）。`OrderSnapshotProvider`（spi 自有 record `OrderSnapshot{orderId, status, amount, currency, productName, quantity, createdAt}`；多商品单 `productName` = 首行商品名，`quantity` = 总件数）给 refund / aftersale 的领域策略与 `aftersale.list.get` 的 `order` 摘要用；**屏层不直接调它**（屏只用 `previousOutputs`）。
- 各领域实现放本模块 `infra/screen/`：`RefundScreens`（原 `UiSchemaBuilder` 两屏搬迁，行为不变）、`OrderScreens`（OrderList 行内按钮按状态、OrderCard 多商品、LogisticsTimeline、删除确认）、`ProductScreens`、`AftersaleScreens`。`displayName` 不再硬编码：spi 新增 `ToolNameSink { void register(String toolId, String name); }`，runtime 的 `ToolDisplayNames` 改为实现它的可变注册表 Bean（`ConcurrentHashMap`），Registry 的 `StartupManifestRegistrar` 注册每个 Manifest 时回填（方向：registry → spi ← runtime，无反向依赖）；`RuleBasedLlmClient` / `SpringAiLlmClient` **持有 `ToolDisplayNames` 引用**而不是构造期 `all()` 快照。`summaryOf` 删除，改调 `ScreenBuilder.summary`。

| 触发工具 | 屏 |
|---|---|
| `order.list.search` 结果 | `OrderList`（行内按钮按状态：PAID → 退款；SHIPPED → 查看物流 + 申请售后；COMPLETED → 查看物流 + 申请售后 + 删除订单；CANCELLED / REFUNDED → 删除订单）；`total > items.length` 时 `emptyText = "共 N 单，仅展示最近 M 单"` |
| `order.detail.get` 结果 | `OrderCard`（多商品、物流状态）+ 有物流则 `LogisticsTimeline` |
| `order.logistics.get` 结果 | `LogisticsTimeline` |
| `order.delete` 确认 | `OrderCard`（来自前置 `order.detail.get`）+ `ConfirmationCard{title:"确认删除订单", message:"删除后订单将从列表消失，不可恢复"}`（无 Form）；结果 → `ResultCard` |
| `product.list.search` 结果 | `ProductList`（行内按钮：查看商品） |
| `product.detail.get` 结果 | `Card{title, description, items: 价格 / 库存 / 分类 / 销量 / 规格…}` |
| `aftersale.create` 确认 | `OrderCard`（来自 `aftersale.list.get.order` 摘要）+ `Form{type: select[RETURN/EXCHANGE/REPAIR] required, reason: text required}`；结果 → `ResultCard` |
| `aftersale.list.get` 结果 | `Card` 列售后单 |
| `refund.*` | 现状不变 |

#### 2.4.2 实体抽取（M2）

`application/EntityExtractor`（纯函数）在 `RunOrchestrator.start` 的**路由之后、`EntityRequirementCheck` 之前**执行，产出 `Map<String,String> entities`（`{order: "10002", product: "P-1003"}`），来源合并：消息正则（`订单\s*(\d{5})`、`商品\s*(P-\d{4})`）优先，`pageContext.selectedEntity` 补位。`EntityRequirementCheck.check` 与 `LlmClient.PlanRequest` 都改用该 map（替换现单一 `type/id`）；`PromptBuilder` 「页面实体」段改名「已识别实体」。日志只记录抓到的 ID 与类型，不记录原文。

#### 2.4.3 规则规划器：动词 → 目标工具（M1）

`RuleBasedLlmClient` 不再按领域套模板，也**不**按 riskLevel 排序碰运气。确定性两步：

1. **目标工具**：`IntentVerbs` 表（runtime 常量，按顺序匹配）：`删除|删掉 → order.delete`；`物流|到哪|快递 → order.logistics.get`；`售后|换货|维修|退货 → aftersale.create`；`退款|退钱 → refund.create`；`详情|看看这个|查看商品 → <domain>.detail.get`；无动词命中：有该领域实体 → `<domain>.detail.get`，无实体 → `<domain>.list.search`（aftersale 无实体 → `aftersale.list.get`）。目标工具必须在候选内，否则 `TOOL_SELECTION_INVALID`（user_002 说「删除」→ 候选无 `order.delete` → 此路径；⑭ 期望写死为 `run.failed TOOL_SELECTION_INVALID`）。
2. **前置只读依赖**（写在表里，不靠排序）：`refund.create ← [refund.eligibility.check, refund.preview]`；`order.delete ← [order.detail.get]`；`aftersale.create ← [aftersale.list.get]`；其余无前置。`refund.status.get` **不进**退款计划。
3. 需确认工具**只在动词命中时**进入计划，绝不追加；每步参数从 `entities` 填。**动词命中但目标工具必填实体缺失**（如无号码无 pageContext 的「删除订单」，领域候选含无实体工具所以 `EntityRequirementCheck` 放行了）→ 规划器抛 `MissingEntity(entityType)` 信号，`RunOrchestrator` 走与 `EntityRequirementCheck` 相同的 `message.delta(提示文案)` + `run.completed`（不算失败，与 refund 领域行为一致）。真正的候选外 / 表外情况才是 `TOOL_SELECTION_INVALID`。LIVE 模式下 `ToolSelectionValidator` 把「需确认工具的必填实体参数缺失」同样映射为 `MissingEntity`，使两种模式行为一致。

`PlanSelfCheck` 扩为对 5 条核心消息断言 toolId 序列：「帮我把这个订单退款」+ order → `[eligibility.check, preview, create]`；「查看订单 10002 的物流」→ `[order.logistics.get]`；「订单 10002 申请售后」→ `[aftersale.list.get, aftersale.create]`；「删除订单 10005」→ `[order.detail.get, order.delete]`；「有什么商品」→ `[product.list.search]`。真模型模式：prompt 附动词表与前置依赖说明，输出仍过 `ToolSelectionValidator`（增加校验：需确认工具的前置只读步骤必须齐全且在前）。

#### 2.4.4 确认后重校验（M5）

spi 新增：

```java
public interface ConfirmationRecheck {
  String toolId();                                        // 被确认的工具
  String recheckToolId();                                 // 确认后经 Gateway 重调的只读工具
  Map<String,String> recheckArgs(Map<String,String> fixedArgs);
  Optional<String> reject(JsonNode recheckOutput, JsonNode shownUi);   // 非空 → CONFIRMATION_REJECTED（内部原因，不给前端）
  Map<String,String> trustedArgs(JsonNode recheckOutput);             // 覆盖 formData 的可信参数（如 amount）
}
```

`recheckToolId` 的 **version** 由 runtime 从当前计划中同 toolId 的前置只读步骤取（三个 recheck 工具都恰是各自的前置步骤：eligibility.check / order.detail.get / aftersale.list.get）；取不到 → `INTERNAL_ERROR`。需确认工具若查不到 `ConfirmationRecheck` 或 `ScreenBuilder.confirmToolIds()` 未覆盖 → **fail-closed**：`INTERNAL_ERROR` 结束 Run；启动自检 `ConfirmationCoverageSelfCheck` 断言 Registry 中所有 `confirmation=required` 工具都被两者覆盖。`reject` 非空时 `run.failed.code` 仍 `CONFIRMATION_REJECTED`（不动 sse 契约），但用户文案区分：`RunFailure` 增可选 `userText`，`fail()` 有则用之、无则按 code 映射；令牌 / 并发拒绝 → 现文案「确认已过期或已被使用，请重新发起」；重校验策略拒绝 → `userText = "订单状态已变化，本次操作未执行"`；`reject` 返回的内部原因只进日志。`trustedArgs` 的键与确认屏 `Form.fields[]` 键**互斥**（不变量，`ConfirmationCoverageSelfCheck` 顺带校验）。

各领域实现：`RefundRecheck`（recheck = `refund.eligibility.check`；`reject` = 不 eligible 或 `refundableAmount != shownUi.RefundConfirmCard.amount`；`trustedArgs = {amount}`，即首期 M3 逻辑原样迁出）；`OrderDeleteRecheck`（recheck = `order.detail.get`；`reject` = `DeletionPolicy` 不允许该 status）；`AftersaleRecheck`（recheck = `aftersale.list.get`；`reject` = `AftersalePolicy`：订单状态不在 {SHIPPED, COMPLETED} 或已有进行中售后）。runtime `executeConfirmed` 通用化：查 `ConfirmationRecheck` → 调 recheck 工具 → `reject` 非空 → `CONFIRMATION_REJECTED` → 合并 `trustedArgs` → 执行 → 结果屏。**领域策略不进 runtime**。`TRUSTED_ONLY_ARGS = {amount}` 与 formData 键白名单逻辑不变。

**双保险各测各的**：⑬「删除订单 10001（PAID）」→ 确认后重校验拒绝 → `run.failed CONFIRMATION_REJECTED`；另加 ⑬'：直接经 Gateway 调 `order.delete 10001` → handler 自身 `DeletionPolicy` 拒绝 → `HANDLER_ERROR`。

### 2.5 前端（`@strato-ui/core` + `apps/chat`）

- core 新增 3 组件的 desktop（antd `List` / `Card` / `Timeline`）与 mobile（antd-mobile `List` / `Card` / `Steps`）实现；`OrderCard` 支持多商品与物流状态；`thumbnail` 图标映射表（白名单 12 个名字 → `@ant-design/icons`，未知名字显示占位图标）。
- 新增 `SchemaRenderer` prop `onIntent?: (text: string) => void`；行内按钮点击调用它（不在 core 内发请求）。
- `apps/chat`：`AgentChatPanel` 把 `onIntent` 接到 `start.mutate({ message: text, … })`，并在消息区显示为用户消息「→ 查看订单 10002 的物流」；输入框支持连续对话（每次发送开一个新 Run，`conversationId` 不变；上一屏保留到下一屏 `ui.replace` 到达）。
- 演示页：URL 参数仍支持；另加一行「示例问题」快捷 chip（「看看我的订单」「有什么商品」「查看订单 10030 的物流」「订单 10002 申请售后」），点击 = 发送。`OrderList.emptyText` 语义：`items` 为空时显示；`total > items.length` 时作为列表底部说明「共 N 单，仅展示最近 M 单」。
- `verify-pack` 公共 API 清单：运行时 17 不变；类型 +1（`IconName`）→ 11；`FormComponentHandlers` 改名 `ComponentHandlers{onChange?, onIntent?}`（内部类型，不在公共清单）；`COMPONENT_TYPES` 10。体积基线：T08b 完成后 `pnpm -C fronted run verify-pack -- --write-baseline` 重写（coding_report 记录前后 KB，预期 40 → ≤ 60）。
- core README「安全边界」两列各加一行：随包走「`onIntent` 只回调纯文本，core 不发请求、不解释文本」；留宿主「把 `onIntent` 文本当用户输入原样提交，不拼接、不改写」。
- 行内按钮 DOM 带 `data-intent="<intent 原文>"`，e2e 用属性精确定位。

### 2.6 Harness

- `check-seed.mjs`（§2.2）纳入 `ci.mjs`；doctor 必需文件加它。
- `e2e-backend.sh` 新增（规则模式；全部**无 pageContext**，只靠消息内实体，顺序放在现有 M2 幂等用例之后）：
  ⑦「看看我的订单」→ `ui.replace` 含 `OrderList`，`items.length == 20`、`total == 30`、`items[0].orderId == 10030`、`items[1]`（10029）的 inlineActions 含「删除订单」；
  ⑧「查看订单 10002 的物流」→ `LogisticsTimeline`，`orderId == 10002`，`events.length ≥ 3`；
  ⑨「有什么商品」→ `ProductList` `total == 20 && items.length == 20`；
  ⑩「查看商品 P-1003 的详情」→ `Card` 且 title 含 P-1003 的商品名；
  ⑪「订单 10002 申请售后」→ 事件 `run.started tool.*×3(aftersale.list.get) ui.replace confirmation.required`，确认屏 `[OrderCard, Form]`；确认 `{type:"RETURN", reason:"包装破损"}` → `… ui.replace run.completed`，`ResultCard`；`aftersale.list.get 10002` 经 Gateway 返回 1 条；
  ⑫「删除订单 10005」→ 确认屏 `[OrderCard, ConfirmationCard]`（无 Form，提交 `formData: {}`）→ 确认 → `ResultCard`；之后 `order.list.search {}` `total == 29`；
  ⑬「删除订单 10001」→ 确认 → `run.failed CONFIRMATION_REJECTED`，`message` 为「订单状态已变化，本次操作未执行」，**该 runId 作用域内** `order.delete` 审计 0 行；⑬' 直接 Gateway `order.delete 10001` → HTTP 502 `INTERNAL_ERROR`（`HANDLER_ERROR` 映射）且审计 `failed:HANDLER_ERROR`；
  ⑭ `user_002` 「删除订单 10005」→ `run.failed TOOL_SELECTION_INVALID`（候选无 `order.delete`）；
  ⑮「订单 10006 退款」（无 pageContext；10001 已被 §6.2.8 退掉不能复用）→ 发起序列 == §6.2.8、确认后序列 == §6.2.9、`ResultCard`、`refund.status.get 10006` == 1（M2 的证明）；
  ⑯「删除订单」（无号码、无 pageContext）→ `run.started message.delta run.completed`，text 含「选择一个订单」（S-A：动词命中但缺实体走友好提示）。
- **既有断言同步清单**（T10a 必改）：`e2e-backend.sh` 自检「contracts 9 schemas, 20 examples OK」→ 25；`PlanSelfCheck` 日志文案改为「plan 5 messages OK」；新增自检 `InlineActionSelfCheck`、`ConfirmationCoverageSelfCheck` 各一行；`deploy-verify.sh` 与 `e2e-backend.sh` 的 selfcheck 总数 **5 → 7**；`check-registry` 输出 10。
- `OrderScreens` / `ProductScreens` 自检 `InlineActionSelfCheck`：对种子全部订单生成的每个 `inlineAction` 断言「intent 含该行 orderId」且 label ↔ 动词映射一致（S9）。
- `e2e-frontend.mjs` 新增步骤：输入「看看我的订单」→ `[data-component-id="order-list"]` 出现 → 点击 `[data-intent="查看订单 10030 的物流"]`（10030 = 最新 SHIPPED，夹具表写死）→ 消息区出现该文本作为用户消息 → `[data-screen-id]` 含 `LogisticsTimeline` → 输入「有什么商品」→ ProductList → 点 `[data-intent="查看商品 P-1003 的详情"]` → Card。截图 `ui-order-list.png`、`ui-logistics.png`、`ui-product-list.png`。
- 文档：`wiki/domain-model.md`（三领域实体与状态机）、`wiki/api-contracts.md`（11 工具）、`backed/README.md`、`fronted/packages/core/README.md`（10 组件 + `onIntent`）、`project-structure.md` §2（`ScreenBuilder` 归属领域模块）、`contracts.md` §4（`actions[].intent` 是自然语言不是 URL）。

## 3. 非目标（Out of Scope）

- **不**做预签 action（行内按钮不带 token）；**不**改 `intent-request` / `sse-events` / `action-request` 契约。
- **不**做真实图片 / URL；缩略图只用图标名。
- **不**做售后后续流程（审核 / 寄回 / 换货发货）；**不**做取消订单、修改地址、支付。
- **不**做多轮对话上下文（每条消息独立 Run；「删掉那单」这种指代不解析，必须带订单号或页面实体）。
- **不**接 MySQL / Redis；只保证数据形态可导入（DDL + json），Repository 仍内存。
- **不**做多用户隔离（单租户单用户）；`user_002` 只用于权限过滤 e2e。
- **不**做分页 UI；列表 `limit` 上限 50，前端一次渲染。
- **不**改真实 IdP、外置存储、`@strato-ui/agent` 拆包。
- **不**改 `tool-search` 契约（displayName 经 spi 回填，不加六字段）；**不**做 `clientCapabilities.components` 交集过滤；**不**解析「10002 这单」裸号 / 「删掉那单」指代；**不**做 `tool.completed.summary` 之外的语义摘要。

## 4. 核心场景

### 4.1 「看看我的订单」→ 订单列表 → 点「查看物流」
路由 rule → order；`EntityExtractor` 无实体；`EntityRequirementCheck` 放行（`order.list.search` 不需实体）；动词表：无动词无实体 → `order.list.search` → `tool.*` 三帧 → `ui.replace{OrderList 20 行, total 30}` → `run.completed`。用户点某行「查看物流」→ 前端发送「查看订单 10030 的物流」→ 新 Run → 路由 order → 抓 `{order:10030}` → 动词「物流」→ `order.logistics.get` → `ui.replace{LogisticsTimeline}`。

### 4.2 「订单 10002 申请售后」→ 确认屏 → 结果
路由：「售后」在 aftersale 表中且 aftersale 先于 order 匹配 → aftersale；抓 `{order:10002}`；动词「售后」→ 目标 `aftersale.create`，前置 `aftersale.list.get`（输出含 `order` 摘要）→ 确认屏 `OrderCard`（来自摘要）+ `Form{type, reason}` + `confirmation.required`。确认 → `AftersaleRecheck`：经 Gateway 重调 `aftersale.list.get`，`AftersalePolicy` 通过 → 执行 → `ResultCard{售后单号}`。

### 4.3 「删除订单 10005」→ 确认 → 成功；「删除订单 10001」→ 确认 → 策略拒绝
两条都走确认屏（前置 `order.detail.get` 提供 `OrderCard`）；后者确认后 `OrderDeleteRecheck` 重调 `order.detail.get`，`DeletionPolicy` 拒绝 PAID → `run.failed{CONFIRMATION_REJECTED}`，`order.delete` 未被调用（审计 0 行）。handler 自身的 `DeletionPolicy` 作为第二道保险由 ⑬' 直连 Gateway 证明。

### 4.4 「有什么商品」→ 商品列表 → 点「查看商品」→ 商品卡
路由 product（「商品」）；无实体无动词 → `product.list.search {}` → `ProductList 20 行` → 点击 → 「查看商品 P-1003 的详情」→ 抓 `{product:P-1003}`，动词「查看商品」→ `product.detail.get` → `Card`。

### 4.5 「订单 10001 退款」
带或不带 pageContext 都一样：抓 `{order:10006}`（e2e ⑮，10001 已被 §6.2.8 退掉），动词「退款」→ `refund.create`，前置 `[eligibility.check, preview]` → 三步 + 确认 + `RefundRecheck`（金额比对）+ 结果，与首期事件序列完全一致；屏与重校验逻辑搬到 refund-service。

## 5. 契约影响

**修改 1 个**：`ui-schema.schema.json` —— `componentType` enum +3；新增 `$defs` `orderListProps` / `productListProps` / `logisticsTimelineProps` / `inlineAction{label, intent}` / `iconName`；`OrderCard` props 增可选字段；`if/then` 对三个新组件 props 做结构约束（与 Form 同款）。
**新增示例 5 个**（§2.3）。其余 8 个契约不变。`contracts.md` §4 增一条：`actions[].intent` 与组件内 `actions[].intent` 是**自然语言文本**，前端只把它作为新消息发送，禁止解释为命令 / URL。

## 6. 验收标准

### 6.1 契约与数据
- [ ] `pnpm -C .harness run check-contracts` → 仍 9 schema，示例 20 → **25**，全部通过。
- [ ] `check-seed` 各校验项各植入一条反例 → 红（外键悬空、金额不等、PAID 带物流、DDL 行尾注释、10030 非 SHIPPED、10004 金额改 60.00、同单两条进行中售后）。
- [ ] `node .harness/scripts/check-seed.mjs` 退出码 0（含 4 个领域目录）；`jq length` orders 30 / products 20 / aftersales 4 / refunds 3；手机号字段全部匹配 `^1\d{2}\*{4}\d{4}$`。
- [ ] `mysql --version` 可用时：`mysql -e "source schema.sql"` 到临时库无错（可选，记录是否执行）。

### 6.2 后端
- [ ] `mvn verify` 0；`check-module-deps` 0：refund-service / aftersale-service 不 import `com.strato.domain.order.`（只经 spi `OrderSnapshotProvider`）；植入 → 红。
- [ ] 启动日志 `startup registration done: 11 tools from 4 sources`；自检 **7/7**（新增 `InlineActionSelfCheck`、`ConfirmationCoverageSelfCheck`；`PlanSelfCheck` 改 5 条消息；`RefundIdempotencySelfCheck` 用 10003 不变）；`deploy-verify.sh` / `e2e-backend.sh` selfcheck 期望同步为 7。
- [ ] 单独 Gateway（在任何 e2e 写操作之前执行）：`order.list.search {}` → `total == 30`、`items.length == 20`、`items[0].orderId == 10030`；`{status:"DELETED"}` 被 inputSchema enum 拒绝（400）。
- [ ] `e2e-backend.sh` 规则模式：首期 + 上一 change 全部用例仍绿，新增 ⑦–⑯（含 ⑬'）全绿；LIVE 模式：⑦–⑫、⑮ 同样断言（真模型需产出相同 toolId 序列，失败按 prompt 缺陷处理并记录），⑬ / ⑬' / ⑭ 两模式都跑。
- [ ] `grep -rn "SeededOrderLookup" backed` 0；`grep -rn "UiSchemaBuilder" backed/agent-runtime/src` 只剩兜底屏类（改名 `FallbackScreenBuilder`）。
- [ ] `order.delete` 对 PAID 订单：⑬ 走确认流 → `CONFIRMATION_REJECTED` 且该 runId 内 `order.delete` 审计 0 行；⑬' 直连 Gateway → 审计 `failed:HANDLER_ERROR`；两者之后订单列表仍含 10001。
- [ ] `grep -rn "OrderSnapshotProvider" backed/domains/*/src/main/java/*/infra/screen` 0 行（确认屏不直读领域数据）。

### 6.3 前端
- [ ] `rm -rf fronted/*/dist && pnpm -C fronted run ci` 0；`check-registry` 10 类型五方一致；verify-pack 17 运行时 + 11 类型；基线经 `--write-baseline` 重写并记录前后值。
- [ ] `grep -rn "http\|href=" fronted/packages/core/src` 0（缩略图为图标名）。
- [ ] `e2e-frontend.mjs` 原 21 项 + 新增 ≥ 8 项全绿，含「点行内按钮 → 新 Run → 新屏」。
- [ ] `/dev/schema?example=order-list|product-list|logistics` 三个新示例在 1280 / 375 下渲染，console.error 0。

### 6.4 全仓
- [ ] `pnpm -C .harness run ci`（含 check-seed）四段 + 1 段全 0；doctor 0；deploy-verify 12/12（自检 7）。

## 7. 风险与权衡

| 风险 | 影响 | 缓解 |
|---|---|---|
| 行内按钮的 `intent` 文本被后端当命令解析 | 越权 | 契约明确它只是消息；前端原样发送；后端仍完整走路由 / 候选 / 校验 / 确认；e2e ⑭ 用无权用户证明点「删除」不会执行 |
| 动词表 / 前置依赖表与候选集合脱节（新工具没进表） | 规划出错或 `TOOL_SELECTION_INVALID` | `PlanSelfCheck` 对 5 条核心消息各断言 toolId 序列；`IntentVerbs` 表引用的 toolId 在启动时校验都已注册 |
| 规则规划器不理解语义，动词误命中（「退货」归售后而非退款） | 走错领域 | 路由顺序与关键词表写死并有 e2e；模型模式可纠正 |
| `EntityExtractor` 正则从用户原文抓 ID | 日志泄露原文 | 只记录抓到的 ID，不记录原文；ID 格式白名单 |
| 契约 enum 扩 3 触发 check-registry / verify-pack 的五方一致 | 漏一处即红 | 正是这两个门禁的用途；tasks 把契约 → 前端 → 后端顺序写死 |
| 夹具金额 / 状态被 gen-seed 随机化 | 首期 / 上一 change 断言（128.00、59.00、1.00）红 | 夹具表钉金额，`gen-seed` 断言、`check-seed` 校验 |
| 跨领域数据（售后 / 删除确认屏要订单信息） | 领域间耦合或绕过 Gateway | 确认屏只用同 Run 内经 Gateway 的 `previousOutputs`；策略层才经 spi `OrderSnapshotProvider`；`check-module-deps` 新规则：domains 之间不得互相 import，`platform-spi` / `contracts-java` pom 不得依赖任何 `com.strato` artifact |
| 30 单 + 20 品手工数据算错金额 | 数据不可信 | `check-seed` 校验金额和与外键；seed 加载时二次校验，不一致启动失败 |
| mobile 端三个新组件的 antd-mobile 实现体量 | 工期 | `Steps` / `List` 直接可用；e2e 步骤 3 会验证 375 宽度 |

## 8. 假设（非阻塞）
- `thumbnail` 图标白名单 12 个：`phone, laptop, headphones, watch, home, lamp, shirt, shoe, coffee, cookie, gift, box`。
- 快捷 chip 只在 `apps/chat` 演示页，不进 core。
- `user_002` 的 e2e ⑭：动词「删除」命中但目标 `order.delete` 不在候选 → `TOOL_SELECTION_INVALID`（已在 §2.4.3 写死）。
- 夹具与可见区状态表（§2.2）由 `gen-seed.mjs` 断言保证、`check-seed.mjs` 校验。
