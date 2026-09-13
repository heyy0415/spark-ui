# Coding Report v1 — feat-production-hardening-20260912

## 概要

T01–T11 全部完成，T12 全链路回归见 §5。契约零变更（spec §5 核对成立：`run-summary.currentUi` 形状不变、`sse-events` 不增码、`tool-search` 六字段不动）。spark-ui 零改动。

改动 45 个文件：平台代码 20、测试 9、脚本 / 门禁 5、规则 / wiki 5、文档 3、新模块 1（`spark-rooter-redis`，9 个文件）、CI 1。

## 逐 task

### T01 内存实现补 TTL（B1 / B2）

| 文件 | 改动 |
|---|---|
| `runtime/infra/InMemoryConfirmationTokenStore.java` | 加 `Clock`；`put` 时 `removeIf(expired)`。与 `InMemoryConversationMemory` 同一手法：没有新确认屏就没有新增长，不需要定时器 |
| `gateway/infra/InMemoryIdempotencyStore.java` | 值从 `CompletableFuture` 改为 `Slot{future, completedAt}`；`complete` 记时间戳，每 64 次触发 `sweep()`；占位中的槽位（`completedAt == null`）永不清 |
| `starter/SparkRooterProperties.java` | `Gateway` 增 `idempotencyTtl` 默认 24h |
| `starter/GatewayBeans.java` / `RuntimeBeans.java` | 两个 Bean 注入 `Clock` 与 TTL |

**spec 偏差**：spec 说"接口增 `evictExpired` / `evictCompletedBefore`，编排器 / Gateway 调用"。实现改为**存储内部自清**（写入时 / 按计数节流）。理由：接口加方法要每个实现都实现（Redis 版是空实现），而"什么时候变小"本就是存储自己的事；编排器多一个调用点等于多一处会被忘掉的地方。语义等价，更收敛。

**自证**：掏空 `sweep()` → `sweepRemovesCompletedEntriesOlderThanTtl` + `completeTriggersSweepEveryNCalls` 红；删 `put` 里的 `removeIf` → `tokenStorePutSweepsExpiredTokens` 红；恢复 → 全绿。

### T02 编排器临时态进 Run 聚合（B3 前置）

| 文件 | 改动 |
|---|---|
| `runtime/domain/Run.java` | 增 `currentUi: String` / `stepOutputs: Map<String,String>` / `stepSchemas: Map<String,String>` / `clarified: boolean`；`attachPlan(plan, schemas, now)` 快照计划各步骤 schema；`restore(...)` 静态重建；`clearStepOutputs()` |
| `runtime/application/RunOrchestrator.java` | 删 `lastUi` / `inputSchemas` / `stepOutputs` / `clarified` / `confirmLocks` 五个字段；全部读写改走 `Run`；`confirm()` 迁移 EXECUTING 后立即 `runs.save`（评审 S-1）；终态清 `stepOutputs` 再 save（评审 L-1）；`lastUi(runId)` 改读仓储 |

**`confirmLocks` 删除的依据**（spec §2.2 分析 + 评审 S-1 核实）：并发确认的互斥完全由 `tokens.consume` 的原子性提供——`rejectRequest` 不改 Run，第二个请求即使通过了过时的状态校验，`consume` 拿到空 → 拒绝。`RunOrchestratorConfirmTest.concurrentConfirmationsExecuteExactlyOnce`（32 线程）锁住这条。

**自证**：`lastUi` 改回实例内读 → `lastUiIsReadFromRepositoryNotFromInstance` 红（连原有的 `singleReadOnlyStep…` 也红）；让 `TokenUnknown` 不拒绝而是伪造令牌继续 → 并发用例红（`恰好一个确认真正执行` expected 1）。

### T03 Redis 模块

新模块 `spark-rooter-redis`，9 个文件：

| 类 | 要点 |
|---|---|
| `RunSnapshot` | 专用 DTO，**不带 `Run.message`**（e2e-multi-instance 第 8 条当场红了：首版原样带了用户原话）。`restore` 出来的 `message` 为 null，运行时规划后无路径再读它 |
| `RedisRunRepository` | `SET … PX runTtl`；`evictExpired()` 返回空 |
| `RedisConfirmationTokenStore` | `put` 用令牌自身 `expiresAt` 算 TTL，已过期不存；`consume` = `GETDEL` |
| `RedisIdempotencyStore` | `SET NX PX claimTtl` 值 `CLAIMING`；Awaiting 靠 50ms 轮询：值变响应 → 完成，变 nil → 异常完成让调用方重 claim（评审 S-2）；`release` 用 Lua「仍是 CLAIMING 才 DEL」 |
| `RedisConversationMemory` | 直存 `Memory` record |
| `SparkRooterRedisAutoConfiguration` | `before = SparkRooterAutoConfiguration`（让 starter 的 `@ConditionalOnMissingBean` 让位）、`after = RedisAutoConfiguration`；`@ConditionalOnProperty(spark.storage.type=redis)` + `@ConditionalOnBean(StringRedisTemplate)` |
| `SparkRedisProperties` | 只有 `claimTtl`（默认 5m）；TTL 类沿用 `spark.runtime.*` / `spark.gateway.*` 已有值，不重复定义 |

**spec 偏差**：spec 写 `spark.storage=redis`，实现是 `spark.storage.type=redis`（record 属性需要一层）。README / 配置表已按实际写。

**Redis 不可用时不降级**：连接失败 → 500 + Spring Boot 自带 `RedisHealthIndicator` DOWN。`RuntimeBeans` 在 `storage.type=redis` 却走到内存实现时 WARN（缺模块 / 缺 `StringRedisTemplate`），不拒绝启动（单副本误配的宿主不该被拒）。

测试 17 条真实 Redis（`@EnabledIf` 探 6379，不通则 skip 而非 pass）；每测试独立 key 前缀，不 FLUSHDB。可选性实测：host-demo 引了 redis 模块但 `storage.type=memory` 时 `spark storage: redis` 日志 0 命中、health 不受 Redis 影响；provider-demo 依赖树 `spring-data-redis` 0。

### T04 断连提前终止

| 文件 | 改动 |
|---|---|
| `runtime/.../port/RunEventSink.java` | `default boolean isClosed()` |
| `web-mvc/.../SseRunEventSink.java` | 返回 `closed.get()` |
| `spi/tool/ToolMeta.java` | 增 `sideEffect`；保留 7 参构造器（默认 false）兼容测试与 provider 侧 |
| `contracts/tool/ManifestDeriver.java` | 从 `@SparkRisk.sideEffect` 填 |
| `RunOrchestrator.runSteps` | 取步后 `sink.isClosed() && !isWrite(toolId)` → `fail(INTERNAL_ERROR, "client gone")`，日志 `run abandoned … reason=client_gone`；`isWrite` 取不到元数据视为 true |

**e2e 断言口径调整**：spec 要求 e2e 断言 `run abandoned` 出现。实测 fake planner 下三步退款链全程 ~2ms，`curl --max-time 0.05` 也来不及在两步之间断开，断言恒 0。改为 e2e 只守「断开后 Run 必到终态、不悬挂」；「剩余只读步骤被终止」由单测用可控 sink 证明（`clientGoneBeforeReadOnlyStepAbandonsRun` / `…WriteStepStillExecutes` / `…UnknownToolMetaIsTreatedAsWrite`）。这是把断言放到能真正区分行为的层。

**自证**：`if (false)` 替换判定 → `clientGoneBeforeReadOnlyStepAbandonsRun` 红。

### T05 健康探针

`HealthBeans`（`@ConditionalOnClass(HealthIndicator)`）+ `SparkReadinessHealthIndicator`：`planner=unavailable` → DOWN；熔断只进 detail（评审 M-1：摘流量会让熔断器永不恢复）。starter pom 加 `spring-boot-actuator` optional（与 micrometer 同一手法）。

host-demo `application.yml`：`probes.enabled`、readiness 组含 `sparkRooter`、`show-details`。**Dockerfile HEALTHCHECK 改打 `/actuator/health/liveness`**——根端点聚合了 `sparkRooter`，没配模型时 DOWN，会让容器被反复重启。`deploy-verify` 的 health 断言改为只比 `status` 字段（开 probes 后响应多了 `groups` / `components`）。

实测：无模型启动 → 根 health DOWN、readiness 503、`sparkRooter: {DOWN, planner=unavailable, circuit=closed}`；fake planner → readiness 200。

### T06 运维默认值 + 自发包路径

- host-demo yml：`server.shutdown=graceful`、`timeout-per-shutdown-phase=30s`、`max-http-form-post-size=64KB`
- parent pom：examples 5 模块进 `examples` profile（`activeByDefault`）；`release` profile 挂 source + javadoc（`doclint none`，默认不激活）
- `check-module-deps` 增两条：Redis 模块 / `spring-data-redis` 不得出现在 8 个平台 / provider 模块 pom；`examples/` 不得回到顶层 `<modules>` 且 `examples` profile 必须存在

实测：`-P '!examples' install` → `~/.m2/com/sparkrooter` 10 个目录（9 artifact + parent）；`-P release` → spi 产出 `-sources.jar` / `-javadoc.jar`；默认 install → 15 个。门禁双向自证：runtime 反向依赖 redis → 红；`demo-support` 挪回 `<modules>` → 红；恢复 → 绿。

### T07 压测

`load-test.mjs`（Node 20 原生 fetch）。**首版有一个假象**：一轮出现 1022s 的"最大延迟"——实际是本机短连接风暴耗尽临时端口后 `connect()` 卡住，服务端毫无痕迹。加了 100s 客户端硬超时、`wall` 实际耗时、`client_timeout` 单列后不再出现。量具自己先要可信。

结论（`deployment/load_test.md`）：三个默认值与实测吻合，**不改**。javadoc 里的「估算」全部改为「实测 + 数字」。真模型下约 40 个并发对话是单副本实际上限，要更多就水平扩而不是调队列。

### T08 多副本 e2e

`e2e-multi-instance.sh` 21 条断言全绿：两 hub 共享 Redis db 15，在 A 发起 → B 上 GET 看到 WAITING_CONFIRMATION 与 currentUi → 在 B 确认（B 审计有 refund.create、A 没有）→ A 看到 COMPLETED → 回 A 重放被拒 → 对 A 直调 gateway 同 key 得 `replayed` 且执行数不变 → A 写记忆 B 解析「第二个」→ Redis 值里无用户原话。

首版踩到 bash 的 `$REDIS_DB；` 被当成变量名的一部分（全角分号紧跟），`set -u` 报 unbound。全部改 `${REDIS_DB}`；`check-shell` 未抓到这类问题，记入经验沉淀。

### T09 CI + 社区文件

`.github/workflows/ci.yml` 单 job（JDK 21 / Node 20 / pnpm 10 / Redis service 容器 / shellcheck），跑 `ci` + `doctor`；e2e 不进 CI。`SECURITY.md` / `CONTRIBUTING.md`。`dev-workflow.md` 阶段 6 口径同步。

### T10 规则与 wiki

`agent-safety.md` §3 增「Run 自包含」「确认互斥靠令牌原子消费」「用户原话不进共享存储」；`backend-standard.md` 增 §7b 多副本 / §7c 客户端断开 / §7d 健康探针；`project-structure.md` 模块树 + 依赖方向 + examples profile 红线；`architecture.md` 依赖方向 + 可替换端口。

### T11 README 重写

结构按 spec §2.10；新增「部署到生产」（单/多副本、探针、停机、断连、容量、密钥、Docker）与「自己发包」两节；「已知限制」去掉「全内存状态」（已解决），加 provider 断连与注册表两条。口号词门禁 0 命中。数字逐项核对见 §4。旧版 12 个 H2 全部有归宿（`Docker 部署` 并入 `部署到生产`）。

## 4. README 数字核对

| README 写的 | 核对来源 | 结果 |
|---|---|---|
| 14 tools / 6 beans | `backend.log` | ✓ |
| 7 个平台模块 + Redis + 2 starter | `pom.xml` `<module>spark-*` = 9 | ✓ |
| 发 9 个 artifact | `-P '!examples' install` → 10 目录含 parent | ✓ |
| e2e-backend 162 / e2e-frontend 7 / deploy-verify 14 / e2e-provider 15 / e2e-multi-instance 21 | 各脚本本轮输出 | ✓ |
| 前端单测 107 / 后端 275 | `pnpm -r test` / `mvn test` 汇总 | ✓ |
| 9 契约 / 28 示例 / 10 种 SSE 事件 / 5 白名单组件 / 7 指标 | 目录计数 / 源码 grep | ✓ |
| pack 约 30 KB | `pnpm pack` 实测 30 KB（首稿写 32，已改） | ✓ |
| 单副本约 40 并发对话 | 8 核心 + 32 队列，真模型每 run 占线程数十秒 | 推导，已注明 |

## 5. 回归（T12）

| 项 | 结果 |
|---|---|
| `mvnw install`（含单测） | BUILD SUCCESS，275 单测 0 失败（含 Redis 18 条真实 Redis；评审后 +2） |
| `e2e-backend` | **162 passed, 0 failed**（+1：断连后 Run 到终态） |
| `e2e-provider` | **15 / 0** |
| `e2e-frontend` | **7 passed** |
| `deploy-verify` | **14 / 0**（+2：readiness 组 UP、含 sparkRooter） |
| `e2e-multi-instance` | **21 / 0**（新） |
| 前端单测 | **107**（零改动，数字不变） |
| `check-module-deps` | 新增 2 条规则，双向自证 |
| `doctor` | 0 errors / 0 warnings |
| `pnpm -C .harness run ci` | 见 `ci_result/ci_summary.md` |

一次假阳性：e2e-backend 与 deploy-verify 并行跑时共用同一 change 的 `deployment/backend.log`，后者启动截断了前者的启动段，15 条 grep 启动日志的断言红。单独重跑全绿。教训：验收脚本不能并行跑在同一 change 目录下——已记入经验沉淀。

## 6. 关键决策记录

1. **内存存储自清而非接口加 evict**：什么时候变小是存储自己的事，编排器不该多一个会被忘掉的调用点。
2. **删 `confirmLocks`**：互斥押在令牌原子消费上，内存 / Redis 两实现各有 32 线程并发测试锁住。
3. **`inputSchemas` 快照进 Run 而非重取**：评审 M-2——确认落到另一副本时注册表可能已变，Run 必须自包含。
4. **熔断不改 readiness**：评审 M-1——摘流量会让熔断器锁死。
5. **`RunSnapshot` 不带 `message`**：e2e 断言当场抓到，与日志红线同一口径。
6. **断连 e2e 断言降级为"到终态"**：fake planner 太快，两步之间断不开；行为由单测证明。把断言放到能区分行为的层，而不是让 e2e 恒红或恒绿。
7. **压测脚本加硬超时**：1022s 假象来自本机端口耗尽，不是服务端。量具先要可信。
8. **不改版本号**：用户决策项，README 写 `versions:set` 步骤。
