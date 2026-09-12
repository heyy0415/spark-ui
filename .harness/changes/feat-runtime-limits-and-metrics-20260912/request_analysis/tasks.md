# Tasks: feat-runtime-limits-and-metrics-20260912

> 契约先行（T01）。顺序 `T01 → T02(G2) → T03(G3) → T04(G5) → T05(G1) → T06 门禁 → T07 文档 → T08 回归`。
>
> **阶段 2 评审后修订**：G2 默认值 8→**6**（M-2）；G3 单测改用只读工具（M-1）；新增 **T04 = G5 审计失败不吞结果**（M-3，既有缺陷，用户决策本 change 一并修）。
>
> 每个 task 的验收都含「单体零回归」——本 change 全是加约束与埋点，最大风险是误伤既有 161 条链路。

---

## T01 契约：error.code 增 RATE_LIMITED

- **目标**：G3 的限流拒绝有专属错误码，调用方能凭 code 判断「该不该重试」。
- **所属端**：contracts（**契约 task，前置于 G3 实现**）
- **输入**：`.harness/contracts/tool-invoke.schema.json`；`ToolInvoke.java` 的 `ErrorCode` 枚举
- **输出**：
  - 契约 `error.code` 的 enum 增 `RATE_LIMITED`
  - `ToolInvoke.ErrorCode` 枚举同步增值
  - invalid 示例：一个用未知 code（如 `THROTTLED`）的响应，必须被拒——证明 enum 真的在约束
  - `contracts.md` §5x 留变更记录：新增值而非破坏性（无前端投影、4 个消费方全在仓内），`$id` 保持 `/v1/`
  - `pnpm -C .harness run sync-contracts`
- **验收**：
  - `pnpm -C .harness run check-contracts` **0**（含副本一致性）
  - 新增的 invalid 示例**确认被拒**（看到 `rejected`，不是"跑了没报错"）
  - `e2e-backend` **161 passed**（加枚举值不该影响任何既有路径）
- **依赖**：无

## T02 G2：计划步骤数上限

- **目标**：挡住「模型规划 50 步」这类失控。
- **所属端**：spark-rooter（runtime + starter）
- **输入**：`PlanValidator.java:131`（既有的 steps 非空校验）；`SparkRooterProperties.Runtime`
- **输出**：
  - `PlanValidator` 校验 `steps.size() > maxPlanSteps` → `RunFailure("TOOL_SELECTION_INVALID", ...)`
  - 配置项 `spark.runtime.max-plan-steps`，默认 **6**（实测最大 3 步的 2 倍；**不取 8** 是为了不与 `FakeLlmPlanner` 里的 8 处 `DraftStep` 重合——评审 M-2）
  - **日志带实际步数与上限**：`plan rejected steps=7 max=6`（否则线上只见「无法制定方案」，排查不到根因）
  - 用户看到的是既有文案「暂时无法为该请求制定可执行的方案」——**不暴露上限值**
- **验收**：
  - 单测：7 步被拒（code `TOOL_SELECTION_INVALID`）、6 步通过、**3 步（现有最长链）通过**
  - 单测：日志含 `steps=7 max=6`
  - **自证**：把上限判断删掉，7 步用例必须变红
  - `e2e-backend` **161 passed**（现有链路最长 3 步，不该被误伤）
- **依赖**：无（可与 T01 并行，但为免两轮 sync-contracts 排在 T01 后）

## T03 G3：按会话并发限制

- **目标**：单个会话不能占满整个工具池，让其他用户全被拒。
- **所属端**：spark-rooter（gateway + starter）
- **输入**：`InvokeToolUseCase`；`SparkRooterProperties.Gateway`
- **威胁模型**（评审 M-1）：防的是**只读查询洪水**。写操作（`idempotency=required`，3/14）已被 `claimOrAwait` 序列化；只读工具（11/14）无任何序列化，是真正的暴露面。
- **输出**：
  - `sessionId → 在飞计数`（`ConcurrentHashMap<String, AtomicInteger>`），超限抛 `GatewayException(RATE_LIMITED)`
  - 计数在 **`finally` 里减**；计数归零时从 map 移除该 key（否则 map 随会话数无界增长——「加了限流又引入新泄漏」的典型）
  - 配置项 `spark.gateway.max-concurrent-per-session`，默认 **4**（与 `toolQueue=64` 挂钩：需 16 个并发会话才占满池；改池容量时同步复核）
  - `RunOrchestrator` 把 `RATE_LIMITED` 映射为用户文案「当前请求较多，请稍后重试」（与 `AgentRunController` 过载文案一致）
- **验收**：
  - 单测**必须用只读工具**（`idempotency=none`）：第 5 个并发被拒、前 4 个通过。**拿写操作工具测会被 claim 拦住，结果是绿的但没测到限流逻辑**（评审 M-1）
  - 单测：**异常路径后计数归零**（工具抛异常后该会话仍能调用）
  - 单测：计数归零后 map 里不留 key（防自身泄漏）
  - **自证三条**：删 `finally` 减计数 → 异常路径用例红；删上限判断 → 第 5 个用例红；不移除 key → 泄漏用例红
  - `e2e-backend` **161 passed**（既有链路单会话并发 1，不该触发）
- **依赖**：T01

## T04 G5：审计失败不吞掉已成功的结果（评审 M-3，既有缺陷）

- **目标**：宿主的 `AuditSink` 抛异常时，已执行的工具结果照常返回——不让审计故障变成「用户重试 → 重复扣款」。
- **所属端**：spark-rooter（gateway + spi）
- **输入**：`InvokeToolUseCase:136`（成功路径）、`:150`（失败路径）；`AuditSink.java`
- **输出**：
  - 两处 `audit.record` 包 try-catch：失败记 **ERROR**（含 runId / toolId / status，便于补账），**不影响返回值 / 不改写异常**
  - `AuditSink` javadoc 增约束：「实现不应抛异常。抛出会被 Gateway 捕获并记 ERROR，不影响工具执行结果——审计失败不能让已执行的操作对调用方表现为失败」
  - javadoc 同时写明**合规取舍**：审计丢失可由 ERROR 日志告警补账，而重复扣款不可逆
- **验收**：
  - 单测：`AuditSink` 抛异常时**成功路径**仍返回 `succeeded`
  - 单测：`AuditSink` 抛异常时**失败路径**仍抛原本的 `GatewayException`（不被审计异常掩盖）
  - **自证**：去掉任一处 try-catch，对应用例必须变红
  - `e2e-backend` **161 passed**（默认 `LogAuditSink` 不抛，行为不变）
- **依赖**：T03（同改 `InvokeToolUseCase`，串行避免冲突）

## T05 G1：指标导出

- **目标**：生产能看到 QPS / 错误率 / 延迟 / token 成本，不必捞日志。
- **所属端**：spark-rooter（spi + runtime + gateway + starter）
- **输入**：既有 `LlmMetricsSink`（字段已够用，**不改**）；`InvokeToolUseCase`；`RunOrchestrator`
- **输出**：
  - spi 新增 `ToolMetricsSink` / `RunMetricsSink`（纯接口，**无 Micrometer 类型**）
  - runtime / gateway 经 `ObjectProvider` 取 Bean，缺则 no-op（与其他 spi 端口一致）
  - starter 装配 Micrometer 实现，`@ConditionalOnClass(MeterRegistry.class)`，`micrometer-core` 设 `<optional>true</optional>`
  - 7 个指标（spec §3.1 表 + 实现期补的 run.duration）；**标签集合硬编码**，不接受动态标签
  - **逐指标写明埋点方法**（评审 S-1）：`spark.run.outcomes` 必须覆盖 Run 的**每个出口**（正常完成 / 5 个 `RunFailureCode` / 线程池拒绝），漏一个成功率就失真
  - **埋点包 try-catch**（评审 S-2）：registry 抛异常时只记一次 WARN（不能每次都记，否则日志洪水），不影响主链路
- **验收**：
  - 单测：未装 Micrometer 时用 no-op 实现，主链路正常
  - 单测：**标签集合 ⊆ 白名单，且不含 `sessionId` / `runId` / `conversationId`**
  - 单测（S-1）：Run 的每个出口都有对应 `spark.run.outcomes` 埋点
  - 单测（S-2）：`MeterRegistry` 抛异常时主链路仍成功
  - **自证**：给任一指标加 `sessionId` 标签 → 基数用例必须变红；去掉埋点 try-catch → S-2 用例红
  - `host-demo` 起来后 `/actuator/prometheus` 出现 7 个 `spark.*` 指标（host-demo 已有 actuator）
  - `e2e-backend` **161 passed**
- **依赖**：T04（同改 `InvokeToolUseCase` / `RunOrchestrator`，串行避免冲突）

## T06 门禁：Micrometer 不得成为硬依赖

- **目标**：让「provider 与不用监控的宿主被迫引入 Micrometer」这件事不可能发生。
- **所属端**：harness
- **输出**：
  - `check-module-deps` 断言 `spark-rooter-runtime` / `gateway` / `spi` / `contracts` / provider-starter 的 pom **不含** `micrometer`
  - starter 的 micrometer 依赖必须带 `<optional>true</optional>`（只断言存在性不够——不带 optional 就会传递给宿主）
- **验收**：
  - 双向自证：给 runtime pom 加 micrometer → 红；去掉 starter 的 optional → 红；恢复 → 绿
  - `pnpm -C .harness run ci` **0**
- **依赖**：T05

## T07 文档

- **所属端**：harness + README
- **输出**：
  - `backend-standard.md`：指标命名与**标签低基数红线**（禁 sessionId / runId / conversationId / 实体 ID 作标签，理由：打爆时序库比没监控更糟）
  - `README.md`：三个新配置项进配置表；「已知限制」补 G4 的上界推导（TTL 窗口内活跃会话数 × 几百字节，非无界）
  - `contracts.md`：T01 的变更记录（若 T01 未写全）
- **验收**：`pnpm -C .harness run doctor` 0；`pnpm -C .harness run ci` 0
- **依赖**：T06

## T08 全链路回归

- **所属端**：harness
- **验收**（数字必须与本 change 前一致）：
  - `pnpm -C .harness run ci` **0**
  - `e2e-backend` **161 passed, 0 failed**
  - `e2e-provider` **15 passed, 0 failed**
  - `e2e-frontend` **7 passed**
  - `deploy-verify` **12 passed, 0 failed**
  - 前端单测 **106 passed**（本 change 前端零改动，数字不应变）
  - `mvnw test` 0
- **依赖**：T07
