# Tasks: feat-production-hardening-20260912

> 顺序：T01 → T02 → T03（并行 T04 / T05）→ T06 → T07 → T08 → T09 → T10 → T11 → T12。**无契约 task**（spec §5 核对无 Schema 变更）。
>
> 每个 task 验收都含「五套回归数字不退」：e2e-backend ≥161 / e2e-provider 15 / e2e-frontend 7 / deploy-verify 12 / 前端单测 107。本 change 全是加约束与加实现，最大风险是误伤既有链路。

---

## T01 内存实现补 TTL（B1 / B2）

- **目标**：令牌表与 hub 幂等表长跑不无界增长。
- **所属端**：spark-rooter（runtime + gateway + starter）
- **输入**：`InMemoryConfirmationTokenStore`、`InMemoryIdempotencyStore`、`RunOrchestrator.evictExpired()`、`InvokeToolUseCase.pipelineWithinLease()`
- **输出**：
  - `ConfirmationTokenStore` 增 `default List<String> evictExpired(Instant now) { return List.of(); }`；内存实现按 `expiresAt` 清；`RunOrchestrator.evictExpired()` 调用
  - `IdempotencyStore` 增 `default void evictCompletedBefore(Instant cutoff) {}`；内存实现给已完成 future 记 `completedAt`，只删已完成且过期；`InvokeToolUseCase` 每 64 次 `complete()` 调一次（计数器节流，不引调度器）
  - `SparkRooterProperties.Gateway` 增 `idempotencyTtl` 默认 `24h`；javadoc 写理由
- **验收**：
  - 单测：令牌 3 个（2 过期 1 未）→ 清 2；幂等表已完成过期删、未完成占位不删、已完成未过期不删
  - **自证**：删两处实现体 → 对应用例红
  - `e2e-backend` 数字不退
- **依赖**：无

## T02 编排器临时态进 Run 聚合（B3 前置）

- **目标**：`RunOrchestrator` 不再持有按 runId 的进程内状态；确认请求可落到任意副本。
- **所属端**：spark-rooter（runtime）
- **输入**：`RunOrchestrator:99-112` 五个缓存；`Run`；`ToolMeta`
- **输出**：
  - `Run` 增字段：`String currentUi`（JSON 字符串，可空）、`Map<String,String> stepOutputs`（toolId → JSON 字符串）、`boolean clarified`；配 `setCurrentUi / putStepOutput / markClarified` 与读取器；`updatedAt` 随之刷新
  - 删 `lastUi` / `stepOutputs` / `clarified` / `confirmLocks` / `inputSchemas` 五个字段
  - `inputSchemas` → `Run.stepSchemas`（评审 M-2）：`attachPlan` 时从 `found` 快照计划各步骤的 inputSchema（toolId → JSON 字符串）；`typedArgs` 改读 `run.stepSchema(toolId)`
  - `confirmLocks` 删除；`confirm()` 依赖 `tokens.consume` 原子性（spec §2.2 分析）；`:289` 迁移 EXECUTING 后**立即 `runs.save`**（评审 S-1）
  - 终态 `complete()` / `fail()` 清空 `run.stepOutputs` 再 save（评审 L-1）
  - `Run.restore(...)` 静态重建入口（供 T03 快照反序列化）
  - `evictExpired()` 只剩 `runs.evictExpired()` + T01 的令牌清扫
  - `lastUi(runId)` 改读 `runs.find(runId).flatMap(Run::currentUi)` 并反序列化
- **验收**：
  - `RunOrchestratorTest` 13 + `RunOrchestratorConfirmTest` 全绿（改 fixture 不改断言）
  - 新增：第二个 `RunOrchestrator` 实例共享同一 `RunRepository`，`lastUi(runId)` 得同一屏（证明状态不在实例里）
  - 新增：32 线程并发消费同一令牌恰好 1 成功（内存版；Redis 版 T03 复用）
  - `e2e-backend` 数字不退（含「评审 M2 并发两次确认只有 1 笔退款」）
- **依赖**：T01

## T03 Redis 模块（B3）

- **目标**：hub 多副本可用。
- **所属端**：spark-rooter（新模块 `spark-rooter-redis`）+ harness（门禁）
- **输入**：四个端口接口；`PlatformMapper`；本机 Redis 8.2
- **输出**：
  - 模块 pom：依赖 spi / contracts / runtime / gateway（域接口）+ `spring-boot-starter-data-redis`（**非 optional**——引本模块就是要 Redis）；release 21
  - `RunSnapshot` DTO（record，全部字段）+ `RedisRunRepository` / `RedisConfirmationTokenStore`（`GETDEL`）/ `RedisIdempotencyStore`（`SET NX PX` + Lua release + 轮询 Awaiting）/ `RedisConversationMemory`
  - `SparkRooterRedisAutoConfiguration` + `AutoConfiguration.imports`；条件 `spark.storage=redis`
  - `SparkRooterProperties` 增 `@DefaultValue("memory") String storage`（`memory` / `redis`）
  - 抽象契约测试 `IdempotencyStoreContract` / `ConfirmationTokenStoreContract`，内存与 Redis 各继承；Redis 测试 `@EnabledIf` 探测 `redis-cli ping`；契约增「owner 消失后等待者重 claim 成为 Owner」（评审 S-2：内存版靠 release 唤醒，Redis 版靠 key 过期 → 轮询见 nil）与「32 线程并发消费同一令牌恰好 1 成功」
  - **工作量说明**：本 task 超 0.5 天。不拆：四个实现共用一个自动装配与一套契约测试，拆开会让"多副本可用"这个承诺分散在多次提交里，回滚困难
  - `check-module-deps`：`spark-rooter-redis` 不得被 spi / contracts / runtime / registry / gateway / web-mvc / starter / provider-starter 依赖（可选模块不得反向进平台）；`spring-data-redis` 不得出现在上述模块 pom
  - `project-structure.md` 模块清单 + 依赖方向登记
- **验收**：
  - 契约测试两实现全绿；Redis 不在时跳过并打印 `redis unavailable, skipped`
  - `host-demo` / `provider-demo` 依赖树 `spring-data-redis` 计数 0
  - 门禁自证：给 runtime pom 加 redis 模块 → 红
- **依赖**：T02

## T04 断连提前终止（H1）

- **目标**：只读步骤在客户端断开后不再白跑。
- **所属端**：spark-rooter（runtime + web-mvc + spi + starter）
- **输入**：`RunEventSink`、`SseRunEventSink`、`RunOrchestrator.runSteps`、`ToolMeta`、`SparkToolScanner`
- **输出**：
  - `RunEventSink.isClosed()` default false；`SseRunEventSink` 返回 `closed.get()`
  - `ToolMeta` 增 `boolean sideEffect`（构造点：`SparkToolScanner` 从 `@SparkRisk`；测试 fixture）
  - `runSteps`：取步后 `if (sink.isClosed() && !isWrite(step))` → `fail(INTERNAL_ERROR, "client gone")`，日志 `run abandoned runId={} step={} reason=client_gone`；`isWrite` = `meta.find(toolId).map(ToolMeta::sideEffect).orElse(true)`（**取不到视为写，fail-safe**）
  - `executeConfirmed` 不检查
- **验收**：
  - 单测：断连 + 只读 → 终止且 Run FAILED；断连 + 写 → 继续；确认路径断连 → 继续
  - **自证**：删判定 → 红
  - `e2e-backend` 增 1 条：发起 3 步链后 `--max-time 0.5` 断开，`sleep 1` 后 `GET` state=FAILED，log 含 `run abandoned`
- **依赖**：T02（`runSteps` 签名已改）

## T05 健康探针（H2）

- **目标**：模型不可用 / 熔断时 readiness 非 UP。
- **所属端**：spark-rooter（starter）+ host-demo
- **输入**：`LlmClient.name()`、`LlmCircuitBreaker.state()`、`MetricsBeans` 装配手法
- **输出**：
  - `HealthBeans` 配置类（`@ConditionalOnClass(HealthIndicator.class)`）+ `SparkReadinessHealthIndicator`（Bean 名 `sparkRooter`）：`planner=unavailable` → DOWN；**熔断只进 detail 不改状态**（评审 M-1：摘流量会让熔断器失去探测机会，永不恢复）
  - host-demo `application.yml`：`management.endpoint.health.group.readiness.include: readinessState,sparkRooter`；`probes.enabled: true`；exposure 加 `metrics`
- **验收**：
  - 单测：unavailable → DOWN；可用 + 熔断 CLOSED → UP；可用 + 熔断 OPEN → **UP** 且 detail `circuit=open`（这条就是 M-1 的锁）
  - `deploy-verify` 增 1 条：`/actuator/health/readiness` 200 且 `sparkRooter.status=UP`（fake planner）
  - 手工：不配模型启动 → readiness 503
- **依赖**：无

## T06 运维默认值 + 自发包路径（H3 / M2）

- **所属端**：spark-rooter（parent pom + host-demo）+ harness
- **输出**：
  - host-demo yml：`server.shutdown: graceful`、`spring.lifecycle.timeout-per-shutdown-phase: 30s`、`server.tomcat.max-http-form-post-size: 64KB`
  - parent pom：examples 5 模块进 `examples` profile（`activeByDefault`）；`release` profile 挂 source + javadoc 插件（`doclint none`，默认不激活）
  - `check-module-deps`：`<modules>` 直接子项不得含 `examples/`
- **验收**：
  - `mvn -q -DskipTests -P '!examples' install` 后 `~/.m2/repository/com/sparkrooter` 只有 9 个目录（8 平台 + parent）
  - `mvn -q -DskipTests -P release install` 产出 `*-sources.jar` / `*-javadoc.jar`
  - `pnpm -C .harness run ci` 0（默认 profile 行为不变）
  - 门禁自证：把 order-service 挪回 `<modules>` → 红
- **依赖**：无

## T07 压测脚本 + 实测（M1）

- **所属端**：harness
- **输出**：
  - `.harness/scripts/load-test.mjs`：`--port --concurrency --duration --message`；并发 `POST /agent/runs` 消费 SSE 至终态；统计 ok / 拒绝（run.failed 文案含「请稍后重试」）/ 延迟分位；退出码 0
  - 实测：起 fake planner 后端（`--spark.selfcheck.enabled=false`），`--concurrency 16 / 32 / 64 / 128 --duration 20s` 四轮，结果写 `deployment/load_test.md`
  - 据结果复核 `runQueue / toolQueue / maxConcurrentPerSession`：差 ≥2× 则改默认值；否则 javadoc 补「实测：…」
- **验收**：`load_test.md` 含四轮数据与结论；javadoc 无「估算」字样残留或已注明实测数
- **依赖**：T02（否则测的是旧编排器）

## T08 多副本 e2e（B3 验证）

- **所属端**：harness
- **输出**：`.harness/scripts/e2e-multi-instance.sh`：探测 `redis-cli ping`（不通则退出 2 并打印原因）；`FLUSHDB` 隔离 db 15；起 hub-A（8095）/ hub-B（8096）均 `--spark.storage=redis --spring.data.redis.database=15 --spring.profiles.active=e2e`；断言 ≥ 8 条（spec §6.2）；`check-shell` 覆盖
- **验收**：脚本 `≥ 8 passed, 0 failed`；不通 Redis 时退出 2
- **依赖**：T03、T05

## T09 CI + 社区文件（M3）

- **所属端**：harness
- **输出**：`.github/workflows/ci.yml`（单 job 门禁）；`SECURITY.md`；`CONTRIBUTING.md`；`dev-workflow.md` 阶段 6 口径改回「GitHub Actions 跑 `pnpm -C .harness run ci`」
- **验收**：`actionlint`（若本机有）或 YAML 解析通过；doctor 0
- **依赖**：无

## T10 规则与 wiki 文档

- **所属端**：harness
- **输出**：`backend-standard.md` 增「多副本」节；`agent-safety.md` §3 改「令牌原子消费」口径；`architecture.md` 可替换端口 + 新模块；`spark-rooter/README.md` 配置表增 3 项；`project-structure.md`（T03 已改）
- **验收**：doctor 0；`grep -rn confirmLocks .harness/rules .harness/wiki` 为 0
- **依赖**：T02–T06

## T11 根 README 重写（M4）

- **所属端**：docs
- **输出**：按 spec §2.10 结构重写；每个数字核对；旧版每个 H2 在新版有归宿
- **验收**：doctor 口号词 0；`coding_report` 附数字核对表与旧节归宿表
- **依赖**：T01–T10

## T12 全链路回归

- **验收**：`ci` 0；`e2e-backend` ≥162 / 0；`e2e-provider` 15 / 0；`e2e-frontend` 7；`deploy-verify` 13 / 0；`e2e-multi-instance` ≥8 / 0；前端单测 107；`mvnw test` 0；`doctor` 0
- **依赖**：T11
