# Tasks: refactor-spark-embedded-starter-20260909

> v2 — 响应评审 v1：T07 拆 a/b（逻辑 / 移动）、T09 拆 a/b（内核 / 示例迁移）；T01 领域包名一次到位；T03 增 lockfile 断言；T05 增 `RunContextPropagator` / `clarifiesEntity` / `unit`；T09a 增 `ManifestParitySelfCheck` / `ProxyInvocationSelfCheck`；T10 增 `DemoContextPropagator` / 反面路径 / `viewer`；T12 澄清屏改 Runtime 投影；T14 ㉓ 二次启动、自检 8、e2e-frontend 36。18 task。
> v1。所属端 contracts / backed（spark-rooter）/ fronted（spark-ui）/ harness。顺序：改名 → 契约 → spi 注解 → 平台去身份 → starter 装配 → 抽取 / 记忆 / 澄清 → 示例宿主 → 前端 → e2e / 文档 → 验收。每个 task ≤ 0.5 天；移动 / 重命名与逻辑分开提交。

## Phase A — 改名（纯机械，不改逻辑）

### T01 目录、Maven 坐标、Java 包改名
- **目标**：`git mv fronted spark-ui`、`git mv backed spark-rooter`；Maven `com.strato:strato-backed` → `com.sparkrooter:spark-rooter-parent`，子模块 artifactId 按 spec §2.2 表（此时仍保留 `app` 与 `domains/*`，T09 再重组）；Java 包 `com.strato.{spi,contracts,runtime,registry,gateway,app}` → `com.sparkrooter.*`；领域服务 `com.strato.domain.<svc>` → **`com.sparkrooter.examples.<svc>`**（一次到位，T09b 只搬目录不再改包）（目录移动 + `package` / `import` 替换）；`StratoApplication` → `SparkRooterApplication`；属性前缀 `strato.*` → `spark.*`；环境变量 `STRATO_*` → `SPARK_*`（`LlmConfiguration`、脚本）。
- **所属端**：backed / fronted / harness
- **输入**：spec §2.1 表
- **输出**：两个新目录；全部 pom / java；`.harness/scripts/**`、`rules/**`、`agents/**`、`skills/**`、`CLAUDE.md`、`AGENTS.md`、`lefthook.yml`、`docs/**` 中的路径引用
- **验收**：`mvn -q verify` 0；`grep -rw "fronted\|backed" --exclude-dir={node_modules,target,dist,.git} . | grep -v ".harness/changes"` 0；`grep -rn "com\.strato\|STRATO_\|strato\." spark-rooter .harness/scripts` 0；`git log --follow spark-rooter/spark-rooter-spi/pom.xml` 追到 `backed/platform-spi/pom.xml`
- **依赖**：—

### T02 前端与契约改名
- **目标**：`@strato-ui/core` → `@spark-ui/core`、`strato-chat` → `spark-chat`、workspace `fronted` → `spark-ui`；导出 `Strato*` → `Spark*`（`SparkThemeProvider / SparkDeviceProvider / SparkThemeTokens / SparkThemeProviderProps`）；CSS 变量 `--strato-*` → `--spark-*`；console 前缀 `[spark-ui]`；`.oxlintrc.json` / `vite.config.ts` / `check-deps` / `check-registry` / `verify-pack`（`RUNTIME_EXPORTS` / `TYPE_EXPORTS` / 包名）同步；契约 9 个 `$id` → `https://spark-rooter.local/contracts/v1/`，`SchemaValidator.ID_PREFIX` 同步；`e2e-frontend.mjs` 的 `[strato-ui]` 断言同步。
- **所属端**：fronted / contracts / harness
- **输入**：T01
- **输出**：`spark-ui/**`、`.harness/contracts/*.schema.json`、`spark-rooter/spark-rooter-contracts/**/SchemaValidator.java`
- **验收**：`pnpm -C spark-ui install`（lockfile 由 pnpm 重生成，不手改）后 `grep -c strato spark-ui/pnpm-lock.yaml` 0；`pnpm -C spark-ui run ci` 0（verify-pack 基线重写并记录）；`check-contracts` 9 / 26；`grep -ri strato spark-ui .harness/contracts spark-rooter --exclude-dir={node_modules,target,dist}` 0
- **依赖**：T01

### T03 改名门禁 + 历史脚本变量
- **目标**：`.harness/scripts/check-rename.mjs`：全树（排除 `node_modules / target / dist / .git / .harness/changes`）断言无 `strato`（大小写不敏感）、无 `\bfronted\b|\bbacked\b`；纳入 `ci.mjs` 与 doctor 必需文件；`change-dir.{mjs,sh}` 的 `STRATO_CHANGE` → `SPARK_CHANGE`；`e2e-backend.sh` / `deploy-verify.sh` / `e2e-frontend.mjs` 的 `STRATO_PORT / STRATO_BACKEND / STRATO_FRONT_BASE / STRATO_LLM_*` → `SPARK_*`；`vite.config.ts` 的 `STRATO_BACKEND` → `SPARK_BACKEND`。summary.md 记录：本 change 关闭后下一 change 删除 `check-rename`。
- **所属端**：harness
- **输入**：T01、T02
- **输出**：`check-rename.mjs`、`ci.mjs`、`harness-doctor.mjs`、四个脚本
- **验收**：`node check-rename.mjs` 0；植入 `spark-ui/README.md` 一个 `Strato` → 红；`pnpm -C .harness run ci` 0；`SPARK_PORT=8091 bash e2e-backend.sh` 全绿（此时逻辑未变，用例数不变）
- **依赖**：T02

## Phase B — 契约与 spi

### T04 契约：去 pageContext / principal，executionContext 换 sessionId，Card.actions
- **目标**：`intent-request` 删 `pageContext` 与 `$defs.pageContext / selectedEntity`；`tool-search.request` 删 `principal`，`required:["domain"]`；`tool-invoke.executionContext` 删 `userId / tenantId`，增 `sessionId{string,1..128}`，`required` 同步；`tool-manifest.authorization.permission` 改可选；`ui-schema.cardProps` 增 `actions{array, maxItems 6, items inlineAction}`；示例：`intent-request.example.json` 删 `pageContext`、`tool-search.example.json` 删 `principal`、`tool-invoke.example.json` 换字段、新增 `ui-schema.product-detail.example.json`（Card 含 `actions`）；`contracts.md` 变更记录；`wiki/api-contracts.md`。
- **所属端**：contracts
- **输入**：spec §2.9
- **输出**：5 个 schema、4 个示例、2 个文档
- **验收**：`check-contracts` 9 / 27；植入示例 `pageContext` → 红；植入 `tool-search` request 含 `principal` → 红（`additionalProperties:false`）；植入 `Card.actions` 7 项 → 红
- **依赖**：T02

### T05 spi：注解集合、ToolContext、端口
- **目标**：`spark-rooter-spi` 新增 `@SparkTool`（含 `clarifiesEntity`）`/ @SparkRisk / @SparkPrerequisite / @SparkParam`（含 `unit`）`/ @SparkDefault`（spec §2.4 属性表，含 Javadoc 与枚举成员注释）；`ToolContext{runId, toolCallId, idempotencyKey, traceId}`；端口接口 `AuditSink`、`SessionIdResolver`、`ToolAccessPolicy(toolId, sessionId)`、`RunContextPropagator{capture, restore, clear}`、`ConversationMemory`（含 `Memory{domain, entities, lastTable{toolId, rowIds}, at}` record）、`ToolTransport`、`ToolProviderDiscovery`（后两者只有接口 + Javadoc「本期仅 in-process」）；**删除** `Principal`、`PrincipalPermissionResolver`；`ExecutionContext` 改为 `{runId, toolCallId, sessionId, idempotencyKey, traceId}`。
- **所属端**：backed
- **输入**：T03、spec §2.3 / §2.4 / §2.6 / §2.7
- **输出**：`spark-rooter-spi/src/**`（+11 文件，−2）
- **验收**：`mvn -q -pl spark-rooter-spi verify` 0；`check-module-deps` spi 规则不变（无 com.sparkrooter 依赖）；`grep -rn "userId\|tenantId\|Principal" spark-rooter-spi/src` 0
- **依赖**：T04

## Phase C — 平台去身份、去 Web 绑定

### T06 registry / gateway：去 principal，sessionId，AuditSink 端口
- **目标**：Registry `ToolSearchPort.search(Request)`（无 principal）、`domains()`；`SearchToolsUseCase` / `DiscoveryPolicy` 删权限过滤，保留 status / risk 过滤；新增可选 `ToolAccessPolicy` 注入点（有 Bean 才过滤）。Gateway `InvokeToolUseCase` 删鉴权步骤（`PrincipalPermissionResolver` 引用全删），可选 `ToolAccessPolicy` 拒绝 → `FORBIDDEN`；`ExecutionContext` 用新字段；审计行 `principal=` → `sessionId=`；`LogAuditSink` 实现 spi `AuditSink`，`InvokeToolUseCase` 注入端口。`ToolInvoke` / `ToolSearch` DTO 同步。
- **所属端**：backed
- **输入**：T04、T05
- **输出**：`spark-rooter-registry/**`、`spark-rooter-gateway/**`、`spark-rooter-contracts/**/model`
- **验收**：`mvn -q verify` 0；`grep -rn "userId\|tenantId\|Principal" spark-rooter-{registry,gateway,contracts}/src` 0；`GatewayIdempotencySelfCheck` 仍过
- **依赖**：T05

### T07a runtime：去 principal / pageContext，令牌双绑定，上下文传播钩子
- **目标**：`RunOrchestrator.start(IntentRequest, String sessionId, traceId, sink)`；`Run` 去 principal 存 `conversationId + sessionId`；`confirm` 双校验（不一致 → `CONFIRMATION_REJECTED` 令牌文案）；`ConfirmationToken` 增两字段；`DomainResolver.resolve(message)`；`EntityRequirementCheck.check(domain, candidates, entities)` 删 pageContext 分支；`ExecutionContext` 新字段；Runtime 线程池提交与 Gateway 执行前后调用 `RunContextPropagator.restore / clear`（finally），`web-mvc`（T07b 前暂在 `api/`）接到请求时 `capture`。
- **所属端**：backed
- **输入**：T06
- **输出**：`spark-rooter-runtime/**`
- **验收**：`mvn -q verify` 0；`grep -rn "userId\|tenantId\|Principal\|pageContext\|selectedEntity" spark-rooter-runtime/src` 0；`TokenSelfCheck` 增「sessionId 不一致拒绝」一条
- **依赖**：T06

### T07b Web 层剥离为 `spark-rooter-web-mvc`（纯移动 + pom）
- **目标**：新模块 `spark-rooter-web-mvc`；`git mv` 三个平台模块的 `api/` 包（Controller、advice、`SseRunEventSink`、`RuntimeExecutorConfiguration` 的 SSE ping 部分）进来；三个平台模块 pom 去 `spring-boot-starter-web` / `starter-validation`，改依赖 `spring-context` + `spring-web`；`web-mvc` 依赖 `spring-boot-starter-web`；`SessionIdResolver` 默认实现（返回 `conversationId`，构造期 WARN）与默认 `RunContextPropagator`（no-op，WARN）放 `web-mvc`。**不改逻辑**。
- **所属端**：backed
- **输入**：T07a
- **输出**：`spark-rooter-web-mvc/**`、三个 pom
- **验收**：`mvn -q verify` 0；`check-module-deps` 新规则「平台模块 pom 禁 `spring-boot-starter-web`」植入 → 红；`grep -rn "org.springframework.web.servlet\|SseEmitter\|@RestController" spark-rooter-{runtime,registry,gateway}/src` 0；`git log --follow` 任一 Controller 追到旧路径
- **依赖**：T07a

## Phase D — Starter 与注解扫描

### T08 starter：AutoConfiguration、属性、默认实现条件装配
- **目标**：新模块 `spark-rooter-spring-boot-starter`：`META-INF/spring/…AutoConfiguration.imports` → `SparkRooterAutoConfiguration`（`@Import` 平台各 `infra` 配置；**不依赖包扫描**）；`@ConfigurationProperties("spark")` 属性类（`llm / runtime / gateway / web / selfcheck / owner-team`，spec §2.3 默认值）；所有默认实现（内存仓储 ×5、`LogAuditSink`、`SpringAi*` / 规则、`ConversationIdSessionResolver`、`AllowAllToolAccessPolicy`、`InProcessToolTransport`、`LocalToolProviderDiscovery`、线程池、平台专用 `ObjectMapper`、`SchemaValidator`）全部 `@ConditionalOnMissingBean`；Bean 名前缀 `sparkRooter*`；`web-mvc` 的装配 `@ConditionalOnWebApplication(SERVLET)`，`/internal/**` 端点 `@ConditionalOnProperty(spark.web.internal-endpoints)`；`spark.web.base-path` 生效；`SelfCheckRunner` / `SelfCheckProperties` 从 `app` 移入 starter，默认关。**平台模块内的 `@Component/@Service/@Repository/@Configuration` 全部移除**（改由 starter `@Bean` 装配），`domain/` 包本就无注解。
- **所属端**：backed
- **输入**：T07
- **输出**：`spark-rooter-spring-boot-starter/**`；三个平台模块去注解
- **验收**：`mvn -q verify` 0；`grep -rn "@Component\|@Service\|@Repository\|@Configuration" spark-rooter-{runtime,registry,gateway}/src` 0；临时用 `app` 模块（主类包改为 `com.example.tmp`，不扫 `com.sparkrooter`）启动成功、`/agent/runs` 可用 → 证明装配不靠扫描（T09 后 `app` 删除）；宿主定义一个 `AuditSink` Bean → 启动日志 `sparkRooterAuditSink` 未创建（`--debug` 条件报告）
- **依赖**：T07b

### T09a `@SparkTool` 扫描、Manifest 推导、代理调用（内核）
- **目标**：starter 内 `SparkToolScanner`（`SmartInitializingSingleton`）：遍历 Bean，`AopUtils.getTargetClass` 找 `@SparkTool`；非 public / final 类 / final 方法 / `@Configuration` 类上 → 启动失败并指明原因；`ManifestDeriver`（spec §2.4 类型映射表，递归 record、Optional/@Nullable、Java enum、时间、金额；`authorization: {}`；经 `tool-manifest` 契约校验）；`AnnotatedToolHandler`（持代理 Bean + `ClassUtils.getMostSpecificMethod` / `AopUtils.selectInvocableMethod` 得到的 `Method`；`JsonNode → In` 用平台 ObjectMapper `NON_ABSENT`；第二参 `ToolContext` 可选；宿主异常 → `HANDLER_ERROR`，消息只保留异常类名 + 首行）；`ToolMetaRegistry`（`entity` 参数、`prerequisites`、`clarifiesEntity`、`unit`、`aliases`）；`IntentVerbs.PREREQUISITES` / `EntityRequirementCheck.ENTITY_ARGS` 改读注册表（保留内核默认表供手写 Manifest 工具）；自检 `ProxyInvocationSelfCheck`（带 `@Around` 计数切面的测试 Bean，调用后计数 == 1）、`ManifestParitySelfCheck`（推导结果与 classpath `legacy-manifests/*.json` 逐一 diff，示例宿主放 change 4 的 12 个 JSON）。
- **所属端**：backed
- **输入**：T08
- **输出**：starter `SparkToolScanner / ManifestDeriver / AnnotatedToolHandler / ToolMetaRegistry`、两个自检
- **验收**：`mvn -q verify` 0；用 starter 内测试 Bean（`@SparkTool` 三个方法覆盖标量 / 枚举 / 嵌套 record / List / Optional）跑 `ManifestDeriver` 输出 == 期望 JSON；植入 `final` 方法测试 Bean → 启动失败含「final」；植入两方法同 id → 启动失败
- **依赖**：T08

### T09b 四个领域服务改注解形态，删 `app`
- **目标**：`domains/*` → `spark-rooter/examples/domains/*`（`git mv`，包名 T01 已定 `com.sparkrooter.examples.<svc>`）；删 `ToolHandler` 实现类与 `tool-manifests/*.json`（JSON 移到 `examples/host-demo/src/main/resources/legacy-manifests/` 供 parity 自检）；每个领域一个 `@Service` `XxxTools` 类，`@SparkTool` 方法 + record In/Out（id / 版本 / 输出字段与现状一致；`order.list.search` 参数 `status / limit`（沿用 `limit`，`@SparkParam(unit={"单","条","个"}, min=1, max=50) @SparkDefault("20")`）；`order.list.search` 加 `@SparkTool(clarifiesEntity = ORDER)`、`product.list.search` 加 `PRODUCT`；`order.delete` `@SparkRisk(HIGH, REQUIRED, idempotency REQUIRED)` + `@SparkPrerequisite("order.detail.get")`；同理 aftersale / refund）；`ScreenBuilder` / `ConfirmationRecheck` / `OrderSnapshotProvider` / seed 仓储不变；`app` 模块删除；`examples/domains` pom 只依赖 spi + contracts + spring-context；根 pom modules。
- **所属端**：backed
- **输入**：T09a
- **输出**：`examples/domains/**`；根 pom
- **验收**：`mvn -q verify` 0；`check-module-deps`：`examples/domains/*` 不依赖平台模块 → 植入 → 红；`grep -rn "implements ToolHandler\|tool-manifests" examples/domains` 0
- **依赖**：T09a

### T10 示例宿主 host-demo
- **目标**：`spark-rooter/examples/host-demo`：独立 Maven 工程（parent `spring-boot-starter-parent:3.5.x`，**不在根 modules 内**），依赖 `com.sparkrooter:spark-rooter-spring-boot-starter` + 四个 `examples/domains` artifact（本地仓）；主类 `com.example.demo.HostDemoApplication`；`MockUserContextInterceptor`（`X-Demo-User` → `DemoUserContext` ThreadLocal，缺省 `user_001`）；`DemoContextPropagator implements RunContextPropagator`；`@DemoRequiresRole("admin")` 注解 + `@Aspect`（`user_001` 是 admin，`guest` 不是）标在 `OrderTools.delete` 上（正面）；`DemoInterceptorOnlyGuard` 拦截器只拒 `guest` 访问 `/demo/**`（反面，e2e ⑭' 证明对 spark 无效）；`order.list.search` 输出增 `viewer`（示例宿主专有字段，回显当前用户）；领域方法体读 `DemoUserContext`；`legacy-manifests/*.json`（12 个）供 parity 自检；`application.yml`：`spark.selfcheck.enabled=true`、`spark.web.internal-endpoints=true`、`spark.llm.*` 从 `SPARK_LLM_*` 取；e2e profile `spark.runtime.memory-ttl=1s` 用 `--spring.profiles.active=e2e-ttl`；README 三步接入。
- **所属端**：backed
- **输入**：T09
- **输出**：`examples/host-demo/**`
- **验收**：`mvn -q install`（根）→ `cd examples/host-demo && mvn -q package`（首次在线）→ `mvn -q -o package` 0 → `java -jar target/host-demo.jar --server.port=8091` → 日志 `spark-rooter: 12 tools registered from 4 beans`、自检 **8/8**（含 parity / proxy）、`SessionIdResolver` demo WARN、`/actuator/health` UP；`GET /internal/tool-registry/tools/order.list.search/versions` 的 inputSchema == spec §6.2 期望；`X-Demo-User: guest` 直调 Gateway `order.delete 10005` → 502 且切面日志 `denied`
- **依赖**：T09b

## Phase E — 参数边界、记忆、澄清

### T11 ArgumentExtractor（枚举别名 / 数量 / 相对时间）与规划器填参
- **目标**：`EntityExtractor` → `ArgumentExtractor`（纯函数，注入 `Clock`）：实体 ID（尾边界不变）、枚举别名（来自 `ToolMeta` 的 `@SparkParam.aliases`）、数量 `(\d+)\s*(单|条|个|件)`（映射到该工具 `@SparkParam` 整型且 aliases 含单位词的参数，截断到 max）、相对时间表（spec §2.6，产出 `format=DATE` 参数）；`RuleBasedLlmClient.argsFor` 用抽取结果 + `@SparkDefault` 填参（默认值写进 args，使 argsDigest 确定）；`ToolSelectionValidator` 增 JSON Schema 值校验（enum / min / max / format，用 `SchemaValidator.validateWithInlineSchema` 对单参数）；`PromptBuilder.system` 增「只能填 schema 内字段，ID 必须来自用户原话，未知字段留空走默认」；`PlanSelfCheck` 增用例：「最近 5 单已发货的订单」→ `{status:SHIPPED,size:"5",page:"1"}`、「最近 100 单」→ `size:"50"`、「订单 10002 的第 3 页」→ 只有 orderId。
- **所属端**：backed
- **输入**：T09a
- **输出**：`spark-rooter-runtime/**/{ArgumentExtractor,RuleBasedLlmClient,ToolSelectionValidator,PromptBuilder,IntentVerbs}.java`、`PlanSelfCheck`
- **验收**：`mvn -q verify` 0；自检 `plan N messages OK`（N = 6 + 3）；host-demo 上 e2e ⑰⑱⑲（T13）
- **依赖**：T09a

### T12 ConversationMemory、序数指代、澄清屏、Card.actions
- **目标**：`InMemoryConversationMemory`（TTL 来自 `spark.runtime.memory-ttl`，`Clock` 注入）；`RunOrchestrator` 终态写入记忆（domain / entities / lastTable）；`ArgumentExtractor` 记忆补位（仅实体，日志 `source=memory`）与序数指代 `第(\d+)(个|单|条)|最后一(个|单)|第一个` → `lastTable.rowIds`；澄清屏：`MissingEntity(type)` 时查 `ToolMetaRegistry.clarifiesEntity == type` 的工具，经 Gateway 调它拿原始输出，runtime 通用 `ClarificationScreen` 投影为 Table（行 id = `entity` 标记字段，cells = 标量字段，`actions[0] = {label:<原动词标签>, intent:"<原消息> 订单 <id>"}`）→ `ScreenRegistry.toUi` 校验；`message.delta("请选择要操作的订单")`，`run.completed`；无 `clarifiesEntity` 工具或空 → 现状提示。`Card.actions`：`ProductScreens.detail` 增 `[{label:"返回列表", intent:"有什么商品"}]`；`OrderScreens.detail` 增 `[{label:"查看物流", intent:"查看订单 X 的物流"}]`（有物流时）；`UiNodes.inlineAction` 复用；`InlineActionSelfCheck` 覆盖 Card.actions。
- **所属端**：backed
- **输入**：T11、T04
- **输出**：runtime `ConversationMemory` 默认实现、`RunOrchestrator`、`ArgumentExtractor`、`ClarificationScreen`（runtime，通用 Table 覆写，不含领域词汇）、两个 `*Screens`
- **验收**：`mvn -q verify` 0；`grep -rln "订单\|商品" spark-rooter-runtime/src/main/java` == `{IntentVerbs.java, ClarificationScreen.java, EntityRequirementCheck.java}`（文案 / 关键词表，其余 0）；`grep -rn "order.list.search\|product.list.search" spark-rooter-runtime/src` 0（内核不写死 toolId）；host-demo 上 e2e ⑳㉑㉒㉓㉕（T13）
- **依赖**：T11

## Phase F — 前端

### T13 前端：去 pageContext，Card.actions，AgentChatPanel 收敛
- **目标**：`@spark-ui/core`：`CardPropsSchema` 增 `actions`（Zod）；`CardDesktop` / `CardMobile` 底部渲染 `Button`（`data-intent`、`handlers.onIntent`，无 handler 时 disabled）；README 更新。`spark-chat`：删 `PageContextQuery`、URL 参数解析、`ChatPage` 上下文行；`AgentChatPanel` props → `{conversationId, baseUrl?: string, fetch?: typeof fetch}`，`useAgentRun` 用注入的 `fetch` 与 `baseUrl`（默认 `/agent`、`window.fetch`），去 `principalHeaders`（宿主要带身份就在 `fetch` 里带）；`buildIntentRequest` 不再拼 `pageContext`；chip 只预填输入框；`entities/agent-run/model/types.ts` 的 `IntentRequest` / `ExecutionContext` Zod 与契约同步。
- **所属端**：fronted
- **输入**：T04
- **输出**：`spark-ui/packages/core/src/{schema,components}/**`、`spark-ui/apps/chat/src/**`
- **验收**：`rm -rf spark-ui/*/dist && pnpm -C spark-ui run ci` 0；`grep -rn "pageContext\|entityType\|entityId\|principalHeaders\|X-User-Id" spark-ui/apps/chat/src spark-ui/packages/core/src` 0；playground `product-detail` 示例 1280 / 375 渲染且 `data-intent="有什么商品"` 存在
- **依赖**：T04（可与 Phase C–E 并行）

## Phase G — e2e、文档、验收

### T14 e2e 与 deploy-verify 改造
- **目标**：`e2e-backend.sh` / `deploy-verify.sh` 对 `examples/host-demo/target/host-demo.jar` 启动（`--server.port=$PORT`）；㉓ 在脚本末尾以 `--spring.profiles.active=e2e-ttl` **第二次启动**单跑；删 user_002 与 `principal` 断言；首期 §6.2.8 / M2 / 路由 ②③⑥ 改为消息带订单号；⑭ 宿主切面正面、⑭' 拦截器反面；新增 ⑰–㉗（spec §2.10）；selfcheck 名单同步（`ProxyInvocationSelfCheck`、`ManifestParitySelfCheck`，**共 8**，`deploy-verify` 同）；`e2e-frontend.mjs` 删「上下文行」1 条、步骤 5 消息带号、增「返回列表」2 条 → **36**；`preview-console.mjs` URL 去参数。
- **所属端**：harness
- **输入**：T10、T12、T13
- **输出**：三个脚本
- **验收**：规则模式全绿；LIVE 模式全绿（⑳–㉓ 两模式都跑）；deploy-verify 12/12（自检 8）；e2e-frontend 36/36
- **依赖**：T10、T12、T13

### T15 文档与规则同步
- **目标**：`spark-rooter/README.md`（模块表、三步接入指南、可替换端口表、配置表、「身份与权限归宿主」段）；`examples/host-demo/README.md`；`spark-ui/packages/core/README.md`（`Spark*`、`Card.actions`）；`wiki/architecture.md`（embedded 图、预留分布式端口）、`wiki/api-contracts.md`、`wiki/domain-model.md`（去 Principal）；`rules/project-structure.md` §2（新模块树、红线：平台模块禁 web starter / 禁 userId 标识符 / examples 不依赖平台）、`rules/agent-safety.md` §2（授权归宿主；内核保证经 Gateway + 经宿主切面）、§3（令牌绑 sessionId）、`rules/backend-standard.md`、`06-backend-module-spec.md`、`CLAUDE.md`（目录名、命令、硬约束中的目录路径）。
- **所属端**：harness
- **输入**：T14
- **输出**：上述文档
- **验收**：doctor 0（L1 一致性检查随 CLAUDE.md 更新）；`grep -rn "Principal\|pageContext" .harness/rules .harness/wiki spark-rooter/README.md` 0；`grep -rn "permission" 同范围` 每条命中行必须同时含「宿主」或「ToolAccessPolicy」（脚本断言：命中行数 == 同时含关键字的行数）；README 含「生产必做：实现 SessionIdResolver / RunContextPropagator；权限必须方法级」三条
- **依赖**：T14

### T16 全链路验收
- **目标**：`rm -rf spark-ui/*/dist && pnpm -C .harness run ci`（含 check-rename、host-demo 离线 package）；deploy-verify；产物冻结；`coding_report_v1.md`。
- **所属端**：harness
- **输入**：T15
- **输出**：`deployment/**`、`coding/coding_report_v1.md`
- **验收**：spec §6 全部为真
- **依赖**：T15

## 依赖图

```
T01 → T02 → T03 → T04 → T05 → T06 → T07a → T07b → T08 → T09a → T09b → T10 ─┐
                        └──────────────────────────────── T13 ─────────────┼→ T14 → T15 → T16
                                                   T09a → T11 → T12 ───────┘
```
