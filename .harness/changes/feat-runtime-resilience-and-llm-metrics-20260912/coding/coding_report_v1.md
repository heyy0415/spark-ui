# Coding Report v1 — feat-runtime-resilience-and-llm-metrics-20260912

- **阶段**: 3 编码实现
- **依据**: `request_analysis/spec.md` v2、`tasks.md` v2、`review/spec_review_v1.md`（APPROVED + 3 SHOULD 全部落实）
- **编码顺序**: T01 → T02 → T03 → T04 → T05 → T06 → T07

## 0. 门禁与验收（真实退出码）

| 项 | 结果 |
|---|---|
| `pnpm -C .harness run ci` | 0（9 步全绿） |
| `pnpm -C .harness run doctor` | 0 |
| `grep -rn 'Executors\.new'`（排除 target / test） | **无命中**（验收 2） |
| `mvn install`（含全部单测） | 0 |
| `e2e-backend.sh` | 0 —— **161 passed, 0 failed** |
| `e2e-frontend.sh` | 0 —— **7 passed** |
| `deploy-verify.sh` | 0 —— **12 passed, 0 failed**，`planner=fake-e2e` |

新增单测：`BoundedPoolTest`（3 用例）、`LlmCircuitBreakerTest`（9 用例）。

## 1. 端到端行为验证（本 change 的核心价值）

### 过载（验收 4）

`run-pool=1 run-queue=1`，并发 6 个请求：

```
#1: run.failed  → run_rejected | INTERNAL_ERROR | 当前请求较多，请稍后重试
#2: run.started … run.completed
#3: run.failed  → 同上
#4: run.started … run.completed
#5: run.failed  → 同上
#6: run.failed  → 同上
```

日志 4 条 `run rejected: agent-run executor saturated, queued=1`。**改造前这 4 个请求会挂到 90s SSE 超时**（无界队列静默排队）。

### 工具池拒绝不挂死（验收 4b，评审 S-1 要求）

`tool-pool=1 tool-queue=1`，并发 5 个请求，**全部收到终态**（2 个 `run.completed` + 3 个 `run.failed`），无挂死。被拒者为 `TOOL_EXECUTION_FAILED | 执行过程中工具调用失败，请稍后重试`——与 spec §2.2 的预测完全一致（`RejectedExecutionException` 冒泡到 `RunOrchestrator:642` 的 `catch (RuntimeException)`）。

### 熔断（验收 5，评审 S-3 修正为日志判据）

`SPARK_LLM_BASE_URL=http://127.0.0.1:1`，**严格串行** 4 个请求：

```
llm outcome=transport_error  durationMs=3121  circuitOpen=false
llm outcome=transport_error  durationMs=3032  circuitOpen=false
llm outcome=circuit_open     durationMs=0     circuitOpen=true
llm outcome=circuit_open     durationMs=0     circuitOpen=true
```

`durationMs=0` 是熔断价值的直接量化：从等 3 秒变成立刻返回。三条判据：

| 判据 | 实测 |
|---|---|
| `circuit open` 日志 | 有，`2 consecutive transport failures (threshold 2), skipping calls for PT30S` |
| `llm upstream error` 总数 | **2**（= 阈值），而非 5 —— 证明熔断后没再发请求 |
| `llm call skipped: circuit open` | 3 次 |

**顺带实测确认了评审 S-2 的推理**：`LLM call failed attempt` 出现 **6 次** = 2（阈值）× 3（Spring AI 内部重试）。这验证了「熔断器看到的一次失败 = 3 次真实网关请求」，所以阈值定 2 而非初版的 5 是对的——按 5 会是 15 次真实请求。

HALF_OPEN 也验了：静默期满 31s 后发请求，`llm circuit half-open` 出现 1 次，`upstream error` 从 2 增到 3（确实发了探测），网关仍挂则回 OPEN。

### 埋点（验收 8）

| 模式 | `LLM_METRICS` 条数 |
|---|---|
| 假规划器（e2e profile） | **0** —— fake 不经 `LlmPlanner`，符合预期 |
| 真 `LlmPlanner` | 3 个请求 → 3 条 |

token 显示 `-` 而非 0（上游未返回 usage）。

## 2. 改动文件

### 属性与装配（starter）

| 文件 | 变化 |
|---|---|
| `SparkRooterProperties.java` | 新增 `Circuit` record；`Llm` 加 `readTimeout` / `circuit`；`Runtime` 加 `runQueue`；`Gateway` 加 `toolQueue`。每项 javadoc 写明默认值依据 |
| `NamedThreads.java` | 新增 `boundedPool(core, queueCapacity, prefix)`：显式 `ThreadPoolExecutor` + `ArrayBlockingQueue` + `AbortPolicy` |
| `RuntimeBeans.java` | `runExecutor` 改有界；新增 `LlmCircuitBreaker` 与 `LlmMetricsSink` 两个 `@ConditionalOnMissingBean` Bean；`LlmFactory` 调用点传新参数 |
| `GatewayBeans.java` | `toolExecutor` 改有界，注释写明拒绝的传播路径 |
| `SparkRooterWebMvcAutoConfiguration.java` | `pingScheduler` 改显式 `new ScheduledThreadPoolExecutor`，注释说明为何不设界 |

### 拒绝路径（web-mvc）

| 文件 | 变化 |
|---|---|
| `AgentRunController.java` | `submit(Runnable)` → `submit(RunEventSink, Runnable)`，捕获 `RejectedExecutionException` → 发 `run.failed` + close；新增 `REJECTED_RUN_ID` / `OVERLOADED_TEXT` 常量与 `queueDepth()` |

### LLM 韧性与埋点（runtime + spi）

| 文件 | 变化 |
|---|---|
| `LlmCircuitBreaker.java` | **新增 118 行**：3 态状态机，`AtomicInteger` + `AtomicLong` 无锁 |
| `LogLlmMetricsSink.java` | **新增 33 行**：默认实现，token 为 null 输出 `-` |
| `spi/LlmMetricsSink.java` | **新增 36 行**：端口 + `Sample` record |
| `LlmPlanner.java` | 接入熔断器（只在传输失败时计数）；`plan()` 拆为外层包装（计时 + 六出口打点）+ `planInternal`；改用 `responseEntity` 一次拿 `ChatResponse` 与 entity |
| `LlmFactory.java` | `READ_TIMEOUT` 常量 → `chat(...)` 参数；`llmClient(...)` 加熔断器与 sink 参数 |

### 文档与规则

| 文件 | 变化 |
|---|---|
| `spark-rooter/README.md` | 配置表补 7 项，含默认值依据；新增「LLM 埋点」小节 |
| `backend-standard.md` §6 | 新增 4 条：线程池必须有界、拒绝必须对用户可见、`ScheduledThreadPoolExecutor` 例外、熔断只统计传输失败 |

## 3. 关键决策

**`plan()` 拆成外层包装 + `planInternal`**。出口有六个（三类决策、校验失败、传输失败、熔断跳过），在每处各写一次打点必漏。包一层统一计时与分类，`RunFailure` 的 code 与 message 用来判定 outcome。

**用 `responseEntity(Class)` 而非 `entity()` + `chatResponse()`**。后者读起来像会再发一次请求；前者一次返回「原始 ChatResponse + 解析后实体」，语义明确。已用 `javap` 确认该方法存在于 `ChatClient$CallResponseSpec`。

**`recordSuccess()` 放在拿到 draft 之后、`decide` 之前**。模型应答了就说明传输通路正常，输出是否合规是另一回事。放在 `decide` 之后会让「输出不合规」间接影响熔断。

**`REJECTED_RUN_ID = "run_rejected"`**。契约要求 `run.failed` 的 runId 匹配 `^run_[A-Za-z0-9_-]{1,60}$`（已查 `sse-events.schema.json`），而此时 Run 尚未创建。用符合 pattern 的占位值，否则前端 Zod 会拒掉这一帧。

**熔断器不引第三方库**。Resilience4j 会进宿主依赖树；状态机只有 3 态 + 2 个原子变量，单测覆盖全部转换。

## 4. 编码期发现

**`Usage.getPromptTokens()` 返回 `Integer` 而非 `int`**（`javap` 确认）。这印证了 spec §2.4 用包装类型的决定——上游不返回 usage 时是 null，写成 `int` 会得到 0 并被误读成「没消耗」。

**`DOMAIN_WORDS` 红线抓到我一次**。我在 `toolQueue` 的 javadoc 里写了「退款链是 3 步」举例，`check-module-deps` 报错：平台模块不得含领域词汇。已改为「需确认的工具还要先跑前置只读步骤」。**这正是该红线存在的意义**——领域举例最容易从注释渗进内核。

**一次测试方法的误判**。并发发 3 个请求时，第 3 个显示 `transport_error` 而非 `circuit_open`，我起初怀疑熔断有 bug。查毫秒级时间线后确认：第 3 个请求在 `18:25:21` 就已通过 `shouldSkip()`，而熔断在 `18:25:23` 才打开——**在途请求不会被中途取消**，这是熔断器的正常语义。改用严格串行验证后得到干净结果。教训：验证并发相关行为时，测试方式本身要先确定。

## 5. agent-safety 六条边界自查

| 条 | 结论 |
|---|---|
| §1 四面职责 | 未变。熔断与拒绝都在编排之前，不改四面划分 |
| §2 工具发现 | 未变。`PlanValidator` 的校验路径一行未动 |
| §3 确认机制 | 未变。令牌签发、重校验、trustedArgs 全未触碰 |
| §4 前端边界 | 未变。新增的 `run.failed` 帧符合既有契约（已核对 runId pattern 与 code enum） |
| §5 Gateway | 工具池改有界，拒绝路径已验证为失败而非挂死；校验 / 幂等 / 审计顺序未变 |
| §6 流式输出 | 埋点落 `LLM_METRICS` 日志，**不进 SSE**；不含 prompt 原文、不含模型名、不含网关地址 |

补充：`LogLlmMetricsSink` 只输出 outcome / 耗时 / token 数量，无任何用户原话或配置信息。

## 6. 已知限制

- **熔断状态是进程内的**：多副本部署时各自独立熔断。这需要第 9 项 Redis，已明确不做；单副本下完全有效（已在类注释说明）。
- **`toolExecutor` 拒绝的用户文案有偏差**：显示「工具调用失败」而实际原因是系统过载。spec §2.2 已论证接受（行为正确、文案含「请稍后重试」），记为遗留债务。
- **`read-timeout` 从 3 分钟降到 90s**：推理模型可能不够。README 已写明「需同时配大 `read-timeout` 与 `sse-timeout`」。
- **埋点只覆盖 LLM 一处**：Run / 工具 / 确认三个维度未埋，spec §3 明确为非目标。`LlmMetricsSink` 作为 spi 端口留出了接 Micrometer 的位置。
