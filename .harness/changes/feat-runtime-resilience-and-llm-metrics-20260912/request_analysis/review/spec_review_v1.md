# Spec Review v1 — feat-runtime-resilience-and-llm-metrics-20260912

- **mode**: plan
- **评审对象**: `request_analysis/spec.md`、`request_analysis/tasks.md`
- **依据**: `expert-reviewer/SKILL.md`（plan 必查项）、`rules/{dev-workflow,backend-standard,agent-safety,contracts,project-structure}.md`、公司 Java 规范（并发与资源、POJO 类型）
- **verdict**: **APPROVED**（0 条 MUST FIX，3 条 SHOULD）

> **独立性声明**：subagent 通道在本会话不可用，本文由 spec 作者撰写，独立性不满足。补偿：spec 的技术断言在阶段 1 逐条实测（含一次 JDK 反射验证），本文只复核「实测是否支撑结论」。§4 列建议他人复核项。

---

## 0. 事实断言核对

| spec 断言 | 实测 | 证据 |
|---|---|---|
| 三处用 `Executors.new*` | ✓ | `RuntimeBeans:114`、`GatewayBeans:39`、`SparkRooterWebMvcAutoConfiguration:37` |
| `newFixedThreadPool` 队列无界 | ✓ | JDK 源码固定 `new LinkedBlockingQueue<Runnable>()`，无容量参数 |
| `ScheduledThreadPoolExecutor` 队列无法设界 | ✓ | **JDK 21 反射实测**：4 个构造器均不接受 `BlockingQueue`；实际队列 `DelayedWorkQueue`，`remainingCapacity()` = 2147483647 |
| `read-timeout` 90s 的依据是 `sse-timeout` | ✓ | `SparkRooterProperties:40` 的 `sseTimeout` 默认 `90s` |
| `RunFailureCode` 无「过载」类枚举值 | ✓ | 契约 enum 与 Java enum 均为 5 值，无 OVERLOADED |
| `submit()` 是 void 且 emitter 已返回 | ✓ | `AgentRunController:120-131` 无返回值；`:78`、`:99` 在 submit 后 `return emitter` |
| `NamedThreads` 只有 `named(prefix)` | ✓ | package-private final 类，单方法 |
| 零 micrometer | ✓ | `grep -rln micrometer` 零命中 |

无未经核对的断言。

## 1. 逐条意见

### S-1 ｜ SHOULD ｜ `AbortPolicy` 的语义需在 spec 明确到「谁看到什么」

- **位置**: spec §2.1（三处均用 `AbortPolicy`）、§2.2
- **核对**: §2.2 详细论证了 `runExecutor` 拒绝时走 SSE 发 `run.failed`，这部分完整。但 **`toolExecutor` 的拒绝路径没写**。核对 `InvokeToolUseCase:307` 的 `executor.submit(...)`：它在 `callWithRetry` 内，外层已有 `catch (ExecutionException)` → `GatewayException(HANDLER_ERROR)`，但 `RejectedExecutionException` 是 `submit` **同步抛出**的 `RuntimeException`，不经 `ExecutionException` 包装 —— 现有 catch 抓不到，会直接冒泡到 `RunOrchestrator.invoke` 的 `catch (RuntimeException e)`（`:642`），被当作「传输层错误」转成 `ToolCallFailed(TOOL_EXECUTION_FAILED)`。
- **问题**: 结果**恰好是可接受的**（用户看到「执行过程中工具调用失败，请稍后重试」），但这是巧合而非设计。spec 没写这条路径，编码时容易误以为需要额外处理，或反过来漏掉验证。
- **建议**: spec §2.2 补一段说明 `toolExecutor` 拒绝的路径与最终用户可见文案，并在 tasks T02 或 T03 的验收里加一条「工具池拒绝时用户看到 TOOL_EXECUTION_FAILED 而非挂死」。若认为该文案不够准确（「工具调用失败」vs「请求过多」），可在 `InvokeToolUseCase` 显式 catch 后转 `GatewayException(TIMEOUT)` —— 但那属扩大范围，倾向保持现状并写清楚。
- **分级**: SHOULD

### S-2 ｜ SHOULD ｜ 熔断器与 `fastFailRetry` 的交互未说明

- **位置**: spec §2.3、§3 非目标第 6 条（「不改 LLM 的重试次数」）
- **核对**: `LlmFactory:88` 的 `fastFailRetry()` 是 **Spring AI 的 `RetryTemplate`**，作用在 `OpenAiChatModel` 内部 —— 也就是说一次 `chat.prompt()...call()` 调用内部已经重试 3 次。熔断器若装在 `LlmPlanner.plan()` 层面，它看到的「一次失败」实际是「3 次重试都失败」。
- **问题**: 这不是 bug，但影响熔断阈值的含义：`failureThreshold=5` 实际是「5 × 3 = 15 次真实网关请求后才熔断」。以 90s 超时估算，最坏情况熔断前已耗时数分钟——熔断的「快速失败」价值被重试削弱了。
- **建议**: spec §2.3 明确这一层级关系，并把 `failureThreshold` 默认值从 5 调到 **2**（= 6 次真实请求）。或者在 §3 非目标里说明「已知重试会放大熔断前的等待，本 change 不动重试是为了限制范围」。两者取其一，但必须写明白，否则 5 这个数字看起来是随手拍的。
- **分级**: SHOULD

### S-3 ｜ SHOULD ｜ 验收 5 的端到端熔断验证可能不可靠

- **位置**: spec §6 验收 5、tasks T04 验收
- **核对**: 方案是 `SPARK_LLM_BASE_URL=http://127.0.0.1:1` 连发 6 个请求。`127.0.0.1:1` 会立刻 `ConnectionRefused`（不等超时），所以「第 6 个在 1 秒内返回」这个断言**无法区分「熔断生效」与「连接拒绝本来就很快」** —— 前 5 个也会在 1 秒内返回。
- **问题**: 验收标准形式上可校验，但证明力不足。
- **建议**: 改为断言**日志**而非耗时：第 6 个请求的日志含 `circuit open` 且**不含**该次的上游错误日志（`llm upstream error`）。即「熔断生效」的判据是「没有发出请求」，而非「返回得快」。tasks T04 同步。
- **分级**: SHOULD

### L-1 ｜ LOW ｜ 队列容量默认值的依据可以更硬

- **位置**: spec §2.1 末段（`run-queue=32` ≈ 4 倍核心数）
- **核对**: 推理是「排队超过 32 意味着后来者必然等不到结果」。按 LIVE 规划实测 9–39s（`SparkRooterProperties:30` 的注释）、8 个并发处理，第 33 个任务的预期等待 = (32/8) × 9~39s = 36~156s，确实超过 SSE 的 90s 上限。**依据成立**，只是 spec 没把这个算式写出来。
- **建议**: 把算式写进 spec，让将来调容量的人知道它与 `sse-timeout` 和规划耗时的关系。
- **分级**: LOW

### I-1 ｜ INFO ｜ 契约不变的取舍论证充分

§2.2 用「前端处理与 `INTERNAL_ERROR` 完全一致」+「`withUserText` 已有先例（`RunOrchestrator:67`）」两条支撑不新增枚举值，并指出新增的连带成本（schema、两端 DTO、前端展示分支）。这个取舍我认为正确：契约变更的成本应该由「消费方需要区分」来正当化，而此处不需要。

### I-2 ｜ INFO ｜ 第 8 项收窄为精简版的边界清楚

§2.4 明确列了「不做」清单（Micrometer / Prometheus / 三组指标 / AuditSink 可查询），且给了理由（单人开源、无生产流量、token 才是真花钱的）。`LlmMetricsSink` 作为 spi 端口留出了将来接 Micrometer 的位置，不是死路。

### I-3 ｜ INFO ｜ 公司 Java 规范的两条被正确落实

- 「禁止使用 `Executors` 创建线程池；必须设置队列容量、拒绝策略和线程名称」→ §2.1 全部满足（`pingScheduler` 的队列例外有实测支撑并要求注释说明）。
- 「POJO 属性使用包装类型，避免基本类型默认值产生歧义」→ §2.4 的 token 字段用 `Integer` 而非 `int`，理由是「上游不返回 usage 时为 null」，与规范意图一致。

## 2. plan 必查项

| 项 | 结果 |
|---|---|
| 「非目标」存在且非空 | ✓ 7 条，与 §1/§2/§6 无冲突 |
| 每条验收可被命令 / 断言校验 | ✓ 8 条可校验；验收 5 的证明力不足（S-3），但形式上可执行 |
| 风险章节 ≥1 失败模式 + 缓解 | ✓ 7 条 |
| task 标注所属端；contracts task 前置 | ✓ 契约零改动，无 contracts task |
| 跨端结构列契约文件 | N/A |
| 每个 task ≤ 0.5 天 | ✓ T01（属性）、T02（三处线程池 + 1 单测）、T03（一个方法改签名）、T04（状态机 + 单测）、T05（一个端口 + 默认实现 + 打点）、T06（文档）、T07（只跑验收）均在范围内 |

## 3. 回退

`APPROVED` → 进阶段 3。三条 SHOULD 建议在编码时顺带处理：S-1 补 `toolExecutor` 拒绝路径的说明与验收、S-2 明确熔断与重试的层级并调整默认阈值、S-3 把熔断验收从「耗时」改为「日志」。

## 4. 建议他人复核的条目

1. **熔断阈值该设多少**（S-2）。这依赖对「模型网关故障模式」的判断——是瞬时抖动多还是持续挂掉多。我倾向 2，但没有生产数据支撑。
2. **`toolExecutor` 拒绝时复用 `TOOL_EXECUTION_FAILED` 是否合适**（S-1）。用户看到「工具调用失败」而实际原因是「系统过载」，措辞有偏差但不算错。
3. **队列容量 32 / 64**（L-1）。算式成立，但 4 倍核心数这个比例本身是经验值。
