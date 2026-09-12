# Spec: feat-runtime-limits-and-metrics-20260912

> 核对改造清单剩余项时手工审计发现的 4 个缺口（不在原 20 项清单内）。共同点：都是「生产环境才会疼、本地演示察觉不到」的资源与可观测性问题。

## 1. 背景

改造清单 1–15 项已全部完成（9 项按用户决策跳过）。核对时我对六个维度做了手工审计（多智能体审计因通道限制全部失败，返回的空结果不可信，故逐项 grep 取证），查出 4 个真实缺口。

### 1.1 逐项现状核实

| # | 缺口 | 取证 | 严重度 |
|---|---|---|---|
| G1 | **零指标导出** | 无 `micrometer` / `MeterRegistry` 依赖（grep 全仓 0 命中）；只有 `LogLlmMetricsSink` 打日志 | **important** |
| G2 | **计划步骤数无上限** | `PlanValidator:131` 只校验 `steps` 非空，不校验 `size()` | **important** |
| G3 | **无按会话的并发限制** | `InvokeToolUseCase` 无 semaphore / 按 sessionId 的限流；仅靠线程池 `AbortPolicy` | nice-to-have |
| G4 | 会话记忆无条数上限 | `InMemoryConversationMemory` 有 TTL 30m，无 entry cap | **降级为 nice-to-have**（见下） |

### 1.2 G4 的自我纠正：风险比我最初描述的小

我在核对时说「无条数上限 → 高并发下内存可涨到很大」。读代码后发现**前提不准**：

- `put()` 在每次写入前 `store.entrySet().removeIf(expired)` —— 全量清扫过期项，不是只清自己那条；
- `Memory` 只存 ID（`domain` + `entities` Map + `rowIds` List + 时间戳），单条几百字节。

所以实际上界是「TTL 窗口内的活跃会话数 × 几百字节」，不是无界。10 万活跃会话约几十 MB，对 JVM 不构成威胁。

**G4 降级为 nice-to-have，本 change 不做**，只在文档写明上界的推导。理由：加 entry cap 需要淘汰策略（LRU 需额外结构），而当前没有任何证据表明必要 —— 这正是 KISS 与「不做 speculative 扩展」要求避免的。

## 2. 目标与非目标

### 目标

1. **G1 指标导出**：`LlmMetricsSink` 之外提供 Micrometer 实现，工具调用与 Run 编排也出指标。指标名与标签遵循低基数原则。
2. **G2 步骤数上限**：`PlanValidator` 校验 `steps.size()`，超限 → `TOOL_SELECTION_INVALID`。
3. **G3 按会话并发限制**：单 sessionId 同时在跑的工具调用数设上限，超限直接拒绝而非排队。防的是**只读查询洪水**（写操作已被幂等 claim 序列化）。
4. **G5 审计失败不吞结果**（评审 M-3 发现的既有缺陷）：`audit.record` 失败只记 ERROR，不让已执行的工具对调用方表现为失败。
5. **契约**：`tool-invoke` 的 `error.code` 增 `RATE_LIMITED`（G3 需要），并在 `contracts.md` 留变更记录。
6. **文档**：把 G4 的上界推导与 G1–G3 的配置项写进规则与 README。

### 非目标

- **不引入 Prometheus 服务端 / Grafana 面板**。只出 Micrometer 指标，导出端点由宿主决定（装 `micrometer-registry-prometheus` 即可，本项目不替宿主选监控栈）。
- **不做 G4 的 entry cap**（理由见 §1.2）。
- **不做分布式限流**。G3 是进程内的；多实例部署的全局配额需外部存储，与「不依赖外部中间件」的既有定位冲突。
- **不改前端**。

## 3. 设计

### 3.1 G1 指标导出

**关键约束：Micrometer 必须是可选依赖。** hub 的 `spark-rooter-runtime` / `gateway` 不能硬依赖它——宿主可能用别的监控栈，或（如 provider 形态）根本不需要。

做法与其他 spi 端口一致：

| 层 | 内容 |
|---|---|
| spi | 已有 `LlmMetricsSink`；**新增** `ToolMetricsSink`、`RunMetricsSink`（纯接口，无 Micrometer 类型） |
| runtime / gateway | 只依赖 spi 接口，`ObjectProvider` 取 Bean，缺则 no-op |
| starter | `@ConditionalOnClass(MeterRegistry.class)` 装配 Micrometer 实现；`micrometer-core` 设 `<optional>true</optional>` |

**指标清单**（名与标签遵循公司规范「稳定、低基数」）：

| 指标 | 类型 | 标签 | 理由 |
|---|---|---|---|
| `spark.llm.requests` | Counter | `outcome`（6 个 planner 出口） | 已有 6 个出口分类，直接复用 |
| `spark.llm.duration` | Timer | `outcome` | 规划耗时是主要延迟来源 |
| `spark.llm.tokens` | Counter | `kind`(prompt/completion) | 成本核算 |
| `spark.tool.invocations` | Counter | `toolId`, `status` | — |
| `spark.tool.duration` | Timer | `toolId` | — |
| `spark.run.outcomes` | Counter | `outcome`（completed / 5 个 `RunFailureCode` / confirmation_rejected） | 成功率 |
| `spark.run.duration` | Timer | `outcome` | 端到端延迟（实现期补：只有出口分布看不出「成功但很慢」） |

**禁止作为标签**：`sessionId` / `runId` / `conversationId` / 实体 ID（高基数，会打爆时序库——公司规范明确禁止）。`toolId` 是有限集合（当前 14 个），可做标签。

### 3.2 G2 步骤数上限

`PlanValidator` 在既有的「steps 非空」校验旁加上限：

```java
if (draft.steps().size() > maxSteps) {
  throw new RunFailure("TOOL_SELECTION_INVALID", "plan has too many steps: " + size);
}
```

**默认值 6（评审 M-2 由 8 改为 6）**：

| 依据 | 值 |
|---|---|
| 全部 e2e 日志实测的 `steps=` 分布 | 1 / 2 / 3，最大 **3** |
| 最长链构成 | `RefundTools:153` 有 2 个 `@SparkPrerequisite` → 2 前置 + 1 目标 = 3 步 |
| 取值 | **6** = 实测最大 3 的 **2 倍** |

**为什么不取 8**：`FakeLlmPlanner` 里恰好有 8 处 `DraftStep`。虽然那是 8 个不同场景各自的步骤而非单个 8 步计划，但让上限与一个现存数字重合是危险的巧合——将来给假规划器加场景时容易撞线，而 e2e 报的是「无法制定方案」，排查者想不到是上限。取 6 与任何现存数字都不重合。

可配 `spark.runtime.max-plan-steps`。

**日志必须带上实际步数与上限**（评审 M-2）：`plan rejected steps=9 max=6`。否则线上只能看到「无法制定方案」，排查不到根因。

用户看到的仍是 `TOOL_SELECTION_INVALID` 的既有文案「暂时无法为该请求制定可执行的方案」——不暴露内部上限值。

### 3.3 G3 按会话并发限制

现状：线程池有界（`runQueue=32` / `toolQueue=64`）+ `AbortPolicy`，过载时**全局**拒绝。

**威胁模型（评审 M-1 修正）**：spec 初稿写「单个会话可以占满整池」过于笼统。核实后的准确表述：

| 工具类别 | 数量 | 单会话能否并发占池 |
|---|---|---|
| `idempotency=required`（写操作） | 3 / 14 | **不能**。同 `(sessionId, idempotencyKey)` 走 `claimOrAwait`（`InvokeToolUseCase:199`），第二个调用者等待或重放，不会并发执行 |
| `idempotency=none`（只读查询） | 11 / 14 | **能**。无任何序列化 ← **G3 真正要防的是这个** |

所以 G3 防的是**只读查询洪水**，不是写操作并发。这个区别决定了单测必须拿**只读工具**测（拿写操作工具会被 claim 拦住，测不到限流逻辑，结果是绿的但无效）。

做法：`InvokeToolUseCase` 维护 `sessionId → 在飞计数`，超 `maxConcurrentPerSession` 直接抛 `GatewayException(RATE_LIMITED)`（不排队——排队只是把拒绝延后，还占着连接）。

**错误码：契约新增 `RATE_LIMITED`**（用户决策）。

现有 6 个值（`INPUT_INVALID` / `OUTPUT_INVALID` / `FORBIDDEN` / `TOOL_NOT_FOUND` / `TIMEOUT` / `HANDLER_ERROR`）都不表达限流。复用 `FORBIDDEN` 会与宿主 `ToolAccessPolicy` 的权限拒绝混同，调用方无法凭 code 判断「该不该重试」——限流应稍后重试，权限不足重试无意义。

**影响面核实后认定为「新增而非破坏性」**：

| 核实项 | 结果 |
|---|---|
| 前端是否投影 `tool-invoke` | **不投影**（后端内部契约，contracts.md §1 已载明） |
| 消费方 | 4 个，全部在仓内：`ToolInvoke` / `InvokeToolUseCase` / `GatewayException` / `RunOrchestrator` |
| 是否有外部消费方 | 无 |

故与 `provider` 段同理：`$id` 保持 `/v1/`、`schemaVersion` 保持 `1.0`，不发 `/v2/`。但**必须在 contracts.md 留变更记录**，且首个外部消费方出现后再改必须发新版本。

`RunOrchestrator` 需把 `RATE_LIMITED` 映射到用户文案。归类为「稍后重试」而非「方案不可行」——复用 `TOOL_EXECUTION_FAILED` 的文案「执行过程中工具调用失败，请稍后重试」不够准确，加一条「当前请求较多，请稍后重试」（与 `AgentRunController` 过载时的既有文案一致，用户体验统一）。

**默认值 4 的依据（评审 M-1 重写）**：初稿写「现有最长链 3 步且串行」是错的——串行是**规划层**的性质，池里的并发来自多个并发 HTTP 请求，与单个计划的步数无关。

新依据：`toolQueue=64`，若单会话上限为 4，则需 16 个并发会话才能占满池——这个比例让「一个用户拖垮所有人」不再可能，同时 4 个并发只读查询对正常交互（一次对话一个请求）有 4 倍余量。取值与 `toolQueue` 挂钩，改池容量时应同步复核。

计数必须在 `finally` 里减——否则一次异常就永久占额。

### 3.4 G5 审计写入失败不得吞掉已成功的结果（评审 M-3，既有缺陷）

**位置**：`InvokeToolUseCase:136`（成功路径）与 `:150`（失败路径），`audit.record()` 均无 try-catch。

成功路径的形状是：

```java
Outcome out = pipeline(req, ec);        // 工具已真的执行完（副作用已发生）
audit.record(new AuditSink.Entry(...)); // ← 宿主实现抛异常
return resp;                            // ← 到不了
```

`AuditSink` 是 spi 端口，宿主可换成写库 / 写 Kafka（默认 `LogAuditSink` 只打日志不会抛）。一旦宿主实现抛异常：副作用已发生但调用方收到失败 → Runtime 转成 `TOOL_EXECUTION_FAILED` → 用户看到「请稍后重试」→ **可能真的重试 → 重复扣款**。

且 `AuditSink` 的 javadoc **没告诉宿主实现不能抛异常**。

**修法**（用户决策：本 change 一并修）：

1. 两处 `audit.record` 包 try-catch，失败记 ERROR（含 runId / toolId 便于补账）但**不影响返回值**；
2. `AuditSink` javadoc 写明约束：「实现不应抛异常。抛出会被 Gateway 捕获并记 ERROR，**不影响工具执行结果**——审计失败不能让已执行的操作对调用方表现为失败」。

**与 S-2 同一手法**：指标埋点失败也这样处理（监控 / 审计故障不得拖垮业务）。两者在同一 change 用同一模式，比分两次改一致。

**合规权衡**：静默吃掉审计失败也有风险（合规上审计不得丢）。取舍是「ERROR 日志 + 不影响业务」——审计丢失可由日志告警补账，而重复扣款不可逆。这个权衡写进 javadoc 让宿主知情。

## 4. 影响面

| 端 | 改动 |
|---|---|
| 契约 | `tool-invoke.schema.json` 的 `error.code` enum 增 `RATE_LIMITED`（新增值，非破坏性——无前端投影、无外部消费方）；补 invalid 示例；`sync-contracts` 同步后端副本 |
| spi | 新增 `ToolMetricsSink` / `RunMetricsSink` 接口 |
| runtime | `PlanValidator` 加步骤上限；`RunOrchestrator` 出 Run 指标 |
| gateway | `InvokeToolUseCase` 加会话并发计数 + 工具指标 + **审计失败不吞结果**（G5） |
| spi | `AuditSink` javadoc 增「实现不应抛异常」约束（G5） |
| starter | Micrometer 实现（`@ConditionalOnClass`）、3 个新配置项 |
| 规则 | `backend-standard.md` 指标命名与标签基数红线；README 配置表 |
| 前端 | **零改动** |

## 5. 风险

| 风险 | 后果 | 缓解 |
|---|---|---|
| Micrometer 变成硬依赖 | provider / 不用监控的宿主被迫引入 | `<optional>true</optional>` + `@ConditionalOnClass`；**加门禁**断言 runtime/gateway 的 pom 不出现 micrometer |
| 高基数标签打爆时序库 | 监控系统被打挂（比没监控更糟） | 指标实现里**硬编码**允许的标签集，不接受动态标签；加单测断言 sessionId/runId 不在标签里 |
| 步骤上限挡住合法长链 | 正常需求被拒 | 默认 8 = 现有最长链的 2.5 倍；可配；e2e 161 条回归会暴露误伤 |
| 会话并发计数泄漏 | 一次异常后该会话永久被拒 | `finally` 减计数 + 单测断言异常路径后计数归零 |
| 指标埋点拖慢主链路 | 延迟上升 | Counter/Timer 都是无锁原子操作；埋点不进临界区 |
| **监控 / 审计故障拖垮业务** | 正常工具调用因 registry 或 AuditSink 抛异常而失败 → 用户重试 → 重复副作用 | G5 + S-2 同一手法：包 try-catch，失败记 ERROR 不影响返回值；两条单测自证（去掉 catch 则红） |
| 审计静默丢失 | 合规上审计不得丢 | 取舍已明示：ERROR 日志可告警补账，而重复扣款不可逆。权衡写进 `AuditSink` javadoc 让宿主知情 |

## 6. 验收

1. `pnpm -C .harness run ci` **0**。
2. **单体零回归**：`e2e-backend` **161 passed**、`e2e-frontend` **7**、`deploy-verify` **12**、前端单测 **106**、`e2e-provider` **15**。
3. **G1**：宿主装 `micrometer-registry-prometheus` 时 `/actuator/prometheus` 出现 7 个 `spark.*` 指标；**不装时正常启动且无 Micrometer 类加载**（单测断言 no-op 实现被使用）。
4. **G1 门禁**：runtime / gateway 的 pom 不含 micrometer（双向自证）。
5. **G1 基数**：单测断言指标标签集合 ⊆ 白名单，且不含 `sessionId` / `runId` / `conversationId`（双向自证：加一个 sessionId 标签会红）。
6. **G2**：单测断言 7 步被拒、6 步通过、3 步（现有最长链）通过；错误码 `TOOL_SELECTION_INVALID`；**日志含 `steps=7 max=6`**（评审 M-2）。
7. **G3**：单测用**只读工具**（`idempotency=none`）断言第 5 个并发被拒、前 4 个通过；异常路径后计数归零；计数归零后 map 不留 key。**三条都必须自证会红**（评审 M-1：拿写操作工具测会被 claim 拦住，测不到限流）。
8. **G5**（评审 M-3）：单测断言 `AuditSink` 抛异常时**工具结果照常返回**（成功路径与失败路径各一条），且日志有 ERROR。**自证：去掉 try-catch 则用例红**。
9. **S-2**：单测断言 `MeterRegistry` 抛异常时主链路仍成功。
10. **S-1**：单测断言 Run 的每个出口（正常完成 / 5 个 `RunFailureCode` / 线程池拒绝）都有对应 `spark.run.outcomes` 埋点。
11. `mvnw test` 0。

## 7. 回退

三项互相独立，可单独回退：

| 项 | 回退动作 | 是否牵连契约 |
|---|---|---|
| G1 | 删 starter 的 Micrometer Bean 与两个 spi 接口 | 否 |
| G2 | 删一个 if + 一个配置项 | 否 |
| G3 | 删计数器 + 配置项 | **是**：`RATE_LIMITED` 枚举值需一并删。但它是新增值，留着不用也无害（无消费方依赖它存在），故可先只回退代码 |
| G5 | 删两处 try-catch | 否。但**不建议回退**——它修的是既有缺陷，回退等于把重复扣款风险放回去 |

无数据迁移。

## 8. 开放项核实结果（阶段 1 内验完，不留到编码期）

| 开放项 | 核实 | 结论 |
|---|---|---|
| `ErrorCode` 有无适合限流的值 | 读枚举与契约 enum | **无**。6 个值都不表达限流 → 按用户决策新增 `RATE_LIMITED`；核实前端不投影、4 个消费方全在仓内，故为新增而非破坏性（§3.3） |
| `MeterRegistry` 由谁提供 | `host-demo/pom.xml` 已有 `spring-boot-starter-actuator` | actuator 传递带入 `micrometer-core`。`@ConditionalOnClass(MeterRegistry.class)` 可用；starter 自身仍设 `<optional>true</optional>`，不强加给 provider 与不用监控的宿主 |
| `LlmMetricsSink.Sample` 字段是否够用 | `record Sample(outcome, durationMs, promptTokens, completionTokens, attempts, circuitOpen)` | **够**。三个 LLM 指标全能从现有字段拼出，**不改已有接口** |
