# Code Review v2 — feat-agent-tool-platform-20260903

- **mode**: execution（独立评审，未阅读 coding_report_v1.md / code_review_v1.md）
- **date**: 2026-09-04
- **reviewer**: expert-reviewer（有罪推定，逐项证伪）
- **评审范围**
  - 规格：`request_analysis/spec.md` v3.1（§3 / §4 / §5 / §6）
  - 规则：`coding-standard.md`、`backend-standard.md`、`contracts.md`、`agent-safety.md`、`project-structure.md`
  - 契约：`.harness/contracts/*.schema.json`（9）+ `examples/`（20）
  - 后端：`backed/{platform-spi,contracts-java,tool-registry,tool-gateway,agent-runtime,domains/*,app}/src/main/**`、`application.yml`、6 份 tool-manifest
  - 前端：`fronted/src/**`、`fronted/scripts/*`、`.oxlintrc.json`、`vite.config.ts`、`tsconfig.app.json`
  - Harness：`.harness/scripts/{e2e-backend.sh,e2e-frontend.mjs,sse-parse.mjs,check-module-deps.mjs}`
- **本地实际执行**（仓库根）：`pnpm -C .harness run check-contracts` → `9 schemas OK`；`node .harness/scripts/check-module-deps.mjs` → OK；`node fronted/scripts/check-registry.mjs` → 7 types consistent；`node fronted/scripts/check-deps.mjs` → OK。未重新构建 / 启动后端（以 `deployment/` 既有产物做交叉比对）。

分级：**MUST FIX** = 违反 L1 硬约束 / 安全边界被绕过 / 契约不一致 / 会导致错误行为；**SHOULD** = 明确风险但不阻塞；**LOW / INFO** = 可读性、建议、记录。

---

## 0. 结论摘要

| 分级 | 条数 |
|---|---|
| MUST FIX | 3 |
| SHOULD | 12 |
| LOW | 11 |
| INFO | 7 |

**verdict：REVISION REQUIRED**（3 MUST FIX）。

---

## 1. MUST FIX

### M1. 入站 HTTP 请求体未按契约 Schema 校验，契约约束在后端边界大面积失效

- **位置**
  - `backed/agent-runtime/src/main/java/com/strato/runtime/api/AgentRunController.java:54-70`（`start`）、`:72-92`（`confirm`）
  - `backed/tool-gateway/src/main/java/com/strato/gateway/api/ToolGatewayController.java:26-35`
  - `backed/tool-registry/src/main/java/com/strato/registry/api/ToolRegistryController.java:49-53`（`search`）
  - `backed/contracts-java/src/main/java/com/strato/contracts/model/IntentRequest.java:9-23`、`ActionRequest.java:10-11`
  - 全仓 `assertValid` 调用只有 3 处：`RegisterToolUseCase.java:28`（tool-manifest）、`UiSchemaBuilder.java:128`（ui-schema）、`RunOrchestrator.java:423`（sse-events）——**没有一处**校验 `intent-request` / `action-request` / `tool-invoke.request` / `tool-search.request`。
- **问题**（观察）
  - 三个 Controller 只用 `@Valid` + `@NotBlank/@NotNull`。Spring Boot 默认 `FAIL_ON_UNKNOWN_PROPERTIES=false`（仓库无任何 Jackson 严格化配置，`grep -rn "fail-on-unknown\|FAIL_ON_UNKNOWN" backed` 为空），因此契约里的 `additionalProperties:false`、`uiSchemaVersion const "1.0"`、`pageContext.page pattern`、`conversationId maxLength 64`、`message maxLength 2000`、`components minItems 1 / uniqueItems`、`confirmationToken minLength 16`、`formData maxProperties 16 / propertyNames pattern / 只允许标量值`、`tool-invoke.executionContext.runId ^run_ / toolCallId ^tc_`、`tool-search.domain pattern` 等约束在后端**全部不生效**。
  - `ActionRequest.formData` 类型为 `Map<String,Object>`，嵌套对象可通过；`RunOrchestrator.java:207` 用 `String.valueOf(v)` 把它拼成 `"{a=1}"` 交给 Gateway。
  - `RuntimeExceptionHandler.java:34-45` 与 `RegistryExceptionHandler.java:29-40` 都写了 `ContractViolationException → 400 REQUEST_INVALID` 的映射，但对请求体而言这条路径永远不会被触发（死代码），说明设计意图存在而实现缺位。
- **依据**：`contracts.md §1`「后端 = 投影，字段与约束必须与 Schema 一致」；`backend-standard.md §3`「入站请求体：校验失败返回 400 + ErrorResponse（networknt）」；CLAUDE.md 硬约束「跨边界数据按 `.harness/contracts/` Schema 校验」。
- **建议**：在三处 Controller（或一个 `HandlerMethodArgumentResolver` / `RequestBodyAdvice`）对原始 `JsonNode` 先 `validator.assertValid("<contract>", node)` 再 `convertValue` 为 record；或至少全局开启 `spring.jackson.deserialization.fail-on-unknown-properties=true` 并补齐 Jakarta 注解（`@Size`、`@Pattern`）。以 e2e 加一条反例：`POST /agent/runs` 带 `"extra":1` 或 `formData:{"reason":{"x":1}}` 必须 400。

### M2. 确认阶段并发 / 重放会把执行中的 Run 打成 FAILED（成功退款被报告为失败）

- **位置**：`backed/agent-runtime/src/main/java/com/strato/runtime/application/RunOrchestrator.java:178-182`（状态检查）、`:190-191`（consume）、`:194`（transition EXECUTING）、`:392-397`（`fail()`：`if (!run.state().terminal()) run.fail(...)`）；`domain/Run.java` 全类无同步。
- **问题**（观察）：`Run` 是可变对象且无锁；`confirm()` 中先 `tokens.consume` 再 `transition(EXECUTING)`。设两次 `POST .../actions/confirm-refund` 并发到达（双击、网络重试）：线程 A 消费令牌进入执行；线程 B 在 A 执行期间到达，要么因 `state != WAITING_CONFIRMATION` 抛 `RunFailure`（:178），要么因令牌已消费抛 `RunFailure`（:190）——两种情况都进入 `fail(run, e, sink)`，此时 `run.state()==EXECUTING` 非终态，于是 `run.fail(code)` 把**同一个** Run 置为 FAILED。随后 A 调 `run.advance()`、`complete()` → `transition(COMPLETED)` 从 FAILED 非法 → `IllegalStateException` → 被 `catch (RuntimeException)` 转为 `INTERNAL_ERROR` 再 `fail()`。结果：`refund.create` 已成功（审计行 succeeded、内存已有退款单），但 A 的 SSE 发 `run.failed`，`GET /agent/runs/{id}` 返回 `FAILED`。
- **依据**：`backend-standard.md §6`「Run 状态机迁移必须是幂等的，同一事件重放不产生第二次副作用」——副作用没有重复，但状态面被重放破坏，用户与 RunSummary 得到错误结论。属"会导致错误行为"。
- **建议**：(a) 因令牌 / 状态不匹配而拒绝的确认**不应改变 Run 状态**（只向该 SSE 发 `run.failed{CONFIRMATION_REJECTED}` 并关闭），仅在"令牌有效但重校验失败"时才把 Run 置 FAILED；(b) 对 `confirm()` 按 `runId` 加互斥（`ConcurrentHashMap<String, ReentrantLock>` 或 `synchronized(run)`）；(c) e2e 增加"并发两次确认"用例，断言 summary 为 COMPLETED 且仅 1 条退款。

### M3. 确认后执行的金额可能不是用户看到并确认的金额（计划内 `amount` 优先于试算结果）

- **位置**：`backed/agent-runtime/src/main/java/com/strato/runtime/application/RunOrchestrator.java:206-211`

  ```java
  Map<String, String> args = new LinkedHashMap<>(step.fixedArgs());
  formData.forEach((k, v) -> args.put(k, String.valueOf(v)));
  if (!args.containsKey("amount") && cache.containsKey("refund.preview")) {
    args.put("amount", cache.get("refund.preview").path("amount").asText());
  }
  ```

  以及 `infra/llm/ToolSelectionValidator.java:40-47`（只检查 `args` 键在 `inputSchema.properties` 内，`refund.create.inputSchema` 声明了 `amount`，因此模型可以给 `amount` 赋任意值）；`UiSchemaBuilder.java:48`（确认卡展示的是 `preview.amount`）。
- **问题**（观察）：使用 `SpringAiLlmClient` 时，模型合法地产出 `refund.create{orderId, amount:"1.00", reason:"..."}`，校验器接受；确认屏 `RefundConfirmCard` 显示的是试算金额 `128.00`；用户点击确认后，`args.containsKey("amount")` 为 true，实际执行的是模型给的 `1.00`。`RefundService.create` 只拒绝 `amount > refundable`（`RefundService.java:58`），小于可退金额的任意值都会被执行。用户确认的内容与执行内容不一致，且 `argsDigest` 恰好"保护"了这个被篡改的计划。用规则规划器（无 key）时不会触发，但规则规划器是回退路径而非目标路径。
- **依据**：`agent-safety.md §3`「用户确认时，后端用 Token 找回原始计划，重新校验：权限、**金额**、订单状态…」；spec §4.1「Gateway(refund.create, {orderId,"amount":"128.00",reason})」明确金额来自试算。
- **建议**：确认后的有副作用参数（至少 `amount`）**一律**以后端可信来源（重校验 / 试算结果）覆盖，忽略计划中的同名值；或在 `ToolSelectionValidator` 中对 `requiresConfirmation` 步骤禁止模型填写 `amount` 一类"金额 / 数量"字段（白名单只允许 `orderId`）。同时把"确认屏展示值 == 执行值"作为 e2e 断言（比对 `RefundConfirmCard.props.amount` 与 `refund.create` 审计 / `refund.status.get` 结果）。

---

## 2. SHOULD

### S1. Runtime 进程内适配依赖对方模块 `application` 包而非 `api` 包
- **位置**：`agent-runtime/.../infra/inprocess/InProcessToolGatewayClient.java:4`（`import com.strato.gateway.application.InvokeToolUseCase`）、`InProcessToolRegistryClient.java:4`（`com.strato.registry.application.SearchToolsUseCase`）。
- **问题**：spec §2.2 与 `backend-standard.md §4` 都写"直接调用对方模块 `api` 包公开用例接口 / 跨模块只依赖对方的 api 包"。当前依赖的是 `application` 包实现类（非接口）。`check-module-deps` 只看 pom，检测不到包级别越界。
- **建议**：在 gateway / registry 的 `api` 包暴露接口（如 `ToolInvokePort`），runtime 只依赖接口；或修订规则措辞并把包级依赖检查加进 `check-module-deps`。

### S2. `application` 层反向依赖 `infra` 层，且 `displayName` 为硬编码副本
- **位置**：`RunOrchestrator.java:306`（`com.strato.runtime.infra.llm.LlmConfiguration.displayName(toolId)`）；`LlmConfiguration.java:24-31` `DISPLAY_NAMES` 硬编码 6 个名称。
- **问题**：application → infra 反向依赖；`tool.selected.displayName` 契约注释和 `tool-manifest.name` 描述都说明来源应是 Manifest `name`。新增工具需同步改 Java 常量，否则 `displayName` 回落为 toolId。
- **建议**：`ToolSearch.ToolCandidate` 受六字段限制，可让 Registry 端口新增 `displayNameOf(toolId@version)`（不进候选集，不给模型）或在 Plan 阶段由 `ToolResolver` 取 Manifest `name`。

### S3. Gateway 失败经 HTTP 映射为 502 / INTERNAL_ERROR，`tool-invoke.response.failed` 形态从未产出
- **位置**：`GatewayExceptionHandler.java:34-35`（`TIMEOUT, HANDLER_ERROR, OUTPUT_INVALID → BAD_GATEWAY + INTERNAL_ERROR`）；`InvokeToolUseCase.java:100-113`（失败一律 `throw`）；`ToolInvoke.java:41-45`（`Response.failed` 无调用方，死代码）。
- **问题**：spec §4.2 HTTP 表无 502；契约 `tool-invoke.response` 定义了 `status=failed + error{code∈6 值}`，实现永不返回该形态，前后两份"失败语义"并存。
- **建议**：二选一并同步 spec：要么 `execute()` 返回 `Response.failed(...)`（HTTP 200 + 契约 failed 体，Runtime 按 `error.code` 映射），要么删除契约中的 failed 分支并把 502 写进 §4.2。

### S4. Runtime 依赖 Gateway 异常**消息文本**来区分 `TOOL_OUTPUT_INVALID`
- **位置**：`RunOrchestrator.java:355-360`：`e.getClass().getSimpleName().contains("Gateway") && e.getMessage().contains("outputSchema")`。
- **问题**：字符串嗅探，改一个日志文案就会把 `TOOL_OUTPUT_INVALID` 静默降级为 `TOOL_EXECUTION_FAILED`；也说明端口 `ToolGatewayClient` 的失败通道设计不足（见 S3）。
- **建议**：端口返回结构化 `error.code`，Runtime 用 `switch` 映射。

### S5. 「权限不足 → CONFIRMATION_REJECTED」未实现
- **位置**：`RunOrchestrator.confirm` `:183-185` 只比对 principal 相等；权限实际在 Gateway 校验，失败后经 `invoke()` `:348-364` 变成 `TOOL_EXECUTION_FAILED`。
- **问题**：spec §4.1「校验 … 权限」与 §4.2「权限不足 → CONFIRMATION_REJECTED」。若确认时权限已被回收，用户看到的是"工具调用失败"而非"确认被拒"。
- **建议**：确认时先经 `PrincipalPermissionResolver`（或 Registry 端口）校验 `refund:create`，不通过直接 `CONFIRMATION_REJECTED`。

### S6. Gateway 超时不取消任务，且幂等 `putIfAbsent` 在执行之后
- **位置**：`InvokeToolUseCase.java:196-198`（`f.get(timeoutMs)` 超时后未 `f.cancel(true)`）；`:152-159` 与 `:186`（查 → 执行 → 写）。
- **问题**：超时后 handler 线程继续占用 `gateway-tool-*` 线程池（16 个）；同租户同 `idempotencyKey` 并发到达会都执行 handler（首期靠 `RefundService` 自身幂等兜底，其他 side-effect 工具无此保障）。
- **建议**：超时 `cancel(true)`；幂等改为"先占位再执行"（`putIfAbsent` 占位 + 完成后填充 / 失败回收）。

### S7. 非法 JSON 请求体 → 500 而非 400
- **位置**：`RuntimeExceptionHandler.java:63-69`、`GatewayExceptionHandler.java:57-63`、`RegistryExceptionHandler.java:58-64` 均无 `HttpMessageNotReadableException` 处理。
- **问题**：`curl -d '{bad json'` 会落到 `Exception` 兜底 → 500 `INTERNAL_ERROR` 并打 ERROR 堆栈；spec §4.2 要求 400 `REQUEST_INVALID`。
- **建议**：三处 advice 增加 `HttpMessageNotReadableException → 400 REQUEST_INVALID`。

### S8. 前端：SSE 流在无终态事件时结束，UI 永久停留在 `streaming`
- **位置**：`fronted/src/features/agent-chat/api/useAgentRun.ts:55-66`（`stream`）、`model/runView.ts:44-110`（无"流结束"归约）、`ui/AgentChatPanel.tsx:52-53`（错误仅来自 mutation error）。
- **问题**：`consumeSse` 正常 `done` 但最后一帧不是 `run.completed / run.failed / confirmation.required`（服务端 5 分钟 `SseEmitter` 超时、代理断流、或 `run.failed` 帧本身没通过 Zod 被 `apply` 丢弃 `:40-46`），mutation 成功返回，`busy=false`，工具条目停在"执行中…"，无任何错误提示。
- **建议**：`stream()` 结束后检查 view.phase 仍为 `streaming` 则置 `failed{INTERNAL_ERROR,"连接中断"}`；被契约拒绝的帧若 `event` 为终态名也应触发同样处理。

### S9. 前端：确认提交不校验必填字段，空 `formData` 直接烧掉一次性令牌
- **位置**：`useAgentRun.ts:28`（`formRef` 初值 `{}`）、`:96-99`（直接提交）；`shared/ui/generate/desktop/Form.tsx:24` 的 `rules: required` 只在 antd Form 内生效，`ActionBar` 与 Form 无联动。
- **问题**：用户不选原因直接点"确认退款" → 后端 `refund.create` 缺 `reason` → Gateway `INPUT_INVALID` → `TOOL_EXECUTION_FAILED`，令牌已消费、Run 终态 FAILED，用户只能重新发起整条链路。
- **建议**：提交前按 `ui.components` 中 `Form.props.fields[].required` 校验 `formRef`，缺失则本地提示不发请求。

### S10. 前端：全局缺 `<ErrorBoundary>`
- **位置**：`fronted/src/app/router/router.tsx:9-17`（无 `errorElement` / `ErrorBoundary`）；`grep -rn "ErrorBoundary\|errorElement" fronted/src` 为空。
- **问题**：`coding-standard.md §6`「使用 `<ErrorBoundary>`（在路由级 / 关键 feature 顶层）」。`React.lazy` chunk 加载失败或封装组件渲染抛错会白屏。
- **建议**：`RootLayout` 路由加 `errorElement`，`SchemaRenderer` 外包一层 feature 级 ErrorBoundary。

### S11. `check-module-deps.mjs` 可被轻易绕过
- **位置**：`.harness/scripts/check-module-deps.mjs:72`（只匹配 `^import\s+org\.springframework\.`）、`:76-77`（只检查直接父目录为 `domain` 的文件）、`:53-55`（pom 只做 `<artifactId>id</artifactId>` 字面匹配）。
- **问题**：(a) `domain/policy/Foo.java` 之类子包不被检查；(b) 用全限定名 `org.springframework.stereotype.Component` 注解、不写 import 即可绕过；(c) pom 用属性 `${domain.artifact}` 或标签内换行即可绕过。另外 spec §6.2 的原文命令 `grep -rln "org.springframework\|com.fasterxml" backed | grep "/domain/"` 因领域模块基础包为 `com.strato.domain.*`，实际输出 5 个 `infra` 文件（本次评审已运行验证），**spec 字面验收项不可满足**，脚本通过收窄"直接父目录"规避了它。
- **建议**：脚本改为"路径中任一段为 `domain` 且位于 `com/strato/<module>/` 之后"的分层判定 + 全文匹配 `org\.springframework\.` / `com\.fasterxml\.`；pom 用 XML 解析；修订 spec §6.2 该条措辞（或把领域模块基础包改为 `com.strato.domains.*`）。

### S12. e2e-backend.sh 未覆盖 spec §6.2 第 5–7 条
- **位置**：`.harness/scripts/e2e-backend.sh:2`（自述"第 8–15 条"）。
- **问题**：Registry search（user_001 → 4、user_002 → 3）、HTTP 重复注册 → 409、Gateway 缺 `orderId` → 400、user_002 调 `refund.create` → 403 这四条验收没有脚本化，只能靠人工命令 / 报告，违背 §7「验收全部命令化」的缓解措施。脚本其他断言均非恒真（`json()` 解析失败会得到空串从而红灯，已核对）。
- **建议**：补 4 条 `check`。

---

## 3. LOW

- **L1** `RunOrchestrator.java:429`：`argsDigest` 用 `TreeMap.toString()`（`{orderId=10001}`）而非规范化 JSON；Gateway `InvokeToolUseCase.java:84` 用 `req.arguments().toString()`（未按键排序）。两处"argsDigest"定义不同且都不是 spec 所述"规范化 JSON 的 SHA-256"。建议共用一个 `Canonical JSON` 工具。
- **L2** `ToolSelectionValidator.java:30-32`：`Collectors.toMap` 遇同 toolId 多版本候选会抛 `IllegalStateException` → `INTERNAL_ERROR`，应显式选版本或 `toMap(..., (a,b)->a)`。
- **L3** `RunOrchestrator.java:68-72, 227`：`lastUi`、`stepOutputs`（仅在 confirm 的 finally 清理，start 失败 / 无能力路径 / 永不确认的 Run 都泄漏）、`InMemoryRunRepository`、`InMemoryConfirmationTokenStore`（过期令牌不清理）均无淘汰。首期内存可接受，建议加 TTL 清理。
- **L4** `AgentRunController.java:83`：confirm 的 404 预检不比对 principal，他人可探测 runId 存在性（`get()` `:101-103` 做了隐藏）。
- **L5** `ToolRegistryController.java:34, 56-59`：Controller 直接注入并调用 `domain` 端口 `ToolRegistryRepository`，绕过 application 层。
- **L6** `SchemaValidator.java:97-101`：`validateWithInlineSchema` 每次调用重新编译 Schema；Gateway 每次调用两次。可按 `toolId@version` 缓存。
- **L7** `fronted/src/shared/ui/generate/SchemaRenderer.tsx:35-36, 43`：`registry[type]` / `PROPS_SCHEMAS[type]` 未用 `Object.hasOwn` 守卫，`type="constructor"` 会取到 `Object.prototype.constructor` 并在 `schema.safeParse` 处抛 TypeError。契约路径下被 Zod enum 拦截，仅 playground 可达，属纵深防御。`:53` `parsed.data as never` 为内部强转，建议用泛型收敛。
- **L8** `fronted/src/shared/ui/generate/types.ts:11-23` `FormFieldPropsSchema` 与 `entities/agent-run/model/types.ts:123-142` `FormFieldSchema` 重复且更宽松（options 无 min/max）；`AgentChatPanel.tsx:10-18` `COMPONENTS` 重复 `COMPONENT_TYPES`。建议引用真源。
- **L9** `fronted/src/shared/api/httpClient.ts:46`：`JSON.parse(text)` 对非 JSON 错误体（如网关 HTML）抛 SyntaxError 而非 `HttpError`；`ErrorResponseSchema` 全仓无运行时使用（`grep` 仅 types.ts/index.ts），错误体未按契约解析给 UI。
- **L10** `fronted/.oxlintrc.json:104-119`：`src/shared/ui/**` override 丢弃了 `@entities/*/model/*` 穿透 index 的限制；`desktop/Table.tsx:6` `{key, ...r}` 行内 `key` 列会覆盖 React key。
- **L11** `.harness/scripts/e2e-frontend.mjs:31` `checkTrue` 的 `|| detail` 无效（`check` 返回 undefined）；多处 `sleep()` 固定等待易抖动；`e2e-backend.sh:20,83` `pkill -f app.jar` 会误杀机器上任意 `app.jar`。

---

## 4. INFO

- **I1** 内部端点 `/internal/tool-gateway/invoke`、`/internal/tool-registry/*` 不要求 `X-Tenant-Id / X-User-Id` 头，principal 直接取自请求体 `executionContext`（`ToolGatewayController.java:27`）。这是 spec §6.2 验收命令本身的设计（e2e `gw()` 也未带头），与 `backend-standard.md §7`「所有对外端点默认需要身份头」的边界在于"内部"二字；后续拆分为独立服务时需加服务间鉴权，建议在 spec 风险表登记。
- **I2** Gateway 实际顺序为 寻址 → 输入校验 → 鉴权 → 幂等 → 调用 → 输出校验 → 脱敏 → 审计（`InvokeToolUseCase.java:121-187`），寻址前置是取 Manifest 的必要条件，与 spec §2.2 文字顺序略有差异，可接受。输入校验先于鉴权意味着无权用户也能拿到参数级 400 反馈。
- **I3** `SelfCheckRunner`（`app/SelfCheckRunner.java:36-39`）与 `application.yml:11-12` 默认 `enabled: true`；`RefundIdempotencySelfCheck` 会在内存中为订单 10003 创建真实退款单，`TokenSelfCheck` 使用共享 `ConfirmationTokenStore`（已核对 4 个令牌均被 consume，不残留）。README 已注明生产关闭，符合 spec §2.2。
- **I4** spec §2.3 文件名偏差：`shared/ui/device/useDevice.ts` 不存在（合并在 `DeviceContext.ts`）；`features/agent-chat` 无独立 `useSubmitAction`（合并进 `useAgentRun`）。功能等价。
- **I5** `deployment/` 证据不自洽：`run_events.log`（13:44，runId `run_1ce0e294…`）与 `backend.log`（14:12–14:29，含 4 个其他 runId、审计 `traceId=null`、无 `trace_e2e`）不是同一次运行的产物。代码路径上 `X-Trace-Id` → `ExecutionContext.traceId` → 审计是通的，但 deploy-verify 阶段应一次性生成并冻结全部产物。另：Runtime 从未把 traceId 放入 MDC，`RuntimeExceptionHandler.traceId()` 返回的是随机值而非请求头。
- **I6** SSE 客户端断开后 Runtime 不取消执行（`SseRunEventSink.emit` 静默丢帧），`refund.create` 仍会完成。首期可接受，属产品决策。
- **I7** `AgentPage.tsx:8` 固定 `user_001@tenant_001`，符合 spec §3 非目标。

---

## 5. agent-safety 六条核对表

| § | 条目 | 结论 | 证据位置 |
|---|---|---|---|
| §1 | Runtime 只经端口调 Registry / Gateway；不直连领域服务 | **通过**（有偏差） | `RunOrchestrator` 只持有 `ToolRegistryClient` / `ToolGatewayClient`（`:57-58`）；pom 无 domains 依赖（`agent-runtime/pom.xml:14-24`）。偏差：进程内适配依赖对方 `application` 包（S1）；`application` 反向依赖 `infra.llm`（S2） |
| §1 | Registry 无转发 / 代理端点、无出向客户端 | **通过** | `ToolRegistryController.java` 仅 `/tools`、`/search`、`/tools/{id}/versions`；pom 无 HTTP client |
| §1 | Gateway 不做选择 / 规划 | **通过** | `InvokeToolUseCase` 按 `toolId@version` 精确寻址（`:162`），无候选逻辑 |
| §1 | Run 状态机不绕过 Gateway | **通过**（自检例外已由 spec 允许） | 所有执行经 `invoke()` → `gateway.invoke`（`:334`）；`RefundIdempotencySelfCheck` 直调 handler 为 spec §2.2 明示 |
| §2 | 确定性路由 → Registry 有限候选；带 principal；按租户 / 权限 / 状态过滤 | **通过** | `DomainRouter.route`（`:27-40`）；`SearchToolsUseCase.java:30-32` + `DiscoveryPolicy.java:15-20`（status ∈ {active,canary} ∧ permission）。租户隔离依赖权限表键 `user@tenant`（`InMemoryPrincipalPermissionResolver.java:50-52`） |
| §2 | 候选六字段、无内部地址 / Owner | **通过** | `ToolSearch.ToolCandidate`（`:29-35`）恰六字段；契约 `additionalProperties:false` |
| §2 | description 转义 / 截断；模型 toolId 越界拒绝 | **通过** | `PromptBuilder.sanitize`（`:61-71`，500 截断 + `{}` / 反引号 / 换行替换）；`ToolSelectionValidator.java:36-39` 抛 `TOOL_SELECTION_INVALID`；`SpringAiLlmClient.java:34-59` 重试一次 |
| §3 | 计划不下发前端；只给 actionId + 不透明 token | **通过** | `UiSchemaBuilder.refundConfirmation` 只含展示字段 + `confirmationToken`；`RunSummary` 无 plan |
| §3 | token 随机 / 一次性 / 过期 / 绑定 runId+actionId+argsDigest / formData 键白名单 | **通过** | `ConfirmationTokenService.issue`（SecureRandom 24B，`:35-37`）、TTL 10min（`:22`）、`consume`（`:54-78` 逐项校验，`store.remove` 原子一次性） |
| §3 | 确认后重新校验：权限 / 金额 / 订单状态 / 令牌 | **未通过** | 订单状态：经 Gateway 重调 `refund.eligibility.check`（`RunOrchestrator.java:197-203`）✓；令牌 ✓；**权限**：只比对 principal 相等，未校验权限（S5）；**金额**：未重校验，计划内 `amount` 优先于试算（**M3**） |
| §3 | 令牌重放不重复副作用 | **部分通过** | 第二次 consume 必失败，`refund.create` 不会二次执行；但并发重放会把执行中的 Run 打成 FAILED（**M2**） |
| §3 | high / required 未确认不执行；low 自动执行 | **通过** | `runSteps` 遇 `requiresConfirmation` 即 `waitForConfirmation` 并 `return`（`:249-252`）；`ToolSelectionValidator.java:48-50` 按 riskLevel / confirmation 标记 |
| §4 | principal 只来自请求头；pageContext 不参与鉴权 | **通过** | `AgentRunController.principal()`（`:115-120`）；`pageContext.selectedEntity` 仅作为 `entity` 提示进入规划（`RunOrchestrator.java:134-141`），鉴权在 Gateway 按 header principal |
| §4 | 前端白名单渲染、无 URL / HTML / 代码执行、按 actionId 提交 | **通过** | `SchemaRenderer` 只查 `componentRegistry`；`grep -rnE "\bany\b|console\.log|dangerouslySetInnerHTML|\beval\(|new Function" fronted/src` 为空；`componentRegistry.ts` 固定路径 `lazy`；`useAgentRun.submitAction` 用 `actionPath(runId, action.id)` + 原样回传 token |
| §5 | 输入校验 → 鉴权 → 幂等 → 寻址 → 超时 / 重试 → 输出校验 → 脱敏 → 审计 | **通过**（顺序微调） | `InvokeToolUseCase.pipeline`（`:121-187`）；`RetryPolicy.java:13-17` 仅幂等或无副作用可重试；`redact()`（`:225-245`）；审计 9 字段 `LogAuditSink.java:16-26`，`deployment/backend.log` 实际行含全部 9 个 `key=` |
| §6 | 事件先校验后发出；data 无原文 / 堆栈 / 凭据；ping；close 幂等 | **通过** | `RunOrchestrator.emit`（`:418-425` `assertValid("sse-events")`）；`run.failed.message` 为固定用户文案（`:409-416`）；`SseRunEventSink` 15s `: ping`（`:22, 32-46`）、`closed` CAS（`:67-76`）；`backend.log` 中 `grep -c "帮我把这个订单退款"` = 0、`grep -c "ct_"` = 0 |

---

## 6. 契约字段对照表摘要

对照方法：9 个 Schema 的 `required` / `additionalProperties` / enum / pattern / min-max，逐一比对 `fronted/src/entities/agent-run/model/types.ts`（Zod）与 `backed/contracts-java/.../model/*.java`（record）**及其在边界上是否真正被执行**。

| 契约 | 前端 Zod | 后端 record 形状 | 后端边界执行 | 结论 |
|---|---|---|---|---|
| intent-request | 全部一致（`.strict()`、const、pattern、长度） | 形状一致（`IntentRequest` 4 字段 + 嵌套） | **未校验**：仅 `@NotBlank/@NotNull`；`additionalProperties`、`uiSchemaVersion const`、pattern、maxLength、`components` minItems/uniqueItems 均不生效 | **不一致（M1）** |
| action-request | 一致（`FormDataSchema` 键名 pattern、≤16 键、标量值） | `Map<String,Object>` 允许嵌套；`confirmationToken` 仅 `@NotBlank` | **未校验** | **不一致（M1）** |
| ui-schema | 一致；Form 的 if/then 用 `superRefine` 实现 | `UiSchema` 5 字段、`ComponentType` 7 值、`ActionStyle` 小写 wire、`Action.confirmationToken` NON_NULL | 出站 `assertValid("ui-schema")`（`UiSchemaBuilder.java:128`） | 一致 |
| run-summary | 一致（含 FAILED→failureCode、WAITING→currentUi 两条 if/then） | 7 字段一致，`Instant` → ISO-8601 | 出站未再校验（record 构造即受控） | 一致 |
| sse-events | 10 事件 discriminatedUnion，字段 / pattern / enum 一致 | 10 个 data record，`ToolCompletedData.summary` NON_NULL | 出站 `assertValid("sse-events")` | 一致 |
| tool-manifest | 前端不投影（按 spec） | 13 字段 + 4 enum；`Protocol` wire 值 `in-process` 等 | 入站 `assertValid("tool-manifest")`（`RegisterToolUseCase.java:28`） | 一致 |
| tool-search | 不投影 | `Request` 4 字段、`ToolCandidate` 恰六字段 | 请求**未校验**（`domain` / `intent` / `entityType` pattern 不生效） | **不一致（M1）** |
| tool-invoke | 不投影 | `Request` 4 字段、`ExecutionContext` 6 字段 | 请求**未校验**（`runId ^run_` / `toolCallId ^tc_` 不生效）；`Response.failed` 形态从不产出（S3） | **不一致（M1 / S3）** |
| error-response | 一致（`.strict()`，details 可选） | `ErrorResponse` NON_NULL，`Code` 6 值 | 出站由 advice 构造 | 一致（前端运行时未使用该 Schema 解析错误体，L9） |

**不一致项：4 项**（intent-request、action-request、tool-search.request、tool-invoke.request/response）——根因同一：后端入站边界缺契约校验（M1）+ 失败响应形态未实现（S3）。前端 Zod 投影与 9 个 Schema 逐字段核对 **0 项不一致**；`check-contracts`、`check-registry` 均通过。

---

## 7. 偏离 spec 清单（§4 / §5 / §6）

| # | spec 条目 | 实现现状 | 分级 |
|---|---|---|---|
| D1 | §2.2 / backend-standard §4 进程内适配调用对方 `api` 包 | 调用 `application` 包实现类 | S1 |
| D2 | §4.1 `tool.selected.displayName` 来自 Manifest `name` | 硬编码 `DISPLAY_NAMES` | S2 |
| D3 | §4.2 HTTP 表无 502 | Gateway TIMEOUT/HANDLER_ERROR/OUTPUT_INVALID → 502 `INTERNAL_ERROR` | S3 |
| D4 | §4.2 权限不足 → `CONFIRMATION_REJECTED` | 变成 `TOOL_EXECUTION_FAILED` | S5 |
| D5 | §4.1 确认时重校验金额 | 未做，计划内 amount 优先 | **M3** |
| D6 | §4.2 400 `REQUEST_INVALID` 覆盖请求体 Schema 失败 | 仅 Bean Validation；非法 JSON → 500 | **M1** / S7 |
| D7 | §6.2 `grep … backed \| grep "/domain/"` 无输出 | 字面命令输出 5 个 infra 文件；脚本以"直接父目录"规避 | S11 |
| D8 | §6.2 第 5–7 条验收命令化 | e2e-backend.sh 未覆盖 | S12 |
| D9 | §2.3 `useDevice.ts`、`useSubmitAction` | 合并实现 | I4 |
| D10 | §2.2 Gateway 顺序"校验 → 鉴权 → 幂等 → 寻址" | 寻址前置 | I2 |

---

## 8. 其余核对（无问题项，记录以示已查）

- 后端 `domain/` 包（分层意义）无 Spring / Jackson：已逐文件核对 `runtime/domain`、`gateway/domain`、`registry/domain`、`order/domain`、`refund/domain`；`platform-spi` 无 Spring。
- 金额 / ID：后端 `BigDecimal` 运算、`amountText()` 两位小数字符串；前端 `MoneySchema` string；contracts pattern 一致。
- 无空 catch：`SseRunEventSink.java:72` `catch (IllegalStateException ignored)` 带注释且语义明确（emitter 已由容器完成）。
- 日志：Controller 入口日志不含 `message` 原文；`fail()` 只记 code + 内部 reason（键名，不含值）；`backend.log` 无用户原文、无 `ct_` 令牌。
- 前端 `exactOptionalPropertyTypes`：`ToolProgress.summary` 用条件展开、`SseFrame.id` 用条件返回、`RequestInit.signal` 条件赋值，均正确。
- `sseClient.parseFrame`：多行 data、`\r\n`、注释帧、尾部半帧处理正确；非 2xx 抛 `HttpError`。
- `useAgentRun` 重复提交：`busy` 禁用输入与按钮；`stream()` 用 `AbortController` 取消前一流。
- FSD：`check-deps` 通过；`shared/ui` 对 `@entities` 仅 `import type`（spec 明示允许）。
- Registry 409：`InMemoryToolRegistryRepository.putIfAbsent` 原子；`StartupManifestRegistrar` 捕获冲突仅 WARN。
- `RetryPolicy` 与 6 份 Manifest：`refund.create` maxRetries 0 + idempotency required；其余 sideEffect=false 可重试 1 次。

---

**verdict：REVISION REQUIRED**（MUST FIX 3 条：M1 入站契约校验缺失、M2 确认并发破坏 Run 状态、M3 确认金额未重校验）。
