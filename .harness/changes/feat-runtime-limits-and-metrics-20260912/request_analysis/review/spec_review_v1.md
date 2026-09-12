# 需求评审 v1 — feat-runtime-limits-and-metrics-20260912

## 0. 独立性声明

**自评审，不满足独立性要求。** subagent 通道返回 `400 专用渠道限制`，无法派独立评审 agent。本 change 的审计阶段已因此吃过一次亏（6 个 probe agent 全失败，workflow 算出假的 `confirmed: []`），所以本轮**只提能用 grep / 实测证明的问题**，每条先取证再判断。

**建议他人复核**：§2.1 G3 的威胁模型被我自己缩小后，是否还值得它的成本（新增契约枚举值 + 一个计数器 + 三条自证）。

## 1. 结论

**CHANGES REQUESTED** —— **3 项 MUST FIX**、2 项 SHOULD。（M-3 是补查盲区时发现的既有缺陷，建议纳入本 change）

四个缺口的取证都站得住（我逐条 grep 复核了 spec 的每个行号引用），G4 的自我降级判断正确。问题集中在**我自己写的论证里有事实错误**，以及**G3 的威胁模型比 spec 描述的窄**。

## 2. MUST FIX

### M-1 ｜ G3 的威胁模型被高估：幂等 claim 已经serialize了一部分

**位置**：spec §3.3「单个会话可以占满整池」

**核对**：`InvokeToolUseCase:199-202` —— 对 `idempotency=required` 的工具，同 `(sessionId, idempotencyKey)` 会走 `claimOrAwait`，第二个调用者**等待或重放**，不会并发执行。

```java
boolean idem = manifest.execution().idempotency() == ToolManifest.Idempotency.required;
if (idem) {
  Optional<ToolInvoke.Response> shared = claimOrAwait(req, ec, manifest.execution().timeoutMs());
```

实测覆盖面：示例领域 14 个工具里 **3 个**声明 `idempotency=required`（`grep -c 'idempotency = REQUIRED'`）。

**问题**：spec 说「单个会话可以占满整池」过于笼统。准确表述应是：

- 对 3 个写操作工具：同 key 已被 claim 序列化，**并发占池不成立**；
- 对 11 个只读工具：无任何序列化，**单会话确实可以用不同请求打满池** ← G3 真正要防的是这个。

这不是推翻 G3，而是**它的价值论证要改**：G3 防的是「只读查询洪水」，不是「写操作并发」。这个区别影响两件事：

1. 默认值 4 的理由要重写。spec 写「现有最长链 3 步且串行」——但串行是**规划层**的性质，而池子里的并发来自多个并发请求，与单个计划的步数无关。4 这个数字需要新的依据；
2. 单测要覆盖「同一会话对只读工具的并发」，而不是拿写操作工具测（那会被 claim 拦住，测不到限流逻辑）。

**分级**：MUST FIX。不改会让实现者按错误的模型写测试，测出来是绿的但没测到真正的路径。

### M-2 ｜ G2 的「2.5 倍余量」算错了，且默认值依据不成立

**位置**：spec §3.2「默认值 8，理由：现有四个示例领域最长的链是 3 步。8 给了 2.5 倍余量」

**核对**：

| 取证 | 结果 |
|---|---|
| `RefundTools:153` | `@SparkPrerequisite({"refund.eligibility.check", "refund.preview"})` —— **2 个**前置 |
| 全部 e2e 日志的 `steps=` 分布 | `steps=1` / `steps=2` / `steps=3`，最大 **3** |
| `FakeLlmPlanner` 的 `DraftStep` 出现次数 | 8 |

两处问题：

1. **算术**：3 → 8 是 2.67 倍，不是 2.5 倍。小事，但 spec 里的数字应当准确。
2. **依据不成立（真问题）**：`FakeLlmPlanner` 里有 **8 处** `DraftStep`。虽然那是 8 个不同场景各自的步骤而非单个 8 步计划，但**上限恰好等于 8 是危险的巧合**——若将来有人给假规划器加一个场景、或某个场景本身需要 4 步，就会撞上限，而 e2e 报的是 `TOOL_SELECTION_INVALID`（"无法制定方案"），排查者不会想到是上限问题。

**要求**：

- 默认值改为与实测最大值有清晰倍数关系且**不与任何现存数字重合**的值。建议 **6**（实测最大 3 的 2 倍），或明确写出为什么 8 与 `FakeLlmPlanner` 的 8 无关；
- 无论取值，**必须加一条 e2e 或单测断言「现有最长链不触发上限」**，让误伤立刻可见；
- 日志里必须带上实际步数与上限值（`plan rejected steps=9 max=6`），否则线上排查只能看到"无法制定方案"。

**分级**：MUST FIX。这是「默认值选得让人排查不到」的典型，且我自己的论证里有算术错误。

### M-3 ｜ 补查盲区发现：审计写入失败会吞掉已成功的工具结果（既有缺陷）

**位置**：`InvokeToolUseCase:136`（成功路径）与 `:150`（失败路径），均无 try-catch

**核对**：

```java
try {
  Outcome out = pipeline(req, ec);          // 工具已真的执行完
  ToolInvoke.Response resp = out.response();
  audit.record(new AuditSink.Entry(...));   // ← 若宿主实现抛异常
  return resp;                              // ← 这行到不了
}
```

`AuditSink` 是 spi 端口，宿主可替换为写库 / 写 Kafka（默认实现 `LogAuditSink` 只打日志，不会抛）。一旦宿主实现抛异常：

- **副作用已经发生**（退款已执行），但调用方收到失败；
- Runtime 的 `catch(RuntimeException)` 会转成 `TOOL_EXECUTION_FAILED`，用户看到"执行失败，请稍后重试"，**可能真的去重试** → 重复扣款；
- 且 `AuditSink` 的 javadoc **没有说明实现是否允许抛异常**，宿主没被告知这个约束。

这与我在 S-2 给指标提的是同一个失败模式（监控/审计故障拖垮业务），但审计这条是**既有缺陷**，不是本 change 引入的。

**要求**（二选一，倾向前者）：

1. **本 change 一并修**：`audit.record` 包 try-catch，失败记 ERROR 但不影响返回值；`AuditSink` javadoc 写明「实现不应抛异常；抛出会被 Gateway 捕获并记 ERROR，不影响工具执行结果」。理由：它与 S-2 是同一类问题，同一 change 里用同一手法处理，比分两次改一致；
2. 或明确不修并记入 README 已知限制，把「宿主实现必须自己兜住异常」写进 `AuditSink` javadoc。

**分级**：MUST FIX（无论选哪条，**至少要把约束写进 javadoc**——现在宿主完全没被告知）。

## 3. SHOULD

### S-1 ｜ G1 的「6 个指标」缺少埋点位置的落点

**位置**：spec §3.1 指标清单表

**问题**：表里给了指标名与标签，但没说**埋在哪个方法的哪一步**。`spark.run.outcomes` 尤其模糊——Run 的"outcome"在 `RunOrchestrator` 有多个出口（正常完成、`RunFailure` 各码、SSE 断连、线程池拒绝）。不写清落点，实现时容易漏掉某个出口，导致成功率指标失真（比失真更糟的是"看起来有监控"）。

**建议**：tasks 的 T04 输出里逐指标列出埋点方法名，并要求「每个出口都有对应埋点」的单测。

### S-2 ｜ 没说指标埋点失败时怎么办

**位置**：spec §5 风险表只提了"埋点拖慢主链路"

**问题**：若宿主的 `MeterRegistry` 实现抛异常（自定义 registry、标签冲突、后端不可达），埋点会把异常冒到主链路，**让一次正常的工具调用因为监控故障而失败**。这是监控系统拖垮业务的经典事故。

**建议**：spi 实现里包 try-catch，埋点失败只记一次 WARN（不能每次都记，否则日志洪水）。加单测断言「registry 抛异常时主链路仍成功」。

## 4. 逐项核对

| 项 | 结论 |
|---|---|
| 四个缺口的取证是否准确 | ✓ 逐条复核 spec 的行号引用；`PlanValidator:131` / `InMemoryConversationMemory` 的 TTL 清扫 / 无 micrometer 依赖 均属实 |
| G4 降级判断 | ✓ **正确**。`put()` 全量清扫 + `Memory` 只存 ID，上界是「TTL 窗口内活跃会话 × 几百字节」，加 entry cap 属 speculative |
| 契约变更是否真的非破坏性 | ✓ 已核实：`tool-invoke` 无前端投影（`grep contracts.ts` 0 命中）、4 个消费方全在仓内、无外部消费方 |
| `MeterRegistry` 由 actuator 提供 | ✓ **实测确认**：`mvn dependency:list` 显示 `micrometer-core:1.15.12:compile` 经 actuator 传递带入 |
| `LlmMetricsSink.Sample` 字段够用 | ✓ `(outcome, durationMs, promptTokens, completionTokens, attempts, circuitOpen)` 能拼出三个 LLM 指标，不改已有接口 |
| 标签低基数 | ✓ 明确禁 sessionId / runId / conversationId；`toolId` 有限集合（14 个）可做标签 |
| 每条验收可命令化 | ✓ 8 条；其中 4 条要求双向自证 |
| 契约 task 前置 | ✓ T01 前置于 G3（T03） |
| 单体零回归判据 | ✓ 每个 task 都含 161；T07 含五套数字 |
| 每个 task ≤ 0.5 天 | ✓ T04 偏大（4 个模块 + 6 指标），建议编码时按「先 spi 接口 + no-op，再 Micrometer 实现」分两步提交 |
| 回退是否可行 | ✓ 三项独立；G3 回退时 `RATE_LIMITED` 留着无害（无消费方依赖其存在） |

## 5. 评审中排除的疑点

| 疑点 | 核对 | 结论 |
|---|---|---|
| `PlanValidator` 是否已有别的数量限制（会与 G2 冲突） | grep `size()` / `MAX` / `limit` | **无**，只有第 90 行用于日志。G2 不重复 |
| G2 会不会与「澄清屏多轮」叠加步数 | 全部 e2e 日志 `steps=` 分布 | 最大 3，多轮不叠加（每轮独立规划）。不冲突 |
| 加枚举值会不会让既有 invalid 示例失效 | 契约 enum 是白名单，加值只放宽 | 不失效；但 T01 要求**新增**一个 invalid 示例证明 enum 仍在约束 |

## 6. 评审自评

M-1 / M-2 是**我自己 spec 里的论证错误**（威胁模型高估、默认值依据不成立 + 算术错），不是实现缺陷——因为还没开始编码。这说明 spec 阶段的自评审有价值：这两个错误若带进编码，M-1 会导致测试测错路径（绿但无效），M-2 会埋一个排查不到的默认值陷阱。

M-3 性质不同：它是**既有缺陷**，靠"补查有没有漏列的"才发现，而且后果（重复扣款）比本 change 原列的三个缺口都严重。

**我随后自己补查了那条盲区**（"有没有该做而没列的"），查了两处：

| 盲区 | 核对 | 结果 |
|---|---|---|
| SSE 并发连接数上限 | grep `SseEmitter` / `emitters` 的计数与上限 | **无显式上限**，但 Run 编排池有界（`runQueue=32` + `AbortPolicy`）已间接限流——拿不到编排线程就不会建立长连接。不算独立缺口 |
| 审计写入失败 | 读 `InvokeToolUseCase:136` 上下文 | **发现既有缺陷** → 已升级为 M-3 |

补查是对的：M-3 的后果（重复扣款）比本 change 原有的三个缺口都严重。**这说明"只审 spec 说的对不对"确实不够**，评审必须同时问"该做的都列了吗"。

仍未覆盖的方向，建议他人沿此线索继续：`ConfirmationTokenStore` 的容量上限、`RunRepository` 的 Run 数上限（两者都是内存实现，与 G4 同类但我没查）。
