# Spec: refactor-spark-embedded-starter-20260909

> v1 — 阶段 1 产出。用户决策（本轮对话）：① 后端核心能力作为 **Spring Boot Starter** 引入任意 Java 业务工程，方法级 `@SparkTool` 注解即可声明工具；② 只做**非分布式（embedded）**：平台与领域服务同进程，分布式 provider / 中心平台 / 服务发现全部进非目标，仅预留端口；③ **整个工程改名**：前端 `spark-ui`、后端 `spark-rooter`，覆盖目录、npm 包与导出名、Java 包 / Maven 坐标 / 注解名、契约 `$id` 与文档；④ **内核不定义用户与权限**：身份、授权、页面上下文、业务字段全归宿主，spark-rooter 只认「工具」与「屏」；⑤ 前端**始终只发自然语言**，`pageContext` / chips 从契约与 core 移除，多级界面由后端预写行内 intent 逐屏下发；⑥ 不拆 `@spark-ui/agent`，`useAgentRun` / `AgentChatPanel` 留在 `spark-chat` 应用；⑦ 参数边界靠 `@SparkParam` 声明 + 确定性抽取 + 校验器三层；⑧ 会话记忆支持省略实体的追问；⑨ 澄清屏（实体缺失且候选可枚举时出 Table 让用户点选）。

## 1. 背景

前四个 change 交付了一个可运行的 Spring Boot 单体：Runtime / Registry / Gateway 与四个 mock 领域服务同进程，前端 `@strato-ui/core` 渲染五种官方组件。审查（本轮对话）发现它**装不进任何其他 Java 服务**：Bean 靠 `com.strato` 包扫描、契约从 monorepo 相对路径复制、身份写死在两个请求头、权限表写死在 yml、Web 层与 MVC 强绑定、所有可替换点缺「宿主提供则覆盖」的装配。同时项目定位调整：内核与业务彻底解耦——不管用户、不管权限、不管页面上下文，运行在宿主已有的体系之下；前端只发自然语言。

本 change 把「能跑的单体」改造成「能引入的内核 + 能引入的渲染引擎 + 一个演示宿主」，并完成全量改名。

## 2. 范围（In Scope）

### 2.1 改名（机械，先做先提交）

| 层 | 旧 | 新 |
|---|---|---|
| 目录 | `fronted/` / `backed/` | `spark-ui/` / `spark-rooter/` |
| npm | `@strato-ui/core` / `strato-chat` / workspace `fronted` | `@spark-ui/core` / `spark-chat` / `spark-ui` |
| 导出名 | `StratoThemeProvider` / `StratoDeviceProvider` / `StratoThemeTokens` / `StratoThemeProviderProps` | `Spark*` |
| CSS 变量 | `--strato-*` | `--spark-*` |
| Java 包 | `com.strato.{spi,contracts,runtime,registry,gateway,domain,app}` | `com.sparkrooter.{spi,contracts,runtime,registry,gateway,…}` |
| Maven | `com.strato:strato-backed` 及子模块 | `com.sparkrooter:spark-rooter-parent`；模块见 §2.2 |
| 属性 / 环境变量 | `strato.*` / `STRATO_LLM_*` / `STRATO_CHANGE` / `STRATO_PORT` / `STRATO_BACKEND` / `STRATO_FRONT_BASE` | `spark.*` / `SPARK_LLM_*` / `SPARK_CHANGE` / `SPARK_PORT` / `SPARK_BACKEND` / `SPARK_FRONT_BASE` |
| 契约 `$id` | `https://strato.local/contracts/v1/` | `https://spark-rooter.local/contracts/v1/` |
| 日志 / 类名 | `StratoApplication`、`selfcheck: …`、`[strato-ui]` 前缀 | `SparkRooterApplication`（仅示例）、`[spark-ui]` |
| Harness | `.harness/scripts/**`、`rules/**`、`agents/**`、`skills/**`、`CLAUDE.md`、`AGENTS.md`、`lefthook.yml`、`docs/**` 中全部 `fronted/backed/strato` | 同步 |

`.harness/changes/**` 历史 change 目录**不改**（Audit Trail）。`git mv` 保留历史。改名完成的机械判据：全树（排除 `node_modules / target / dist / .git / .harness/changes`）`grep -i strato` 与 `grep -w fronted|backed` 均 0；`pnpm -C .harness run ci` 0。

### 2.2 后端 spark-rooter：模块与产物

```
spark-rooter/
├── pom.xml                              # parent：com.sparkrooter:spark-rooter-parent
├── spark-rooter-spi/                    # 注解 + 接口；零 Spring（原 platform-spi）
├── spark-rooter-contracts/              # 契约 schema 打进 jar + record DTO + SchemaValidator（原 contracts-java）
├── spark-rooter-runtime/                # 原 agent-runtime（去 api/ 包）
├── spark-rooter-registry/               # 原 tool-registry（去 api/ 包）
├── spark-rooter-gateway/                # 原 tool-gateway（去 api/ 包）
├── spark-rooter-web-mvc/                # /agent/runs SSE 端点 + 异常映射 + 三个 /internal 端点；@ConditionalOnWebApplication(SERVLET)
├── spark-rooter-spring-boot-starter/    # AutoConfiguration.imports + 属性类 + @ConditionalOnMissingBean 默认实现装配；宿主唯一引入坐标
└── examples/
    ├── domains/                         # 四个领域服务（order / product / aftersale / refund）改为 @SparkTool 形态；只被示例宿主依赖
    └── host-demo/                       # 独立 Maven 工程（非 parent 子模块）：只依赖本地仓 starter + examples/domains；MockUserContextInterceptor；这是「引入即可用」的验收物
```

- **依赖方向**（`check-module-deps` 更新）：`spi ↛ 任何 com.sparkrooter`；`contracts → spi`；`runtime / registry / gateway → contracts, spi`（三者之间只经 `api` 包接口，不变）；`web-mvc → 三者`；`starter → 全部平台模块`；`examples/domains → spi, contracts`（不依赖平台模块）；`host-demo → starter, examples/domains`。**平台模块 pom 不得出现 `spring-boot-starter-web`**，只依赖 `spring-context` / `spring-web`（`web-mvc` 除外）。
- **契约打包**：`spark-rooter-contracts` 的 `pom.xml` 仍从 `../../.harness/contracts` 复制 schema 与示例进 jar（真源不变），但 jar 内自带 `contracts/*.schema.json` + `contracts/examples/INDEX`，运行期不依赖仓库布局；`host-demo` 只消费 jar。
- **发布**：本 change 不发私服。验收等价物：`mvn -q install` 到 `~/.m2` 后，在 `examples/host-demo`（独立 `pom.xml`，parent 为 `spring-boot-starter-parent`，不是 `spark-rooter-parent`）`mvn -q spring-boot:run` 成功且 e2e 全绿。

### 2.3 自动装配与配置

`spark-rooter-spring-boot-starter` 提供 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，无需 `@Enable*`。

| 属性 | 默认 | 说明 |
|---|---|---|
| `spark.llm.base-url / api-key / model` | 空 | 缺任一 → 规则规划器 + noop 分类器，启动 WARN；兼容环境变量 `SPARK_LLM_*`（Spring 宽松绑定） |
| `spark.runtime.run-pool` / `ping-pool` | 8 / 2 | 线程池 |
| `spark.runtime.sse-timeout` | 60s | SSE 连接超时 |
| `spark.runtime.token-ttl` | 10m | 确认令牌有效期 |
| `spark.runtime.memory-ttl` | 30m | 会话记忆有效期（§2.6） |
| `spark.gateway.tool-pool` | 8 | 工具执行线程池 |
| `spark.web.base-path` | `/agent` | `/agent/runs` 前缀；`/internal/**` 端点默认**不装配**（`spark.web.internal-endpoints=false`） |
| `spark.selfcheck.enabled` | `false` | 启动自检；示例宿主打开 |

**可替换端口**（宿主定义同类型 Bean 即覆盖，全部 `@ConditionalOnMissingBean`）：

| 端口（spi） | 默认实现 | 备注 |
|---|---|---|
| `RunRepository` / `ConfirmationTokenStore` / `IdempotencyStore` / `ToolRegistryRepository` / `ConversationMemory` | 内存（`RunRepository` / `ConversationMemory` 带 TTL 淘汰） | 多实例部署换 Redis（后续 change） |
| `AuditSink` | `AUDIT` logger 一行 | 接审计系统 |
| `LlmClient` / `IntentClassifier` | Spring AI OpenAI 兼容 / 规则 | 换供应商 |
| `SessionIdResolver` | 返回请求体 `conversationId` | 见 §2.7；宿主可改为绑自己的登录态 |
| `ToolAccessPolicy`（可选） | 全放行 | 宿主实现后 Registry 候选过滤 + Gateway 拒绝按它判定；**内核不定义用户模型**，policy 入参只有 `toolId` 与 `HttpServletRequest`（web-mvc 提供）/ 宿主自取上下文 |
| `ToolTransport` / `ToolProviderDiscovery` | in-process / 本进程 | **预留，本 change 不实现其它实现**（分布式后续） |

**Bean 名前缀**统一 `sparkRooter*`（`sparkRooterRunExecutor`、`sparkRooterSchemaValidator`…）；平台自建 `ObjectMapper`（`JavaTimeModule`、禁 timestamps、`FAIL_ON_UNKNOWN_PROPERTIES`），不借宿主的。

### 2.4 方法级注解与 Manifest 推导

spi 新增注解（全部 `RUNTIME`）：

```java
@SparkTool(id, version, domain, name, description)                 // 方法级，必填全部
@SparkRisk(level = LOW|MEDIUM|HIGH, confirmation = NEVER|REQUIRED, // 方法级，可选；缺省 LOW / NEVER / none
           idempotency = NONE|REQUIRED, sideEffect = false, reversible = true, timeoutMs = 3000, maxRetries = 1)
@SparkPrerequisite({"order.detail.get"})                            // 方法级，可选；需确认工具的前置只读步骤（替代 IntentVerbs.PREREQUISITES 硬编码）
@SparkParam(description, enums = {}, aliases = {}, min, max, minLength, maxLength, format = NONE|DATE|DATE_TIME|MONEY, entity = NONE|ORDER|PRODUCT)
                                                                    // record 组件级，可选；**未标注的组件不进 inputSchema**（默认不开放）
@SparkDefault("20")                                                 // record 组件级，可选；缺省值（写进 schema default，规划器补参）
```

- 方法签名：`Out method(In in)` 或 `Out method(In in, ToolContext ctx)`；`In` / `Out` 必须是 record。`ToolContext{runId, toolCallId, idempotencyKey, traceId}`，**无用户字段**。
- `inputSchema` 从 `In` 的 `@SparkParam` 组件推导（`type` 由 Java 类型映射：`String→string`、`int/long→integer`、`BigDecimal→string pattern money`、`LocalDate→string format date`、`boolean→boolean`、`List<record>→array`）；`required` = 无 `@SparkDefault` 且非 `Optional` 的组件；`additionalProperties:false`。`outputSchema` 从 `Out` 全部组件推导（不需注解，输出全开放；`additionalProperties:false`）。
- `@SparkParam.entity` 标记实体类参数（替代 `EntityRequirementCheck.ENTITY_ARGS` 硬编码）；`aliases` 供抽取器识别中文别名（如 `status` 的 `SHIPPED` 别名「已发货」）。
- 生成的 Manifest 必须通过 `tool-manifest` 契约校验，否则启动失败；`protocol` 固定 `in-process`；`owner.team` 取 `spark.owner-team` 属性（默认 `host`）。
- 旧 `ToolHandler` 接口保留为底层 spi；注解方法由 `AnnotatedToolHandler` 适配（反射调用，**必须经 `ApplicationContext.getBean` 取代理对象再 invoke，注解查找用 `AopUtils.getTargetClass`**，以保证宿主方法级切面生效）；手写 `ToolManifestSource` + JSON 仍可用（迁移期），两种来源同 toolId 冲突启动失败。
- `SparkToolScanner`：启动时遍历全部 Bean（`BeanFactoryPostProcessor` 之后、`ApplicationReadyEvent` 之前），扫 `@SparkTool` 方法 → Manifest + Handler → 注册进 Registry → `ToolNameSink` 回填。日志：`spark-rooter: {n} tools registered from {m} beans`。

### 2.5 身份、权限、业务字段：全归宿主

- **内核删除**：`Principal`、`PrincipalPermissionResolver`、`InMemoryPrincipalPermissionResolver`、`spark.permissions.grants`、`X-User-Id / X-Tenant-Id` 头解析、`UnauthenticatedException`（401）、`tool-search` 契约的 `principal`、`tool-invoke` `executionContext` 的 `userId / tenantId`、Registry `search(principal)` → `search(domain)`、`domains(principal)` → `domains()`、Gateway 授权步骤、审计行的 `principal=` 字段、`user_002` 全部 e2e。
- **宿主承担**：登录 / 拦截器 / `UserContext`（ThreadLocal 或 SecurityContext）在 spark 之前建立；`@SparkTool` 方法体自行读取；方法级切面（`@PreAuthorize` 等）在 Gateway 反射调用时照常触发（§2.4 的代理要求保证这一点，验收 §6.2）。
- **示例宿主** `host-demo`：`MockUserContextInterceptor` 从请求头 `X-Demo-User` 读用户放进 `DemoUserContext`（ThreadLocal），四个领域工具方法读它；另放一个 `@DemoRequiresRole("admin")` 切面在 `order.delete` 上，用于验收「宿主切面拦截 → `TOOL_EXECUTION_FAILED`」。
- **可选 `ToolAccessPolicy`**：宿主实现后，Registry 候选过滤 + Gateway 执行前拒绝（`FORBIDDEN` → 确认路径 `CONFIRMATION_REJECTED`）。示例宿主**不实现**（演示默认全放行）。
- **`check-module-deps` 新红线**：`spark-rooter-{spi,contracts,runtime,registry,gateway,web-mvc,starter}` 源码不得出现 `userId` / `tenantId` / `Principal` 标识符（大小写敏感的 `\buserId\b` 等）。

### 2.6 自然语言进、UI Schema 出：参数边界与多轮

**契约**：`intent-request` 删 `pageContext`，只剩 `{conversationId, message, clientCapabilities}`。`DomainResolver` 的实体类型提示、`EntityExtractor` 的页面实体补位、`EntityRequirementCheck` 的 pageContext 分支全部删除。`clientCapabilities.components` 仍上送（Runtime 本 change 仍不做交集过滤，非目标不变）。

**参数三层限制**：

1. **声明层**：只有 `@SparkParam` 组件进 inputSchema（§2.4）。
2. **抽取层** `ArgumentExtractor`（原 `EntityExtractor` 扩展，纯函数，表驱动）：
   | 类型 | 文本形态 | 结果 | 来源 |
   |---|---|---|---|
   | 实体 ID | `订单\s*(\d{5})(?!\d)`、`商品\s*(P-\d{4})(?!\d)` | `{order:10002}` | 内核表（`@SparkParam.entity` 决定映射到哪个参数） |
   | 枚举别名 | 「已发货的订单」 | `status=SHIPPED` | `@SparkParam.aliases` |
   | 数量 | 「最近 5 单」「前 10 条」 | 数量类参数（`@SparkParam` 上 `min/max` 且 Java 整型且 `aliases` 含「单」「条」「个」之一）= 5，截断到 `max` | 内核正则 `(\d+)\s*(单|条|个|件)` |
   | 相对时间 | 「最近一周」「这个月」「9 月 1 日以后」 | `format=DATE` 参数 = 计算出的 ISO 日期 | 内核表：`最近(一|1)周 / 最近(\d+)天 / 这个月 / 上个月 / (\d{1,2})月(\d{1,2})日以后` |
   抽取顺序：当前消息 → 会话记忆补位（仅实体 ID）→ `@SparkDefault`。抽到的值日志只记类型与值，不记原文。
3. **校验层** `ToolSelectionValidator`（不变 + 增强）：键 ⊆ inputSchema；值过 JSON Schema（enum / min / max / format）；实体参数值 == 抽取值；需确认工具的可信参数不允许规划填写；必填且无默认缺失 → `MissingEntity` → 澄清屏或提示。

**会话记忆** `ConversationMemory`（spi 端口，默认内存 + TTL）：每个 Run 终态写入 `{conversationId → {domain, entities{type→id}, lastTable{toolId, rowIds[]}, at}}`。抽取层用途：① 当前消息缺实体类型时用记忆同类型实体补位（跨域允许，订单号在 order / refund / aftersale 通用）；② 序数指代「第(\d+)个|第(\d+)单|最后一(个|单)|第一个」→ `lastTable.rowIds[n-1]`。补位来的实体日志标 `source=memory`；**需确认工具的确认屏必须显式展示实体 ID**（现状三屏都有「订单 X」标题，验收断言）。记忆只存 ID，不存业务数据、不存用户。

**澄清屏**：`MissingEntity` 且目标工具所属域存在一个「无实体列表工具」（`IntentVerbs.LIST_TOOL`）时，Runtime 改为：调该列表工具（只读）→ 出 Table 屏，每行 `actions[0] = {label: <原动词标签>, intent: "<原消息> 订单 <id>"}`（如「申请售后」→ 每行「订单 10002 申请售后」）→ `message.delta("请选择要操作的订单")` → `run.completed`。列表工具不存在或返回空 → 现状 `message.delta` 提示。用户点选即第二轮，走完整链路。

**行内动作即多级界面**：`Card` props 增可选 `actions[]`（同 `inlineAction`，≤ 6），让详情卡也能成为下一屏入口（如商品卡「返回列表」→ 「有什么商品」）。`Table.rows[].actions` 不变。

### 2.7 确认令牌与会话

令牌绑定 `runId + actionId + argsDigest + sessionId`；`sessionId` 由 `SessionIdResolver` 提供，默认 = 请求体 `conversationId`（前端整段对话不变，无状态宿主也可用）。宿主可覆盖为自己的登录态标识。`ConfirmationTokenService.consume` 比对 sessionId 不一致 → `CONFIRMATION_REJECTED`（令牌类文案）。

### 2.8 前端 spark-ui

- `@spark-ui/core`：改名；`Card` props 增 `actions?[]`（Zod 同步）；`CardDesktop` / `CardMobile` 渲染底部 `Button`（同 Table 行内按钮：`data-intent`、`onIntent`）；公共 API 17 运行时不变、类型清单 +0（`InlineAction` 已有）；`verify-pack` 基线重写。**不拆 `@spark-ui/agent`。**
- `spark-chat`：改名；删 URL 参数 `page / entityType / entityId` 与 `PageContextQuery`；`AgentChatPanel` props 收敛为 `{conversationId, baseUrl?, fetch?}`（`fetch` 注入让宿主带登录态；默认 `window.fetch`）；示例 chip 保留在页面（纯预填输入框文本，不进 core、不进契约）；消息区 / 连续对话 / 上一屏保留策略不变；`clientCapabilities` 仍上送。
- `useAgentRun` / `runView` 留在 `spark-chat`。

### 2.9 契约变化

| 契约 | 变化 |
|---|---|
| 全部 9 个 | `$id` 前缀 → `https://spark-rooter.local/contracts/v1/`；`SchemaValidator.ID_PREFIX` 同步 |
| `intent-request` | 删 `pageContext`（及 `$defs.pageContext / selectedEntity`） |
| `tool-search` | request 删 `principal`（`required: ["domain"]`）；response 不变 |
| `tool-invoke` | `executionContext` 删 `userId / tenantId`，增 `sessionId`（string, 1–128，不透明） |
| `tool-manifest` | `authorization.permission` 改可选（供 `ToolAccessPolicy`）；其余不变 |
| `ui-schema` | `cardProps` 增 `actions?: inlineAction[] (maxItems 6)` |
| `run-summary` / `sse-events` / `action-request` / `error-response` | 不变（示例中含 `pageContext` 的更新） |

示例：删 `intent-request.example.json` 的 `pageContext`；新增 `ui-schema.product-detail.example.json`（Card 含 `actions`）；示例总数 26 → 27。

### 2.10 Harness

- 脚本改名与端口变量改名（§2.1）；`e2e-backend.sh` 改为对 `examples/host-demo` 启动（`mvn -q spring-boot:run` 或其打包 jar）；`deploy-verify.sh` 同。
- **e2e-backend 变更**：删 user_002 用例与 `principal` 相关断言；首期 §6.2.8「帮我把这个订单退款」+ pageContext 10001 → 「订单 10001 退款」；M2 幂等 / 路由 ②③⑥ 同样改为消息带号；⑭ 改为「`X-Demo-User: guest` 说删除订单 10005 → 宿主切面拒绝 → `TOOL_EXECUTION_FAILED`，`order.delete` 审计 `failed`」；新增：
  ⑰「我想查看最近订单」→ `order.list.search {page:1,size:20}`（argsDigest == 显式 `{page:"1",size:"20"}` 的 digest）；
  ⑱「最近 5 单已发货的订单」→ `{status:SHIPPED, size:5, page:1}`；
  ⑲「最近 100 单」→ `size:50`；
  ⑳「看看我的订单」→「第二个的物流」→ `order.logistics.get{orderId==rows[1].id}`，日志 `source=memory`；
  ㉑「查看订单 10002 的物流」→「申请售后」→ 确认屏 Card 标题「订单 10002」；
  ㉒ 空会话「申请售后」→ 澄清屏 `[Table]` 每行 action label「申请售后」intent 含行 id，`message.delta` 含「请选择」；点选（发 rows[0].actions[0].intent）→ 确认屏；
  ㉓ `spark.runtime.memory-ttl=1s`（示例宿主 e2e profile）→ ㉑ 第二句等 2s → 澄清屏而非补位；
  ㉔ 令牌 sessionId 不一致（用另一 `conversationId` 提交确认）→ `CONFIRMATION_REJECTED`；
  ㉕ 商品详情 Card 含 `actions[0].intent == "有什么商品"`。
- **e2e-frontend**：删 URL 参数用例；步骤 7 末尾增「点商品卡『返回列表』→ Table」；其余不变。
- **新门禁**：`check-module-deps` 增 §2.5 标识符红线 + 平台模块 pom 禁 `spring-boot-starter-web`；`check-rename.mjs`（一次性，纳入 ci 直到本 change 关闭后删除）断言全树无 `strato / fronted / backed`；`host-demo` 独立工程 `mvn -q -o spring-boot:run` 作为 ci 的一段（`-o` 离线证明只依赖本地仓）。
- 文档：`README`（接入指南三步）、`wiki/architecture.md`（embedded 模式图 + 预留分布式端口）、`wiki/api-contracts.md`、`rules/project-structure.md`（新目录 / 模块 / 红线）、`rules/agent-safety.md` §2（候选不再按用户过滤，授权归宿主；`ToolAccessPolicy` 可选）、`06-backend-module-spec.md`、`CLAUDE.md`。

## 3. 非目标（Out of Scope）

- **不**做分布式：无 provider 模式、无中心平台 jar、无 HTTP 工具协议、无服务发现实现（`ToolTransport` / `ToolProviderDiscovery` 只留接口 + in-process 默认实现）。
- **不**发布到公司私服 / npm；验收用本地仓 + 独立示例工程等价。
- **不**做 Redis / MySQL 实现；内存实现加 TTL 淘汰即止。
- **不**实现 `ToolAccessPolicy` 的任何非平凡实现；示例宿主全放行。
- **不**做 NLU 指代消解（属性指代「那个便宜的」、修正指代「换成 10003」）、跨会话记忆、记忆持久化。
- **不**做聚合 / 排序 / 批量编排类工具；计划仍是线性 1～3 步，不循环、不把上一步输出喂下一步参数（前置只读步骤给确认屏用除外）。
- **不**改组件白名单（仍 5 个），`Card.actions` 是 props 扩展不是新组件。
- **不**改 `clientCapabilities` 交集过滤；**不**做 WebFlux 适配（`web-mvc` 只有 SERVLET）。
- **不**改历史 change 目录内容（`.harness/changes/**` 保留旧名）。

## 4. 核心场景

### 4.1 宿主接入（host-demo 即范例）
`pom.xml` 加 `com.sparkrooter:spark-rooter-spring-boot-starter`；`application.yml` 可空；一个 `@Service` 里的方法加 `@SparkTool` + record 参数加 `@SparkParam`。启动日志 `spark-rooter: 12 tools registered from 4 beans`，`GET /actuator/health` UP，`POST /agent/runs` 可用。宿主自己的拦截器把用户放进 ThreadLocal，方法体读它；spark 不知道用户存在。

### 4.2 「我想查看最近订单」
路由 rule → order；抽取无实体、无枚举、无数量、无时间；无动词无实体 → `order.list.search`；参数全走 `@SparkDefault` `{page:1,size:20}`；Gateway 反射调 `OrderTools.list(in)`（经代理，宿主切面触发）；`OrderScreens` → Table 20 行，每行 `actions` 预写「查看订单 X 的物流」等；SSE `ui.replace`。

### 4.3 「有什么商品」→ 点第二行「查看商品」→ 「返回列表」
第一轮 Table；点击 = `POST /agent/runs {message:"查看商品 P-1011 的详情"}`（与输入框同一函数）；第二轮抽取 `{product:P-1011}` → `product.detail.get` → Card（含 `actions:[{label:"返回列表", intent:"有什么商品"}]`）；点击 = 第三轮。前端全程只发文本。

### 4.4 「查看订单 10002 的物流」→ 「申请售后」
第一轮记忆写入 `{entities:{order:10002}}`；第二轮抽取无实体 → 记忆补位 `order=10002`（`source=memory`）→ aftersale 域 → `aftersale.list.get → aftersale.create` → 确认屏 Card 标题「订单 10002」（用户可见补位结果）→ 确认（令牌 sessionId == conversationId）→ 重校验 → 执行 → Result。

### 4.5 空会话「申请售后」
抽取无实体、记忆空 → `MissingEntity(order)` → aftersale 域 `LIST_TOOL` 是 `aftersale.list.get`（无实体时列全部售后单，不是订单列表）→ 澄清屏改用 **`order` 域的 `order.list.search`**（`ArgumentExtractor` 报缺的是 `order` 实体，澄清屏按**实体类型**选列表工具：`order → order.list.search`、`product → product.list.search`）→ Table 每行 `{label:"申请售后", intent:"订单 X 申请售后"}` + `message.delta("请选择要操作的订单")`。

### 4.6 宿主权限拦截
`X-Demo-User: guest` 说「删除订单 10005」→ 候选含 `order.delete`（默认不过滤）→ 前置 `order.detail.get` 成功 → 确认屏 → 确认 → 重校验通过 → Gateway 反射调 `delete` → 宿主 `@DemoRequiresRole("admin")` 切面抛异常 → `HANDLER_ERROR` → `run.failed TOOL_EXECUTION_FAILED`；审计 `order.delete … status=failed`。安全上仍拦住，体验上晚一步——这是默认取舍（§7）。

## 5. 契约影响

修改 9 个契约的 `$id`；`intent-request` 删 `pageContext`；`tool-search` 删 `principal`；`tool-invoke` `executionContext` 字段替换；`tool-manifest.authorization.permission` 可选；`ui-schema.cardProps` 增 `actions`。示例 26 → 27。契约 `contracts.md` 变更记录写明「破坏性：删 pageContext / principal，无外部消费方」。

## 6. 验收标准

### 6.1 改名与结构
- [ ] 全树（排除 `node_modules / target / dist / .git / .harness/changes`）`grep -ri strato` 0；`grep -rw "fronted\|backed"` 0；`git log --follow` 对任一改名文件能追到旧路径。
- [ ] `ls spark-rooter` == `pom.xml spark-rooter-spi spark-rooter-contracts spark-rooter-runtime spark-rooter-registry spark-rooter-gateway spark-rooter-web-mvc spark-rooter-spring-boot-starter examples README.md`；`examples/host-demo/pom.xml` parent 为 `spring-boot-starter-parent`。
- [ ] `check-module-deps` 0；植入 `spark-rooter-runtime/pom.xml` 加 `spring-boot-starter-web` → 红；植入 runtime 源码 `String userId` → 红。
- [ ] 平台模块源码 `grep -rn "@Component\|@Service\|@Repository"` 只出现在 `infra/**` 且全部由 starter 的 `@AutoConfiguration` 显式 `@Import` / `@Bean`（不依赖包扫描）：`host-demo` 主类包名为 `com.example.demo`，启动后 12 工具注册成功即证明。

### 6.2 后端
- [ ] `mvn -q verify` 0；`mvn -q install` 后 `cd examples/host-demo && mvn -q -o spring-boot:run` 启动，日志 `spark-rooter: 12 tools registered from 4 beans`，自检 7/7（示例宿主 `spark.selfcheck.enabled=true`）。
- [ ] Manifest 推导：`GET /internal/tool-registry/tools/order.list.search/versions`（示例宿主打开 internal 端点）返回的 `inputSchema` == 期望 JSON（`status enum 5 / page min 1 default 1 / size min 1 max 50 default 20`，`additionalProperties false`，`required []`）；`order.delete` 的 `risk.level high / confirmation required / idempotency required`；植入一个 `@SparkTool` 缺 `description` → 启动失败；植入两个方法同 id → 启动失败；植入 `In` 非 record → 启动失败。
- [ ] 代理调用：`@DemoRequiresRole` 切面在 `order.delete` 上，⑭ 用例 `run.failed TOOL_EXECUTION_FAILED` 且宿主切面日志出现 `denied`；同一用例 `X-Demo-User: admin` → `Result`。
- [ ] `grep -rn "userId\|tenantId\|Principal" spark-rooter/spark-rooter-{spi,contracts,runtime,registry,gateway,web-mvc,spring-boot-starter}/src` 0。
- [ ] e2e-backend 规则模式：首期 + 前三 change 保留用例（改为消息带号）+ ⑦–⑬' + ⑮–㉕ 全绿；LIVE 模式同（⑳–㉓ 记忆用例两模式都跑；LLM 只在规划，抽取与记忆是确定性的）。
- [ ] ㉔ 令牌 sessionId 不一致 → `CONFIRMATION_REJECTED`；⑳ 日志含 `source=memory`；㉓ TTL 过期后走澄清屏。
- [ ] 日志：无用户原文、无 LLM 主机名 / 密钥 / 模型名（doctor 形态扫描不变）。

### 6.3 前端
- [ ] `rm -rf spark-ui/*/dist && pnpm -C spark-ui run ci` 0；`check-registry` 5；verify-pack 17 运行时；`@spark-ui/core` 包名、`Spark*` 导出；基线重写并记录。
- [ ] `grep -rn "pageContext\|entityType\|entityId\|chips" spark-ui/packages/core/src` 0；`spark-chat` 无 URL 参数解析。
- [ ] e2e-frontend：原 35 − URL 参数相关 + 「返回列表」步骤 ≥ 34 全绿；商品卡 `data-intent="有什么商品"` 可点且回到 Table。

### 6.4 全仓
- [ ] `pnpm -C .harness run ci` 全 0（含 `check-rename`、`host-demo` 离线启动段）；doctor 0；deploy-verify 12/12（对 host-demo）。

## 7. 风险与权衡

| 风险 | 影响 | 缓解 |
|---|---|---|
| 候选工具不再按用户过滤，模型可能规划出用户无权的操作 | 用户看到「执行失败」而非「无此能力」；**安全上仍由宿主切面拦住** | agent-safety §2 改写为「授权归宿主；内核保证的是：任何工具调用必经 Gateway、必经宿主方法级切面」；可选 `ToolAccessPolicy` 供需要前置过滤的宿主 |
| 反射调用绕过宿主 AOP（拿裸实例而非代理） | 宿主权限失效 = 真实安全漏洞 | §2.4 强制 `getBean` 取代理 + `AopUtils.getTargetClass` 找注解；6.2 用真切面验收；`AnnotatedToolHandler` 单元级自检：目标对象 `AopUtils.isAopProxy` 时必须走代理 |
| `conversationId` 前端可伪造，记忆与令牌都绑它 | A 猜到 B 的 id 可借 B 的记忆补位、或用 B 的令牌 | 记忆只存 ID 不存数据；确认屏显式展示实体；令牌仍绑 `argsDigest`（换参数即失效）；宿主应把 `conversationId` 绑到自己的登录态（`SessionIdResolver` 可覆盖）；README 写明 |
| 改名牵连 170+ 文件、Java 包路径全部移动，容易漏 | 编译 / 引用断裂 | 改名单独 task 先做先提交；`check-rename.mjs` 机械断言 0 命中；`git mv` 保留历史 |
| 注解推导 schema 与手写 JSON 表达力差距（`oneOf`、嵌套对象） | 复杂参数写不出 | 首期只支持标量 / 枚举 / 日期 / 金额 / record 数组；不够时业务方仍可用 `ToolManifestSource` 手写 JSON（迁移期保留） |
| Starter 与宿主 Spring Boot / Jackson 版本冲突 | 装不进老工程 | 前提写死 Boot 3.5 / Java 21；平台自建 `ObjectMapper` 不借宿主的；Bean 名带前缀 |
| 相对时间抽取依赖「今天」 | 测试不稳定 | `ArgumentExtractor` 注入 `Clock`（与 `runtimeClock` 同源）；e2e 断言用相对断言（`since == today-7d`） |
| 澄清屏对 aftersale 域用 order 列表工具，跨域调用 | 与「领域互不 import」红线冲突？ | 不冲突：Runtime 经 Gateway 调 `order.list.search`，仍是平台编排，不是领域互 import；但 `order` 域的 ScreenBuilder 出的屏被 aftersale 意图复用，行内 label 由 Runtime 覆写为原动词标签 |

## 8. 假设（非阻塞）
- 宿主使用 Spring Boot 3.5.x、Java 21、Spring MVC（Servlet）；Jackson 由 Boot 管理。
- `host-demo` 的四个领域工具与 change 4 的工具 id / 版本 / 输出一致（`order.list.search 1.1.0` 等），确保既有 e2e 断言可复用。
- 相对时间抽取按 `Asia/Shanghai`；`Clock` 可注入。
- 澄清屏的实体类型 → 列表工具映射写死在内核表：`order → order.list.search`、`product → product.list.search`；列表工具不在候选（宿主没声明）→ 退回一句提示。
