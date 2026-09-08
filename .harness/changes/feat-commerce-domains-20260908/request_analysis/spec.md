# Spec: feat-commerce-domains-20260908

> v1 — 阶段 1 产出。用户决策：① 纯自然语言驱动，行内按钮 = 自然语言快捷指令（点击即发送一条意图文本，走同一条 Runtime 链路，不预签 action）；② 删除订单 / 申请售后 / 退款三个写操作都走确认流；售后只到「创建售后单」；③ mock 数据 = 每领域 `data/*.json` 种子 + 配套 `schema.sql`（MySQL DDL，snake_case 列名 = json 键名），启动时由种子加载器读入内存；④ 单租户单用户（tenant_001 / user_001），商品全局可见。

## 1. 背景

首期只有「订单详情 + 退款」一条链路、3 条内存订单、7 个白名单组件里真正被后端使用的只有 4 个。要验证「纯自然语言驱动的实时 UI」这套基建是否成立，需要一组**贴近真实电商**的场景：用户在聊天里说「看看我的订单」「删掉那单」「这单的物流到哪了」「申请售后」「退款」「有什么商品」「看看这个商品」，后端理解并调对应工具，前端渲染出**可继续交互**的列表 / 详情 / 确认屏 / 结果屏。同时 mock 数据要能原样导入 Redis / MySQL，为后续外置存储 change 铺路。

## 2. 范围（In Scope）

### 2.1 领域与工具（后端 `backed/domains/`）

三个领域服务，共 **11 个工具**（原 6 个保留 4 个、改 2 个，新增 5 个）：

| 领域 | toolId | 版本 | 风险 | 确认 | 输入 | 输出（要点） |
|---|---|---|---|---|---|---|
| order | `order.list.search` | 1.1.0 | low | never | `status?`、`limit?`（≤ 50，默认 20） | `items[]{orderId, productId, productName, thumbnail?, quantity, amount, currency, status, createdAt}`、`total` |
| order | `order.detail.get` | 1.1.0 | low | never | `orderId` | 订单全字段 + `items[]`（多商品行） + `logistics?{carrier, trackingNo, status}` + `address{receiver, phoneMasked, region}` |
| order | `order.logistics.get` **新** | 1.0.0 | low | never | `orderId` | `carrier, trackingNo, status, events[]{time, location, description}` |
| order | `order.delete` **新** | 1.0.0 | **high** | required | `orderId` | `orderId, deleted:true, deletedAt` |
| product | `product.list.search` **新** | 1.0.0 | low | never | `keyword?`、`category?`、`limit?` | `items[]{productId, title, price, currency, stock, category, thumbnail?}`、`total` |
| product | `product.detail.get` **新** | 1.0.0 | low | never | `productId` | 商品全字段 + `specs[]{name, value}` + `salesCount` |
| aftersale | `aftersale.create` **新** | 1.0.0 | **high** | required | `orderId`、`type ∈ {RETURN, EXCHANGE, REPAIR}`、`reason` | `aftersaleId, orderId, type, status:SUBMITTED, createdAt` |
| aftersale | `aftersale.list.get` **新** | 1.0.0 | low | never | `orderId?` | `items[]{aftersaleId, orderId, type, status, createdAt}` |
| refund | `refund.eligibility.check` | 1.2.0 | low | never | 不变 | 不变 |
| refund | `refund.preview` | 1.3.0 | low | never | 不变 | 不变 |
| refund | `refund.create` | 2.1.0 | high | required | 不变 | 不变 |
| refund | `refund.status.get` | 1.0.0 | low | never | 不变 | 不变 |

- `order.delete` 规则：只允许 `COMPLETED / CANCELLED / REFUNDED` 状态删除（软删，`status → DELETED`，列表默认不含）；`PAID / SHIPPED` 拒绝并返回业务原因（Gateway `HANDLER_ERROR` → 前端「执行失败」；`EligibilityPolicy` 风格的纯函数 `DeletionPolicy`）。
- `aftersale.create` 规则：订单 `status ∈ {SHIPPED, COMPLETED}` 且该订单无进行中售后单。
- `refund` 领域的 `OrderLookup` 改为经 `order-service` 的 **`platform-spi` 端口**（新增 `spi/OrderSnapshotProvider`，order-service 实现，refund / aftersale 依赖 spi 接口而非 order 模块），删掉 `SeededOrderLookup` 双份种子。
- 权限：`order:read`、`order:delete`（新）、`product:read`（新）、`aftersale:read`、`aftersale:create`（新）、`refund:read`、`refund:create`。`user_001` 全部；`user_002` 只读三项。
- 领域路由：`DomainRouter` 关键词表增 `product`（商品 / 货 / 买 / product）、`aftersale`（售后 / 换货 / 维修 / 退货）；**refund 关键词删掉「退货」**（归售后）。`DomainDescriptions` 增两条。实体映射增 `productId ↔ product`。

### 2.2 mock 数据（`backed/domains/<svc>/src/main/resources/data/`）

每领域一个目录：

```
data/
├── schema.sql        # MySQL 8 DDL：CREATE TABLE IF NOT EXISTS，snake_case 列，主键、外键、索引；金额 DECIMAL(12,2)，时间 DATETIME(3)，ID VARCHAR(64)
├── products.json     # 数组；键 = 列名（snake_case）；值类型与 DDL 对齐（金额为字符串 "128.00"，时间 ISO-8601）
├── orders.json / order_items.json / logistics_events.json / aftersales.json / refunds.json
└── README.md         # 导入命令：mysql < schema.sql + jq 生成 INSERT；redis-cli HSET <table>:<pk> 的一行示例
```

- 规模：商品 **20**（4 类：数码 / 家居 / 服饰 / 食品，含库存 0 与库存充足、价格 9.90 ~ 6999.00）；订单 **30**（状态覆盖 PAID / SHIPPED / COMPLETED / REFUNDED / CANCELLED；每单 1~3 个商品行；SHIPPED / COMPLETED 有物流 3~6 条轨迹事件；地址手机号存脱敏形态 `138****1234`）；售后单 **4**（RETURN / EXCHANGE / REPAIR，含一条 APPROVED）；退款单 **3**（对应 REFUNDED 订单）。
- 时间：2026-08-01 ~ 2026-09-07，`created_at` 单调可读；金额 = Σ(单价 × 数量) 精确一致（seed 加载时校验，不一致启动失败）。
- 三个 e2e / 自检专用订单保留并改造：`10001`（PAID，可退款）、`10002`（SHIPPED，可售后）、`10003`（自检）、`10004`（幂等 e2e）；新增 `10005`（COMPLETED，可删除）作为删除 e2e 夹具。其余 25 单 ID 从 `10006` 起。
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
- `intent-request` / `sse-events` / 其余契约**不变**。

### 2.4 后端屏生成（`agent-runtime`）

把 `UiSchemaBuilder` 拆为按工具注册的 `ScreenBuilder`（`platform-spi` 新增接口 `ScreenBuilder { Set<String> resultToolIds(); Set<String> confirmToolIds(); UiSchema result(toolId, output, ctx); UiSchema confirmation(toolId, args, previousOutputs, token) }`），每领域一个实现放在领域模块 `infra/screen/`，runtime 按 `toolId` 查表；查不到走**通用兜底**（结果 → `Card` 列出输出键值；确认 → `ConfirmationCard` 列参数 + 空 `Form`）。

| 触发工具 | 屏 |
|---|---|
| `order.list.search` 结果 | `OrderList`（行内按钮按状态生成） |
| `order.detail.get` 结果 | `OrderCard`（多商品）+ 若有物流则 `LogisticsTimeline` + `actions`（同行内按钮，屏级） |
| `order.logistics.get` 结果 | `LogisticsTimeline` |
| `order.delete` 确认 | `OrderCard` + `ConfirmationCard{title:"确认删除订单", message:"删除后不可恢复…"}`；确认 → `ResultCard` |
| `product.list.search` 结果 | `ProductList` |
| `product.detail.get` 结果 | `Card{title, items: 价格 / 库存 / 分类 / 规格… , description}` + `actions[]{label:"看看相关订单"?}`（可选，首期不做） |
| `aftersale.create` 确认 | `OrderCard` + `Form{type: select[RETURN/EXCHANGE/REPAIR], reason: text}`；确认 → `ResultCard` |
| `aftersale.list.get` 结果 | `Card` 列售后单（复用 Card items） |
| `refund.*` | 现状不变（迁到 refund-service 的 `RefundScreens`） |

- 屏级 `actions[]` 仍只有 `submit / cancel` 两类（契约不变）；「行内自然语言按钮」全部在组件 props 内，不签 token。
- 规划器：`RuleBasedLlmClient` 的按领域模板改为**通用规则**：候选按 `riskLevel` 升序、只读在前、需确认放最后；每步参数从实体 / 上下文填，缺必填则跳过；一个领域一次最多 1 个需确认步骤。refund 三步模板保留为该规则的自然结果（eligibility → preview → create）。删除 `WITH_ENTITY / WITHOUT_ENTITY`。
- 规则规划器对「查看物流 / 申请售后 / 删除订单 / 查看商品 X」这类快捷指令的**实体提取**：`EntityExtractor`（正则）从消息里抓 `订单 (\d{5})` / `商品 (P-\d{4})`，与 `pageContext.selectedEntity` 合并（消息里的优先）。真模型模式由 prompt 完成同一件事，输出仍过 `ToolSelectionValidator`。
- `refund` 确认后金额重校验逻辑不变。`order.delete` / `aftersale.create` 确认后经 Gateway 重调只读工具（`order.detail.get`）校验状态仍满足策略，再执行。

### 2.5 前端（`@strato-ui/core` + `apps/chat`）

- core 新增 3 组件的 desktop（antd `List` / `Card` / `Timeline`）与 mobile（antd-mobile `List` / `Card` / `Steps`）实现；`OrderCard` 支持多商品与物流状态；`thumbnail` 图标映射表（白名单 12 个名字 → `@ant-design/icons`，未知名字显示占位图标）。
- 新增 `SchemaRenderer` prop `onIntent?: (text: string) => void`；行内按钮点击调用它（不在 core 内发请求）。
- `apps/chat`：`AgentChatPanel` 把 `onIntent` 接到 `start.mutate({ message: text, … })`，并在消息区显示为用户消息「→ 查看订单 10002 的物流」；输入框支持连续对话（每次发送开一个新 Run，`conversationId` 不变；上一屏保留到下一屏 `ui.replace` 到达）。
- 演示页：URL 参数仍支持；另加一行「示例问题」快捷 chip（「看看我的订单」「有什么商品」「订单 10002 的物流」），点击 = 发送。
- `verify-pack` 公共 API 清单：运行时 17 不变，类型 +3（`OrderListProps`、`ProductListProps`、`LogisticsTimelineProps` 不导出，走 `PROPS_SCHEMAS`；只导出 `IconName` 类型）→ 类型 11；`COMPONENT_TYPES` 10。

### 2.6 Harness

- `check-seed.mjs`（§2.2）纳入 `ci.mjs`；doctor 必需文件加它。
- `e2e-backend.sh` 新增（规则模式）：⑦「看看我的订单」→ `ui.replace` 含 `OrderList`，`items.length` == 非 DELETED 订单数（≤ 20 由 limit 截断则断言 `total`）；⑧「查看订单 10002 的物流」→ `LogisticsTimeline` 且 `events.length ≥ 3`；⑨「有什么商品」→ `ProductList` `total == 20`；⑩「查看商品 P-1003 的详情」→ `Card`；⑪「订单 10002 申请售后」→ `confirmation.required`，确认 `{type:"RETURN", reason:"…"}` → `ResultCard`，`aftersale.list.get 10002` 为 1；⑫「删除订单 10005」→ 确认 → `ResultCard`，之后 `order.list.search` 不含 10005；⑬「删除订单 10001」（PAID）→ 确认后 `run.failed TOOL_EXECUTION_FAILED`（策略拒绝）；⑭ `user_002` 「删除订单 10005」→ 候选无 `order.delete` → 无能力路径或提示（按规划器实际行为写死其一）。
- `e2e-frontend.mjs` 新增步骤：主页面输入「看看我的订单」→ 出现 `[data-component-id]` 的 OrderList → 点第一条 SHIPPED 订单的「查看物流」按钮 → 出现 `LogisticsTimeline`；「有什么商品」→ ProductList → 点「查看商品」→ Card。截图 `ui-order-list.png`、`ui-logistics.png`、`ui-product-list.png`。
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

## 4. 核心场景

### 4.1 「看看我的订单」→ 订单列表 → 点「查看物流」
路由 rule → order；无实体，`order.list.search` 不需实体 → 放行；规划 1 步 → `tool.*` 三帧 → `ui.replace{OrderList 25 行}` → `run.completed`。用户点某行「查看物流」→ 前端发送「查看订单 10002 的物流」→ 新 Run → `EntityExtractor` 抓 10002 → `order.logistics.get` → `ui.replace{LogisticsTimeline}`。

### 4.2 「订单 10002 申请售后」→ 确认屏 → 结果
route rule → aftersale；抓 10002；候选只在本领域：`aftersale.list.get`（只读，先执行）+ `aftersale.create`（需确认）；确认屏由 aftersale 领域的 `ScreenBuilder.confirmation` 经 `OrderSnapshotProvider`（spi）取订单信息渲染 `OrderCard`（不跨领域调工具）→ `aftersale.create` 需确认 → `ui.replace{OrderCard + Form{type, reason}}` + `confirmation.required`。确认 → 重校验（经 Gateway 调 `aftersale.list.get` 确认无进行中售后）→ 执行 → `ResultCard{售后单号}`。

### 4.3 「删除订单 10005」→ 确认 → 成功；「删除订单 10001」→ 确认 → 策略拒绝
两条都走确认屏；后者确认后 `order.delete` handler 抛 `IllegalStateException("PAID 订单不可删除")` → Gateway `HANDLER_ERROR` → `tool.completed{failed}` + `run.failed{TOOL_EXECUTION_FAILED}`（现有映射）。

### 4.4 「有什么商品」→ 商品列表 → 点「查看商品」→ 商品卡
route rule → product；`product.list.search {}` → `ProductList 20 行` → 点击 → 「查看商品 P-1003 的详情」→ `product.detail.get` → `Card`。

### 4.5 「订单 10001 退款」
与首期完全一致（refund 三步 + 确认 + 结果），只是屏生成器搬到了 refund-service。

## 5. 契约影响

**修改 1 个**：`ui-schema.schema.json` —— `componentType` enum +3；新增 `$defs` `orderListProps` / `productListProps` / `logisticsTimelineProps` / `inlineAction{label, intent}` / `iconName`；`OrderCard` props 增可选字段；`if/then` 对三个新组件 props 做结构约束（与 Form 同款）。
**新增示例 5 个**（§2.3）。其余 8 个契约不变。`contracts.md` §4 增一条：`actions[].intent` 与组件内 `actions[].intent` 是**自然语言文本**，前端只把它作为新消息发送，禁止解释为命令 / URL。

## 6. 验收标准

### 6.1 契约与数据
- [ ] `pnpm -C .harness run check-contracts` → 仍 9 schema，示例 20 → **25**，全部通过。
- [ ] `node .harness/scripts/check-seed.mjs` 退出码 0：3 个领域目录 json 键 == DDL 列；30 单金额一致；外键全部可解析；植入一条 `order_items.order_id = "99999"` → 红。
- [ ] `grep -c '"order_id"' backed/domains/order-service/src/main/resources/data/orders.json` == 30；`products.json` 20 条；手机号字段全部匹配 `^1\d{2}\*{4}\d{4}$`。
- [ ] `mysql --version` 可用时：`mysql -e "source schema.sql"` 到临时库无错（可选，记录是否执行）。

### 6.2 后端
- [ ] `mvn verify` 0；`check-module-deps` 0：refund-service / aftersale-service 不 import `com.strato.domain.order.`（只经 spi `OrderSnapshotProvider`）；植入 → 红。
- [ ] 启动日志 `startup registration done: 11 tools from 4 sources`；自检 5/5（`RefundIdempotencySelfCheck` 用 10003 不变）。
- [ ] `e2e-backend.sh` 规则模式：首期 + 上一 change 全部用例仍绿，新增 ⑦–⑭ 全绿；LIVE 模式同（⑭ 与 ③ 一样只在各自模式）。
- [ ] `grep -rn "SeededOrderLookup" backed` 0；`grep -rn "UiSchemaBuilder" backed/agent-runtime/src` 只剩兜底屏类（改名 `FallbackScreenBuilder`）。
- [ ] `order.delete` 对 PAID 订单：Gateway 审计 `status=failed:HANDLER_ERROR`；订单列表仍含该单。

### 6.3 前端
- [ ] `rm -rf fronted/*/dist && pnpm -C fronted run ci` 0；`check-registry` 10 类型五方一致；verify-pack 17 运行时 + 11 类型。
- [ ] `grep -rn "http\|href=" fronted/packages/core/src` 0（缩略图为图标名）。
- [ ] `e2e-frontend.mjs` 原 21 项 + 新增 ≥ 8 项全绿，含「点行内按钮 → 新 Run → 新屏」。
- [ ] `/dev/schema?example=order-list|product-list|logistics` 三个新示例在 1280 / 375 下渲染，console.error 0。

### 6.4 全仓
- [ ] `pnpm -C .harness run ci`（含 check-seed）四段 + 1 段全 0；doctor 0；deploy-verify 12/12（自检 5）。

## 7. 风险与权衡

| 风险 | 影响 | 缓解 |
|---|---|---|
| 行内按钮的 `intent` 文本被后端当命令解析 | 越权 | 契约明确它只是消息；前端原样发送；后端仍完整走路由 / 候选 / 校验 / 确认；e2e ⑭ 用无权用户证明点「删除」不会执行 |
| 规则规划器通用化后 refund 三步顺序变化 | 首期 e2e 红 | 规则「只读在前、需确认最后、按 riskLevel」对 refund 候选的自然结果就是 eligibility → preview → create；首期 e2e 全量保留作回归 |
| `EntityExtractor` 正则从用户原文抓 ID | 日志泄露原文 | 只记录抓到的 ID，不记录原文；ID 格式白名单 |
| 契约 enum 扩 3 触发 check-registry / verify-pack 的五方一致 | 漏一处即红 | 正是这两个门禁的用途；tasks 把契约 → 前端 → 后端顺序写死 |
| 跨领域屏（售后确认屏要订单信息） | 领域间耦合 | 只经 `platform-spi.OrderSnapshotProvider`，check-module-deps 新规则守护 |
| 30 单 + 20 品手工数据算错金额 | 数据不可信 | `check-seed` 校验金额和与外键；seed 加载时二次校验，不一致启动失败 |
| mobile 端三个新组件的 antd-mobile 实现体量 | 工期 | `Steps` / `List` 直接可用；e2e 步骤 3 会验证 375 宽度 |

## 8. 假设（非阻塞）
- `thumbnail` 图标白名单 12 个：`phone, laptop, headphones, watch, home, lamp, shirt, shoe, coffee, cookie, gift, box`。
- 快捷 chip 只在 `apps/chat` 演示页，不进 core。
- `user_002` 的 e2e ⑭：规则规划器在候选无需确认工具时会选到 `order.list.search`（只读）而非拒绝——以实际行为写死断言，两种都可接受，不可接受的是执行了删除。
