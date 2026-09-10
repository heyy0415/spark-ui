# Code Review (Backend) v1 — refactor-spark-embedded-starter-20260909

- 评审对象：`c8b9b36..2dcf93e`（T01–T16 后端 + Harness 脚本），全量 diff `471f732..2dcf93e -- spark-rooter .harness/scripts`
- 模式：execution / 有罪推定；依据 `request_analysis/spec.md` v2、`tasks.md` v2、`rules/{agent-safety,backend-standard,project-structure,contracts}.md`、`coding/coding_report_v1.md`
- 方法：逐文件阅读 starter / runtime / gateway / registry / web-mvc / spi / examples / host-demo / e2e 脚本；对 `check-module-deps.mjs` 的正则用 node 实测；对 spec 验收项逐条 grep 复核。未运行 JVM。
- 评审人：独立后端评审（Claude）· 日期 2026-09-10

## 1. 必查项

| # | 必查项 | 结果 | 依据 |
|---|---|---|---|
| C1 | `@SparkTool` 经 Spring 代理调用（宿主方法级切面生效） | ✅ | `SparkToolScanner:64` `context.getBean(name)` 取代理；`:111-112` `getMostSpecificMethod` + `selectInvocableMethod`；`AnnotatedToolHandler:65-66` 在代理上 invoke；`ProxyInvocationSelfCheck` 计数==1；e2e ⑭ 切面真实拒绝 |
| C2 | final / 非 public / `@Configuration` 启动失败 | ✅ | `SparkToolScanner:84-99`；报告植入反例各红 |
| C3 | `ConfirmationTokenService` 双绑定 conversationId + sessionId | ⚠️ | `ConfirmationTokenService:92` 比对存在；但 `AgentRunController:91` 的 conversationId 取自 Run 自身，conversationId 一侧恒等（S-3） |
| C4 | `RunContextPropagator` restore/clear 覆盖全部切线程点 | ✅ | `AgentRunController:120-131`（start / confirm 共用 submit）；`InvokeToolUseCase:274,309-314`（每次 attempt restore，finally clear，含 recheck / clarify / 确认路径，均在 agent-run 线程内经同一 Gateway 入口） |
| C5 | 默认 `SessionIdResolver` 风险显眼 | ✅ | `RuntimeBeans:57` 构造期 WARN；spi Javadoc；README 第 3 步「生产必须」 |
| C6 | 日志无用户原文 / 密钥 | ⚠️ | 代码层面：抽取只记类型与值、LLM 异常只记类名 ✅；但 e2e / deploy-verify 的「user text in log」断言是空洞的（M-3） |
| C7 | `InvokeToolUseCase` 幂等域换 sessionId | ✅ | `:202,207,222` 以 `(sessionId, idempotencyKey)`；key 含 runId，跨会话无碰撞；`/internal` 直调可自选 sessionId 但端点默认关 |
| C8 | 平台模块无 Spring 组件注解 / 无 userId·tenantId·Principal | ❌ | 注解 0 ✅；`spark-rooter-spi/.../OrderSnapshotProvider.java:7` 含 `tenantId`（M-2） |
| C9 | 域层（`domain/`）无 Spring / Jackson | ✅ | grep 0；`check-module-deps` 该规则有效 |
| C10 | `check-module-deps` 新红线可用性 | ❌ | 身份标识符红线未实现；3 条规则正则仍是旧包名，实测不命中（M-1） |
| C11 | Manifest 推导 12/12 与手写 JSON parity | ✅ | `ManifestParitySelfCheck`；两处有意差异已回写并在报告说明 |
| C12 | starter 条件装配无循环 / 顺序问题 | ✅ | `SmartInitializingSingleton` 早于 `ApplicationReadyEvent`；`ObjectProvider` 延迟解析；未见环。`@EnableAspectJAutoProxy` 与 Boot 的 CGLIB 默认合并正常 |
| C13 | 自检 EchoTools 是否污染生产工具表 | ⚠️ | 是：`spark.selfcheck.echo` 以 `status=active` 进 Registry，`domains()` 含 `spark`（S-5） |
| C14 | e2e 新用例断言是否空洞 | ⚠️ | ⑭ ⑳ ㉑ ㉒ ㉓ ㉕ 实；㉔ 名不符实、⑭' 同义反复、⑲ 无法区分截断、log 卫生断言空洞 |

## 2. 发现

| # | 位置 | 问题（含复现） | 建议 | 分级 |
|---|---|---|---|---|
| M-1 | `.harness/scripts/check-module-deps.mjs:71,139,177`；缺失规则 | ① spec §2.5 / §2.10、`project-structure.md` §4 #11 要求的「平台模块源码禁 `\buserId\b` / `\btenantId\b` / `\bPrincipal\b`」**没有实现**（脚本中不含 `userId` 字样；报告的植入反例表也没有这一条，spec §6.1「植入 runtime `String userId` → 红」不可能成立）。② 三条既有规则正则仍写旧包名，全部失效：`:71` `com\.spark<\/groupId>`（实际 `com.sparkrooter`）、`:139` `com\.spark\.${o}\.(infra|domain|application)`（实际 `com.sparkrooter.gateway.infra`）、`:177` `com\.sparkrooter\.domain\.([a-z]+)\.`（实际 `com.sparkrooter.examples.<svc>.`）。node 实测：`new RegExp('\\bcom\\.spark\\.gateway\\.(infra|domain|application)\\.').test('import com.sparkrooter.gateway.infra.LogAuditSink;') === false`。即 runtime 直接 import gateway 的 `infra` 包、spi 依赖 runtime、领域互相 import 三种红线现在都**静默放行**。 | 新增身份标识符规则（范围 spi/contracts/runtime/registry/gateway/web-mvc/starter，`\b(userId|tenantId|Principal)\b`）；三处正则改 `com\.sparkrooter\.`；每条规则配一个内置「植入即红」自测（Hashimoto） | MUST FIX |
| M-2 | `spark-rooter-spi/src/main/java/com/sparkrooter/spi/OrderSnapshotProvider.java:7` | `Optional<OrderSnapshot> snapshot(String tenantId, String orderId)` —— 平台模块（spi）源码出现 `tenantId`，直接违反 `project-structure.md` §4 #11 与 spec §2.5。`coding_report_v1.md:37` 声称「userId\|tenantId\|Principal 0」与实际 grep 不符。 | 端口去租户参数（`snapshot(String orderId)`，租户由实现从宿主上下文取），或把 `OrderSnapshot(Provider)` 迁到 `examples/demo-support`；同步更正报告；M-1 的门禁落地后此项会被机器抓住 | MUST FIX |
| M-3 | `.harness/scripts/e2e-backend.sh:355`；`.harness/scripts/deploy-verify.sh:51`；`.harness/contracts/examples/intent-request.example.json:3` | 「§6.2.15 user text in log」断言 `grep -c '帮我把这个订单退款'`，而 T04 已把示例消息改为「帮我把订单 10001 退款」，全脚本再无该短语 → 计数恒 0，断言永真。spec §6.2「日志无用户原文」实际未被验证。可复现：在 `RunOrchestrator.start` 加 `log.info(intent.message())`，脚本仍全绿。 | 断言改为对本轮实际发送的若干消息原文（如「帮我把订单 10001 退款」「最近 5 单已发货的订单」「删除订单 10010」）逐条 grep 0；deploy-verify 同步 | MUST FIX |
| M-4 | `RunOrchestrator.java:613-616`（clarify 写记忆）→ `:780-783`（complete → remember）→ `:645`（`lastExecuted(run).map(Step::toolId).orElse("")`）；`:510` fillOrdinal | 澄清屏后的序数指代**失效**。`clarify()` 先写 `LastTable(c.toolId(), ids)`，随即 `complete()`→`remember()`：该 Run 无 plan，`lastExecuted` 为空 → 以 `LastTable("", ids)` **覆盖**；下一轮 `fillOrdinal` 对 `meta.find("")` 得 empty → 直接 return。复现：空会话「申请售后」→ 澄清屏 → 「第二个」→ 不解析行 id，再次出澄清屏。与 `:604` 注释「用户下一句『第二个』即指它的行」及 spec §2.6 相悖；e2e 无此用例（㉒ 用行内 intent 点选绕过了它）。 | `remember()` 在无 plan 时保留 `clarify()` 已写的 lastTable（或 clarify 后不再 remember）；e2e 补「澄清屏 → 第二个 → 确认屏标题含 rows[1].id」 | MUST FIX |
| S-1 | `SearchToolsUseCase.java:34-36,51`；`InProcessToolRegistryClient.java:17`；`ToolSearchPort.search(Request)` | Runtime 发现路径 `search(req)` → `execute(req, null)`，`ToolAccessPolicy.allowed(toolId, **null**)`。spec §2.3「宿主实现后 Registry 候选过滤按 `(toolId, sessionId)` 判定」在 Runtime 路径不成立；宿主按 sessionId 过滤会 NPE 或全拒。 | `ToolSearchPort.search(Request, String sessionId)`（或 Request 增 sessionId 内部字段）；Runtime 透传 `run.sessionId()`；自检加「策略收到非空 sessionId」 | SHOULD |
| S-2 | `spi/ConversationMemory.java`；`RunOrchestrator.java:170,613,653`；`InMemoryConversationMemory.java:24-31` | 记忆仅按 `conversationId` 键控，未纳入 `sessionId`。宿主接入真实 `SessionIdResolver` 后，B 用 A 的 conversationId（前端可伪造）即继承 A 的 `entities` / `lastTable`：「申请售后」直接补位 A 的订单号进确认屏标题（订单号外泄；执行是否被拒取决于宿主切面）。 | 记忆键改 `(sessionId, conversationId)`；spi 方法签名增 sessionId | SHOULD |
| S-3 | `AgentRunController.java:91,105`；`ConfirmationTokenService.java:92`；`e2e-backend.sh:321-333` | ① 确认路径 `sessionId = sessions.resolve(run.conversationId())`、`consume(..., run.conversationId(), sessionId, ...)`：conversationId 一侧取自 Run 自身，与签发值恒等，「双绑定」只有 sessionId 一因子起作用（默认实现下再退化为 runId 绑定）。② e2e ㉔ 标题「令牌 sessionId 不一致」，实际断言的是「令牌用于另一 Run → 拒绝」（runId/actionId 绑定），会话不一致仅由 `TokenSelfCheck` 单测覆盖；报告已承认但脚本仍以㉔命名，易误导后续评审。 | host-demo 增 `X-Demo-User` 派生的 `SessionIdResolver`（仅 `e2e` profile，默认仍走 demo WARN），让㉔成为真实 HTTP 级断言（A 的 token + B 的头 → REJECTED）；控制器改为从请求侧解析 sessionId 并保留 conversationId 比对的实际意义（或文档如实写「单因子」） | SHOULD |
| S-4 | `ClarificationScreen.java:81-84`；`RunOrchestrator.java:597,226-229` | ① intent = `truncate(originalMessage + " 订单 " + id, 200)`：用户消息 ≥ ~190 字时 id 被截掉，点选后下一轮抽不到实体 → 再出澄清屏，死循环。② 用户消息含 `<`（如「申请售后 <」）：intent 违反契约 `inlineAction.intent` pattern `^(?!.*(://|<)).*$` → `screens.toUi` 抛 `ContractViolationException` → `start()` 的 `RuntimeException` 分支 → `run.failed INTERNAL_ERROR` + `log.error("run_unhandled")`。用户可触发的 500 级失败与 ERROR 日志。 | intent 组装改为「`<动词标签>` + 实体名 + id」（不回拼原话）或先对原话做 `sanitize`（去 `<`、`://`）并只截原话部分，保证 id 恒在 | SHOULD |
| S-5 | `SelfCheckBeans.java:73-76`；`ProxyInvocationSelfCheck.java:27-42`；`ManifestDeriver.java:91`（`status=active`）；`DomainResolver.java:46` | 开启 `spark.selfcheck.enabled` 时 `spark.selfcheck.echo` 以 active 状态进 Registry / Gateway：`/internal/tool-registry/search {domain:"spark"}` 可见；`registry.domains()` 含 `spark` → LIVE 模式作为分类枚举送给模型；e2e 首行断言的「14 tools / 6 beans」即证据。生产宿主若开自检即被污染。 | 自检 EchoTools 以 `status=draft`（不可发现）注册并在 Gateway 侧允许直调；或自检完成后注销（Registry/Gateway 增 `unregister`）；`domains()` 排除 |  SHOULD |
| S-6 | `InMemoryRunRepository.java`（无 TTL）；`RunOrchestrator.java:87` `lastUi` 从不淘汰 | spec §2.3 明文「`RunRepository` / `ConversationMemory` 带 TTL 淘汰」，RunRepository 未实现；`lastUi` 按 runId 只 put 不 remove。长跑进程无界增长；报告「关键决策与偏差」未披露。 | `InMemoryRunRepository` 加 TTL（复用 `Clock` + `memoryTtl` 或新增 `run-ttl`），`lastUi` 随 Run 淘汰或改存 Run 内；报告补记 | SHOULD |
| S-7 | `ToolSelectionValidator.java:66-70,92-94` | 在 runtime 内另起 `JsonSchemaFactory` + 裸 `ObjectMapper`，每个参数每次规划都 `getSchema()` 编译；T11 明确要求复用 `SchemaValidator.validateWithInlineSchema`。裸 mapper 无 `SchemaMapper`，将来 `$ref` 到契约会失败。 | 注入 `SchemaValidator`（`validate(LlmPlanDraft, …, validator)`），删本地工厂 | SHOULD |
| S-8 | `check-module-deps.mjs:89` | 组件注解规则 `^\s*@(Component|Service|…)\b` 只匹配简名；`@org.springframework.stereotype.Service` 内联 FQN 直接绕过（node 实测 false）。脚本 `:110` 注释自己也说明了 FQN 绕过风险，却只对 domain 规则做了。 | 正则改 `@(org\.springframework\.stereotype\.|org\.springframework\.context\.annotation\.)?(Component|Service|Repository|Configuration|ComponentScan)\b` | SHOULD |
| S-9 | `ToolMetaRegistry.java:100-110` | `entityArgs()` 把全部工具的 `@SparkParam.entity` 按**参数名**全局合并，同名不同类型静默 last-wins（宿主 A 工具 `id→ORDER`、B 工具 `id→PRODUCT`），且合并结果每次调用重算（`EntityRequirementCheck.check` 循环内、`validate` 每个 arg、`remember` 每步都调）。 | `register()` 时检测同名冲突即启动失败；合并表在 register 时增量维护 | SHOULD |
| S-10 | `DemoInterceptorOnlyGuard.java:19`；`e2e-backend.sh:255-258` | ⑭' 「Controller 拦截器对 spark 无效」：拦截器只拦 `/demo/**`，`/agent/runs` 本就不在其范围，guest 成功是同义反复，没有证明「保护同一能力的拦截器被绕过」。 | 让 guard 保护一个真正调用 `OrderTools.list` 的 `/demo/orders` 端点：断言 guest 直调 403 **且**同一能力经 spark 200 | SHOULD |
| L-1 | `SparkToolScanner.java:61-67` | 对每个 bean definition 调 `getBean(name)`：会实例化 `@Lazy` 单例与 prototype（并把某个 prototype 实例固定为 handler 目标）；`catch (RuntimeException) continue` 吞掉一切。 | 只遍历 `isSingleton && !isLazyInit` 的定义（`ConfigurableListableBeanFactory.getBeanDefinition`）；对含 `@SparkTool` 的 lazy/prototype bean 报错 | LOW |
| L-2 | `ArgumentExtractor.java:121`；`RuntimeBeans.java:83` | 相对时间用 `clock.withZone(ZoneId.systemDefault())`，Clock Bean 为 UTC；spec §8 假设 `Asia/Shanghai`。且四个示例工具无任何 `format=DATE` 参数，`PlanSelfCheck` / e2e 对相对时间表零覆盖（spec §2.6 承诺的能力未验证）。 | 时区可配（`spark.runtime.zone`，默认 Asia/Shanghai）；`PlanSelfCheck` 用带 DATE 参数的测试 ToolMeta 覆盖 5 条规则 | LOW |
| L-3 | `ManifestDeriver.java:215-216` | 自引用 record（`record Node(String id, List<Node> children)`）递归推导 → `StackOverflowError`，无可读原因。 | 递归深度上限或类型访问栈检测，报「recursive record at …」 | LOW |
| L-4 | `IntentVerbs.java:33-46`；`tasks.md` T12 验收 | `LIST_TOOL` / `DETAIL_TOOL` 仍硬编码 `order.list.search` 等 toolId；T12 验收「`grep order.list.search\|product.list.search` runtime == 0」与「含『订单/商品』文件 == 3」实际为 4 处 / 12 个文件；报告未说明。澄清屏本身已改读注册表 ✅。 | 报告如实记为已知限制（与 §3 DomainRouter 同列）；或 fallback 表也改由 `ToolMetaRegistry` 派生 | LOW |
| L-5 | `AnnotatedToolHandler.java:56-60` | JSON → In 绑定失败抛 `IllegalArgumentException` → Gateway 归为 `HANDLER_ERROR` 并按策略重试；本质是输入问题。 | 定义 spi 级 `ToolInputException` 映射 `INPUT_INVALID`，不重试 | LOW |
| L-6 | `RuntimeBeans:108`、`GatewayBeans:39`、`SparkRooterWebMvcAutoConfiguration:37` | `Executors.newFixedThreadPool` 无界队列：SSE 请求堆积时 `runExecutor.submit` 永不拒绝，emitter 挂到 `sse-timeout`。公司 Java 规范禁 `Executors`。 | `ThreadPoolExecutor` + 有界队列 + `AbortPolicy`，拒绝映射 503 | LOW |
| L-7 | `ClarificationScreen.java:44`；spec §2.6 | 行 id 字段按命名约定 `<type>Id` 取，spec 写「以 `@SparkParam.entity == type` 标记的输出字段」；未按注解实现且未在报告中说明。列标题直接用英文字段名。 | 报告说明偏差；后续可用输出组件注解 | LOW |
| L-8 | `SparkTool.clarifiesEntity` Javadoc「须为无必填参数的列表工具」 | 未在扫描期校验；违反时运行期 `INPUT_INVALID` → clarify 静默退回提示。 | `ManifestDeriver` 对 `clarifiesEntity != NONE` 断言 `required` 为空 | LOW |
| L-9 | `e2e-backend.sh:290-292` | ⑲「最近 100 单 → limit 50」断言 rows==29（剩余订单数），limit=100 时结果相同，无法证明截断。 | 直调 Gateway `{"limit":100}` 断言 400，或 grep 规划日志 | LOW |
| L-10 | `backend-standard.md` §6；`ToolSearch.java:15-17` | 规则仍写「Gateway 用 `(tenantId, idempotencyKey)` 去重」；`ToolSearch.Request.intent` / `Context.entityType` 为 pageContext 时代残留字段。 | 文档改 sessionId；删残留字段 | LOW |
| L-11 | 多处 | 公共方法缺 JavaDoc：`ManifestDeriver.derive`、`ToolMetaRegistry.find/all`、`SessionIdResolver.resolve`、`ToolAccessPolicy.allowed`；魔法数字：`ClarificationScreen` 50 行 / 200 / 32、`ArgumentExtractor` `\d{1,3}`、token 24 字节。 | 补 JavaDoc；提取具名常量 | LOW |
| I-1 | `coding_report_v1.md:37` | 「userId\|tenantId\|Principal 0」不实（M-2）；T12 两条 grep 验收未达成未说明（L-4）；RunRepository TTL 缺失未说明（S-6）。 | 报告 v2 如实列出 | INFO |
| I-2 | `e2e-backend.sh:260-262` | ㉖ 走 `/internal/tool-gateway/invoke`，只覆盖 HTTP 线程 → tool 线程一跳；Runtime 跳（request → agent-run → tool）由 ⑭ 切面读到 `guest` 间接覆盖。可接受，建议㉖补一条经 `/agent/runs` 的 `demo.whoami` 断言。 | — | INFO |
| I-3 | 报告 §关键决策 9 | LIVE 模式未跑；`SpringAiLlmClient.fillDefaults` 与 `SpringAiIntentClassifier` 的 `spark` 域污染（S-5）只有 LIVE 才会显现。 | 阶段 7 必跑 LIVE | INFO |

## 3. Verdict

**REVISION REQUIRED** — 4 MUST FIX / 10 SHOULD / 11 LOW / 3 INFO。

代理调用、上下文传播、令牌 sessionId 因子、去身份、starter 装配等主线是扎实的；阻塞点集中在**门禁与验收本身失真**（M-1 / M-3：脚本声称守住的红线实际不生效或永真）、一处**红线实违**且报告误报（M-2），以及一处 spec 承诺功能的**可复现缺陷**（M-4）。按 Hashimoto 法则，M-1 / M-3 / S-8 优先于修业务代码。

## 4. 最小补丁清单

1. `check-module-deps.mjs`：三处正则改 `com\.sparkrooter\.`；新增身份标识符红线；组件注解规则允许 FQN 前缀；每条规则内置自测（对内存中构造的违规文本断言 fail）。（M-1, S-8）
2. `spi/OrderSnapshotProvider.snapshot(String orderId)`（实现从宿主上下文取租户），或迁至 `examples/demo-support`；报告更正。（M-2）
3. `e2e-backend.sh:355`、`deploy-verify.sh:51`：对本轮真实发送的 ≥3 条消息原文逐条 `grep -c … == 0`。（M-3）
4. `RunOrchestrator.remember()`：无 plan 时不覆盖 `lastTable`（或 `clarify()` 返回后跳过 remember）；e2e 增「澄清屏 → 第二个 → 确认屏标题含 rows[1].id」。（M-4）
5. `ToolSearchPort.search(Request, sessionId)` 并透传 `run.sessionId()`。（S-1）
6. `ConversationMemory` 键 `(sessionId, conversationId)`。（S-2）
7. host-demo `e2e` profile 增 `X-Demo-User` 派生 `SessionIdResolver`，㉔ 改为真实跨会话拒绝断言。（S-3）
8. `ClarificationScreen` intent 改「动词标签 + 实体名 + id」并 sanitize。（S-4）
9. 自检 EchoTools 以 `draft` 注册 / 完成后注销，`domains()` 排除。（S-5）
10. `InMemoryRunRepository` TTL + `lastUi` 淘汰；报告补记偏差。（S-6）
11. `ToolSelectionValidator` 改用注入的 `SchemaValidator`。（S-7）
12. `ToolMetaRegistry.register` 同名参数不同实体类型 → 启动失败。（S-9）
13. ⑭' 改为保护同一能力的 `/demo/orders` 端点对照。（S-10）
