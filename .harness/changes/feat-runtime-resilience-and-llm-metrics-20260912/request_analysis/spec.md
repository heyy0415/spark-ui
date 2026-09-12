# Spec: feat-runtime-resilience-and-llm-metrics-20260912

> 改造清单第 6、7、8' 三项合并。都在 starter 的装配层与 runtime 的 LLM 基础设施，改动区域重叠，分开做要重复跑三轮门禁。
>
> 目标：负载高时快速失败而非静默堆积（6）；模型网关故障时立刻告知而非让用户等满超时（7）；知道每次对话花了多少 token 与时间（8'）。
>
> **v2 修订**（依据 `review/spec_review_v1.md`，APPROVED + 3 SHOULD 全部采纳）：S-1 补 `toolExecutor` 拒绝路径的说明与验收 4b；S-2 熔断阈值 5 → **2** 并写明与 Spring AI 内部重试的层级关系；S-3 熔断验收从「耗时」改为「日志」判据。

## 1. 背景

### 1.1 三处线程池无界（第 6 项）

| 位置 | 现状 |
|---|---|
| `RuntimeBeans.java:114` | `Executors.newFixedThreadPool(runPool=8)` |
| `GatewayBeans.java:39` | `Executors.newFixedThreadPool(toolPool=8)` |
| `SparkRooterWebMvcAutoConfiguration.java:37` | `Executors.newScheduledThreadPool(pingPool=2)` |

`Executors.newFixedThreadPool` 用的是 `LinkedBlockingQueue` **无界**队列。后果：请求量超过 8 个并发时任务无声排队，队列可以涨到 OOM；用户侧表现为 SSE 连接挂着不动，既不失败也不返回。这同时违反公司 Java 规范「禁止使用 `Executors` 创建线程池；线程池必须设置合理的队列容量、拒绝策略和线程名称」。

### 1.2 LLM 超时写死 3 分钟且无熔断（第 7 项）

- `LlmFactory.java:31` 的 `READ_TIMEOUT = Duration.ofMinutes(3)` 是编译期常量，无法按模型调整。
- `LlmFactory.java:88` 的 `fastFailRetry()` 重试 3 次；`LlmPlanner.java:21` 的 `MAX_ATTEMPTS = 2`（校验失败喂回模型）。最坏情况：3 分钟 × 3 次重试 × 2 轮校验 = 用户等 18 分钟才收到失败。
- 无熔断：模型网关挂掉时每个请求各自等满超时，`agent-run-*` 线程池（8 个）很快占满，系统连「快速告知不可用」都做不到。

### 1.3 零 metrics（第 8' 项）

只有 `LogAuditSink` 打审计日志（`grep -rln micrometer` 零命中）。回答不了「这次对话花了多少 token / 多少秒」。

**本项按用户决定做精简版**：只埋 LLM 的 token 与耗时，**不接 Prometheus、不做 Run / 工具 / 确认三组指标**。理由：完整可观测是为运维团队准备的，本仓库是单人开源项目，短期无生产流量；而 token 消耗是真实花钱的地方，有直接价值。

## 2. 范围（In Scope）

### 2.1 线程池有界化（第 6 项）

- 新增 `starter/NamedThreads.boundedPool(int core, int queueCapacity, String prefix, RejectedExecutionHandler)`，内部用 `new ThreadPoolExecutor(...)`（不用 `Executors`）。现有 `NamedThreads.named(prefix)` 保留作线程工厂。
- 三处改用它：
  - `runExecutor`：core = `spark.runtime.run-pool`（默认 8），队列 = 新增属性 `spark.runtime.run-queue`（默认 **32**），拒绝策略 `AbortPolicy`（抛 `RejectedExecutionException`）。
  - `toolExecutor`：core = `spark.gateway.tool-pool`（默认 8），队列 = 新增 `spark.gateway.tool-queue`（默认 **64**，比 run 大因为一个 Run 可能有多个工具步骤），`AbortPolicy`。
  - `pingScheduler`：`ScheduledThreadPoolExecutor` 的队列**本质无界**（`DelayedWorkQueue`），无法设容量。**不改队列**，只改为显式 `new ScheduledThreadPoolExecutor(...)` 以满足「不用 `Executors`」，并在注释说明为何此处不设界（任务是固定 15s 心跳，数量与活跃 SSE 连接同阶，不会失控）。
- **队列容量默认值的算式**（v2 补充，评审 L-1）：按 LIVE 规划实测 9–39s（`SparkRooterProperties:30` 注释）、8 个并发处理，第 33 个任务的预期等待 = (32 / 8) × 9~39s = **36~156s**，已超出 SSE 的 90s 上限——也就是说排到第 32 位之后必然等不到结果，不如直接拒绝。`tool-queue=64` 取 2 倍，因为一个 Run 可能有多个工具步骤（退款链是 3 步）。
  将来调容量时请重算这个式子，它依赖 `sse-timeout` 与规划耗时两个量。

### 2.2 拒绝时的用户可见行为（第 6 项的关键设计）

`AgentRunController.submit()` 是 `void`，调用时 `SseEmitter` **已经返回给客户端**（`:78`、`:99`），所以拒绝不能用 HTTP 状态码，必须通过 SSE 发终态事件。

- `submit()` 捕获 `RejectedExecutionException`，向该连接发 `run.failed` 后 `sink.close()`。
- **失败码选用 `INTERNAL_ERROR`，不新增契约枚举值**。取舍理由：
  - 新增 `OVERLOADED` 之类的值属**契约变更**，要改 `sse-events.schema.json` 的 enum、`run-summary.schema.json`、后端 `RunFailureCode`、前端 Zod 投影，且前端得为它写新的展示分支。
  - 而前端对这个场景的处理与 `INTERNAL_ERROR` 完全一致（显示错误文案、允许重发）。为一个无差异的展示分支付契约变更的代价不值。
  - 用户文案区分：`RunFailure.withUserText(INTERNAL_ERROR, …, "当前请求较多，请稍后重试")` —— 契约码不变，用户看到的文案准确。这与既有的 `POLICY_REJECTED_TEXT` 用法一致（`RunOrchestrator.java:67`）。
- 服务端日志用 WARN 记录拒绝，含线程池名与当前队列深度，便于判断是否该调容量。

**`toolExecutor` 拒绝走另一条路径**（v2 补充，评审 S-1）：

`InvokeToolUseCase:307` 的 `executor.submit(...)` 抛的 `RejectedExecutionException` 是**同步 RuntimeException**，不经 `ExecutionException` 包装 —— 现有三个 catch（`TimeoutException` / `ExecutionException` / `InterruptedException`）都抓不到。它会冒泡到 `RunOrchestrator.invoke` 的 `catch (RuntimeException e)`（`:642`），被当作传输层错误转成 `ToolCallFailed(TOOL_EXECUTION_FAILED)`，用户看到「执行过程中工具调用失败，请稍后重试」。

**本 change 接受这个结果，不加额外处理**，理由：用户可见文案已包含「请稍后重试」，行为正确（失败而非挂死）；在 Gateway 里显式 catch 再转码属扩大范围。但**必须验证它确实不挂死**（验收 4b），因为「恰好正确」与「设计正确」的区别就在于有没有测过。

措辞偏差是已知的：用户看到「工具调用失败」而实际原因是系统过载。记为遗留债务，若将来 Gateway 要做过载区分再改。

### 2.3 LLM 超时分级与熔断（第 7 项）

- **超时可配**：`READ_TIMEOUT` 常量改为属性 `spark.llm.read-timeout`（默认 **90s**，不再是 3 分钟）。默认值依据：SSE 超时是 90s（`spark.runtime.sse-timeout`），LLM 读超时不该超过它——超过意味着 SSE 先断，用户看不到结果而后端还在等。推理模型需要更久时由使用者显式配大，同时也要配大 `sse-timeout`。
- **熔断器**：新增 `runtime/infra/llm/LlmCircuitBreaker`，纯 JDK 实现（不引第三方依赖，避免给宿主增加依赖树）：
  - 状态：CLOSED → （连续失败 ≥ `failureThreshold`）→ OPEN → （静默 `openDuration` 后）→ HALF_OPEN → （一次成功）→ CLOSED，（HALF_OPEN 失败）→ OPEN。
  - 属性：`spark.llm.circuit.failure-threshold`（默认 **2**）、`spark.llm.circuit.open-duration`（默认 30s）、`spark.llm.circuit.enabled`（默认 true）。

  **阈值为何是 2 而非 5**（v2 修正，评审 S-2）：`LlmFactory:88` 的 `fastFailRetry()` 是 Spring AI 的 `RetryTemplate`，作用在 `OpenAiChatModel` **内部**——一次 `chat.prompt()...call()` 已经自带 3 次重试。所以熔断器看到的「一次失败」= 3 次真实网关请求。

  | 阈值 | 熔断前的真实请求数 | 最坏等待（90s 超时） |
  |---|---|---|
  | 5（初版） | 15 | 数分钟 |
  | **2**（采用） | 6 | ~9 分钟降到 ~3 分钟 |

  取 2 是在「避免瞬时抖动误熔断」与「快速失败」之间折中：一次抖动通常被 `RetryTemplate` 的 3 次重试内部消化，能穿透到熔断器的已是连续故障。不改重试次数是有意限制范围（§3 非目标），但这个层级关系必须写明，否则阈值看起来是随手拍的。

  - OPEN 期间 `LlmPlanner.plan()` **不发请求**，直接抛 `RunFailure.withUserText(INTERNAL_ERROR, "llm circuit open", "模型服务暂时不可用，请稍后重试")`。
  - 只统计**传输类失败**（`RestClientException` / `TransientAiException` / `NonTransientAiException`）。校验失败（`TOOL_SELECTION_INVALID`）**不计入**——那是模型输出不合规，不是服务不可用，计入会让「模型能力不足」误触发熔断。
  - 线程安全：用 `AtomicInteger` 计连续失败数 + `AtomicLong` 记 OPEN 起始时刻，无锁。

### 2.4 LLM 埋点（第 8' 项，精简）

- 新增 spi 端口 `LlmMetricsSink`（宿主可替换，与 `AuditSink` 同风格）：
  ```
  record Sample(String outcome, long durationMs, Integer promptTokens, Integer completionTokens, int attempt, boolean circuitOpen)
  void record(Sample sample)
  ```
  `outcome` ∈ `{planned, clarify, no_capability, invalid_output, transport_error, circuit_open}`。
- 默认实现 `LogLlmMetricsSink`（落 `LLM_METRICS` logger，一行一次调用），starter 以 `@ConditionalOnMissingBean` 装配。
- token 来源：Spring AI 的 `ChatResponse.getMetadata().getUsage()`。**若上游网关不返回 usage 则为 null**，Sample 的两个 token 字段因此用包装类型 `Integer` 而非 `int`（公司 Java 规范：POJO 字段用包装类型避免默认值歧义）。
- **不做**：Micrometer 依赖、Prometheus 端点、Run / 工具 / 确认三组指标、`AuditSink` 的可查询实现。

### 2.5 属性与文档

- `SparkRooterProperties` 新增：`runtime.runQueue`、`gateway.toolQueue`、`llm.readTimeout`、`llm.circuit.{enabled,failureThreshold,openDuration}`。
- `spark-rooter/README.md` 的配置表补这些项。
- `backend-standard.md` §6 补一句：「线程池必须有界并显式拒绝策略；`ScheduledThreadPoolExecutor` 的延迟队列无法设界，此类池需在注释说明任务量为何不会失控」。
- `host-demo/README.md` 与根 `README.md` 无需改（不涉及接入方式变化）。

## 3. 非目标（Out of Scope）

- **不做第 9 项 Redis 状态存储**（用户明确暂不做；接口已抽好，需要时再补）。
- **不做完整可观测**：不引 Micrometer、不接 Prometheus、不埋 Run / 工具 / 确认指标、不做 `AuditSink` 可查询实现。
- 不新增契约枚举值（见 §2.2 的取舍）；本 change **契约零改动**。
- 不改 `pingScheduler` 的队列容量（`DelayedWorkQueue` 本质无界，见 §2.1）。
- 不引入第三方熔断库（Resilience4j 等）——纯 JDK 够用，避免给宿主加依赖树。
- 不改 LLM 的重试次数（`fastFailRetry` 3 次、`MAX_ATTEMPTS` 2 轮）：熔断已解决「网关挂掉时的雪崩」，重试次数是另一个维度，无证据表明当前值不合适。
- 不做多模型 failover。

## 4. 核心场景

- **过载**：并发请求超过 `run-pool + run-queue` 时，后来者立刻收到 `run.failed{INTERNAL_ERROR}` 与「当前请求较多，请稍后重试」，而不是 SSE 挂着不动直到 90s 超时。
- **模型网关挂掉**：前 2 个请求各自失败（每个内部已被 Spring AI 重试 3 次），第 3 个起熔断 OPEN，立刻返回「模型服务暂时不可用」。30s 后 HALF_OPEN 放一个探测，网关恢复则整体恢复。
- **看花了多少钱**：`LLM_METRICS` 日志一行一次调用，含 outcome、耗时、prompt/completion token。
- 运行链路不变：拒绝与熔断都发生在编排之前，`PlanValidator` / Gateway / 领域实现全部未改。

## 5. 契约影响

- **NONE**。§2.2 已论证为何不新增 `RunFailureCode` 枚举值。

## 6. 验收标准

1. `pnpm -C .harness run ci` 退出 0（9 步）；`pnpm -C .harness run doctor` 退出 0。
2. `grep -rn 'Executors\.new' spark-rooter --include='*.java' | grep -v '/target/' | grep -v '/src/test/'` **无命中**（三处全部改为显式 `ThreadPoolExecutor` / `ScheduledThreadPoolExecutor`）。
3. 新增单测（后端）：
   - `BoundedPoolTest`：队列满时 `submit` 抛 `RejectedExecutionException`；线程名前缀正确。
   - `LlmCircuitBreakerTest`：连续失败达阈值转 OPEN；OPEN 期间拒绝；`openDuration` 后转 HALF_OPEN；HALF_OPEN 成功转 CLOSED、失败回 OPEN；校验失败不计入。
   - 全部单测（现有 131 + 新增）`mvn install` 退出 0。
4. **过载行为端到端可验**：临时把 `run-pool=1` `run-queue=1` 启动，并发发 5 个请求，至少一个收到 `run.failed` 且 `message` 含「请稍后重试」；日志含拒绝 WARN。验证后还原配置。
4b. **工具池拒绝不挂死**（v2 补充，评审 S-1）：临时 `tool-pool=1` `tool-queue=1`，并发发 5 个请求，全部在 SSE 超时内收到终态事件（`run.completed` 或 `run.failed`），**无连接挂死**；被拒的那条为 `run.failed{TOOL_EXECUTION_FAILED}`。
5. **熔断行为可验**（v2 修正，评审 S-3）：配 `SPARK_LLM_BASE_URL=http://127.0.0.1:1`（立刻 ConnectionRefused），连发 5 个请求（阈值 2，第 3 个起应熔断）。判据是**日志而非耗时**——「返回得快」无法区分熔断与连接拒绝本来就快：
   - 第 3 个及之后的请求日志含 `circuit open`
   - 且这些次**不含** `llm upstream error`（证明没有发出请求，这才是熔断生效的判据）
   - `grep -c 'llm upstream error'` 的总数应 ≈ 2 × 3 = 6（阈值 × Spring AI 重试），而非 5 × 3 = 15
6. `SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh` 退出 0 且 `0 failed`（默认配置下行为不变，161 条断言不受影响）。
7. `SPARK_PORT=8091 bash .harness/scripts/deploy-verify.sh` 退出 0 且 `0 failed`。
8. `LLM_METRICS` 埋点在 LIVE 模式下有输出；假规划器模式下**不输出**（fake 不经 `LlmPlanner`）——后者用 `grep -c LLM_METRICS` == 0 验证。

## 7. 风险与权衡

| 风险 | 缓解 |
|---|---|
| 队列容量默认值拍得不准，正常流量被拒 | 32 / 64 均 ≈ 4–8 倍核心数，且可配。验收 4 用极端小值（1/1）验拒绝路径，默认值下 e2e 161 条不受影响即证明不误拒 |
| 读超时从 3 分钟降到 90s，推理模型可能不够 | 改为可配是本项的核心改进。README 说明「推理模型需同时配大 `read-timeout` 与 `sse-timeout`」；90s 的依据是不该超过 SSE 超时 |
| 熔断误触发（把模型能力不足当成服务不可用） | 只统计传输类异常，`TOOL_SELECTION_INVALID` 明确不计入；单测覆盖这一条 |
| 熔断状态是进程内的，多副本各自独立 | 接受。多副本本就需要第 9 项，而它已明确不做。单副本下熔断完全有效 |
| 自研熔断器有并发 bug | 状态机极简（3 态 + 2 个原子变量），单测覆盖全部转换。不引第三方是为了不给宿主加依赖 |
| `RejectedExecutionException` 发生在 `submit` 内，此时 `SseEmitter` 已返回 | §2.2 已明确走 SSE 发 `run.failed`；验收 4 端到端验证 |
| token 字段用 `Integer` 可能被误当 0 处理 | 上游不返回 usage 时为 null 是真实情况；`LogLlmMetricsSink` 输出 `-` 而非 0 |
| `toolExecutor` 拒绝的用户文案有偏差（「工具调用失败」而实际是过载） | 已知并接受（§2.2），行为正确（失败而非挂死）且文案含「请稍后重试」。验收 4b 验证不挂死。记为遗留债务 |
| 熔断阈值 2 可能对抖动频繁的网关过于敏感 | 阈值可配；且穿透到熔断器的失败已被 Spring AI 内部重试 3 次消化过一轮。若误熔断，`open-duration` 仅 30s，HALF_OPEN 会自动探测恢复 |
