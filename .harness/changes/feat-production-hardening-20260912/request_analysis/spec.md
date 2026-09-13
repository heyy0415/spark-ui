# Spec: feat-production-hardening-20260912

> 生产可用性收口。审计口径见 §1.1；用户决策见 §1.3。**不发包**（别人 clone 后自己发）——发布相关只做"让别人能顺利自己发"。

## 1. 背景

### 1.1 审计发现（2026-09-13，手工逐文件取证；多智能体审计 6/6 因通道限制失败）

| # | 问题 | 位置 | 严重度 |
|---|---|---|---|
| B1 | 确认令牌表永不清理：只有 `put` / `consume`，`tokenTtl` 只在消费时校验；`RunOrchestrator.evictExpired()` 清 5 个缓存但不含令牌表；接口无 evict 方法 | `InMemoryConfirmationTokenStore:11-21`、`RunOrchestrator:745-752`、`ConfirmationTokenStore` | **blocker**（内存泄漏，增速 = 「发起高风险操作但未确认」频率） |
| B2 | hub 幂等表已完成结果永久保留：`release()` 只删未完成占位，注释明写"已 complete 的必须保留"；无 TTL、无容量上限 | `InMemoryIdempotencyStore:44-51` | **blocker**（每次幂等写留一条永久记录） |
| B3 | hub 多副本破坏幂等与确认链路：四个存储进程内；且 `RunOrchestrator` 另有 5 个按 runId 的进程内缓存（`lastUi` / `stepOutputs` / `inputSchemas` / `clarified` / `confirmLocks`），光换存储端口不够 | `RunOrchestrator:99-112` | **blocker**（两副本时写操作在 A claim、重试落 B → 重复执行；令牌在 A 签发、确认到 B → 找不到） |
| H1 | SSE 断连后 Run 照常跑完全部步骤：`RunEventSink` 无 `isClosed()`，编排器无法感知 | `SseRunEventSink:66-75`、`RunEventSink` | high |
| H2 | 模型不可用时 `/actuator/health` 仍 UP，K8s readiness 会把流量打到 100% 失败的 Pod | 无 `HealthIndicator` | high |
| H3 | 无请求体上限、无优雅停机、starter 无 CORS 配置 | grep 为空 | high |
| M1 | 零负载数据：默认阈值 `runQueue=32 / toolQueue=64 / maxConcurrentPerSession=4` 是推算值 | `SparkRooterProperties` javadoc | medium |
| M2 | 自发包障碍：`0.1.0-SNAPSHOT`（私服 release 仓拒收）；5 个 examples 模块随 `deploy` 一起发；无 source/javadoc 插件 | `spark-rooter/pom.xml` | medium |
| M3 | 无 CI；无 SECURITY / CONTRIBUTING；`.github/` 不存在 | — | medium |
| M4 | README 未在显著位置说明单副本限制；多处数字需随本 change 更新 | `README.md` | medium |

### 1.2 已确认没问题（不在本 change 范围，记录以免重复审）

缺 `SessionIdResolver` 拒绝启动；`/internal/**` 默认关；兜底异常不泄漏堆栈；前端零 XSS 注入点、零 `as any`、`pnpm audit` 全 0；traceId 跨进程携带；README 抽查数字一致；日志密钥红线（实测 0 命中）。

### 1.3 用户决策

| 分叉 | 决策 |
|---|---|
| 多副本存储 | **提供 Redis 实现**（新模块 `spark-rooter-redis`，可选装配） |
| 多副本范围 | **彻底做**：Redis 端口 + 编排器临时态进 Run 聚合 + 分布式确认锁 |
| CI | **加回最小 CI**（受众变了：别人 clone 后立刻有 CI） |
| 断连语义 | **只读步骤提前终止，写步骤跑完** |
| 压测 | **写 Node 压测脚本 + 实测一轮**，把实测值写进 javadoc / README |
| 发包 | **不发**；只消除别人自发的障碍 |

## 2. 范围（In Scope）

### 2.1 内存实现补齐 TTL / 上限（B1 / B2）

- `ConfirmationTokenStore` 接口增 `default List<String> evictExpired(Instant now)`；`InMemoryConfirmationTokenStore` 实现之（按 `expiresAt`）；`RunOrchestrator.evictExpired()` 调它。
- `IdempotencyStore` 接口增 `default void evictCompletedBefore(Instant cutoff)`；`InMemoryIdempotencyStore` 给已完成结果打时间戳并实现之；`InvokeToolUseCase` 在每次 `complete()` 后按节流（每 N 次或每分钟）调一次。TTL 取 `spark.gateway.idempotency-ttl`，默认 `24h`（幂等重放窗口应长于任何合理的客户端重试窗口，短于一天没人会重试同一个 key）。
- 两处都是 **惰性 + 顺手清扫**（与 `InMemoryRunRepository` 同一手法），不引调度器。

### 2.2 编排器临时态进 Run 聚合（B3 前置）

`RunOrchestrator` 五个缓存的处置：

| 缓存 | 处置 | 理由 |
|---|---|---|
| `lastUi` | 进 `Run`（字段 `currentUi`，类型为 **JSON 字符串**） | 前端 `GET /agent/runs/{id}` 要它；跨副本必须随 Run 走。存字符串是因为 `runtime/domain` 不得 import `com.fasterxml`（红线 7 的同类约束），编排器读写时经 mapper 转换 |
| `stepOutputs` | 进 `Run`（字段 `stepOutputs: Map<String,String>`，toolId → 输出 JSON 字符串） | 确认屏需要前置步骤输出；确认请求可能落到另一副本 |
| `inputSchemas` | 进 `Run`（字段 `stepSchemas: Map<String,String>`，**只快照计划各步骤**的 inputSchema，`attachPlan` 时写入） | 评审 M-2：确认可能落到另一副本，那时若从注册表重取，工具被宿主策略下线会让 `typedArgs` 拿不到 schema → 参数全按字符串 → Gateway `INPUT_INVALID`，排查不到根因。Run 必须自包含。recheck 工具必在计划内（`executeConfirmed` 强制），澄清路径参数为空无需 schema。3 步 ≈ 1.5 KB |
| `clarified` | 进 `Run`（字段 `boolean clarified`） | 决定终态是否覆盖记忆 |
| `confirmLocks` | 换为 `ConfirmationTokenStore` 语义上的**原子消费**已提供的互斥 + Run 状态机 | 分析：并发两次确认，令牌 `consume` 是原子 remove，只有一个拿到；另一个得 `TokenUnknown` → `rejectRequest`。现有 `ReentrantLock` 防的是「两个请求同时通过前置校验后都去 execute」——但令牌消费已在前置校验之内且原子，第二个必然拿不到令牌。**Redis 版 `GETDEL` 同样原子**。因此本地锁在语义上冗余，删除；用 e2e「评审 M2 并发两次确认」现有断言 + 新增 Redis 双副本断言证明 |

`Run` 新增字段均可为空；`InMemoryRunRepository` 不受影响。`Run` 增静态 `restore(...)` 供 Redis 快照重建（构造器保持"新建"语义不动）。

评审 S-1：`confirm()` 迁移到 EXECUTING 后**立即 `runs.save`**——Redis 版每次 `find` 是新副本，不 save 则另一副本的 `GET` 在执行窗口内看到过时状态。这不影响并发安全（第二个确认在另一副本通过过时的状态校验后，`GETDEL` 拿 nil → `TokenUnknown` → `rejectRequest`，后者不改 Run），只影响可观测性。

评审 L-1：终态时清空 `stepOutputs` 再 save（只服务确认屏），`currentUi` 保留。

### 2.3 Redis 模块（B3）

新模块 `spark-rooter-redis`（JDK 21，parent 子模块，位于平台模块之后、provider-starter 之前）：

| 类 | 实现要点 |
|---|---|
| `RedisRunRepository` | key `spark:run:{runId}` → JSON（专用 DTO `RunSnapshot`，不直接序列化 `Run`）；`EXPIRE` = `runTtl`；`save` = `SET` + `EXPIRE`；`evictExpired()` 返回空列表（由 Redis TTL 负责，编排器无需再清缓存——缓存已进 Run） |
| `RedisConfirmationTokenStore` | key `spark:token:{token}` → JSON；`put` = `SET … PX ttl`；`consume` = `GETDEL`（原子，一次性）；`evictExpired` 空实现 |
| `RedisIdempotencyStore` | key `spark:idem:{scope}/{key}`。`claim`：`SET … NX PX (timeoutMs + 5s)` 值为 `"CLAIMING"` → Owner；已有值且 ≠ `CLAIMING` → Replay；= `CLAIMING` → **Awaiting，future 由本地轮询（每 50ms `GET`）完成**：值变为响应 → 正常完成；**值变为 nil（owner release 或崩溃后 key 过期）→ 异常完成，让 `claimOrAwait` 重 claim**——与内存版 `release` 唤醒语义对齐（评审 S-2）。`complete` = `SET` 值为 response JSON + `PX idempotencyTtl`。`release` = Lua「值仍为 CLAIMING 才 DEL」。+5s 余量：Gateway 的 `f.get(timeoutMs)` 超时后会 release，正常路径 owner 总在 key 过期前动作 |
| `RedisConversationMemory` | key `spark:memory:{sessionId}/{conversationId}` → JSON，`PX memoryTtl` |
| `SparkRooterRedisAutoConfiguration` | `@AutoConfiguration(after = RedisAutoConfiguration.class, before = SparkRooterAutoConfiguration.class)`，`@ConditionalOnClass(StringRedisTemplate.class)` + `@ConditionalOnBean(StringRedisTemplate.class)` + `@ConditionalOnProperty(spark.storage=redis)`。四个 Bean 各 `@ConditionalOnMissingBean`，宿主仍可单独替换 |

**Awaiting 语义差异写明**：内存版是 future 被 owner 直接 complete；Redis 版是轮询。等待精度 50ms，对「同 key 并发」这一异常路径够用。

**不做**：Redis Cluster 专项、哨兵配置（走 Spring Boot 的 `spring.data.redis.*` 即可）、Lettuce/Jedis 选型（跟宿主）。

### 2.4 断连提前终止（H1）

- `RunEventSink` 增 `default boolean isClosed() { return false; }`；`SseRunEventSink` 返回 `closed.get()`。
- `runSteps` 循环：取出下一步后，若 `sink.isClosed()` **且**该步骤不是写操作 → `fail(run, RunFailure(INTERNAL_ERROR, "client gone"))`，用户文案不重要（没人在看），日志 `run abandoned runId={} at step={} reason=client_gone`。
- 「写操作」判定：从 **`ToolMetaRegistry`** 取（`ToolCandidate` 六字段固定且契约禁增）：`ToolMeta` 增 `boolean sideEffect`，扫描器从 `@SparkRisk.sideEffect` 填；**取不到视为写，不终止**（fail-safe）。`requiresConfirmation` 的步骤本身会停在 `waitForConfirmation`，不受影响。
- **已知限制**（评审 S-3）：`ToolMetaRegistry` 只由本进程 `SparkToolScanner` 填，http 注册的 provider 工具没有 `ToolMeta` → 断连优化对 provider 工具不生效（全按写处理）。修法是仿 `ToolNameSink` 加风险回填 spi 端口，独立 change。写进 README。
- `executeConfirmed` 路径**不检查**断连：令牌已消费、用户已明确确认，必须跑完。
- 不新增 `RunFailureCode`（那会动 sse-events 契约与前端投影）；`run.failed` 也发不出去（连接已断），只影响 `GET /agent/runs/{id}` 的 `failureCode=INTERNAL_ERROR`，可接受。

### 2.5 健康探针（H2）

- starter 新增 `SparkReadinessHealthIndicator`（`@ConditionalOnClass(HealthIndicator.class)`，与 `MetricsBeans` 同手法 `ObjectProvider`）：
  - 规划器为 `UnavailablePlanner`（`llm.name()` = `"unavailable"`）→ `DOWN`，detail `planner=unavailable`
  - 否则 `UP`；`LlmCircuitBreaker.state()` **只进 detail**（`circuit=open|closed`），**不改状态**
- **为什么熔断不映射为 OUT_OF_SERVICE**（评审 M-1）：`LlmCircuitBreaker.state()` 不做时间驱动转换，OPEN → HALF_OPEN 只发生在真实请求调用 `shouldSkip()` 时。若熔断即摘流量，则「无流量 → 无探测 → 永不恢复 → 永不挂回」——死锁而非雪崩。readiness 只反映**静态**不可用（配置缺失，不会自愈）；熔断是靠流量自愈的瞬态，让流量继续进来才能恢复。
- 注册为 `sparkRooter` 组件；Spring Boot 的 readiness group 由宿主决定要不要把它加进 `management.endpoint.health.group.readiness.include`——**示例宿主加**，README 写明。
- liveness 不受影响（不接 `sparkRooter` 组件）。

### 2.6 运维默认值（H3）

starter 不替宿主决定 CORS（前端同源或宿主网关处理）——只在 README 写明。以下两项进**示例宿主** `application.yml` 并在 README「生产清单」列出：

```yaml
server:
  shutdown: graceful          # SIGTERM 后等在飞请求（含 SSE）收尾
  tomcat.max-http-form-post-size: 64KB
spring.lifecycle.timeout-per-shutdown-phase: 30s
```

`intent-request.message` 契约已限 2000 字符，请求体上限主要防非契约路径。

### 2.7 压测脚本 + 实测（M1）

- `.harness/scripts/load-test.mjs`（Node 20 原生 `fetch`，无新依赖）：参数 `--concurrency N --duration Ns --port`；对 fake planner 后端并发发 `POST /agent/runs`（intent 示例）并消费 SSE 到终态；输出 `total / ok / rejected(RATE_LIMITED 或 OVERLOADED) / p50 / p95 / p99 / max` 与拒绝首次出现的并发数。
- 实测一轮（本机，fake planner），把结果写入 `deployment/load_test.md`；据此复核三个默认值——**若实测与推算差 2 倍以上则改默认值并更新 javadoc**，否则只把实测数写进 javadoc「实测」字样。
- 脚本进 `check-shell`？否（是 .mjs）；进 README「开发与质量门禁」。

### 2.8 自发包路径（M2，不发包）

- parent pom：`examples/*` 5 个模块移入 `<profile><id>examples</id><activation><activeByDefault>true</activeByDefault>`；README 写 `mvn deploy -P '!examples'`。
- parent pom：新增 `<profile><id>release</id>` 挂 `maven-source-plugin` + `maven-javadoc-plugin`（`doclint=none`，避免第三方注解噪音），默认不激活。
- **版本号不动**（用户决策项，Agent 不擅自改）；README 写 `mvn versions:set -DnewVersion=x.y.z` 步骤。
- 前端：`@spark-ui/core` 已 `private=false`、`publishConfig` 完整；README 写 `pnpm publish --registry`。
- `check-module-deps` 增一条：`examples/` 模块必须在 `examples` profile 内（防以后有人加模块加回 `<modules>`）。

### 2.9 CI（M3）

- `.github/workflows/ci.yml`：一个 job，`ubuntu-latest`，JDK 21 + Node 20 + pnpm 10；`pnpm -C .harness run ci` + `doctor`；`SPARK_CHANGE` 指向本 change（CI 上多个非 DONE change 时 change-dir 会退出 2）。**只跑门禁，不跑 e2e**（e2e 需 Playwright 浏览器与两进程，首版不进 CI；README 写明本地跑）。
- `SECURITY.md`（报告渠道 + 支持版本）、`CONTRIBUTING.md`（8 阶段流程指引 + 门禁命令）。

### 2.10 文档（M4）

- 根 `README.md` **重写**（用户要求：最完善、文字自然）。结构：一句话 → 演示 → 为什么这样设计 → 功能 → 快速开始 → 配置模型 → 接入 → 拓扑 → **部署到生产**（新：单副本 vs 多副本、Redis、健康探针、优雅停机、密钥）→ 架构 → 可观测性 → 安全 → **自己发包** → 门禁 → 已知限制 → 许可。
- `spark-rooter/README.md` 配置表：增 `spark.storage`、`spark.gateway.idempotency-ttl`、`spring.data.redis.*` 指引。
- `.harness/rules/backend-standard.md`：增「多副本」小节（哪些状态可进程内、哪些必须共享）。
- `.harness/wiki/architecture.md`：可替换端口清单同步。
- `agent-safety.md` §3：确认互斥改为「令牌原子消费」口径。

## 3. 非目标（Out of Scope）

- 发布到 Maven Central / npm（用户明确不发）。
- Redis Cluster / Sentinel 专项配置、连接池调优。
- 前端 SSE 断线自动重连（当前语义是"回合失败，用户重发"，够用；改它涉及 run-summary 轮询与状态合并，独立 change）。
- CORS 默认值（宿主 / 网关职责）。
- 请求限流（全局 QPS）——现有单会话并发 + 线程池有界已覆盖过载面。
- e2e 进 CI（需浏览器与双进程；首版 CI 只跑门禁）。
- provider 侧指标 / provider 幂等 Redis 化（provider 是别人的服务，存储选型归宿主；README 已写替换方式）。
- MCP、服务发现、其他技术栈 provider。
- 改版本号。

## 4. 核心场景

### 4.1 多副本确认链路（Redis）

```
浏览器 ──POST /agent/runs──▶ hub-A ── Redis: spark:run:{id} / spark:token:{t} 写入
                             hub-A ── SSE: … ui.replace confirmation.required（连接关）
浏览器 ──POST /agent/runs/{id}/actions/confirm──▶ hub-B（负载均衡随机）
                             hub-B ── Redis: GET spark:run:{id}（含 currentUi / stepOutputs）
                             hub-B ── Redis: GETDEL spark:token:{t}（原子，一次性）
                             hub-B ── Gateway: claim spark:idem:{sess}/{id}-refund.create-3 → Owner
                             hub-B ── 执行 → complete → SSE run.completed
浏览器 ──POST 同令牌重放──▶ hub-A
                             hub-A ── GETDEL → nil → TokenUnknown → run.failed{CONFIRMATION_REJECTED}
```

### 4.2 断连

```
浏览器 ──POST /agent/runs「最近 5 单已发货的订单」──▶ hub：计划 [order.list.search]
浏览器 关闭页面（SSE 断）
hub：取下一步 order.list.search（sideEffect=false）→ sink.isClosed()=true → 终止，日志 run abandoned
```

```
浏览器 ──confirm──▶ hub：令牌消费 → 浏览器断 → executeConfirmed 不检查断连 → refund.create 执行完 → Run COMPLETED
```

### 4.3 健康

```
未配模型启动 → GET /actuator/health/readiness → {"status":"DOWN","components":{"sparkRooter":{"status":"DOWN","details":{"planner":"unavailable"}}}}
网关连挂 6 次 → circuit OPEN → readiness OUT_OF_SERVICE → 30s 后 HALF_OPEN → 探测成功 → UP
```

## 5. 契约影响

**无 Schema 变更。** 核对：

- `run-summary`：`currentUi` 已存在（原来从 `lastUi` 取，现在从 `Run.currentUi` 取，形状不变）。
- `sse-events`：不增 `RunFailureCode`；断连用 `INTERNAL_ERROR`。
- `tool-search`：候选六字段不动；`sideEffect` 从 `ToolMetaRegistry` 取，不经契约。
- `tool-manifest` / `tool-invoke` / `error-response` / `ui-schema` / `intent-request` / `action-request`：不动。

## 6. 验收标准

### 6.1 单测（`mvnw test` 0）

- T01：令牌表 `evictExpired` 只删过期；幂等表 `evictCompletedBefore` 只删已完成且过期，未完成占位不删。**自证**：删实现 → 红。
- T02：`Run` 新字段序列化往返（`RunSnapshot` ↔ `Run`）字段逐一相等；`RunOrchestrator` 现有 13 + 确认路径全部用例绿；`RunOrchestratorTest` 增「`GET` 的 `currentUi` 来自 Run 而非编排器缓存」（构造第二个编排器实例共享同一 `RunRepository`，从它读 `lastUi` 得到同一屏）。
- T03：Redis 四实现用 **真实本机 Redis**（`redis-cli ping` 通才跑，否则 `@EnabledIf` 跳过并打印原因）：claim/Awaiting/Replay/release 语义与内存版测试同一套断言（抽成 `IdempotencyStoreContract` 抽象测试类，两实现各继承）；令牌 `GETDEL` 一次性；Run TTL。
- T04：`runSteps` 在 `isClosed()` 且下一步只读时终止、下一步写时继续；`executeConfirmed` 不受 `isClosed()` 影响。**自证**：去掉判定 → 红。
- T05：`SparkReadinessHealthIndicator` 三态。
- T06：`ToolMeta.sideEffect` 由扫描器从 `@SparkRisk` 填；缺注解 → false。

### 6.2 e2e / 脚本

- `e2e-backend` **≥ 161 passed, 0 failed**（现有断言全绿；新增：断连后 `GET` 状态 FAILED 且 backend.log 含 `run abandoned`）。
- `e2e-provider` **15 / 0**、`e2e-frontend` **7**、`deploy-verify` **12 / 0**（deploy-verify 增：`/actuator/health/readiness` 在 fake planner 下 UP——fake planner 的 `name()` ≠ `unavailable`）。
- **新** `e2e-multi-instance.sh`：起 Redis（本机已有）+ 两个 hub（`spark.storage=redis`，不同端口）；在 A 发起退款 → 在 B 确认 → `run.completed`；同令牌回 A 重放 → `CONFIRMATION_REJECTED`；A/B 各 `GET` 同 runId 得同 `currentUi`；幂等：对 B 直调 gateway 同 key → `replayed` 审计行。**≥ 8 条断言**。
- `load-test.mjs` 跑 `--concurrency 64 --duration 30s` 出报告；报告进 `deployment/load_test.md`。
- `pnpm -C .harness run ci` **0**；`doctor` **0 errors**。
- `check-module-deps`：新模块红线自证（把 `spark-rooter-redis` 加进 spi 的 pom → 红；examples 模块挪回 `<modules>` → 红）。
- Redis 模块可选性：`host-demo` 依赖树 `spring-data-redis` 计数 **0**（不引 redis 模块时）；provider-demo 同 0。

### 6.3 文档

- `doctor` 的 README 口号词门禁 0 命中。
- README 每个数字（工具数 / 指标数 / e2e 数 / 配置默认值 / 契约数）与代码逐项核对，记录在 `coding_report`。
- `mvn -q -DskipTests -P '!examples' install` 只装 8 个平台 artifact（`ls ~/.m2/repository/com/sparkrooter | wc -l` 对比）。

## 7. 风险与权衡

| 风险 | 缓解 |
|---|---|
| **agent-safety §3 确认互斥**：删 `confirmLocks` 后靠令牌原子消费。风险：若将来有人把 `consume` 改成"先 GET 再 DEL"（非原子），并发确认会双执行 | `IdempotencyStoreContract` / 令牌测试里加**并发消费恰好一个成功**断言（32 线程），内存与 Redis 两实现都跑；`agent-safety.md` §3 改口径并注明"原子性是安全前提" |
| Redis 不可用时 hub 行为 | 不做降级到内存（那会静默破坏多副本一致性——比宕机更糟）。连接失败 → 请求 500 + readiness DOWN（Spring Boot 的 `RedisHealthIndicator` 自动接管）。README 写明 |
| `Run.currentUi` 存 JSON 字符串，每次 `GET` 反序列化 | 屏 ≤ 几 KB，`GET` 频率低；比让 `domain/` 依赖 Jackson 更值 |
| `inputSchemas` 改为每步重取 | 现在 `start()` 已调一次 `search`；改为把 `found` 存进局部变量传给 `runSteps`（同一 Run 内不重取），只有**确认路径**（另一副本）需重取一次 `search`。成本可接受 |
| 断连判定依赖 `ToolMeta.sideEffect`，provider 形态的工具 `ToolMeta` 从何来 | 现状：http 工具**没有** `ToolMeta`（`SparkToolScanner` 只扫本进程）。`sideEffect` 取不到时 → **视为写操作，不终止**（fail-safe：宁多跑一步只读，不砍掉一步写）。写进 javadoc |
| Redis Awaiting 轮询 50ms | 只影响「同 key 并发提交」异常路径；正常链路零轮询 |
| CI 首次在 GitHub 上跑可能因缓存 / 路径失败 | 复用上次已跑通的 workflow 骨架（`65e5b6c`），只删 e2e job |
| README 重写丢内容 | 逐节对照旧版；旧版每个标题在新版有归宿或在 coding_report 写明删除理由 |
