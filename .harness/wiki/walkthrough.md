# Spark 全链路架构导读

> 面向学习者：从用户输入进来到一张表格出去，每一跳发生了什么、代码在哪、为什么这么设计。
> 所有 `file:line` 指向本仓当前代码，读到不对的地方说明代码变了，以代码为准。

## 0. 先建立一张地图

```
浏览器                          hub（你的 Spring Boot 服务）                       领域 / provider
┌──────────────┐    POST /agent/runs     ┌─────────────────────────────────────┐
│ spark-chat   │ ──── (SSE 长连接) ─────► │ web-mvc   AgentRunController        │
│  或任何页面   │                          │   ↓ submit 到 agent-run-* 线程池     │
│              │                          │ runtime   RunOrchestrator            │
│ @spark-ui/   │                          │   ① 读会话记忆 ConversationMemory     │
│  core/client │                          │   ② registry.search → 候选工具        │     ┌──────────────┐
│  · consumeSse│                          │   ③ LlmPlanner → 模型 → PlanValidator │     │ @SparkTool   │
│  · reduceEvent│◄── 10 种 SSE 事件 ─────  │   ④ runSteps：逐步 gateway.invoke ──┼────►│ 方法（反射）  │
│  · runStore  │                          │   ⑤ 需确认 → 签令牌 → 确认屏 → 断连   │     └──────────────┘
│ core/react   │                          │ gateway   InvokeToolUseCase          │          或
│  · useSparkRun│  POST /runs/{id}/actions│   寻址→校验→策略→限流→幂等→调用→校验  │     ┌──────────────┐
│ core (render)│ ──── (第二条 SSE) ──────►│   →脱敏→审计                          │────►│ HTTP provider │
│  · SchemaRenderer                       │ 状态：Run / 令牌 / 幂等 / 记忆        │     │ (JDK 17 进程) │
└──────────────┘                          │   内存 或 Redis                       │     └──────────────┘
                                          └─────────────────────────────────────┘
```

四个平面的分工是硬约束，门禁守着：

| 平面 | 模块 | 只做 | 绝不做 |
|---|---|---|---|
| Agent Runtime | `spark-rooter-runtime` | 理解、规划、编排、签令牌、出屏 | 直接调领域服务；识别用户 |
| Tool Registry | `spark-rooter-registry` | 注册、发现、按状态过滤 | 转发调用 |
| Tool Gateway | `spark-rooter-gateway` | 校验、幂等、超时重试、脱敏、审计、传输 | 理解语义；做用户鉴权 |
| 领域服务 | `examples/domains/*` 或 provider | 确定性业务执行 | 认识 spark 内核 |

---

## 1. 契约层：为什么一切从 JSON Schema 开始

`.harness/contracts/` 里 9 个 `*.schema.json` 是唯一真源。前端 Zod、后端 record、e2e 断言都是它的**投影**，不是各自的定义。

| 契约 | 方向 | 前端投影 | 后端投影 |
|---|---|---|---|
| `intent-request` | 前端 → hub | `IntentRequestSchema` | `IntentRequest` record |
| `action-request` | 前端 → hub | `ActionRequestSchema` | `ActionRequest` |
| `sse-events` | hub → 前端 | `SseEventSchema`（discriminatedUnion） | `SseEvent.*Data` records |
| `ui-schema` | hub → 前端 | `UiSchemaSchema` + 5 个 `*PropsSchema` | `UiSchema` |
| `run-summary` | hub → 前端 | `RunSummarySchema` | `RunSummary` |
| `error-response` | hub → 前端 | `ErrorResponseSchema` | `ErrorResponse` |
| `tool-manifest` | 领域 → registry | — | `ToolManifest` |
| `tool-search` | runtime ↔ registry | — | `ToolSearch` |
| `tool-invoke` | runtime ↔ gateway | — | `ToolInvoke` |

**后端怎么加载**：`sync-contracts.mjs` 把 schema 复制进 `spark-rooter-contracts/src/main/resources/contracts/`，打进 jar。[SchemaValidator](../../spark-rooter/spark-rooter-contracts/src/main/java/com/sparkrooter/contracts/SchemaValidator.java) 用 networknt 加载，`$id` 前缀 `https://spark-rooter.local/contracts/v1/` 映射到 `classpath:contracts/`（`SchemaValidator.java:57`），所以 `$ref` 跨文件引用不走网络。`check-contracts` 门禁保证副本与真源一致。

**前端怎么投影**：`packages/core/src/client/contracts.ts` 手写 Zod，`SseEventSchema` 是按 `event` 字段的 `discriminatedUnion`（`contracts.ts:134`），每个分支 `.strict()`——多一个字段就拒。`verify-examples.ts` 拿真源的 28 个示例喂 Zod，保证投影没漂。

**为什么不生成**：Zod 和 JSON Schema 的表达力不完全对齐（`if/then`、`$ref` 递归），生成器会在边角处出错还看不出来。手写 + 用真源示例双向校验，错了立刻红。

---

## 2. 一条请求的完整旅程

以「帮我把订单 10001 退款」为例，这是三步链（两只读 + 一需确认），走完全部分支。

### 2.1 前端：从输入框到 SSE 帧

**入口**：[AgentChatPanel.tsx](../../spark-ui/apps/chat/src/features/agent-chat/ui/AgentChatPanel.tsx) 的 `send(message)`。输入框、示例按钮、表格行按钮、卡片按钮全走这一个函数——**文本原样提交，不拼接不改写**。这是「前端只发自然语言」的实现点。

```ts
mutate({
  message,
  clientCapabilities: { uiSchemaVersion: '1.0', components: [...COMPONENT_TYPES] },
});
```

`clientCapabilities` 告诉后端"我能渲染这 5 种组件"。请求体只有这两个字段 + `conversationId`；契约 `additionalProperties: false`，多传 `pageContext` 之类会被 400 拒——e2e 有反例断言。

**useSparkRun**（[useSparkRun.ts](../../spark-ui/packages/core/src/react/useSparkRun.ts)）做三件事：

1. `beginTurn` 在 store 里追加一个 `ChatTurn{status:'streaming'}`
2. 调 `consumeSse` 开流，每帧过 `SseEventSchema.safeParse`，通过才 `reduceEvent`
3. 流结束后 `failIfStillStreaming`——没收到终态事件就标 failed，不让 UI 停在 loading

**为什么自己写 SSE 客户端**（[sse.ts](../../spark-ui/packages/core/src/client/sse.ts)）：原生 `EventSource` 只支持 GET，而请求要带 JSON body。所以用 `fetch` + `ReadableStream`：

```ts
const reader = res.body.getReader();
let buffer = '';
for (;;) {
  const { value, done } = await reader.read();
  if (done) break;
  buffer += decoder.decode(value, { stream: true });
  buffer = buffer.replace(/\r\n/g, '\n');
  let sep = buffer.indexOf('\n\n');          // SSE 帧以空行分隔
  while (sep >= 0) {
    onFrame(parseFrame(buffer.slice(0, sep)));
    buffer = buffer.slice(sep + 2);
    sep = buffer.indexOf('\n\n');
  }
}
```

`parseFrame` 处理 `event:` / `data:`（多行拼接）/ `id:` 三种字段，`: ping` 注释帧丢弃。**注意 `decoder.decode(value, {stream:true})`**——一个多字节 UTF-8 字符可能被切在两个 chunk 里，`stream:true` 让解码器把半个字符攒到下一次。

**状态容器**（[runStore.ts](../../spark-ui/packages/core/src/client/runStore.ts)）：60 行，`subscribe / getSnapshot / dispatch`，形状对齐 React 的 `useSyncExternalStore`。**为什么不用 TanStack Query**：SSE 是推送模型，视图完全由事件序列决定，没有"数据过期要重取"这个概念；为一个 Map 背一个 React 专属依赖，headless 层就不 headless 了。

**归约**（[runView.ts](../../spark-ui/packages/core/src/client/runView.ts)）：`reduceEvent(view, ev)` 是纯函数，只作用于最后一个回合。10 种事件的处理一目了然：`tool.selected` 追加进度条、`ui.replace` 覆盖屏、`confirmation.required` 把状态改成 `waiting_confirmation` 并记 `pendingActionId`。有一条防御值得注意（`runView.ts:115`）：

```ts
if (t.runId && ev.event !== 'run.started' && ev.data.runId !== t.runId) return t;
```

旧流的残帧不得写进新回合——用户快速连发两条时，第一条的尾巴可能在第二条开始后才到。

### 2.2 后端入口：Controller 只做绑定和切线程

[AgentRunController.start](../../spark-rooter/spark-rooter-web-mvc/src/main/java/com/sparkrooter/webmvc/runtime/AgentRunController.java)：

```java
IntentRequest intent = validator.bind("intent-request", null, body, IntentRequest.class);  // 先过契约再绑 record
String sessionId = sessions.resolve(intent.conversationId());                              // 宿主决定会话键
SseEmitter emitter = new SseEmitter(sseTimeoutMs);
SseRunEventSink sink = new SseRunEventSink(emitter, mapper, pingScheduler);
submit(sink, () -> orchestrator.start(intent, sessionId, traceId, sink));                  // 切到 agent-run-* 线程
return emitter;                                                                            // HTTP 线程立刻归还
```

三个要点：

**为什么 `validator.bind` 而不是 `@RequestBody IntentRequest`**：Jackson 绑 record 会静默忽略未知字段、把缺字段填 null。先按契约校验原始 JSON，`additionalProperties`、`pattern`、`const` 才生效。

**`sessions.resolve`**：这是宿主的 `SessionIdResolver`。内核不认识用户，但确认令牌、Run 查询、幂等 scope 都要一个隔离键——宿主把登录态映射成这个键（示例：`DemoUserContext.userId() + ":" + conversationId`）。缺这个 Bean starter 拒绝启动。

**`submit`**（`AgentRunController.java:145`）：`propagator.capture()` 在 HTTP 线程抓宿主 ThreadLocal，工作线程 `restore`，`finally clear`。线程池有界（`runQueue=32`），满了 `submit` 抛 `RejectedExecutionException`——此时 emitter 已返回给客户端改不了状态码，只能发一帧 `run.failed{INTERNAL_ERROR, "当前请求较多"}` 再 close。

**SSE 服务端**（[SseRunEventSink](../../spark-rooter/spark-rooter-web-mvc/src/main/java/com/sparkrooter/webmvc/runtime/SseRunEventSink.java)）：包一个 `SseEmitter`，`emit` = `emitter.send(event().name(...).data(json))`，每 15 秒发 `: ping` 注释帧防代理断连。`onCompletion / onTimeout / onError` 三个回调都置 `closed=true`，编排器通过 `isClosed()` 感知客户端断开。

### 2.3 编排：RunOrchestrator.start

[RunOrchestrator.java:137](../../spark-rooter/spark-rooter-runtime/src/main/java/com/sparkrooter/runtime/application/RunOrchestrator.java) 按序号读：

**① 会话装载**：`memory.find(sessionId, conversationId)` 拿上一轮的实体（`{order: "10001"}`）、列表行 ID、挂起的原话。这是「第二个的物流」能解析的来源。

**② 候选发现**：`registry.search(Request(null,...), sessionId)` 拿**全部**可发现工具——不按领域筛。内核不认识领域，让模型在全部候选里选。宿主 `ToolAccessPolicy` 可按 sessionId 过滤掉这个用户不该看到的工具。

**③ 规划**：`llm.plan(PlanRequest(message, candidates, ctx))` → 返回三选一 `Planned / Clarify / NoCapability`。展开看 [LlmPlanner](../../spark-rooter/spark-rooter-runtime/src/main/java/com/sparkrooter/runtime/infra/llm/LlmPlanner.java)：

- 先 `circuit.shouldSkip()`：熔断 OPEN 直接抛，不发请求
- `PromptBuilder.system()` 是 8 条通用规则，**零领域词**；`PromptBuilder.user()` 把每个候选的 `description / verbs / label / entity / prerequisites` 逐条列出——领域知识全从注解来
- 候选 `description` 视为不可信文本：截断、转义花括号和反引号，防提示注入
- 模型输出 JSON → `PlanDraft` → **`PlanValidator.decide`**，最多重试 2 次

**④ 校验**（[PlanValidator](../../spark-rooter/spark-rooter-runtime/src/main/java/com/sparkrooter/runtime/infra/llm/PlanValidator.java)）是安全模型的核心，逐条：

| 检查 | 失败 → | 防什么 |
|---|---|---|
| `toolId` ∈ 候选 | `TOOL_SELECTION_INVALID` | 模型编造工具 |
| args 键 ⊆ `inputSchema.properties` | 同上 | 模型塞额外参数 |
| 值过 JSON Schema（enum / min / max / pattern） | 同上 | 值域越界 |
| 标了 `entity` 的参数值 ∈ `knownIds`（原话 ∪ 记忆实体 ∪ 最近列表行） | `Clarify` | **模型编造订单号** |
| `prerequisites` 齐全且顺序对 | `TOOL_SELECTION_INVALID` | 跳过前置只读步骤直接写 |
| 需确认步骤不含 `trustedOnlyArgs`（如 `amount`） | 同上 | 模型自己填金额 |

第四条是关键：模型说「退款订单 10001」，`10001` 必须在用户原话里、或上一轮记忆里、或上一张列表里。不在 → 走澄清屏让用户选，**绝不拿编造的 ID 去执行**。

**⑤ 附加计划并快照 schema**：`run.attachPlan(plan, schemaSnapshot(found), now())`——计划各步骤的 `inputSchema` 存进 Run。为什么快照：确认请求可能落到另一个副本，那时不能再依赖注册表的当下状态。

### 2.4 逐步执行：runSteps

```java
while (true) {
  Optional<Step> cur = run.currentStep();
  if (cur.isEmpty()) { /* 出结果屏 → complete */ return; }
  Step step = cur.get();
  if (step.requiresConfirmation()) { waitForConfirmation(run, step, sink); return; }
  if (sink.isClosed() && !isWrite(step.toolId())) { /* 客户端已走，只读步骤不值得再跑 */ throw ...; }
  JsonNode out = invoke(run, step.toolId(), ..., "step");
  run.putStepOutput(step.toolId(), out.toString(), now());
  run.advance(now());
}
```

退款链：`refund.eligibility.check`（只读）→ `refund.preview`（只读）→ `refund.create`（需确认）。前两步各走一次 `invoke`，第三步进 `waitForConfirmation`。

**`invoke`** 每次发三帧：`tool.selected` → `tool.started` → `tool.completed`，中间调 `gateway.invoke(req)`。幂等键 = `{runId}-{toolId}-{seq}`，同一 Run 同一步重放不会双执行。

### 2.5 Gateway：八道工序

[InvokeToolUseCase.pipeline](../../spark-rooter/spark-rooter-gateway/src/main/java/com/sparkrooter/gateway/application/InvokeToolUseCase.java) 顺序固定：

```
1 寻址      resolver.resolve(toolId, version) → Manifest；没有 → TOOL_NOT_FOUND
2 输入校验  validateWithInlineSchema(manifest.inputSchema, args)；不过 → INPUT_INVALID
3 访问策略  access.allowed(toolId, sessionId)；拒 → FORBIDDEN
3b 会话限流 sessionLimiter.acquire(sessionId)；超 4 个在飞 → RATE_LIMITED
4 幂等      idempotency=required 时 claimOrAwait：Owner 执行 / Replay 直接返回 / Awaiting 等 owner
5 调用      callWithRetry：按 protocol 选 transport，线程池 + 超时 + 按 Manifest 重试
6 输出校验  validateWithInlineSchema(manifest.outputSchema, output)；不过 → OUTPUT_INVALID
7 脱敏      递归把 password/token/secret/apiKey 键的值换成 ***
8 审计      AuditSink.record(runId, toolCallId, toolId, sessionId, argsDigest, status, durationMs)
```

**幂等的 claim 语义**（`claimOrAwait`, `:304`）值得展开：`IdempotencyStore.claim` 返回三种——

- `Owner`：我拿到执行权，执行完 `complete(response)`，失败 `release()`
- `Replay`：已有最终结果，直接返回它（用户重复点了）
- `Awaiting`：别人正在执行，拿到一个 future 等（同一秒两个请求撞上了）

内存版用 `ConcurrentHashMap.putIfAbsent` + `CompletableFuture`；Redis 版用 `SET NX PX` + 轮询。等待方超时要 `future.cancel(false)`，否则 Redis 版的轮询任务会跑到 key 过期——这是阶段 4 评审抓到的泄漏。

**重试的判据**（`callWithRetry`, `:409`）不是"错误严不严重"，是"**有没有碰到工具**"：

```java
if (cause instanceof ToolTransportException te && !te.retryable()) throw last;
```

`ToolTransportException.Kind`：`NOT_FOUND / UNREACHABLE`（没碰到，可重试）vs `REMOTE_TIMEOUT / REMOTE_FAILED`（碰到了，结果未知，**不可重试**——远端可能已扣款）。`HttpToolTransport.classifyIoFailure` 专门提成纯函数单测，因为这是全类最容易弄反、后果最严重的判断。

**传输**：`transports` 是 `Map<Protocol, ToolTransport>`。`in-process` → [InProcessToolTransport](../../spark-rooter/spark-rooter-gateway/src/main/java/com/sparkrooter/gateway/infra/transport/InProcessToolTransport.java) 查 handler 表反射调用；`http` → [HttpToolTransport](../../spark-rooter/spark-rooter-gateway/src/main/java/com/sparkrooter/gateway/infra/transport/HttpToolTransport.java) `POST {baseUrl}/spark/tools/invoke`，带 `ProviderAuth` 令牌头和 traceId。

### 2.6 反射调用你的方法

hub 怎么知道有哪些工具？[SparkToolScanner](../../spark-rooter/spark-rooter-spring-boot-starter/src/main/java/com/sparkrooter/starter/tool/SparkToolScanner.java) 在 `SmartInitializingSingleton` 阶段（所有单例就位、`ApplicationReadyEvent` 之前）遍历全部 Bean：

```java
Class<?> target = AopUtils.getTargetClass(bean);           // 穿透代理拿真实类
for (Method m : target.getMethods()) {
  SparkTool tool = m.getAnnotation(SparkTool.class);
  if (tool == null) continue;
  Derived d = deriver.derive(m, params[0], m.getReturnType());   // 注解 → Manifest JSON + ToolMeta
  meta.register(d.meta());
  register.execute(d.manifest());                          // 过 tool-manifest 契约后进 Registry
  Method invocable = AopUtils.selectInvocableMethod(specific, proxy.getClass());
  transport.register(new AnnotatedToolHandler(id, version, proxy, invocable, params[0], ...));
}
```

**`proxy` 而不是 `bean`**：调用时必须走 Spring 代理，宿主的 `@Aspect` / `@PreAuthorize` 才会触发。这是「权限用方法级切面」能成立的原因——Controller 拦截器拦不住 spark，因为 spark 不经 Controller；但 AOP 切在方法上，反射调代理照样切。示例 [DemoRoleAspect](../../spark-rooter/examples/host-demo/src/main/java/com/example/demo/DemoRoleAspect.java) 切 `OrderTools.delete`，guest 用户说「删除订单」→ 切面抛异常 → Gateway `HANDLER_ERROR` → `run.failed`。

**Manifest 推导**（[ManifestDeriver.derive](../../spark-rooter/spark-rooter-contracts/src/main/java/com/sparkrooter/contracts/tool/ManifestDeriver.java)）：入参 record 的每个组件，标了 `@SparkParam` 才进 `inputSchema.properties`；`Optional<T>` 或有 `@SparkDefault` 的不进 `required`；`@SparkRisk` 填 `risk` 和 `execution`；出参 record 全部字段进 `outputSchema`。推导完立刻 `assertValid("tool-manifest")`——注解写错启动就挂，不会到运行时才发现。

**同一份推导代码 hub 与 provider 共用**（`ManifestDeriver` 在 contracts 模块），保证单体和微服务形态推出的 Manifest 逐字段相同。`ManifestParitySelfCheck` 启动自检还会拿手写的 12 个示例 Manifest 与推导结果比对。

### 2.7 需确认步骤：令牌 → 确认屏 → 断连

到 `refund.create` 时 `waitForConfirmation`：

```java
Map<String, JsonNode> cache = /* 前两步的输出 */;
UiSchema probe = screens.confirmation(toolId, args, cache, PLACEHOLDER_TOKEN, ctx);   // 第一遍：拿 actionId 与 Form 字段
String actionId = ScreenRegistry.submitActionId(probe);
Set<String> formKeys = ScreenRegistry.formKeys(probe);
ConfirmationToken token = tokens.issue(runId, actionId, seq, argsDigest, conversationId, sessionId, formKeys);
UiSchema ui = screens.confirmation(toolId, args, cache, token.token(), ctx);           // 第二遍：真令牌进屏
run.setCurrentUi(json(ui)); emit(UI_REPLACE, ui); emit(CONFIRMATION_REQUIRED, actionId, expiresAt);
run.transition(WAITING_CONFIRMATION); runs.save(run); sink.close();                    // 第一条 SSE 结束
```

**两遍生成**是因为令牌要绑 `actionId`，而 `actionId` 在屏里；先用占位令牌读出屏的结构，再签真令牌重出一遍。

**令牌**（[ConfirmationTokenService](../../spark-rooter/spark-rooter-runtime/src/main/java/com/sparkrooter/runtime/application/ConfirmationTokenService.java)）：24 字节 `SecureRandom` + Base64URL，绑 7 样东西：`runId / actionId / stepSeq / argsDigest / conversationId / sessionId / allowedFormKeys`，10 分钟过期，一次性。

**确认屏由领域出**（[RefundScreens.confirmation](../../spark-rooter/examples/domains/refund-service/src/main/java/com/sparkrooter/examples/refund/infra/screen/RefundScreens.java)）：从前两步输出里取商品名、金额、状态拼 Card，加一个 `Form{reason}`，`submit` 动作带令牌。runtime 不知道退款屏长什么样——它只知道要调 `ScreenBuilder.confirmation`。缺这个 Builder，启动自检 `ConfirmationCoverageSelfCheck` 直接失败。

### 2.8 前端：渲染确认屏

`ui.replace` 事件到达 → `reduceEvent` 把 `turn.ui` 换成新屏 → `SchemaRenderer` 重渲染。

[SchemaRenderer](../../spark-ui/packages/core/src/renderer/SchemaRenderer.tsx)：

```tsx
{ui.components.map((c) => {
  const Comp = registry[c.type];                        // 白名单查表；查不到 → UnknownComponent 占位
  const parsed = PROPS_SCHEMAS[c.type].safeParse(c.props); // 每个组件 props 再过一次 Zod
  return <Suspense><Comp id={c.id} props={parsed.data} handlers={handlers} /></Suspense>;
})}
```

[componentRegistry](../../spark-ui/packages/core/src/registry/componentRegistry.ts) 是 `Object.freeze` 的 5 键对象，每个值是 `React.lazy(() => import('../components/desktop/Form'))`——**路径写死**，不按模型输出拼。`check-registry.mjs` 门禁保证键集合 == 契约 `componentType` enum。这就是「不执行模型生成代码」的全部实现：模型只能产出 5 个名字里的一个和一坨过 Zod 的 props。

`ActionBar` 渲染 `ui.actions`——`submit` 按钮的 `confirmationToken` 是不透明字符串，前端不解析。

### 2.9 用户点确认：第二条 SSE

`submitAction.mutate(action)`（`useSparkRun.ts:154`）先本地校验 Form 必填——**缺字段不发请求**，因为令牌一次性，发了就废。然后 `POST /agent/runs/{runId}/actions/confirm-refund`，body `{confirmationToken, formData: {reason: "DAMAGED"}}`。

后端 [RunOrchestrator.confirm](../../spark-rooter/spark-rooter-runtime/src/main/java/com/sparkrooter/runtime/application/RunOrchestrator.java) `:221`：

```java
Run run = runs.find(runId);                                 // 可能在另一个副本——凭仓储重建一切
if (run.state() != WAITING_CONFIRMATION) rejectRequest(...);  // 前置校验只拒本次请求，不改 Run
if (!run.sessionId().equals(sessionId)) rejectRequest(...);
token = tokens.consume(rawToken, runId, actionId, argsDigest(step.fixedArgs()), conversationId, sessionId, formData);
                                                             // ↑ 原子取出并删除；7 项任一不符 → CONFIRMATION_REJECTED
run.transition(EXECUTING); runs.save(run);                   // 令牌已消费，此后失败才把 Run 置 FAILED
executeConfirmed(run, step, token, formData, traceId, sink);
```

**并发确认的互斥完全押在 `consume` 的原子性上**：内存 `ConcurrentHashMap.remove`、Redis `GETDEL`。32 个线程同时点，恰好 1 个拿到令牌，其余 31 个 `TokenUnknown` → 拒绝且不改 Run。没有实例内的锁，所以多副本下也成立。

**`executeConfirmed`**：

1. 找领域的 `ConfirmationRecheck`（缺 → fail-closed `INTERNAL_ERROR`）
2. 经 Gateway **重调** `refund.eligibility.check`——确认屏是 10 分钟前出的，订单状态可能变了
3. `rc.reject(recheckOutput, shownUi)`：领域判定。[RefundRecheck](../../spark-rooter/examples/domains/refund-service/src/main/java/com/sparkrooter/examples/refund/infra/screen/RefundRecheck.java) 比对重查的 `refundableAmount` 与确认屏 Card 上展示的金额，不一致 → 拒绝
4. `rc.trustedArgs(recheck)` 覆盖 `formData`：**金额只信重校验结果**，前端传的 `amount: "0.01"` 被覆盖——e2e 有这条注入断言
5. 执行 `refund.create`，出结果屏，`complete`

### 2.10 终态

`complete`：`transition(COMPLETED)` → `clearStepOutputs`（前置输出无人再读）→ `runs.save` → `remember`（写会话记忆：领域、实体、列表行 ID）→ `emit(run.completed)` → `sink.close()`。

**顺序**：先 save 再 remember。记忆写失败不该让共享存储里的 Run 停在 EXECUTING——这是只在 Redis 下才暴露的 bug，内存版对象引用掩盖了它。

前端收到 `run.completed` → `turn.status = 'completed'` → `ActionBar` 消失，结果屏留着。

---

## 3. 状态与多副本

hub 有四份运行时状态，每份一个 `domain/` 接口 + 两个实现：

| 端口 | 内存实现 | Redis 实现 | 淘汰 |
|---|---|---|---|
| `RunRepository` | `ConcurrentHashMap`，`evictExpired` 按 `updatedAt + runTtl` | `spark:run:{id}` JSON，`PX runTtl` | 每次终态顺手扫 / Redis TTL |
| `ConfirmationTokenStore` | `put` 时 `removeIf(expired)` | `spark:token:{t}`，`PX` 到 `expiresAt`，`GETDEL` | 写入时清 / TTL |
| `IdempotencyStore` | `Slot{future, completedAt}`，每 64 次 `complete` 扫一遍 | `SET NX PX` 值 `CLAIMING` → 响应 JSON | 按 `idempotency-ttl`（24h） |
| `ConversationMemory` | `put` 时清过期 | `spark:memory:{s}/{c}`，`PX memoryTtl` | 写入时清 / TTL |

**Run 自包含**是多副本的前提。`Run` 聚合里带 `currentUi / stepOutputs / stepSchemas / clarified`——确认所需的一切。编排器**不持有**按 runId 的进程内缓存，第二个副本凭 `runs.find(runId)` 就能完成确认。`RunOrchestratorConfirmTest.confirmOnAnotherReplicaSucceedsWithSharedRepository` 用两个编排器实例只共享仓储证明这条。

**`Run` 里三个 JSON 字段存字符串**：`domain/` 包不依赖 Jackson（红线），转换在编排器做。

**Redis 不降级**：连不上就 500，Spring Boot 自带的 `RedisHealthIndicator` 把 health 置 DOWN。宁可拒绝服务，不要两个副本各持一份状态却以为在共享。

**Redis `RunSnapshot` 刻意不带 `Run.message`**——用户原话不进共享存储，与日志红线同一口径。这条是 e2e 第 8 条断言当场抓到的。

---

## 4. 微服务形态：provider

领域服务作为独立进程时，引 `spark-provider-spring-boot-starter`（JDK 17）：

```
provider 启动
  ProviderToolScanner   扫 @SparkTool → ManifestDeriver.derive（同一份代码）→ protocol=http + provider{serviceName, baseUrl}
  ManifestPublisher     ApplicationReadyEvent 后 POST {hubUrl}/internal/tool-registry/tools，带 ProviderAuth 令牌头
hub
  RegistrationGuard     令牌校验（MessageDigest.isEqual 常量时间），令牌与 serviceName 绑定——A 不能冒充 B 注册工具
  RegisterToolUseCase   过契约 → ConfirmationCoveragePolicy（需确认工具必须有屏和重校验）→ 进 Registry
运行时
  hub Gateway           protocol=http → HttpToolTransport → POST {baseUrl}/spark/tools/invoke
  provider              ProviderInvokeController：验令牌 → 幂等去重（provider 侧第二层）→ 反射调 handler → 脱敏 → 返回
```

provider **不含** Runtime / Registry / Gateway / LLM 客户端，pom 被门禁锁死不许依赖它们。它只做「声明工具 + 执行工具」。规划、校验、策略、审计全在 hub——provider 重复一遍既无必要也会掩盖 hub 侧的问题。

provider 侧两件 hub 做不到的事：**幂等去重**（hub 的 claim 覆盖不到网络重传）、**发送端脱敏**（hub 收到时数据已过网络、已进 provider 日志）。

---

## 5. 自保与可观测

| 机制 | 位置 | 防什么 |
|---|---|---|
| 有界线程池 + `AbortPolicy` | `NamedThreads.boundedPool` | 过载时任务无声堆积到 OOM |
| `MAX_PLAN_STEPS = 6` | `PlanValidator` | 模型规划几十步 |
| `SessionConcurrencyLimiter`（CAS 循环） | Gateway 3b | 单会话只读查询洪水占满整池 |
| `LlmCircuitBreaker` | `LlmPlanner` 前 | 模型网关挂掉时每请求各等 90s |
| 断连提前终止 | `runSteps` | 用户刷新后白烧只读步骤 |
| readiness 指示器 | `SparkReadinessHealthIndicator` | 模型没配的 Pod 接流量 |
| 7 个 Micrometer 指标 | `MicrometerMetricsSinks` | 标签硬编码低基数，不含任何 ID |
| 审计 / 埋点失败不抛 | `recordAudit` / `recordToolMetrics` | 监控故障拖垮业务 |

**熔断器为什么不影响 readiness**：`LlmCircuitBreaker` 的 OPEN → HALF_OPEN 转换只在真实请求调 `shouldSkip()` 时发生。熔断即摘流量 → 无流量 → 无探测 → 永不恢复。所以 readiness 只反映静态不可用（模型没配），熔断只进 detail。

---

## 6. Harness：让错误不再发生的那一层

`.harness/` 不是文档目录，是**可执行的工程规则**。

### 6.1 一条命令的门禁

`pnpm -C .harness run ci` 9 步，任一非 0 即失败：

| 步 | 守什么 | 怎么守 |
|---|---|---|
| check-rename | 改名残留 | grep 旧名 |
| check-contracts | 9 契约合法、28 示例通过、invalid 示例被拒、后端副本一致 | ajv 2020 strict |
| check-module-deps | 依赖方向、平台模块禁业务词 / 身份词 / 组件注解、JDK 17 字节码、Redis 不倒灌、examples 不回顶层 | 读 pom + 源码正则 + class 文件头 |
| check-seed | 种子数据 DDL / 外键 / 金额 / 状态一致 | 6 张表交叉校验 |
| check-log-assertions | e2e 里 grep 的日志片段在源码里真的存在 | 收集全部字符串字面量比对 |
| check-shell | 7 个 bash 脚本 | shellcheck |
| spark-ui | typecheck / 107 单测 / lint / format / 契约示例 / 传输层 / 构建 / 打包校验 | 前端 ci 链 |
| spark-rooter | 编译 / 275 单测 / spotless | `mvnw install` |
| host-demo | 离线打包（证明只依赖本地仓） | `mvn -o package` |

每条规则都带**双向自证**：写规则时故意制造一次违规看它红，再恢复看它绿。`check-module-deps.mjs` 末尾还有 `selfTests` 数组——规则正则漂了它自己先红。

### 6.2 8 阶段流程

```
1 需求分析 → 2 需求评审 → 3 编码 → 4 编码评审 → 5 推送 → 6 CI → 7 部署验证 → 8 用户确认
```

每个需求一个 `changes/<type>-<name>-<date>/` 目录，`summary.md` 阶段表每步 DONE 才能到下一步。`harness-doctor` 检查每个 change 有 summary、TODO 不超龄。评审阶段要求独立性（评审者不看编码者的自评），做不到时必须在文件头声明。

`change-dir.mjs` 决定 e2e 产物落哪个目录：恰一个非 DONE 的 change 时自动选，多个时必须 `SPARK_CHANGE=` 显式指定——CI 上就是这么配的。

### 6.3 Hashimoto 法则

`CLAUDE.md` 的最后一节。每发现一个错误，首要动作不是修代码，而是改 Harness 让它再也发生不了。本轮几个实例：

| 错误 | 沉淀成 |
|---|---|
| `verify-pack` 的 regex 漏了 `import "react";` 这种无 `from` 的形式 | 换 `es-module-lexer` 真解析 |
| `@ConditionalOnBean` 在 `@Import` 的配置类上静默失效 | `backend-standard.md` 写死「用 `ObjectProvider`」+ 实测记录 |
| mvnw 指向公司内网 Nexus，本机镜像掩盖了三周 | `harness-doctor` 新规则：10 个构建配置文件禁内网域名 |
| e2e 与 deploy-verify 并行跑共用 `backend.log` 互相截断 | `deploy-verify` skill 注意事项 |
| 压测报告首版有 1022s 假延迟（本机端口耗尽） | 量具加客户端硬超时 + `wall` 实测耗时 |

每条都在 `summary.md` 的「经验沉淀」表里，写明错误和去向。

---

## 7. 读代码的建议顺序

1. `.harness/contracts/sse-events.schema.json` + `ui-schema.schema.json`——先知道两端说什么话
2. `spark-ui/packages/core/src/client/{sse,runView,runStore}.ts`——300 行，前端全部核心
3. `RunOrchestrator.start` → `runSteps` → `waitForConfirmation` → `confirm` → `executeConfirmed`——按调用顺序读
4. `PlanValidator.decide`——安全模型在这 100 行里
5. `InvokeToolUseCase.pipeline` → `callWithRetry`——八道工序
6. `SparkToolScanner` + `ManifestDeriver`——注解怎么变成一切
7. `examples/domains/refund-service/infra/{RefundTools,screen/RefundScreens,screen/RefundRecheck}`——一个完整领域怎么接
8. `.harness/scripts/check-module-deps.mjs`——看规则怎么写成代码

每一处 `// 评审 X-N` 注释都指向 `changes/*/coding/code_review_*.md` 里的一条发现，那里有"为什么这样改"的完整推理。
