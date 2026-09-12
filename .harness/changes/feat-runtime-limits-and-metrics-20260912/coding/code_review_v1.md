# 代码评审 v1 — feat-runtime-limits-and-metrics-20260912

## 0. 独立性声明

**自评审，不满足独立性要求。** subagent 通道返回 `400 专用渠道限制`，派不出独立评审 agent（本 change 的审计阶段已因此吃过一次亏：6 个 probe agent 全失败、workflow 算出假的 `confirmed: []`）。

本轮降偏差的三条做法：

1. **只提能用失败测试或实测证明的问题** —— 每条 MUST FIX 都先写出会红的断言或跑真实进程取证；
2. **对"没问题"的判断也要取证** —— §5 的两条 SHOULD 是靠实测排除的，不是靠推理；
3. **重新检查上一阶段的自证是否有效** —— 结果发现一条假测试（见 F-3）。

**建议他人复核**：§2.1 F-1 的名额语义（等待幂等结果也占名额）是用户决策，但我对"同 key 并发是异常模式"的判断没有实测数据支撑。

## 1. 结论

**APPROVED WITH FIXES** —— 3 项 MUST FIX 已修并回归；2 项 SHOULD 经实测**排除**（非缺陷）。

五套回归全绿：`e2e-backend` **161**、`e2e-provider` **15**、`e2e-frontend` **7**、`deploy-verify` **12**、前端单测 **107**；`pnpm -C .harness run ci` **0**。

## 2. MUST FIX（已修）

### F-1 ｜ 限流名额包住了幂等的「等待别人结果」段

**位置**：`InvokeToolUseCase:254-267`，lease 包住 `pipelineWithinLease`，其中含 `claimOrAwait`

**核对**：`claimOrAwait`（`:304`）对 `idempotency=required` 的工具会阻塞等待 owner 的结果，最长 `execution.timeoutMs`。lease 在外层，所以等待者也占名额。

**具体场景**：同 `idempotencyKey` 的 4 个并发调用 → 1 个 owner 真执行、3 个在等 → 该会话第 5 个正常查询被 `RATE_LIMITED`，尽管只有 1 个工具在跑。

**实测确认**（写了 characterization test）：owner + waiter 共占 **2** 个名额。

**处置**（用户决策：保持现状，写清语义）：

名额衡量的是**在飞请求数**而非**在跑的工具数**。理由：等待并非免费（占一个 Gateway 线程 + 一个 HTTP 连接）；且「同一会话对同一 key 并发提交」本身是异常模式（通常是前端重复提交），拒绝它是合理行为而非误伤。

已把语义写进 `SessionConcurrencyLimiter` javadoc，并把探针改为 **characterization test**（`waitersOnIdempotentKeyAlsoHoldQuota`）—— 将来有人改成「等待不占名额」时会红，促使他先回看这条决策而不是默默改掉。

**我原来的判断是错的**：我最初认为这是缺陷（"等待不占资源，不该占名额"）。核对后发现等待确实占线程与连接，把它当"免费"才是误判。

### F-2 ｜ `RATE_LIMITED` 的用户文案未落实 spec 承诺

**位置**：`RunOrchestrator:674-679`

**核对**：Gateway 的 `RATE_LIMITED` 经 `:676` 归入 `TOOL_EXECUTION_FAILED`，用户看到「执行过程中工具调用失败，请稍后重试」。而 spec §3.3 明确承诺「当前请求较多，请稍后重试」。

**为什么要分开**：二者对用户的含义不同 —— 过载稍后重试有意义，工具真的失败可能反复失败。而 `gatewayCode` 本就一路传了下来，分开的成本只是一个 if。

**修法**：`RATE_LIMITED` 走 `RunFailure.withUserText(...)` 给专属文案。**不新增 `RunFailureCode` 枚举值** —— 那会动 `sse-events` 契约与前端投影，而用户只需要一句准确的话，不需要一个新错误码。

文案常量本地定义并注明「与 `AgentRunController.OVERLOADED_TEXT` 必须一致」：两处都是「系统忙」（那边是编排池拒绝，这边是会话超限），文案不同会让用户以为是两种问题。**不共享常量**是因为方向 —— runtime 不能依赖 web-mvc（project-structure §2），字符串重复两份优于反向依赖。

**自证**：去掉专属文案 → `rateLimitedShowsOverloadTextNotGenericToolFailure` 变红。

### F-3 ｜ 上一阶段有一条假测试（sessionId 写错，断言恒真）

**位置**：`InvokeToolUseCaseTest:404`（T03 写的 `leaseIsReleasedEvenWhenToolFails`）

**发现过程**：我为 F-1 写探针时断言 `inFlight("sess_1")` 得到 `expected: 2 but was: 0`。查 `request()` 发现测试请求的 sessionId 是 **`"sess"`** 而非 `"sess_1"`。

**后果**：T03 的释放断言查的是一个**从未存在的 key**，永远为 0 —— 它什么也没证明。三处受影响（`:404` 释放断言、`:507` 基数断言、我的新探针）。

**修法**：抽 `private static final String SESSION = "sess"` 常量，三处统一引用，`request()` 也改用它。**重新自证**：删掉 `lease.close()` 后 `leaseIsReleasedEvenWhenToolFails` 这次**真的变红**了。

**教训**：T03 阶段我做过"自证"，但自证的是限流拒绝那条（改 `RATE_LIMITED`→`FORBIDDEN` 会红），没单独自证释放那条。**自证必须逐条做，不能因为同一个测试类里有一条会红就认为整类有效。**

## 3. 经实测排除的 SHOULD（非缺陷）

| 疑点 | 取证方式 | 结论 |
|---|---|---|
| `registry.counter(...)` 每次调用都新建 meter，拖慢主链路 | 写 `SimpleMeterRegistry` 探针实测 | **非缺陷**：同名同标签返回**同一实例**（`c1 == c2` 为 true），累加共享，registry 里只有 1 个 meter。Micrometer 内部按 name+tags 缓存，这是 map 查找而非分配 |
| provider 侧无埋点 | grep `ProviderInvokeController` | **非缺陷（是范围外）**：spec 的目标是 hub 侧可观测性，provider 埋点未列入。但这是微服务形态下的真实覆盖缺口，已记入 §6 遗留 |

**排除同样需要取证**：第一条我本可以凭"Micrometer 应该会缓存"直接放过，但那是推测。实测只花几分钟，却把"应该"变成了"确实"。

## 4. 逐项核对

| 项 | 结论 |
|---|---|
| 契约变更是否非破坏性 | ✓ 两份契约都是**新增枚举值**。`tool-invoke` 无前端投影；`error-response` 有，已同步 Zod 并加断言。`$id` / `schemaVersion` 未动 |
| 新增 invalid 示例是否真的被拒 | ✓ `tool-invoke.unknown-error-code.invalid.json` 看到 `rejected`（证明加值后 enum 仍在约束） |
| 单体零回归 | ✓ 每个 task 都跑 161；日志零 `session busy`（既有链路单会话并发 1，不触发） |
| G2 是否误伤现有最长链 | ✓ 实测 e2e 步数分布 1/2/3，上限 6；专门有"误伤哨兵"用例（上限 ≤ 3 则红） |
| G3 计数是否泄漏 | ✓ 归零即 `remove(key, value)` 两参版本；100 次申请释放后 `trackedSessions()` 为 0 |
| G3 并发正确性 | ✓ CAS 循环而非"先自增再回退"；32 线程并发断言放行数**恰好**等于上限 |
| G5 审计失败 | ✓ 成功路径返回 succeeded、失败路径保留原错误码；自证去掉 try-catch 则 2 条红 |
| 埋点标签低基数 | ✓ **实测** `/actuator/metrics/spark.tool.invocations` 的 availableTags 只有 `toolId` / `status`；单测另断言样本 toString 不含 ID |
| 埋点覆盖 Run 每个出口 | ✓ 三处：`complete()` / `fail()` / `rejectRequest()`；自证漏任一处则红 |
| Micrometer 可选性 | ✓ **实测** provider-demo 依赖树 `micrometer-core` 数为 **0**，host-demo 为 **1**。optional 真的阻断了传递 |
| 门禁自证 | ✓ 三条（runtime 加依赖 / starter 去 optional / provider 加依赖）全部可红可绿 |
| 指标数量与文档一致 | ✓ 代码 7 个，spec / tasks / README 逐项核对一致（原写 6，实现期补了 `run.duration`，已全部改对） |
| 阈值是常量还是配置 | ✓ `MAX_PLAN_STEPS` 常量（与仓内 `MAX_ATTEMPTS` / `MAX_TEXT` / `MAX_COLUMNS` 同例）；`maxConcurrentPerSession` 可配（需按池容量调） |

## 5. 评审中修正的一处自己的误判

F-1 我最初判为缺陷（"等待不占资源"），核对后发现等待占着 Gateway 线程与 HTTP 连接，**把它当免费才是误判**。改为"写清语义 + characterization test"而非改实现。

这与阶段 2 评审里 M-3 的情况相反：那次是我的前提错了（以为比对跑在 provider 侧），这次是我的价值判断错了。两种都需要核对代码才能发现。

## 6. 遗留与已知限制

1. **本评审非独立**（见 §0）。
2. **commit 仅本地，未 push**（公司规则：仅在用户明确要求时 push；禁止直接 push 主分支）。
3. **provider 侧无埋点**：微服务形态下 provider 的工具调用不出指标（范围外）。要补需在 `ProviderInvokeController` 接 `ToolMetricsSink`，并处理"provider 不依赖 Micrometer"的约束（用日志实现或让宿主自选）。
4. **`spark.llm.*` 在 e2e 下不出现**：假规划器不经 `LlmPlanner`。带真 key 跑才能验证 LLM 指标 —— **本轮未验证过**。
5. **G4（会话记忆条数上限）判定不做**：上界是「TTL 窗口内活跃会话 × 几百字节」，加淘汰策略属 speculative。理由已写进 README 已知限制。
6. **未查**：`ConfirmationTokenStore` 与 `RunRepository` 的容量上限（内存实现，与 G4 同类）。阶段 2 评审已记为后续方向。
