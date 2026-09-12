# Code Review v1 — feat-runtime-resilience-and-llm-metrics-20260912

- **mode**: execution
- **评审对象**: 本 change 全部改动、`coding/coding_report_v1.md`
- **依据**: `code-review/SKILL.md`、`expert-reviewer/SKILL.md`（execution 必查项）、`rules/{backend-standard,agent-safety,project-structure,contracts}.md`、公司 Java 规范（并发与资源、POJO 类型、日志）
- **verdict**: **APPROVED**（0 条 MUST FIX，2 条 SHOULD）

> **独立性声明**：subagent 通道在本会话不可用，本文由编码者撰写，独立性不满足。补偿：所有行为结论以实跑输出为据（而非代码审查推断）；§5 列建议他人复核项。

---

## 1. 机器化检查

| 命令 | 退出码 |
|---|---|
| `pnpm -C .harness run ci` | 0（9 步） |
| `pnpm -C .harness run doctor` | 0 |
| `mvn install`（含新增 12 个单测） | 0 |
| `grep -rn 'Executors\.new'` | 无命中 |
| `e2e-backend.sh` | 0，161 passed |
| `e2e-frontend.sh` | 0，7 passed |
| `deploy-verify.sh` | 0，12 passed |

三套端到端**零回归**——这是本 change 最重要的机器化证据：改了三处线程池与 LLM 调用链，默认配置下行为完全不变。

## 2. 红线清单

**后端**

- [x] `domain/` 无框架依赖 —— 新增类都在 `infra/` 与 `spi/`
- [x] 平台模块无 Spring 组件注解 —— `check-module-deps` 退出 0
- [x] 平台模块无领域词汇 —— 编码期被红线抓到一次（`toolQueue` javadoc 的「退款链」举例），已修正
- [x] 平台模块无 `userId` / `tenantId` / `Principal`
- [x] 无 `System.out`、无空 catch —— `emitMetrics` 的 `catch (RuntimeException)` 有 `log.debug` 且注明「埋点不能让请求失败」
- [x] 金额 / ID 为 `String` —— 本次未新增此类字段
- [x] **线程池有界 + 拒绝策略 + 命名线程** —— 公司 Java 规范「禁止使用 Executors」已落实

**契约**

- [x] 零改动（`check-contracts` 退出 0）
- [x] 新增的 `run.failed` 帧符合契约：`runId` 匹配 `^run_[A-Za-z0-9_-]{1,60}$`（已查 schema 并用 `run_rejected` 满足）、`code` 在 enum 内

**前端**

- [x] 未改（`spark-ui` ci 退出 0）

## 3. 逐条意见

### I-1 ｜ INFO ｜ 三个 SHOULD 全部落实且验证到位

| 评审条目 | 落实与证据 |
|---|---|
| S-1 `toolExecutor` 拒绝路径 | spec §2.2 补了完整路径说明；验收 4b 实跑 5 个并发请求全部收到终态、无挂死，被拒者为 `TOOL_EXECUTION_FAILED` |
| S-2 阈值 5 → 2 并写明层级 | 已改；**且实测确认了推理**：`LLM call failed attempt` 出现 6 次 = 2 × 3，证明「一次失败 = 3 次真实网关请求」 |
| S-3 熔断判据改日志 | 已改；实测 `upstream error` 总数为 2（= 阈值）而非 5，证明熔断后没再发请求 |

S-2 那条尤其值得记录：评审阶段只是**推理**「熔断器看到的一次失败等于 3 次真实请求」，编码期的 `LLM call failed attempt` 计数把它变成了**实证**。若当初按 5 定阈值，熔断前会有 15 次真实请求。

### I-2 ｜ INFO ｜ `durationMs=0` 是熔断价值的可量化证据

严格串行的实测输出里，熔断生效的两个请求 `durationMs=0`，而前两个是 3121ms / 3032ms。这比「日志里有 circuit open」更有说服力——它直接量化了「从等 3 秒变成立刻返回」。

### I-3 ｜ INFO ｜ 编码期的一次自我纠错值得肯定

并发测试时第 3 个请求显示 `transport_error` 而非 `circuit_open`，coding_report §4 记录了完整的诊断过程：查毫秒级时间线 → 发现该请求在熔断打开前 1.8 秒就已通过 `shouldSkip()` → 确认「在途请求不被中途取消」是正常语义 → 改用严格串行重验。

**没有把测试方法的问题当成代码 bug 去"修"**，这是对的。如果当时加了什么「取消在途请求」的逻辑，反而会引入真问题。

### S-1 ｜ SHOULD ｜`LlmPlanner` 的 outcome 判定依赖异常 message 字符串

- **位置**: `LlmPlanner.plan()` 的 `boolean circuitOpen = "llm circuit open".equals(e.getMessage())`
- **核对**: 熔断跳过时抛 `RunFailure.withUserText("INTERNAL_ERROR", "llm circuit open", CIRCUIT_OPEN_TEXT)`，外层靠 message 字面量反推 outcome。这是**字符串耦合**：将来有人改这条 message（比如加上阈值数字便于排查），埋点的 `circuit_open` 分类会静默退化成 `transport_error`，而且没有测试会红。
- **问题**: 不影响当前行为（已实测 `outcome=circuit_open` 正确），但是个脆弱点。
- **建议**: 两个选项——(a) 把 message 提为常量 `CIRCUIT_OPEN_REASON` 并在抛出与判定两处共用；(b) 在 `planInternal` 里直接返回/抛出一个带类型标记的内部异常。倾向 (a)，改动小。
- **分级**: SHOULD
- **处置**: **本 change 内已落实方案 (a)** —— 新增 `CIRCUIT_OPEN_REASON` 常量，抛出（`:139`）与判定（`:80`）两处共用。门禁复跑 9 步全绿。

### S-2 ｜ SHOULD ｜ 埋点缺少「planned 且带真实 token」的实测

- **位置**: 验收 8 / coding_report §1 的埋点验证
- **核对**: 实测覆盖了 `transport_error`（3 条）与 `circuit_open`（2 条），以及假规划器模式下为 0。但 **`outcome=planned` 且 `promptTokens` 为真实数字**这条路径没有实测——它需要一个能正常应答的模型，本机无 `SPARK_LLM_API_KEY`。
- **问题**: `recordUsage` 的 null 检查链（`response == null` / `getMetadata() == null` / `getUsage() == null`）与 `LogLlmMetricsSink.orDash` 的非 null 分支都未被真实数据走过。风险不高（逻辑简单、`Usage.getPromptTokens()` 返回 `Integer` 已用 `javap` 确认），但属未验证代码。
- **建议**: 记为遗留债务，下次有真 key 时补一次 LIVE 验证；或补一个 `LogLlmMetricsSinkTest` 单测覆盖 null 与非 null 两种 `Sample`（成本约 15 行）。
- **分级**: SHOULD
- **处置**: **本 change 内已补 `LogLlmMetricsSinkTest`**（3 用例：真实 token / null token / 熔断样本），覆盖了 `orDash` 的两条分支。`outcome=planned` 的**端到端** LIVE 验证仍留作遗留债务——它需要真实模型密钥。

### L-1 ｜ LOW ｜ `pingScheduler` 的例外处理是正确的

- **核对**: JDK 21 反射实测（阶段 1）确认 `ScheduledThreadPoolExecutor` 四个构造器都不接受 `BlockingQueue`，`remainingCapacity()` 恒为 `Integer.MAX_VALUE`。代码改为显式 `new ScheduledThreadPoolExecutor` 满足「不用 Executors 工厂」，并在注释里说明为何此处可接受无界。`backend-standard.md` §6 也把这个例外规则化了。
- **分级**: LOW（记录核对结论）

### L-2 ｜ LOW ｜ `recordSuccess()` 的调用位置有讲究

- **核对**: 放在拿到 draft 之后、`PlanValidator.decide` 之前。这样「模型应答了但输出不合规」会 `recordSuccess`（传输正常）而非计入失败。`LlmCircuitBreakerTest.validationFailuresDoNotOpenTheCircuit()` 固化了这个约定。
- 若放在 `decide` 之后，校验失败会跳过 `recordSuccess`，导致连续的「输出不合规」间接累积成熔断——正是 spec 要避免的「把模型能力不足判成服务宕机」。
- **分级**: LOW（记录，这个位置不是随意的）

## 4. execution 必查项

| 项 | 结果 |
|---|---|
| 契约一致性 | N/A 零改动；新增 SSE 帧已核对 runId pattern 与 code enum |
| `agent-safety.md` §1–§6 | 六条逐项核对见 coding_report §5。§6 重点：埋点落日志不进 SSE，无 prompt 原文 / 模型名 / 网关地址 |
| 前端红线 | N/A（未改） |
| 后端红线 | 通过，含公司 Java 规范的线程池要求 |
| 改动 ≠ spec 时显式标注偏差 | 通过。两处编码期发现（`Usage` 返回包装类型、领域词汇被红线抓到）已在 coding_report §4 记录 |

## 5. 建议他人复核的条目

1. **队列容量 32 / 64 是否合适**。算式（排到第 32 位的等待超出 `sse-timeout`）成立，但「4 倍核心数」这个比例是经验值，没有生产数据。
2. **熔断阈值 2 对抖动频繁的网关是否过敏**。已实测确认「2 × 3 = 6 次真实请求」，但多敏感算「过敏」取决于网关的故障模式。
3. **S-1 的字符串耦合**。我倾向提常量，但也许有人认为按异常类型判定更干净。
4. **`outcome=planned` 路径未实测**（S-2）。

## 6. 回退

`APPROVED` → 进阶段 5。**两条 SHOULD 均已在本 change 内落实**（见各条的「处置」），落实后 `pnpm -C .harness run ci` 复跑仍 9 步全绿。唯一保留的遗留债务是 `outcome=planned` 的端到端 LIVE 验证（需真实模型密钥）。
