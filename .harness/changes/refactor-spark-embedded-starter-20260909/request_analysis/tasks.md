# Tasks: refactor-spark-embedded-starter-20260909

> v1。所属端 contracts / backed（spark-rooter）/ fronted（spark-ui）/ harness。顺序：改名 → 契约 → spi 注解 → 平台去身份 → starter 装配 → 抽取 / 记忆 / 澄清 → 示例宿主 → 前端 → e2e / 文档 → 验收。每个 task ≤ 0.5 天；移动 / 重命名与逻辑分开提交。

## Phase A — 改名（纯机械，不改逻辑）

### T01 目录、Maven 坐标、Java 包改名
- **目标**：`git mv fronted spark-ui`、`git mv backed spark-rooter`；Maven `com.strato:strato-backed` → `com.sparkrooter:spark-rooter-parent`，子模块 artifactId 按 spec §2.2 表（此时仍保留 `app` 与 `domains/*`，T09 再重组）；Java 包 `com.strato.*` → `com.sparkrooter.*`（目录移动 + `package` / `import` 替换）；`StratoApplication` → `SparkRooterApplication`；属性前缀 `strato.*` → `spark.*`；环境变量 `STRATO_*` → `SPARK_*`（`LlmConfiguration`、脚本）。
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
- **验收**：`pnpm -C spark-ui run ci` 0（verify-pack 基线重写并记录）；`check-contracts` 9 / 26；`grep -ri strato spark-ui .harness/contracts spark-rooter --exclude-dir={node_modules,target,dist}` 0
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
- **目标**：`spark-rooter-spi` 新增 `@SparkTool / @SparkRisk / @SparkPrerequisite / @SparkParam / @SparkDefault`（spec §2.4 属性表，含 Javadoc 与枚举成员注释）；`ToolContext{runId, toolCallId, idempotencyKey, traceId}`；端口接口 `AuditSink`、`SessionIdResolver`、`ToolAccessPolicy`、`ConversationMemory`（含 `Memory{domain, entities, lastTable{toolId, rowIds}, at}` record）、`ToolTransport`、`ToolProviderDiscovery`（后两者只有接口 + Javadoc「本期仅 in-process」）；**删除** `Principal`、`PrincipalPermissionResolver`；`ExecutionContext` 改为 `{runId, toolCallId, sessionId, idempotencyKey, traceId}`。
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

### T07 runtime：去 principal / pageContext，令牌绑 sessionId，Web 层剥离
- **目标**：`RunOrchestrator.start(IntentRequest, String sessionId, traceId, sink)`；`Run` 去 principal 存 sessionId；`confirm` 比对 sessionId（不一致 → `CONFIRMATION_REJECTED` 令牌文案）；`ConfirmationToken` 增 `sessionId`；`DomainResolver.resolve(message)`（删实体类型提示）；`EntityRequirementCheck.check(domain, candidates, entities)` 删 pageContext 分支；`ExecutionContext` 新字段。**把 `api/` 包整体移到新模块 `spark-rooter-web-mvc`**（`AgentRunController`、`RuntimeExceptionHandler`、`SseRunEventSink`、`RuntimeExecutorConfiguration` 中的 SSE 部分；Registry / Gateway 的 Controller 与 advice 同样移入）；三个平台模块 pom 去 `spring-boot-starter-web` / `starter-validation`，改依赖 `spring-context` + `spring-web`（`RestClient` 需要）；`web-mvc` 依赖 `spring-boot-starter-web`。`SessionIdResolver` 默认实现（web-mvc 内）返回请求体 `conversationId`。
- **所属端**：backed
- **输入**：T06
- **输出**：`spark-rooter-runtime/**`、新 `spark-rooter-web-mvc/**`、三个 pom
- **验收**：`mvn -q verify` 0；`check-module-deps` 新规则：平台模块 pom 禁 `spring-boot-starter-web` → 植入 runtime pom → 红；`grep -rn "userId\|tenantId\|Principal\|pageContext\|selectedEntity" spark-rooter-{runtime,web-mvc}/src` 0；`grep -rn "org.springframework.web.servlet\|SseEmitter\|@RestController" spark-rooter-{runtime,registry,gateway}/src` 0
- **依赖**：T06

## Phase D — Starter 与注解扫描

### T08 starter：AutoConfiguration、属性、默认实现条件装配
- **目标**：新模块 `spark-rooter-spring-boot-starter`：`META-INF/spring/…AutoConfiguration.imports` → `SparkRooterAutoConfiguration`（`@Import` 平台各 `infra` 配置；**不依赖包扫描**）；`@ConfigurationProperties("spark")` 属性类（`llm / runtime / gateway / web / selfcheck / owner-team`，spec §2.3 默认值）；所有默认实现（内存仓储 ×5、`LogAuditSink`、`SpringAi*` / 规则、`ConversationIdSessionResolver`、`AllowAllToolAccessPolicy`、`InProcessToolTransport`、`LocalToolProviderDiscovery`、线程池、平台专用 `ObjectMapper`、`SchemaValidator`）全部 `@ConditionalOnMissingBean`；Bean 名前缀 `sparkRooter*`；`web-mvc` 的装配 `@ConditionalOnWebApplication(SERVLET)`，`/internal/**` 端点 `@ConditionalOnProperty(spark.web.internal-endpoints)`；`spark.web.base-path` 生效；`SelfCheckRunner` / `SelfCheckProperties` 从 `app` 移入 starter，默认关。**平台模块内的 `@Component/@Service/@Repository/@Configuration` 全部移除**（改由 starter `@Bean` 装配），`domain/` 包本就无注解。
- **所属端**：backed
- **输入**：T07
- **输出**：`spark-rooter-spring-boot-starter/**`；三个平台模块去注解
- **验收**：`mvn -q verify` 0；`grep -rn "@Component\|@Service\|@Repository\|@Configuration" spark-rooter-{runtime,registry,gateway}/src` 0；临时用 `app` 模块（主类包改为 `com.example.tmp`，不扫 `com.sparkrooter`）启动成功、`/agent/runs` 可用 → 证明装配不靠扫描（T09 后 `app` 删除）；宿主定义一个 `AuditSink` Bean → 启动日志 `sparkRooterAuditSink` 未创建（`--debug` 条件报告）
- **依赖**：T07

### T09 `@SparkTool` 扫描、Manifest 推导、代理调用；领域服务迁到 examples
- **目标**：starter 内 `SparkToolScanner`（`SmartInitializingSingleton`，早于 `ApplicationReadyEvent`）：遍历 `ApplicationContext` 全部 Bean，`AopUtils.getTargetClass` 找 `@SparkTool` 方法，`ManifestDeriver` 从注解 + record 推导 Manifest（类型映射表、`required`、`additionalProperties:false`、`@SparkDefault` → `default`、`@SparkParam.entity` 记录到 `ToolMeta`），经 `tool-manifest` 契约校验；`AnnotatedToolHandler`（持 Bean 代理引用 + `Method`，`JsonNode → In` 用平台 ObjectMapper，`Out → JsonNode`；第二参 `ToolContext` 可选）；注册进 Registry + `ToolNameSink`；重复 id / 缺 description / In 非 record / 推导失败 → 启动失败。`IntentVerbs.PREREQUISITES` 与 `EntityRequirementCheck.ENTITY_ARGS` 改为从 `ToolMeta`（`@SparkPrerequisite` / `@SparkParam.entity`）汇总的注册表读取（保留内核默认表供手写 Manifest 工具）。四个领域服务移到 `spark-rooter/examples/domains/*`，`ToolHandler` 实现类 + `tool-manifests/*.json` 删除，改为 `@SparkTool` 方法 + record 参数（工具 id / 版本 / 输出字段与现状一致；`order.list.search` 参数 `status / page / size` 按 spec §2.4 示例；`ScreenBuilder` / `ConfirmationRecheck` / `OrderSnapshotProvider` 不变）；`app` 模块删除；`examples/domains` pom 只依赖 spi + contracts + spring-context。
- **所属端**：backed
- **输入**：T08
- **输出**：starter `SparkToolScanner / ManifestDeriver / AnnotatedToolHandler / ToolMetaRegistry`；`examples/domains/**`；根 pom modules
- **验收**：`mvn -q verify` 0；`check-module-deps`：`examples/domains/*` 不依赖平台模块 → 植入 → 红；单元级：`AnnotatedToolHandler` 对 `AopUtils.isAopProxy(bean)` 为真的目标必须经代理调用（自检 `ProxyInvocationSelfCheck`：给一个带 `@Around` 切面的测试 Bean，调用后切面计数 == 1）；`ManifestDeriver` 对 `OrderListIn` 产出的 schema == 期望 JSON
- **依赖**：T08

### T10 示例宿主 host-demo
- **目标**：`spark-rooter/examples/host-demo`：独立 Maven 工程（parent `spring-boot-starter-parent:3.5.x`，**不在根 modules 内**），依赖 `com.sparkrooter:spark-rooter-spring-boot-starter` + 四个 `examples/domains` artifact（本地仓）；主类 `com.example.demo.HostDemoApplication`；`MockUserContextInterceptor`（`X-Demo-User` → `DemoUserContext` ThreadLocal，缺省 `user_001`）；`@DemoRequiresRole("admin")` 注解 + `@Aspect`（`user_001` 是 admin，`guest` 不是）标在 `OrderTools.delete` 上；领域方法体读 `DemoUserContext`；`application.yml`：`spark.selfcheck.enabled=true`、`spark.web.internal-endpoints=true`、`spark.llm.*` 从 `SPARK_LLM_*` 取；e2e profile `spark.runtime.memory-ttl=1s` 用 `--spring.profiles.active=e2e-ttl`；README 三步接入。
- **所属端**：backed
- **输入**：T09
- **输出**：`examples/host-demo/**`
- **验收**：`mvn -q install`（根）→ `cd examples/host-demo && mvn -q -o package && java -jar target/host-demo.jar --server.port=8091` → 日志 `spark-rooter: 12 tools registered from 4 beans`、自检 7/7、`/actuator/health` UP；`GET /internal/tool-registry/tools/order.list.search/versions` 的 inputSchema == spec §6.2 期望；`X-Demo-User: guest` 直调 Gateway `order.delete 10005` → 502 且切面日志 `denied`
- **依赖**：T09

## Phase E — 参数边界、记忆、澄清

### T11 ArgumentExtractor（枚举别名 / 数量 / 相对时间）与规划器填参
- **目标**：`EntityExtractor` → `ArgumentExtractor`（纯函数，注入 `Clock`）：实体 ID（尾边界不变）、枚举别名（来自 `ToolMeta` 的 `@SparkParam.aliases`）、数量 `(\d+)\s*(单|条|个|件)`（映射到该工具 `@SparkParam` 整型且 aliases 含单位词的参数，截断到 max）、相对时间表（spec §2.6，产出 `format=DATE` 参数）；`RuleBasedLlmClient.argsFor` 用抽取结果 + `@SparkDefault` 填参（默认值写进 args，使 argsDigest 确定）；`ToolSelectionValidator` 增 JSON Schema 值校验（enum / min / max / format，用 `SchemaValidator.validateWithInlineSchema` 对单参数）；`PromptBuilder.system` 增「只能填 schema 内字段，ID 必须来自用户原话，未知字段留空走默认」；`PlanSelfCheck` 增用例：「最近 5 单已发货的订单」→ `{status:SHIPPED,size:"5",page:"1"}`、「最近 100 单」→ `size:"50"`、「订单 10002 的第 3 页」→ 只有 orderId。
- **所属端**：backed
- **输入**：T09
- **输出**：`spark-rooter-runtime/**/{ArgumentExtractor,RuleBasedLlmClient,ToolSelectionValidator,PromptBuilder,IntentVerbs}.java`、`PlanSelfCheck`
- **验收**：`mvn -q verify` 0；自检 `plan N messages OK`（N = 6 + 3）；host-demo 上 e2e ⑰⑱⑲（T13）
- **依赖**：T09

### T12 ConversationMemory、序数指代、澄清屏、Card.actions
- **目标**：`InMemoryConversationMemory`（TTL 来自 `spark.runtime.memory-ttl`，`Clock` 注入）；`RunOrchestrator` 终态写入记忆（domain / entities / lastTable）；`ArgumentExtractor` 记忆补位（仅实体，日志 `source=memory`）与序数指代 `第(\d+)(个|单|条)|最后一(个|单)|第一个` → `lastTable.rowIds`；澄清屏：`MissingEntity(type)` 时按 `CLARIFY_LIST_TOOL{order→order.list.search, product→product.list.search}` 调列表工具（经 Gateway，in-process），出 Table 并把每行 `actions` 覆写为 `[{label:<原动词标签>, intent:"<原消息> 订单 <id>"}]`（动词标签取 `IntentVerbs` 命中项的 label 表：删除订单 / 查看物流 / 申请售后 / 退款 / 查看商品），`message.delta("请选择要操作的订单")`，`run.completed`；列表工具不在候选或空 → 现状提示。`Card.actions`：`ProductScreens.detail` 增 `[{label:"返回列表", intent:"有什么商品"}]`；`OrderScreens.detail` 增 `[{label:"查看物流", intent:"查看订单 X 的物流"}]`（有物流时）；`UiNodes.inlineAction` 复用；`InlineActionSelfCheck` 覆盖 Card.actions。
- **所属端**：backed
- **输入**：T11、T04
- **输出**：runtime `ConversationMemory` 默认实现、`RunOrchestrator`、`ArgumentExtractor`、`ClarificationScreen`（runtime，通用 Table 覆写，不含领域词汇）、两个 `*Screens`
- **验收**：`mvn -q verify` 0；`grep -rn "订单\|商品" spark-rooter-runtime/src/main/java` 只允许在 `ClarificationScreen` 的提示文案与 `IntentVerbs` 关键词表（其余 0）；host-demo 上 e2e ⑳㉑㉒㉓㉕（T13）
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
- **目标**：`e2e-backend.sh` / `deploy-verify.sh` 对 `examples/host-demo/target/host-demo.jar` 启动（`--server.port=$PORT`，e2e-ttl profile 用于 ㉓）；删 user_002 与 `principal` 断言；首期 §6.2.8 / M2 / 路由 ②③⑥ 改为消息带订单号；⑭ 改宿主切面用例；新增 ⑰–㉕（spec §2.10）；selfcheck 名单同步（新增 `ProxyInvocationSelfCheck`，共 8）；`e2e-frontend.mjs` 删 URL 参数步骤、增「返回列表」步骤；`preview-console.mjs` URL 去参数。
- **所属端**：harness
- **输入**：T10、T12、T13
- **输出**：三个脚本
- **验收**：规则模式全绿；LIVE 模式全绿（⑳–㉓ 两模式都跑）；deploy-verify 12/12；e2e-frontend ≥ 34 全绿
- **依赖**：T10、T12、T13

### T15 文档与规则同步
- **目标**：`spark-rooter/README.md`（模块表、三步接入指南、可替换端口表、配置表、「身份与权限归宿主」段）；`examples/host-demo/README.md`；`spark-ui/packages/core/README.md`（`Spark*`、`Card.actions`）；`wiki/architecture.md`（embedded 图、预留分布式端口）、`wiki/api-contracts.md`、`wiki/domain-model.md`（去 Principal）；`rules/project-structure.md` §2（新模块树、红线：平台模块禁 web starter / 禁 userId 标识符 / examples 不依赖平台）、`rules/agent-safety.md` §2（授权归宿主；内核保证经 Gateway + 经宿主切面）、§3（令牌绑 sessionId）、`rules/backend-standard.md`、`06-backend-module-spec.md`、`CLAUDE.md`（目录名、命令、硬约束中的目录路径）。
- **所属端**：harness
- **输入**：T14
- **输出**：上述文档
- **验收**：doctor 0（L1 一致性检查随 CLAUDE.md 更新）；`grep -rn "Principal\|permission\|pageContext" .harness/rules .harness/wiki spark-rooter/README.md` 只出现在「归宿主 / ToolAccessPolicy 可选」语境（人工核对 + 行数 ≤ 6）
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
T01 → T02 → T03 → T04 → T05 → T06 → T07 → T08 → T09 → T10 ─┐
                        └────────────────────────── T13 ─────┼→ T14 → T15 → T16
                                          T09 → T11 → T12 ───┘
```
