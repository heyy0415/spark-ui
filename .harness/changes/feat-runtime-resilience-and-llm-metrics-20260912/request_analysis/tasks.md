# Tasks: feat-runtime-resilience-and-llm-metrics-20260912

编码顺序：属性先行 → 线程池 → 拒绝路径 → 熔断 → 埋点 → 文档。契约零改动，无 contracts task。每个 task ≤ 0.5 天。

> **v2 修订**（依据 `review/spec_review_v1.md`）：T01 的 `failureThreshold` 默认值 5 → 2（S-2）；T03 验收加「工具池拒绝不挂死」（S-1）；T04 验收的熔断判据从耗时改为日志（S-3）。

## T01 属性类扩展

- **目标**：后续几个 task 需要的配置项一次加完，避免反复改同一个文件。
- **所属端**：spark-rooter（starter）
- **输入**：spec §2.5 的属性清单；`SparkRooterProperties.java` 现有结构（record + `@DefaultValue`）
- **输出**：
  - `Runtime` record 加 `@DefaultValue("32") int runQueue`
  - `Gateway` record 加 `@DefaultValue("64") int toolQueue`
  - `Llm` record 加 `@DefaultValue("90s") Duration readTimeout` 与 `@DefaultValue Circuit circuit`
  - 新增 `Circuit` record：`enabled`（默认 true）、`failureThreshold`（默认 **2**，依据见 spec §2.3 的层级关系表）、`openDuration`（默认 30s）
  - 每个新字段的 javadoc 说明默认值依据（公司规范：枚举与关键常量必须注释业务含义）
- **验收**：`node .harness/scripts/mvn.mjs -q -B compile` 退出 0；`grep -c 'runQueue\|toolQueue\|readTimeout\|Circuit' SparkRooterProperties.java` ≥ 5
- **依赖**：无

## T02 线程池有界化

- **目标**：三处线程池不再用 `Executors`，run / tool 两处有界且有拒绝策略。
- **所属端**：spark-rooter（starter）
- **输入**：T01 的属性；`NamedThreads.java` 现有的 `named(prefix)`；三处线程池（`RuntimeBeans:114`、`GatewayBeans:39`、`SparkRooterWebMvcAutoConfiguration:37`）
- **输出**：
  - `NamedThreads` 新增 `boundedPool(core, queueCapacity, prefix)`：`new ThreadPoolExecutor(core, core, 0L, MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), named(prefix), new AbortPolicy())`
  - `runExecutor` / `toolExecutor` 改用它
  - `pingScheduler` 改为 `new ScheduledThreadPoolExecutor(pingPool, named("sse-ping-"))`，**注释说明为何不设界**（`DelayedWorkQueue` 无法设容量；任务是固定 15s 心跳，数量与活跃 SSE 连接同阶）
- **验收**：
  - `grep -rn 'Executors\.new' spark-rooter --include='*.java' | grep -v '/target/' | grep -v '/src/test/'` 无命中
  - 新增 `BoundedPoolTest`：队列满时抛 `RejectedExecutionException`、线程名前缀正确
  - `mvn install` 退出 0
- **依赖**：T01

## T03 拒绝路径：SSE 发 run.failed

- **目标**：过载时用户立刻收到失败与「请稍后重试」，而不是 SSE 挂着不动。
- **所属端**：spark-rooter（web-mvc）
- **输入**：`AgentRunController.submit()`（`:120-131`）；`RunFailure.withUserText` 的既有用法（`RunOrchestrator.java:67` 的 `POLICY_REJECTED_TEXT`）
- **输出**：
  - `submit(Runnable)` 改为 `submit(RunEventSink sink, Runnable task)`，捕获 `RejectedExecutionException` → 向该 sink 发 `run.failed{INTERNAL_ERROR, "当前请求较多，请稍后重试"}` → `sink.close()`
  - WARN 日志含线程池名与当前队列深度（`ThreadPoolExecutor.getQueue().size()`）
  - 两处调用点（`:77`、`:95`）同步
  - **不新增契约枚举值**（spec §2.2 的取舍）
- **验收**：
  - 临时 `--spark.runtime.run-pool=1 --spark.runtime.run-queue=1` 启动，并发 5 个请求，至少一个收到 `run.failed` 且 message 含「请稍后重试」；日志含拒绝 WARN。还原配置后复验正常
  - **工具池拒绝不挂死**（S-1）：临时 `tool-pool=1 tool-queue=1`，并发 5 个请求全部收到终态事件（无挂死），被拒者为 `run.failed{TOOL_EXECUTION_FAILED}`
  - `mvn install` 退出 0
- **依赖**：T02

## T04 LLM 熔断器

- **目标**：模型网关挂掉时第 N+1 个请求立刻失败，不再各自等满超时。
- **所属端**：spark-rooter（runtime）
- **输入**：T01 的 `Circuit` 属性；`LlmPlanner.plan()` 的异常分支（传输类 vs 校验类）；`LlmFactory` 的装配
- **输出**：
  - `runtime/infra/llm/LlmCircuitBreaker`：3 态状态机，`AtomicInteger` 连续失败数 + `AtomicLong` OPEN 起始时刻，无锁
  - `LlmPlanner` 接入：OPEN 时不发请求直接抛 `RunFailure.withUserText(INTERNAL_ERROR, "llm circuit open", "模型服务暂时不可用，请稍后重试")`
  - **只统计传输类失败**（`RestClientException` / `TransientAiException` / `NonTransientAiException`）；`TOOL_SELECTION_INVALID` 不计入
  - `LlmFactory.llmClient(...)` 签名加熔断器参数；`RuntimeBeans` 装配时传入
- **验收**：
  - 新增 `LlmCircuitBreakerTest`：连续失败达阈值转 OPEN、OPEN 拒绝、`openDuration` 后 HALF_OPEN、HALF_OPEN 成功转 CLOSED / 失败回 OPEN、**校验失败不计入**
  - 端到端（S-3 修正判据）：`SPARK_LLM_BASE_URL=http://127.0.0.1:1` 连发 5 个请求（阈值 2），第 3 个起日志含 `circuit open` **且不含** `llm upstream error`（证明没发出请求——「返回得快」无法区分熔断与连接拒绝）
  - `mvn install` 退出 0
- **依赖**：T01

## T05 LLM 埋点（精简）

- **目标**：知道每次对话的 outcome、耗时、token。
- **所属端**：spark-rooter（spi + runtime + starter）
- **输入**：spec §2.4；`AuditSink` 的端口风格；Spring AI 的 `ChatResponse.getMetadata().getUsage()`
- **输出**：
  - spi 新增 `LlmMetricsSink` + `Sample` record（token 用包装类型 `Integer`，上游不返回 usage 时为 null）
  - runtime 新增默认实现 `LogLlmMetricsSink`（`LLM_METRICS` logger，token 为 null 时输出 `-`）
  - `LlmPlanner` 在每次 `plan()` 结束时（含失败与熔断）打点
  - starter 以 `@ConditionalOnMissingBean` 装配
  - **不引 Micrometer、不接 Prometheus**
- **验收**：
  - LIVE 模式下 `grep -c LLM_METRICS backend.log` ≥ 1
  - 假规划器模式下 `grep -c LLM_METRICS backend.log` == 0（fake 不经 `LlmPlanner`）
  - `mvn install` 退出 0
- **依赖**：T04（打点要记录熔断状态）

## T06 文档与规则

- **目标**：新属性可查，线程池规则写进 backend-standard。
- **所属端**：spark-rooter / harness
- **输入**：spec §2.5
- **输出**：
  - `spark-rooter/README.md` 配置表补 6 个新属性，注明默认值依据（尤其 `read-timeout` 与 `sse-timeout` 的关系）
  - `backend-standard.md` §6 补线程池有界 + `ScheduledThreadPoolExecutor` 例外说明
- **验收**：`pnpm -C .harness run doctor` 退出 0；`pnpm -C .harness run ci` 退出 0
- **依赖**：T05

## T07 全链路回归

- **目标**：默认配置下行为不变。
- **所属端**：harness
- **输入**：三套验收脚本
- **输出**：无代码改动，只跑验收
- **验收**：
  - `SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh` 退出 0 且 `0 failed`
  - `SPARK_PORT=8091 bash .harness/scripts/e2e-frontend.sh` 退出 0
  - `SPARK_PORT=8091 bash .harness/scripts/deploy-verify.sh` 退出 0 且 `0 failed`
- **依赖**：T06
