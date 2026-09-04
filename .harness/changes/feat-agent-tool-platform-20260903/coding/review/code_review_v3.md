# Code Review v3 — feat-agent-tool-platform-20260903

- **mode**: execution（独立评审，第 2 轮；未阅读 `coding_report_v1.md`；已逐条核对 `code_review_v2.md` 的 3 MUST FIX + 12 SHOULD）
- **date**: 2026-09-04
- **round**: 2
- **reviewer**: expert-reviewer（有罪推定：重点找修复引入的新问题）
- **评审范围**
  - 规格：`request_analysis/spec.md` v3.2（§4.2 / §5 / §6.2 / §6.3.4 回写内容）
  - 规则：`coding-standard.md`、`backend-standard.md`、`contracts.md`、`agent-safety.md`、`project-structure.md`
  - 契约：`.harness/contracts/*.schema.json`（9）
  - 后端：`contracts-java/SchemaValidator`、三处 Controller、三处 `*ExceptionHandler`、`RunOrchestrator` / `ConfirmationTokenService` / `ToolDisplayNames` / `UiSchemaBuilder`、`InProcessToolGatewayClient`、`ToolSelectionValidator` / `PromptBuilder` / `LlmConfiguration`、`InvokeToolUseCase`、`Run` / `RunState`、领域 handler
  - 前端：`useAgentRun.ts`、`AgentChatPanel.tsx`、`runView.ts`、`router.tsx` / `RouteErrorBoundary.tsx`、`sseClient.ts`、`SchemaRenderer.tsx`、`desktop|mobile/Form.tsx`
  - Harness：`e2e-backend.sh`、`e2e-frontend.mjs`、`check-module-deps.mjs`、`sse-parse.mjs`
- **本地实际执行**（仓库根，均为本轮亲自运行）
  - `pnpm -C .harness run check-contracts` → `9 schemas OK`，退出 0
  - `node .harness/scripts/check-module-deps.mjs` → OK，退出 0
  - `pnpm -C fronted run typecheck` → 退出 0；`pnpm -C fronted run lint` → 退出 0（含 check-deps / check-registry）
  - `node .harness/scripts/mvn.mjs -q -B verify` → 退出 0
  - `e2e-backend.sh`：复制到 `/tmp/e2e_review/`（`ROOT` 固定为仓库根、`DEPLOY` 改为 `/tmp/e2e_review`，**不触碰 `deployment/`**）→ **46 passed, 0 failed**，退出 0；fresh `backend.log` 中 `ERROR` 0 行、`ct_` 0 次、用户原文 0 次、`traceId=trace_e2e` 10 行
  - **未**重跑 `e2e-frontend.mjs`（需 Chrome + vite dev server）；以 `deployment/*.png`（16:11）为交叉证据

分级：**MUST FIX** = 违反 L1 硬约束 / 安全边界被绕过 / 契约不一致 / 会导致错误行为；**SHOULD** = 明确风险但不阻塞；**LOW / INFO** = 可读性、建议、记录。

---

## 0. 结论摘要

| 分级 | 条数 |
|---|---|
| MUST FIX | **0** |
| SHOULD | 6（其中 2 条为上轮遗留：S1 未修、S6 部分） |
| LOW | 11 |
| INFO | 5 |

上轮 15 条闭环：**已修 11 / 部分 3 / 未修 1**。

**verdict：APPROVED**（无 MUST FIX；6 条 SHOULD 建议在阶段 7 deploy-verify 前或下一 change 处理，见 §2）。

---

## 1. 上轮 MUST FIX / SHOULD 逐条闭环表

### 1.1 MUST FIX

| # | 上轮问题 | 状态 | 证据（代码 / 脚本） | 绕过路径核查 |
|---|---|---|---|---|
| **M1** | 入站请求体未按契约 Schema 校验 | **已修** | `SchemaValidator.bind(contract, fragment, body, type)`（`SchemaValidator.java:127-137`）：先 `assertValid` 再 `convertValue`；子定义校验 `assertValid(contract, "#/$defs/request", node)`（`:98-119`，编译结果按 `contract+fragment` 缓存于 `ConcurrentHashMap`）。调用点：`AgentRunController.java:66`（intent-request）、`:87`（action-request）、`ToolGatewayController.java:32`（tool-invoke `#/$defs/request`）、`ToolRegistryController.java:57`（tool-search `#/$defs/request`）。全仓 `@RequestBody` 共 5 处，第 5 处 `POST /tools`（`ToolRegistryController.java:49`）经 `RegisterToolUseCase.java:28` `assertValid("tool-manifest")`。e2e 反例 6 条全绿（additionalProperties / const / formData 嵌套对象 / runId pattern / 非法 JSON / Gateway 缺参）。 | 无遗漏端点；`GET /tools/{id}/versions` 与 `GET /agent/runs/{id}` 无请求体。`ActionRequest.formData` 仍是 `Map<String,Object>`，但 Schema 已在绑定前拒绝非标量，`String.valueOf` 只会作用于标量。空请求体 → Spring `HttpMessageNotReadableException` → 400（S7）。 |
| **M2** | 确认阶段并发 / 重放把执行中的 Run 打成 FAILED | **已修** | `RunOrchestrator.confirm`（`:173-225`）：`confirmLocks.computeIfAbsent(runId)` + `synchronized(lock)`；前置校验（状态非 WAITING / principal 不符 / `TokenUnknown`）走 `rejectRequest`（`:284-302`）——只向本连接发 `run.failed{CONFIRMATION_REJECTED}` 并 close，**不改 Run 状态**；`TokenUnknown extends RunFailure`（`ConfirmationTokenService.java:83-89`）在内层 `try` 单独捕获（`:200-205`），先于外层 `catch (RunFailure)`，顺序正确。令牌消费后（过期 / digest / 白名单外键）才 `fail()` → FAILED，此时令牌已被 `store.remove` 消耗、Run 不可能再被确认，置 FAILED 语义正确。e2e M2：两条并发流 `grep -c run.completed` = 1、`grep -c '^run.failed$'` = 1、summary COMPLETED、订单 10002 退款数 1（本轮实跑通过，`deployment/m2_a.log` / `m2_b.log` 亦一致）。 | 锁粒度：B 在 A 移除锁前 `computeIfAbsent` 拿到同一对象 → 排队后看到 COMPLETED → 拒绝；B 在移除后到达 → 新锁对象但 Run 已终态 → 拒绝。无双执行路径。**新问题**见 §2 N1（锁内做 Gateway 调用占线程）、§3 L1（锁泄漏）。e2e 断言非恒真：两条都 completed 时计数为 2 → 红；两条都 failed 时 completed=0 → 红。 |
| **M3** | 确认后执行金额可能不是用户看到的金额 | **已修** | `executeConfirmed`（`:251-265`）：`trustedAmount = recheck.refundableAmount`，与缓存 `refund.preview.amount` 严格字符串相等，否则 `CONFIRMATION_REJECTED`；`args.put("amount", trustedAmount)` **无条件覆盖**。格式一致性：`RefundEligibilityCheckHandler.java:43` 与 `RefundPreviewHandler.java:40` 都用 `setScale(2, HALF_UP).toPlainString()`，字符串比对安全。防御纵深：`ToolSelectionValidator.java:24, 57-68` 对需确认步骤拒绝模型填写 `amount`（`TRUSTED_ONLY_ARGS`）；`PromptBuilder.system()` 明示不要填金额。e2e M3：`SHOWN=128.00`、`EXECUTED==SHOWN` 通过。 | 前端注入 `amount` → 白名单拒绝（e2e §6.2.12b 通过）；模型注入 → 校验器拒绝；`refund.create` 参数最终只可能来自 recheck。**新问题**见 §2 N2（比对源与 UI 展示源不一致，live-LLM 路径可能误拒）；§3 L4（e2e 比对在双空时恒真，当前被前一条 `SHOWN==128.00` 守住）。 |

### 1.2 SHOULD

| # | 上轮问题 | 状态 | 证据 | 接受度 |
|---|---|---|---|---|
| S1 | 进程内适配依赖对方 `application` 包而非 `api` 包 | **未修** | `InProcessToolGatewayClient.java:4` 仍 `import com.strato.gateway.application.InvokeToolUseCase`；`InProcessToolRegistryClient.java:4` 仍 `import com.strato.registry.application.SearchToolsUseCase`；gateway / registry 的 `api/` 包只有 Controller 与 Advice。`agent-runtime/pom.xml:12` 注释声称"只用其 api 包"与事实不符。 | 不接受为已闭环；保留 SHOULD（§2 N4）。 |
| S2 | `application` 反向依赖 `infra.llm`；displayName 硬编码 | **部分** | 依赖方向已修：`ToolDisplayNames` 移到 `application/`（`ToolDisplayNames.java`），`infra.llm.LlmConfiguration:3` 依赖 application（方向正确）。硬编码副本仍在（`NAMES` 6 项），已与 6 份 Manifest `name` 逐一核对一致。 | 接受降级为 LOW（§3 L2）：新增工具需同步改常量的风险仍在。 |
| S3 | Gateway 失败经 HTTP 502；`Response.failed` 从未产出 | **已修** | `InProcessToolGatewayClient.java:25-31` 捕获 `GatewayException` → `ToolInvoke.Response.failed(toolCallId, ms, e.code(), msg)`；spec §4.2 新增 502 行与 v3.2 说明。 | 接受。 |
| S4 | 依赖异常消息文本区分 `TOOL_OUTPUT_INVALID` | **已修** | `RunOrchestrator.invoke`（`:430-438`）按 `resp.error().code()` 结构化映射：`OUTPUT_INVALID → TOOL_OUTPUT_INVALID`，其余 → `TOOL_EXECUTION_FAILED`；`ToolCallFailed` 携带 `gatewayCode`（`:444-457`）。 | 接受。 |
| S5 | 权限不足未映射 `CONFIRMATION_REJECTED` | **已修** | `executeConfirmed` 两处 `catch (ToolCallFailed e)`（`:242-246`、`:269-273`）：`gatewayCode == FORBIDDEN` → `CONFIRMATION_REJECTED`。非确认路径 `runSteps` 不做此映射，`ToolCallFailed` 以 `TOOL_EXECUTION_FAILED` 落地，符合 spec §4.2"确认路径上的 FORBIDDEN"限定。 | 接受。无 e2e（权限表静态无法运行时回收），以代码为证。 |
| S6 | 超时不取消任务；幂等 `putIfAbsent` 在执行之后 | **部分** | `InvokeToolUseCase.java:200-204` 超时后 `f.cancel(true)`；幂等仍是 查（`:154`）→ 执行（`:170`）→ 写（`:186`）。 | **不接受"超时取消"为有效修复**：`CompletableFuture.cancel(true)` 不会中断正在执行的任务（JDK 文档：`mayInterruptIfRunning` 参数无效），handler 线程照旧占用 `gateway-tool-*` 池，注释"避免 handler 线程继续占用执行器"与实际行为相反。见 §2 N1。幂等 check-then-act 未改，见 §2 N6。 |
| S7 | 非法 JSON → 500 | **已修** | 三处 Advice 均新增 `HttpMessageNotReadableException → 400 REQUEST_INVALID`（`RuntimeExceptionHandler.java:65-71`、`GatewayExceptionHandler.java:75-81`、`RegistryExceptionHandler.java:60-66`）；e2e `malformed JSON → 400` 通过。 | 接受。 |
| S8 | SSE 无终态即结束 → UI 永久 streaming | **已修** | `useAgentRun.ts:56-70` `failIfStillStreaming`；`stream()`（`:107-123`）在 `consumeSse` 正常结束且 `!ac.signal.aborted` 时归约为 `failed{INTERNAL_ERROR,"连接中断，请重试"}`。被 Zod 拒绝的 `run.failed` 帧也会落到这里。abort 竞态：`abortRef.current?.abort()` 只在下一次 `stream()` 触发，而 `busy` 禁用输入与按钮，实际不可达；guard 正确。 | 接受。遗留：`consumeSse` 抛 `HttpError` 时 `phase` 停留在 `streaming`（§3 L5）。 |
| S9 | 确认提交不校验必填 → 白烧令牌 | **已修** | `missingRequiredFields`（`:32-53`）按 `Form.props.fields[].required` 校验 `formRef`；`FormIncompleteError`（`:22-29`）在 `setQueryData(streaming)` 之前抛出，不发请求、`phase` 仍为 `waiting_confirmation`、ActionBar 保留可重试；`AgentChatPanel.tsx:53-55` 显示「请先填写：…」。移动端 `Selector` 未选返回 `[]` → `flat[k] = undefined` → 命中缺失判定。 | 接受。 |
| S10 | 全局缺 ErrorBoundary | **已修** | `router.tsx:19` 根路由 `ErrorBoundary: RouteErrorBoundary`；`RouteErrorBoundary.tsx` 不展示堆栈、提供返回首页。未加 feature 级边界（上轮建议为"或"）。 | 接受。 |
| S11 | `check-module-deps.mjs` 可绕过；spec §6.2 字面命令不可满足 | **部分** | 全文正则 `\borg\.springframework\.` / `\bcom\.fasterxml\.`（`:73`）；包路径判定改为"去掉 `com/strato/domain/<svc>` 后任一段为 `domain`"（`:77-83`），覆盖子包；spec §6.2 末条措辞已改。pom 仍是字面 `<artifactId>id</artifactId>` 匹配（`:55`），属性 / 换行绕过仍在。 | 接受为部分（§3 L3）。 |
| S12 | e2e 未覆盖 §6.2 第 5–7 条 | **已修** | `e2e-backend.sh:31-51`：search 4/3 + 六字段 + ajv 响应校验、409、Gateway 缺参 400、user_002 403；本轮实跑全部通过。 | 接受。 |

---

## 2. 新意见（本轮）

### SHOULD

#### N1. `CompletableFuture.cancel(true)` 不中断任务，S6"超时取消"修复无效且注释误导
- **位置**：`backed/tool-gateway/src/main/java/com/strato/gateway/application/InvokeToolUseCase.java:196-204`
- **问题**：`CompletableFuture.supplyAsync(...)` 返回的 future 调用 `cancel(true)` 只把 future 置为 cancelled，**不会**向执行线程发送 interrupt（`CompletableFuture#cancel` 文档明确 `mayInterruptIfRunning` 无效）。handler 线程继续在 `gateway-tool-*`（16 线程）里跑到自然结束；注释「超时必须取消任务，避免 handler 线程继续占用执行器」与实际行为相反，会误导后续维护者认为已有保护。
- **建议**：改为 `Future<JsonNode> f = executor.submit(() -> handler.handle(args, ctx))`，超时后 `f.cancel(true)`（`FutureTask` 会 interrupt）；同时 `ToolHandler` 契约注明"应响应中断"。或删掉误导注释并在 spec 风险表登记。
- **分级**：SHOULD

#### N2. 金额一致性比对只认 `refund.preview` 缓存，而确认屏展示值有回退来源，二者不同源
- **位置**：`RunOrchestrator.java:252-260`（比对 `cache["refund.preview"].amount`）vs `UiSchemaBuilder.java:48`（展示 `text(preview,"amount", text(eligibility,"refundableAmount","0.00"))`）
- **问题**：使用 `SpringAiLlmClient` 时，模型合法产出不含 `refund.preview` 的计划（如 `[eligibility.check, create]`），确认屏用 `eligibility.refundableAmount` 展示 `128.00`，用户确认后 `shownAmount = ""`（无 preview 缓存）→ 与 `trustedAmount` 不等 → `CONFIRMATION_REJECTED`。行为**失败安全**（不会错执行），但目标路径上的合法计划被系统性拒绝，且用户看到的文案是「确认已过期或已被使用」，与实情不符。规则规划器固定含 preview，e2e 无法暴露。
- **建议**：把"确认屏实际展示的金额"作为令牌绑定的一部分（`ConfirmationToken` 增 `shownAmount` 或并入 `argsDigest`），确认时用令牌里的值与 recheck 比对，保证"比对对象 == 渲染对象"；或 `UiSchemaBuilder` 返回其实际使用的 amount 并写回 `stepOutputs`。
- **分级**：SHOULD

#### N3. 确认锁内执行 Gateway 调用，重复确认排队占满 `agent-run` 线程池
- **位置**：`RunOrchestrator.java:183-224`（`synchronized(lock)` 内含两次 `invoke()`，每次最多 `timeoutMs` 5s + 重试）；`RuntimeExecutorConfiguration.java:15`（`RUN_POOL = 8`，全局共享）
- **问题**：并发到达的第 2..N 个确认请求在锁上阻塞，而不是立即拒绝。同一用户对一个 Run 连点 8 次（或脚本重放），8 个 `agent-run` 线程全部挂在锁上等待前一次执行完成，期间**所有租户**的新 `POST /agent/runs` 都排队。M2 的正确性由锁保证，但可用性代价未评估。
- **建议**：改 `ReentrantLock.tryLock()`，拿不到锁直接 `rejectRequest("confirmation already in progress")`——对第二请求的可观察结果（`run.failed{CONFIRMATION_REJECTED}`）不变，e2e M2 断言仍成立，且不占线程。
- **分级**：SHOULD

#### N4. （上轮 S1 未修）进程内适配依赖 gateway / registry 的 `application` 实现类
- **位置**：`InProcessToolGatewayClient.java:4`、`InProcessToolRegistryClient.java:4`；`agent-runtime/pom.xml:12` 注释与事实不符
- **问题**：`backend-standard.md §4`「跨模块只依赖对方的 `api` 包公开接口」、spec §2.2「直接调用对方模块 `api` 包公开用例接口」。`check-module-deps` 只看 pom，不检查包级越界，规则处于"写了但没人守"状态。
- **建议**：在 gateway / registry `api/` 暴露接口（如 `ToolInvokePort` / `ToolSearchPort`），`application` 用例实现之；`check-module-deps` 增加"`com.strato.runtime.**` 不得 import `com.strato.(gateway|registry).(application|infra)`"的包级检查。若决定接受现状，需同步改两处规则措辞，不能三处各说各话。
- **分级**：SHOULD

#### N5. `deployment/` 证据再次不自洽：`backend.log` 与事件日志不是同一次运行（上轮 I5 复发）
- **位置**：`.harness/changes/feat-agent-tool-platform-20260903/deployment/`
- **问题**：`run_events.log`（runId `run_20e7dfd1…`，08:10:52Z）、`run3_events.log` / `m2_*.log`（`run_bf6ed4de…`）与 `backend.log`（进程 16:10:53 启动、唯一 runId `run_c056d3a2…`、4 条审计全部 `traceId=null`、无 `trace_e2e`）不匹配——e2e-backend 跑完后有另一次启动把日志覆盖了（推测为 e2e-frontend 前手动重启）。结果是 §6.2.12「`refund.create succeeded` 审计行 = 1」、§6.2.14「审计 9 字段」、§6.2.15 这些日志类验收在冻结产物里**无法复核**（本轮我在 `/tmp` 重跑证明代码是对的，但 change 产物本身不能自证）。
- **建议**：阶段 7 deploy-verify 一次性运行 e2e-backend → e2e-frontend 并冻结；`e2e-frontend` 前端用例若需后端，应复用 e2e-backend 启动的进程或写到 `backend-frontend.log`，脚本禁止重定向到同一 `backend.log`。
- **分级**：SHOULD（流程 / 证据，不是代码缺陷）

#### N6. （上轮 S6 后半未修）Gateway 幂等仍是 check-then-act
- **位置**：`InvokeToolUseCase.java:152-159, 186`
- **问题**：同租户同 `idempotencyKey` 并发到达会都执行 handler，靠 `RefundService`/`InMemoryRefundRepository.saveIfAbsent` 兜底；其他 side-effect 工具无此保障。Runtime 侧 M2 锁已让 `refund.create` 的重复调用不可达，但 Gateway 作为执行面应自足（agent-safety §5「幂等去重」）。
- **建议**：`IdempotencyStore` 增加占位语义（`reserve(tenant,key)` 原子占位 → 执行 → `complete` / 失败 `release`），并发第二请求等待或返回 in-progress。
- **分级**：SHOULD

### LOW

- **L1** `RunOrchestrator.java:183, 219-222`：`confirmLocks` 只在 Run 终态时清理。Run 停在 WAITING_CONFIRMATION 且收到过一次被拒确认（错 token）→ 锁条目常驻；永不确认的 Run 亦然。与 `stepOutputs` / `lastUi` / 过期令牌同属无 TTL 淘汰（上轮 L3）。
- **L2** `ToolDisplayNames.java:11-18`：displayName 仍为 Manifest `name` 的硬编码副本（S2 部分）。建议 Registry 端口暴露 `displayNameOf(toolId@version)`。
- **L3** `check-module-deps.mjs:53-57`：pom 依赖检查仍是字面 `<artifactId>id</artifactId>`（S11 部分）；`${property}` 或标签内换行可绕过。
- **L4** `e2e-backend.sh:121-123`：`check "executed amount equals shown" "$SHOWN" "$EXECUTED"` 在两者都为空串时**恒真**（本轮误配置 ROOT 时实际观察到 `✓ executed amount equals shown: ` 通过）。目前被前一条 `shown amount == 128.00` 守住，但建议对 `EXECUTED` 单独加非空断言，避免将来有人删掉前一条。
- **L5** `useAgentRun.ts:107-123`：`consumeSse` 抛 `HttpError`（如 401/400）时 `failIfStillStreaming` 不会执行，`view.phase` 停留在 mutationFn 提前写入的 `streaming`；错误文案靠 mutation error 显示，但确认屏 ActionBar 消失、令牌若未消费也无法重试。建议 `catch` 后同样归约为 `failed`。
- **L6** `e2e-frontend.mjs:31`：`checkTrue` 的 `|| detail` 仍是无效表达式（上轮 L11 未改）。
- **L7** `AgentRunController.java:90`：confirm 的同步 404 预检不比对 principal（上轮 L4），他人可探测 runId 存在性；orchestrator 已在锁内拒绝且不改状态，风险仅限信息泄露。
- **L8** `RuntimeExceptionHandler.java:95-100`：Runtime 从未 `MDC.put("traceId")`，`traceId()` 永远是随机值而非请求头 `X-Trace-Id`（上轮 I5 未改）；Gateway 审计里 `traceId` 正确来自 `executionContext`。
- **L9** 三处 Advice 的 `MethodArgumentNotValidException` 处理器：所有 `@RequestBody` 已改为 `JsonNode`，Bean Validation 不再触发，成为死代码；`GatewayExceptionHandler.java:65-71` 该路径会输出 `details: []`（契约允许空数组但与其他两处的 `null` 不一致）。建议删除或统一。
- **L10** `PromptBuilder.java:44`：`sanitize(c.inputSchema().toString())` 同样按 500 字符截断，复杂 inputSchema 会被截成非法 JSON 交给模型；description 限长 500 是规则，schema 不应套用同一上限。
- **L11** `RefundService.create`（`:58`）仍只拒绝 `amount > refundable`；现在金额由 Runtime 可信覆盖，领域侧不再是唯一防线，但作为纵深建议改为 `amount == refundable`（首期规则为全额退）。

### INFO

- **I1** `synchronized` 内 `MDC.put("runId")` 在锁外、`remove` 在 finally，排队期间该线程日志带 runId，可接受。
- **I2** `bind()` 对 `null` body 抛 `ContractViolationException(contract, Set.of())`，message 为 `(no details)`；实际由 Spring 先抛 `HttpMessageNotReadableException`，此分支不可达，作为防御保留合理。
- **I3** `ContractViolationException` message 与 `ErrorResponse.details` 只含 instanceLocation 与 networknt 描述（pattern / const / maxLength 类消息不含实例值），未泄露用户 `message` 原文；fresh `backend.log` 亦验证用户原文 0 次、`ct_` 0 次。
- **I4** `RouteErrorBoundary.tsx:12` 渲染期 `console.error`——lint 允许 `warn`/`error`，DEV StrictMode 下会双打，符合 spec §6.3.4 不计次数的口径。
- **I5** e2e-frontend 本轮未重跑；`deployment/ui-*.png` 5 张（16:10–16:11）存在，与 §6.3.4 五步对应。

---

## 3. agent-safety 六条核对表（本轮重核）

| § | 条目 | 结论 | 证据位置 / 变化 |
|---|---|---|---|
| §1 | Runtime 只经端口调 Registry / Gateway；不直连领域服务 | **通过**（偏差同上轮） | `RunOrchestrator` 只持 `ToolRegistryClient` / `ToolGatewayClient`；pom 无 domains。偏差：进程内适配依赖对方 `application` 包（N4）。上轮 application→infra 反向依赖已消除（S2） |
| §1 | Registry 无转发 / 代理端点、无出向客户端 | **通过** | `ToolRegistryController` 仅注册 / 搜索 / 版本；pom 无 HTTP client |
| §1 | Gateway 不做选择 / 规划 | **通过** | `InvokeToolUseCase.pipeline` 按 `toolId@version` 精确寻址 |
| §1 | Run 状态机不绕过 Gateway | **通过** | 含确认后的重校验与执行均经 `invoke()` → `gateway.invoke`（`:241, :268`） |
| §2 | 确定性路由 → 有限候选；带 principal；按租户 / 权限 / 状态过滤 | **通过** | `DomainRouter` → `SearchToolsUseCase` + `DiscoveryPolicy`；e2e user_001=4 / user_002=3 |
| §2 | 候选六字段、无内部地址 | **通过** | e2e `candidate has exactly 6 fields` + ajv 响应校验通过 |
| §2 | description 转义 / 截断；模型 toolId 越界拒绝 | **通过** | `PromptBuilder.sanitize`；`ToolSelectionValidator:42-45`；自检 `invalid toolId rejected OK` |
| §3 | 计划不下发前端；只给 actionId + 不透明 token | **通过** | `UiSchemaBuilder.refundConfirmation` 只含展示字段 + token |
| §3 | token 随机 / 一次性 / 过期 / 绑定 runId+actionId+argsDigest / formData 键白名单 | **通过** | `ConfirmationTokenService.issue/consume`；`InMemoryConfirmationTokenStore.consume = remove` 原子 |
| §3 | 确认后重新校验：权限 / 金额 / 订单状态 / 令牌 | **通过**（上轮未通过 → 本轮通过） | 令牌 ✓；订单状态经 Gateway 重调 `refund.eligibility.check` ✓；**权限**：FORBIDDEN → `CONFIRMATION_REJECTED`（S5）✓；**金额**：只取 recheck 结果并与展示值比对、无条件覆盖（M3）✓。遗留 N2：比对源与展示回退源不同源，失败安全 |
| §3 | 令牌重放不重复副作用 | **通过**（上轮部分 → 本轮通过） | 并发 / 重放不改 Run 状态（M2）；e2e M2 与 §6.2.12 均通过；fresh 日志 `refund.create succeeded` 恰 1 行 |
| §3 | high / required 未确认不执行；low 自动执行 | **通过** | `runSteps` 遇 `requiresConfirmation` 即等待；校验器按 riskLevel / confirmation 标记 |
| §4 | principal 只来自请求头；pageContext 不参与鉴权 | **通过** | `AgentRunController.principal()`；`selectedEntity` 仅作规划提示 |
| §4 | 前端白名单渲染、无 URL / HTML / 代码执行、按 actionId 提交 | **通过** | `grep -rnE "\bany\b|dangerouslySetInnerHTML|\beval\(|new Function" fronted/src` 仅 2 处注释命中；`componentRegistry` 固定路径 `lazy`；`submitAction` 用 `actionPath(runId, action.id)` 原样回传 token |
| §5 | 输入校验 → 鉴权 → 幂等 → 寻址 → 超时 / 重试 → 输出校验 → 脱敏 → 审计 | **通过**（顺序微调同上轮） | `pipeline`；fresh 日志审计行 9 字段、`traceId=trace_e2e` 正确透传。遗留：超时取消无效（N1）、幂等 check-then-act（N6） |
| §6 | 事件先校验后发出；data 无原文 / 堆栈 / 凭据；ping；close 幂等 | **通过** | `emit()` `assertValid("sse-events")`；`rejectRequest` / `fail` 只发固定用户文案；`SseRunEventSink` 15s ping、CAS close；fresh 日志无用户原文、无 `ct_` |

---

## 4. 契约一致性复核（本轮变化点）

| 契约 | 本轮变化 | 结论 |
|---|---|---|
| intent-request / action-request | 入站 `bind` 校验（M1） | **一致**（上轮不一致 → 已修） |
| tool-search.request | `bind("tool-search", "#/$defs/request")` | **一致** |
| tool-invoke.request / response | 请求 `bind`；`Response.failed` 由进程内适配产出（S3）；`error.code` 6 值与 `ToolInvoke.ErrorCode` 一致 | **一致** |
| error-response | `details` 为空时三处 Advice 输出 `null`（NON_NULL 省略）；Gateway Bean-Validation 死路径可能输出 `[]`（L9） | 一致（契约允许两者） |
| ui-schema / run-summary / sse-events | 无变化；`RunFailureCode` 5 值、`RunState` 6 值与前端 Zod 一致 | 一致 |

`check-contracts` 9 schemas OK；前端 `check-registry` 7 types 一致。

---

## 5. 偏离 spec 清单（v3.2）

| # | spec 条目 | 现状 | 分级 |
|---|---|---|---|
| D1 | §2.2 / backend-standard §4 进程内适配调用对方 `api` 包 | 仍调用 `application` 实现类 | N4（SHOULD） |
| D2 | §4.1 `tool.selected.displayName` 来自 Manifest `name` | 硬编码副本（与 Manifest 一致） | L2 |
| D3 | §4.2 v3.2「确认后金额只取重校验结果并须等于确认屏展示值」 | 已实现；但"展示值"取 preview 缓存而非实际渲染值 | N2（SHOULD） |
| D4 | §6.2 v3.2 三条反例（M1 / M2 / M3） | 均已脚本化且本轮实跑通过 | — |
| D5 | §6 约定日志类断言对 `$DEPLOY/backend.log` | 冻结产物中的 `backend.log` 已被后续启动覆盖 | N5（SHOULD，阶段 7） |
| D6 | §2.3 `useDevice.ts`、`useSubmitAction` | 合并实现（上轮 I4） | INFO |

---

## 6. 其余核对（无问题项，记录以示已查）

- `TokenUnknown` 与普通 `RunFailure` 的语义分界正确：未消费令牌的拒绝不改状态；已消费令牌（过期 / digest / 白名单）→ Run FAILED，`WAITING_CONFIRMATION → FAILED` 为合法迁移。
- `RunState.canTransitionTo` 自迁移 no-op；`complete()` 从 EXECUTING → COMPLETED 合法；M2 修复后不再出现 FAILED → COMPLETED 的非法迁移路径。
- `ToolCallFailed` 在非确认路径（`runSteps`）直接作为 `RunFailure` 落地为 `TOOL_EXECUTION_FAILED` / `TOOL_OUTPUT_INVALID`，先发 `tool.completed{failed}`，符合 §4.2。
- 传输层异常（端口实现自身 RuntimeException）与 Gateway 契约失败分开处理（`invoke()` `:407-423`），`gatewayCode = null` 时不会误映射为 CONFIRMATION_REJECTED。
- 日志敏感信息：`rejectRequest` 只记内部原因（键名，不含值）；`LlmConfiguration` 只记 model / baseUrl，不记 apiKey；`AgentRunController` 不记 `message`。
- 前端 `FormIncompleteError` 在 `setQueryData(streaming)` 之前抛出，视图保持 `waiting_confirmation`，用户可补填后重试；`start.error ?? submitAction.error` 在下一次 mutate 时重置。
- `failIfStillStreaming` 与 `confirmation.required` 不冲突：归约先把 phase 置为 `waiting_confirmation`，流结束后不满足 `phase === 'streaming'`。
- `check-module-deps` 全文正则对 `domain/` 子包生效；本轮实跑通过；`domain` 分层包内确无 Spring / Jackson。
- 金额：后端 `BigDecimal` + `setScale(2)` 字符串；前端 `string`；契约 pattern 一致。
- 后端 `mvn verify`（含 spotless / -Werror）退出 0；前端 typecheck / lint 退出 0。

---

**verdict：APPROVED**（MUST FIX 0；SHOULD 6：N1 超时取消无效、N2 金额比对源不同源、N3 确认锁排队占线程池、N4 S1 未修、N5 deployment 证据不自洽、N6 Gateway 幂等 check-then-act。建议 N5 在阶段 7 必须解决，其余可进下一 change。）
