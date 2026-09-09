# Spec: refactor-spark-embedded-starter-20260909

> v2 — 响应 `review/spec_review_v1.md`（REVISION REQUIRED，4 MUST / 8 SHOULD）：M-1 §2.4 增「代理与线程」小节（final / 非 public 启动失败、`Method` 取法、宿主权限必须方法级、`RunContextPropagator` 端口解决异步线程 ThreadLocal 为空）；M-2 默认 `SessionIdResolver` 标 demo-only + WARN，`consume` 双校验，§7 如实；M-3 T07 / T09 各拆 a/b；M-4 §6 去空洞断言；S-1 类型表补嵌套 / Optional / Java enum / 时间；S-3 澄清屏由 Runtime 从原始输出投影 + `@SparkTool(clarifiesEntity)`；S-4 `@SparkParam.unit`、参数名沿用 `limit`；S-5 `ToolAccessPolicy(toolId, sessionId)`；S-6 自检 8；S-7 ㉓ 二次启动；S-8 `authorization: {}`；L-1 sse-timeout 90s；L-2 路由表可配置列为已知限制；L-3 领域包名一次到位；I-2 i18n 非目标。
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
| `spark.runtime.sse-timeout` | 90s | SSE 连接超时（LIVE 规划实测 9–39s） |
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
| `SessionIdResolver` | 返回请求体 `conversationId`（**demo-only**，启动 WARN） | 见 §2.7；**生产必须**由宿主实现为绑自己的登录态 |
| `ToolAccessPolicy`（可选） | 全放行 | 宿主实现后 Registry 候选过滤 + Gateway 拒绝按它判定；入参 `(String toolId, String sessionId)`，**内核不定义用户模型**；宿主要用户就从自己经 `RunContextPropagator` 恢复的上下文取 |
| `RunContextPropagator`（**宿主强烈建议实现**） | no-op（启动 WARN） | Runtime 与 Gateway 在自有线程池执行，宿主请求线程的 ThreadLocal / SecurityContext **不会自动带过去**；宿主实现 `capture()`（请求线程）/ `restore(ctx)` / `clear()`（工作线程），示例宿主用它传播 `DemoUserContext` |
| `ToolTransport` / `ToolProviderDiscovery` | in-process / 本进程 | **预留，本 change 不实现其它实现**（分布式后续） |

**Bean 名前缀**统一 `sparkRooter*`（`sparkRooterRunExecutor`、`sparkRooterSchemaValidator`…）；平台自建 `ObjectMapper`（`JavaTimeModule`、禁 timestamps、`FAIL_ON_UNKNOWN_PROPERTIES`），不借宿主的。

### 2.4 方法级注解与 Manifest 推导

spi 新增注解（全部 `RUNTIME`）：

```java
@SparkTool(id, version, domain, name, description, clarifiesEntity = NONE|ORDER|PRODUCT)
                                                                    // 方法级，前五项必填；clarifiesEntity 标记「该列表工具可作某实体类型的澄清候选源」（§2.6）
@SparkRisk(level = LOW|MEDIUM|HIGH, confirmation = NEVER|REQUIRED, // 方法级，可选；缺省 LOW / NEVER / none
           idempotency = NONE|REQUIRED, sideEffect = false, reversible = true, timeoutMs = 3000, maxRetries = 1)
@SparkPrerequisite({"order.detail.get"})                            // 方法级，可选；需确认工具的前置只读步骤（替代 IntentVerbs.PREREQUISITES 硬编码）
@SparkParam(description, enums = {}, aliases = {}, unit = {}, min, max, minLength, maxLength, format = NONE|DATE|DATE_TIME|MONEY, entity = NONE|ORDER|PRODUCT)
                                                                    // record 组件级，可选；**未标注的组件不进 inputSchema**（默认不开放）；aliases = 枚举值中文别名；unit = 数量参数的单位词（「单」「条」）
@SparkDefault("20")                                                 // record 组件级，可选；缺省值（写进 schema default，规划器补参）
```

- 方法签名：`Out method(In in)` 或 `Out method(In in, ToolContext ctx)`；`In` / `Out` 必须是 record。`ToolContext{runId, toolCallId, idempotencyKey, traceId}`，**无用户字段**。
- **类型映射表**（输入输出共用）：`String→string`；`int/long/Integer/Long→integer`；`boolean→boolean`；`BigDecimal→string pattern ^\d+(\.\d{1,2})?$`（金额一律字符串）；`LocalDate→string format date`；`Instant/OffsetDateTime→string format date-time`；Java `enum` → `string enum [常量名…]`；嵌套 `record` → `object`（递归，`additionalProperties:false`）；`List<T>` → `array items <T 映射>`；`Optional<T>` 或 `@Nullable T` → 类型同 `T` 但**不进 `required`**，序列化 `null`/empty 时省略字段（平台 ObjectMapper `Include.NON_ABSENT`，与现有 Manifest「省略而非 null」一致）。`Map` / 通配泛型 / 非 record 类 → 启动失败。
- `inputSchema` 只含 `@SparkParam` 组件；`required` = 无 `@SparkDefault` 且非 Optional / @Nullable；`additionalProperties:false`。`outputSchema` 含 `Out` 全部组件（输出全开放，不需注解）。推导结果必须能与现有 12 个手写 Manifest **逐一 diff 为空**（T09a 验收），保证既有 e2e 断言复用。
- `authorization` 节点：`@SparkTool` 无权限概念，推导为 `{}`（契约 `authorization.permission` 改可选，`authorization` 仍 required 对象）。
- `@SparkParam.entity` 标记实体类参数（替代 `EntityRequirementCheck.ENTITY_ARGS` 硬编码）；`aliases` 供抽取器识别中文别名（如 `status` 的 `SHIPPED` 别名「已发货」）。
- 生成的 Manifest 必须通过 `tool-manifest` 契约校验，否则启动失败；`protocol` 固定 `in-process`；`owner.team` 取 `spark.owner-team` 属性（默认 `host`）。
- 旧 `ToolHandler` 接口保留为底层 spi；注解方法由 `AnnotatedToolHandler` 适配（反射调用，**必须经 `ApplicationContext.getBean` 取代理对象再 invoke，注解查找用 `AopUtils.getTargetClass`**，以保证宿主方法级切面生效）；手写 `ToolManifestSource` + JSON 仍可用（迁移期），两种来源同 toolId 冲突启动失败。
- `SparkToolScanner`（`SmartInitializingSingleton`，早于 `ApplicationReadyEvent`）：遍历全部 Bean → `AopUtils.getTargetClass` 找 `@SparkTool` 方法 → Manifest + Handler → 注册 → `ToolNameSink` 回填。日志：`spark-rooter: {n} tools registered from {m} beans`。

**代理与线程（M-1 / S-5，安全关键）**：
- 调用对象必须是 `ApplicationContext.getBean(name)` 返回的**代理**；`Method` 用 `ClassUtils.getMostSpecificMethod(targetMethod, proxy.getClass())` + `AopUtils.selectInvocableMethod` 取可在代理上调用的方法对象（覆盖 CGLIB 与 JDK 接口代理两种情况）。
- 扫描器**启动失败**并指明原因的情况：`@SparkTool` 方法非 `public`；所在类 `final` 或方法 `final`（CGLIB 无法代理，宿主切面会静默失效）；方法在 `@Configuration` 类上；同类内其它方法自调用不受此约束但也不受切面保护（Javadoc 提示）。
- **宿主权限必须是方法级**：AOP 切面、`@PreAuthorize`、或方法体内显式校验。Controller 级拦截器 / Filter 对 spark 的反射调用**无效**（spark 不经 Controller）。README 接入指南与 `agent-safety.md` §2 明写；示例宿主用一个只在拦截器里做权限的「反面路径」在 e2e 中演示会被绕过（作为教学用例，断言它确实绕过），正面路径用 `@Aspect`。
- **线程与上下文**：Runtime 在 `agent-run-*` 线程规划与调用，Gateway 在 `tool-*` 线程执行，**都不是宿主的请求线程**，宿主 ThreadLocal / `SecurityContextHolder` / MDC 在那里为空。spi 端口 `RunContextPropagator { Object capture(); void restore(Object ctx); void clear(); }`：web-mvc 在接到请求时 `capture()`，Runtime / Gateway 每次切线程前 `restore`、结束 `clear`（含 SSE 确认路径）。默认 no-op + 启动 WARN「宿主上下文不会传播」。示例宿主实现它传播 `DemoUserContext`；e2e 断言 `order.list.search` 在工作线程里读到的用户 == 请求头用户。

### 2.5 身份、权限、业务字段：全归宿主

- **内核删除**：`Principal`、`PrincipalPermissionResolver`、`InMemoryPrincipalPermissionResolver`、`spark.permissions.grants`、`X-User-Id / X-Tenant-Id` 头解析、`UnauthenticatedException`（401）、`tool-search` 契约的 `principal`、`tool-invoke` `executionContext` 的 `userId / tenantId`、Registry `search(principal)` → `search(domain)`、`domains(principal)` → `domains()`、Gateway 授权步骤、审计行的 `principal=` 字段、`user_002` 全部 e2e。
- **宿主承担**：登录 / 拦截器 / `UserContext`（ThreadLocal 或 SecurityContext）在 spark 之前建立，并实现 `RunContextPropagator` 让它跨到 spark 工作线程；`@SparkTool` 方法体自行读取；**方法级**切面（`@PreAuthorize` 等）在 Gateway 反射调用时照常触发（§2.4 代理与线程小节保证，验收 §6.2）。
- **示例宿主** `host-demo`：`MockUserContextInterceptor` 从请求头 `X-Demo-User` 读用户放进 `DemoUserContext`（ThreadLocal）；`DemoContextPropagator implements RunContextPropagator`；四个领域工具方法读 `DemoUserContext`（`order.list.search` 输出增 `viewer` 字段回显当前用户，供 e2e 断言传播成功——该字段只在示例宿主，不改契约）；`@DemoRequiresRole("admin")` 切面在 `order.delete` 上（正面路径）；`DemoInterceptorOnlyGuard` 只在 MVC 拦截器里拒绝 `guest` 访问 `/demo/**`（反面路径，证明对 spark 无效）。
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
   | 数量 | 「最近 5 单」「前 10 条」 | `@SparkParam.unit` 含该单位词的整型参数 = 5，截断到 `max` | 内核正则 `(\d+)\s*(<unit 之一>)` |
   | 相对时间 | 「最近一周」「这个月」「9 月 1 日以后」 | `format=DATE` 参数 = 计算出的 ISO 日期 | 内核表：`最近(一|1)周 / 最近(\d+)天 / 这个月 / 上个月 / (\d{1,2})月(\d{1,2})日以后` |
   抽取顺序：当前消息 → 会话记忆补位（仅实体 ID）→ `@SparkDefault`。抽到的值日志只记类型与值，不记原文。
3. **校验层** `ToolSelectionValidator`（不变 + 增强）：键 ⊆ inputSchema；值过 JSON Schema（enum / min / max / format）；实体参数值 == 抽取值；需确认工具的可信参数不允许规划填写；必填且无默认缺失 → `MissingEntity` → 澄清屏或提示。

**会话记忆** `ConversationMemory`（spi 端口，默认内存 + TTL）：每个 Run 终态写入 `{conversationId → {domain, entities{type→id}, lastTable{toolId, rowIds[]}, at}}`。抽取层用途：① 当前消息缺实体类型时用记忆同类型实体补位（跨域允许，订单号在 order / refund / aftersale 通用）；② 序数指代「第(\d+)个|第(\d+)单|最后一(个|单)|第一个」→ `lastTable.rowIds[n-1]`。补位来的实体日志标 `source=memory`；**需确认工具的确认屏必须显式展示实体 ID**（现状三屏都有「订单 X」标题，验收断言）。记忆只存 ID，不存业务数据、不存用户。

**澄清屏**：`MissingEntity(type)` 时，Runtime 查 `ToolMetaRegistry` 中 `clarifiesEntity == type` 的工具（宿主用 `@SparkTool(clarifiesEntity = ORDER)` 标在自己的列表工具上，内核**不写死** toolId）→ 经 Gateway 调它（只读，无参或全默认）拿**原始输出** → runtime 内通用 `ClarificationScreen` 投影：取输出 `items[]`，每项以 `@SparkParam.entity == type` 标记的输出字段为行 id、其余标量字段为 cells，`actions[0] = {label: <原动词标签>, intent: "<原消息> 订单 <id>"}` → 经 `ScreenRegistry.toUi` 校验 → `ui.replace` + `message.delta("请选择要操作的订单")` → `run.completed`。不经领域 `ScreenBuilder`（Runtime 不改领域产物）。无 `clarifiesEntity` 工具或输出空 → 现状一句提示。用户点选即第二轮。原动词标签表：`删除订单 / 查看物流 / 申请售后 / 退款 / 查看商品`（与 `IntentVerbs` 同源）。

**行内动作即多级界面**：`Card` props 增可选 `actions[]`（同 `inlineAction`，≤ 6），让详情卡也能成为下一屏入口（如商品卡「返回列表」→ 「有什么商品」）。`Table.rows[].actions` 不变。

### 2.7 确认令牌与会话

令牌绑定 `runId + actionId + argsDigest + conversationId + sessionId`；`consume` 同时比对 `conversationId` 与 `sessionId`，任一不一致 → `CONFIRMATION_REJECTED`（令牌类文案）。`sessionId` 由 `SessionIdResolver` 提供；**默认实现返回 `conversationId`，等于无隔离，只适合本地演示**——启动 WARN「SessionIdResolver 为 demo 实现，生产必须绑定宿主登录态」，README 接入指南列为生产必做项。宿主实现示例：返回 `SecurityContextHolder` 用户 id 或网关注入的会话串。

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
- **e2e-backend 变更**：删 user_002 用例与 `principal` 相关断言；首期 §6.2.8「帮我把这个订单退款」+ pageContext 10001 → 「订单 10001 退款」；M2 幂等 / 路由 ②③⑥ 同样改为消息带号；⑭ 改为「`X-Demo-User: guest` 说删除订单 10005 → 宿主切面拒绝 → `TOOL_EXECUTION_FAILED`，`order.delete` 审计 `failed`」；⑭' 反面路径：`X-Demo-User: guest` 直接 `GET /demo/orders` 被拦截器拒 403，但同用户经 spark「看看我的订单」成功 → 证明 Controller 级拦截器对 spark 无效（教学断言）；新增：
  ⑰「我想查看最近订单」→ `order.list.search {page:1,size:20}`（argsDigest == 显式 `{page:"1",size:"20"}` 的 digest）；
  ⑱「最近 5 单已发货的订单」→ `{status:SHIPPED, size:5, page:1}`；
  ⑲「最近 100 单」→ `size:50`；
  ⑳「看看我的订单」→「第二个的物流」→ `order.logistics.get{orderId==rows[1].id}`，日志 `source=memory`；
  ㉑「查看订单 10002 的物流」→「申请售后」→ 确认屏 Card 标题「订单 10002」；
  ㉒ 空会话「申请售后」→ 澄清屏 `[Table]` 每行 action label「申请售后」intent 含行 id，`message.delta` 含「请选择」；点选（发 rows[0].actions[0].intent）→ 确认屏；
  ㉓ 以 `--spring.profiles.active=e2e-ttl`（`memory-ttl=1s`）**第二次启动**示例宿主只跑此条：㉑ 第二句等 2s → 澄清屏而非补位；
  ㉔ 令牌 sessionId 不一致（用另一 `conversationId` 提交确认）→ `CONFIRMATION_REJECTED`；
  ㉕ 商品详情 Card 含 `actions[0].intent == "有什么商品"`；
  ㉖ 上下文传播：「看看我的订单」输出 `viewer == X-Demo-User`（示例宿主字段）；
  ㉗ 植入 `final` 的 `@SparkTool` 方法 → 启动失败并含「final」原因（构建期反例，记入 coding_report）。
- **e2e-frontend**：删 URL 参数用例；步骤 7 末尾增「点商品卡『返回列表』→ Table」；其余不变。
- **新门禁**：`check-module-deps` 增 §2.5 标识符红线 + 平台模块 pom 禁 `spring-boot-starter-web` + 平台模块源码禁 `@Component/@Service/@Repository/@Configuration/@ComponentScan`；`check-rename.mjs`（一次性，纳入 ci 直到本 change 关闭后删除）断言全树无 `strato / fronted / backed`；`host-demo` 独立工程 `mvn -q -o spring-boot:run` 作为 ci 的一段（`-o` 离线证明只依赖本地仓）。
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
- **不**做 i18n：动词表、枚举别名、澄清文案、行内 label 全中文。
- **不**做路由关键词表可配置：`DomainRouter` 仍是内核硬编码 4 个领域（order / product / aftersale / refund）；宿主声明其它 `domain` 的工具只能经 LLM 分类到达。**已知限制**，下一 change 提供 `spark.routing.domains.<name>.keywords`。

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
- [ ] `grep -rn "@Component\|@Service\|@Repository\|@Configuration\|@ComponentScan" spark-rooter/spark-rooter-{runtime,registry,gateway,web-mvc}/src` 0（平台 Bean 全部由 starter `@Bean` 装配，不依赖包扫描；`check-module-deps` 守）；`host-demo` 主类包 `com.example.demo`。

### 6.2 后端
- [ ] `mvn -q verify` 0；`mvn -q install` 后 `cd examples/host-demo && mvn -q package`（首次在线拉插件）再 `mvn -q -o package` 0，`java -jar target/host-demo.jar` 启动日志 `spark-rooter: 12 tools registered from 4 beans`，自检 **8/8**（示例宿主 `spark.selfcheck.enabled=true`），启动日志含 `SessionIdResolver` demo WARN（示例宿主故意用默认）。
- [ ] 12 个推导 Manifest 与 change 4 的手写 JSON 逐一 diff 为空（`ManifestDeriver` 自检 `ManifestParitySelfCheck` 读 classpath 下保留的旧 JSON 对照；本条属 8 个自检之一）。
- [ ] Manifest 推导：`GET /internal/tool-registry/tools/order.list.search/versions`（示例宿主打开 internal 端点）返回的 `inputSchema` == 期望 JSON（`status enum 5 / page min 1 default 1 / size min 1 max 50 default 20`，`additionalProperties false`，`required []`）；`order.delete` 的 `risk.level high / confirmation required / idempotency required`；植入一个 `@SparkTool` 缺 `description` → 启动失败；植入两个方法同 id → 启动失败；植入 `In` 非 record → 启动失败。
- [ ] 代理调用：⑭ `guest` 删除 → 切面拒 → `TOOL_EXECUTION_FAILED` 且切面日志 `denied`；`admin` → `Result`；⑭' 拦截器反面路径被绕过（教学断言）；㉖ 上下文传播 `viewer == X-Demo-User`；㉗ `final` 方法启动失败；`ProxyInvocationSelfCheck`（带 `@Around` 的测试 Bean 调用后切面计数 == 1）。
- [ ] `grep -rn "userId\|tenantId\|Principal" spark-rooter/spark-rooter-{spi,contracts,runtime,registry,gateway,web-mvc,spring-boot-starter}/src` 0。
- [ ] e2e-backend 规则模式：首期 + 前三 change 保留用例（改为消息带号）+ ⑦–⑬' + ⑭ ⑭' + ⑮–㉗ 全绿；LIVE 模式同（⑳–㉓ 记忆用例两模式都跑；LLM 只在规划，抽取与记忆是确定性的）；㉓ 在第二次启动（e2e-ttl profile）中跑。
- [ ] ㉔ 令牌 sessionId 不一致 → `CONFIRMATION_REJECTED`；⑳ 日志含 `source=memory`；㉓ TTL 过期后走澄清屏。
- [ ] 日志：无用户原文、无 LLM 主机名 / 密钥 / 模型名（doctor 形态扫描不变）。

### 6.3 前端
- [ ] `rm -rf spark-ui/*/dist && pnpm -C spark-ui run ci` 0；`check-registry` 5；verify-pack 17 运行时；`@spark-ui/core` 包名、`Spark*` 导出；基线重写并记录。
- [ ] `grep -rn "pageContext\|entityType\|entityId\|chips" spark-ui/packages/core/src` 0；`spark-chat` 无 URL 参数解析。
- [ ] e2e-frontend：**36 / 36**（原 35：删「URL 参数上下文行」1 条、删步骤 5 的 `?page=…` 参数改为消息带号不减条数；增「返回列表」2 条：Card 内 `data-intent="有什么商品"` 存在、点击后 `[data-component-id="products"]` 出现）。

### 6.4 全仓
- [ ] `pnpm -C .harness run ci` 全 0（含 `check-rename`、`host-demo` `mvn -o package` 段）；doctor 0；deploy-verify 12/12（对 host-demo，自检 8）。
- [ ] `pnpm -C spark-ui install` 后 `grep -c strato spark-ui/pnpm-lock.yaml` 0。

## 7. 风险与权衡

| 风险 | 影响 | 缓解 |
|---|---|---|
| 候选工具不再按用户过滤，模型可能规划出用户无权的操作 | 用户看到「执行失败」而非「无此能力」；**安全上仍由宿主切面拦住** | agent-safety §2 改写为「授权归宿主；内核保证的是：任何工具调用必经 Gateway、必经宿主方法级切面」；可选 `ToolAccessPolicy` 供需要前置过滤的宿主 |
| 反射调用绕过宿主 AOP（裸实例 / final / 非 public）或宿主权限在 Controller 层 | 宿主权限失效 = 真实安全漏洞 | §2.4 代理与线程小节：代理对象 + `selectInvocableMethod`；final / 非 public 启动失败；文档明写「权限必须方法级」；⑭ ⑭' ㉗ + `ProxyInvocationSelfCheck` 四重验收 |
| 宿主 ThreadLocal 在 spark 工作线程为空 | 方法体读不到用户，或读到上一个请求残留（线程池复用） | `RunContextPropagator` 端口，`restore` / `clear` 成对；默认 no-op + WARN；示例宿主实现；㉖ 断言；`clear` 在 finally |
| **默认 `SessionIdResolver` 无会话隔离**：`conversationId` 前端可伪造，拿到令牌串即可确认他人的高风险操作；`argsDigest` 不是第二因素 | 生产事故级 | 默认实现明确 demo-only + 启动 WARN；README 把「实现 `SessionIdResolver` 绑宿主登录态」列为生产必做第一条；`consume` 双校验；记忆只存 ID；确认屏显式展示实体（给用户核对，不是防攻击） |
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
