# Change Summary: feat-runtime-limits-and-metrics-20260912

| 字段 | 值 |
|---|---|
| Change ID | feat-runtime-limits-and-metrics-20260912 |
| 类型 | feat |
| 状态 | IN_PROGRESS（阶段 1–4 DONE；待用户确认后提交） |
| 负责人 | Platform Owner Agent |
| 涉及端 | contracts（error.code 增值）+ spark-rooter（spi / runtime / gateway / starter）+ harness 规则；**spark-ui 零改动** |
| 缺口 | G1 指标导出、G2 步骤上限、G3 会话并发、**G5 审计失败不吞结果**（评审新增）；G4 判定不做 |
| 起止时间 | 2026-09-12 ~ — |
| Spec | [request_analysis/spec.md](request_analysis/spec.md) |
| Tasks | [request_analysis/tasks.md](request_analysis/tasks.md) |

## 阶段进度表

| # | 阶段 | 状态 | 评审轮次 | 产出 | 时间 |
|---|---|---|---|---|---|
| 1 | 需求分析 | DONE | — | spec.md（4 缺口取证 + 3 开放项当场验完 + 1 项自我降级）、tasks.md（T01–T07） | 2026-09-13 |
| 2 | 需求评审 | DONE | 1/3 | [review/spec_review_v1.md](request_analysis/review/spec_review_v1.md)（**CHANGES REQUESTED** → 3 MUST FIX + 2 SHOULD **全部已落实**；其中 M-3 是补查盲区发现的既有缺陷，用户决策纳入本 change） | 2026-09-13 |
| 3 | 编码实现 | DONE | — | T01–T08 全部完成；五套回归全绿（161 / 15 / 7 / 12 / **107**），`pnpm -C .harness run ci` 0 | 2026-09-13 |
| 4 | 编码评审 | DONE | 1/2 | [coding/code_review_v1.md](coding/code_review_v1.md)（**APPROVED WITH FIXES**：3 MUST FIX 已修，2 SHOULD 经实测排除；自评审，**不满足独立性**） | 2026-09-13 |
| 5 | 代码推送 | TODO | — | — | — |
| 6 | CI 验证 | TODO | — | — | — |
| 7 | 部署验证 | TODO | — | — | — |
| 8 | 用户确认 | TODO | — | — | — |

## 契约变更

- `tool-invoke.schema.json`：`error.code` 的 enum 增 **`RATE_LIMITED`**（G3 限流拒绝需要）。
- **新增值而非破坏性变更**，核实依据：前端**不投影** `tool-invoke`（后端内部契约）；4 个消费方全在仓内（`ToolInvoke` / `InvokeToolUseCase` / `GatewayException` / `RunOrchestrator`）；无外部消费方。故 `$id` 保持 `/v1/`、`schemaVersion` 保持 `1.0`。
- 与既有先例同理（`provider` 段、组件白名单收敛）：首个外部消费方出现后再改必须发 `/v2/`。

## 本 change 的由来

不在原 20 项改造清单内。核对清单剩余项时对六个维度做手工审计查出的 4 个缺口——共同点是「生产环境才会疼、本地演示察觉不到」。

**多智能体审计失败了**：6 个 probe agent 全部返回 `400 专用渠道限制`（`agents_error: 6` / `agents_done: 0`），workflow 因此算出 `confirmed: []`。**零个成功 agent 的空结果不等于"没发现缺口"**——我没采信它，改为逐维度手工 grep 取证。

## 阶段 1 的两处自我纠正

1. **G4 降级并不做**。我核对时说「会话记忆无条数上限 → 高并发下内存可涨到很大」。读代码发现前提不准：`put()` 每次写入前全量清扫过期项，且 `Memory` 只存 ID（几百字节）。实际上界是「TTL 窗口内活跃会话数 × 几百字节」，10 万会话约几十 MB。加 entry cap 需要淘汰策略，而无证据表明必要——属 speculative 扩展，不做，只写清上界推导。
2. **G3 的错误码从"倾向复用"改为"新增枚举值"**。spec 初稿写「扩枚举是破坏性变更，倾向复用 `HANDLER_ERROR`」。核实后发现 `tool-invoke` 无前端投影、消费方全在仓内，新增值并不破坏任何东西；而复用 `FORBIDDEN` 会与权限拒绝混同，让调用方无法凭 code 判断该不该重试。按用户决策新增 `RATE_LIMITED`。

## 经验沉淀
- （留空，每发现一个 Agent 错误后**先**在此追加一行，再决定是否升级到 Skill / Rule）

## 阶段 2 评审发现（3 MUST FIX 全部已落实）

只提能用 grep / 实测证明的问题。**M-1 / M-2 是我自己 spec 里的论证错误**（还没开始编码，所以不是实现缺陷）；**M-3 是补查盲区时发现的既有缺陷**。

| 编号 | 问题 | 后果 | 落实 |
|---|---|---|---|
| M-1 | G3 威胁模型高估：写操作已被幂等 `claimOrAwait` 序列化（3/14 工具），真正暴露的是 11 个只读工具 | 实现者会拿写操作工具写测试，**绿但没测到限流逻辑** | spec §3.3 重写威胁模型；tasks T03 明确「必须用只读工具测」 |
| M-2 | G2 默认值 8 的依据不成立：`FakeLlmPlanner` 里恰好有 8 处 `DraftStep`，撞线时 e2e 只报「无法制定方案」 | 埋一个排查不到的默认值陷阱；顺带算术也错了（3→8 是 2.67 倍不是 2.5） | 默认值改 **6**（实测最大 3 的 2 倍，不与任何现存数字重合）；日志必须带 `steps=7 max=6` |
| M-3 | `audit.record()` 无 try-catch，夹在「工具已执行」与「return 结果」之间 | 宿主换写库/Kafka 的 AuditSink 并抛异常 → 退款已执行但用户看到失败 → **可能重试 → 重复扣款** | 新增 T04；用户决策本 change 一并修 |

S-1（`spark.run.outcomes` 需覆盖 Run 每个出口，漏一个成功率就失真）与 S-2（registry 抛异常不得拖垮主链路）也已落进 T05。

### 评审中排除的三个疑点

`PlanValidator` 有无既存数量限制（**无**，不重复）、G2 会否与澄清屏多轮叠加步数（**不会**，每轮独立规划，实测 steps 最大 3）、加枚举值会否让既有 invalid 示例失效（**不会**，enum 是白名单，加值只放宽）。

### 这轮评审的方法论收获

我在 §6 自评里写「只审了 spec 说的对不对，没审有没有该做而没列的」，然后**自己补查了那条盲区** —— 查了两处（SSE 连接数上限、审计写入失败），其中审计那条查出 M-3，后果比本 change 原列的三个缺口都严重。

**教训：评审必须同时问两个问题**——「说的对不对」和「该做的列全了吗」。只问前者会漏掉最严重的那条。

仍未覆盖、建议后续核查：`ConfirmationTokenStore` 与 `RunRepository` 的容量上限（都是内存实现，与 G4 同类，我没查）。

## 阶段 3 交付（T01–T08）

| task | 内容 | 测试 |
|---|---|---|
| T01 | **两份**契约加 `RATE_LIMITED`（HTTP 429）+ 前端 Zod 投影同步 | 前端 106 → **107** |
| T02 | `PlanValidator.MAX_PLAN_STEPS = 6`，日志带 `steps=7 max=6` | 22 passed，双向自证 |
| T03 | `SessionConcurrencyLimiter`（CAS 无锁，单会话 4） | 63 passed，双向自证 |
| T04 | 审计失败不吞已成功的结果（评审 M-3 的既有缺陷） | 20 passed，自证 |
| T05 | 三个 spi 埋点端口 + Micrometer 实现（7 个指标） | 实测 4 个指标真实出现 |
| T06 | Micrometer 不得成为硬依赖（门禁 + optional 检查） | 三条自证 |
| T07 | 规则 / README / contracts 文档同步 | doctor 0 |
| T08 | 五套回归 | 161 / 15 / 7 / 12 / 107 |

### 本阶段查出一个单测抓不到的真 bug

`MetricsBeans` 上的 `@ConditionalOnBean(MeterRegistry.class)` **不生效**：本类由 starter 的 `@Import` 组合进来，不是独立 AutoConfiguration，条件求值早于 actuator 注册 `MeterRegistry` → 条件永不成立 → **静默退回日志实现**。

单测全绿、编译通过、启动正常，**指标一个都没有**。只有起真实进程查 `/actuator/metrics` 才发现。排查链：无 `spark.*` 指标 → 但 `TOOL_METRICS` 日志有 4 条 → 说明走了日志实现 → `MeterRegistry` 存在（51 个指标）→ 是条件判断错。

改用 `ObjectProvider`（注入时才解析）后指标立刻出现。已写进 `backend-standard.md`：**`@ConditionalOnBean` 在 `@Import` 类上不可靠**。

实测确认的指标与标签：

```
spark.tool.invocations{toolId, status}  ← 标签只有这两个，无任何 ID
spark.tool.duration{toolId}
spark.run.outcomes{outcome=completed}  ← count=1
spark.run.duration{outcome}
```

`spark.llm.*` 未出现是**预期**：e2e profile 用 `FakeLlmPlanner`，不经 `LlmPlanner`（LLM 埋点在那里）。

### 三处偏离 spec（均有理由）

1. **`MAX_PLAN_STEPS` 不做成配置项**。查仓内先例——`LlmPlanner.MAX_ATTEMPTS` / `PromptBuilder.MAX_TEXT` / `ClarificationScreen.MAX_COLUMNS` 全是常量，这类内核自保阈值本仓就不给宿主调。加配置项等于凭空多一个配置面。
2. **契约改了两份不是一份**。`RATE_LIMITED` 要映射 429，而对外的 `error-response` 枚举里也没有限流码。这不在原批准范围，已问过用户后一并加。**代价**：本 change 原定「前端零改动」不再成立（`error-response` 有前端 Zod 投影）。
3. **指标是 7 个不是 6 个**。实现期补了 `spark.run.duration`——只有出口分布看不出「成功但很慢」。已把 spec / tasks / README 的数字全部改对，并逐项核对与代码一致。

### 三次踩同一个坑

`mvnw install` 后忘记重打包 `host-demo`，跑的是旧 jar。本次会话第三次（T01、跨服务 e2e、T05）。根因是 spring-boot repackage 不会因本地仓 SNAPSHOT 更新而重新拉依赖，必须 `rm -rf target`。

## 阶段 4 评审发现（3 MUST FIX 已修）

| 编号 | 问题 | 性质 |
|---|---|---|
| F-1 | 限流名额包住了幂等的「等待别人结果」段：同 key 的 4 个并发里 1 个真执行 3 个干等，都占名额 | **设计取舍**。用户决策保持现状（等待占线程与连接，并非免费；同 key 并发本身是异常模式），写清 javadoc + characterization test 锁住决策 |
| F-2 | `RATE_LIMITED` 归入 `TOOL_EXECUTION_FAILED` 的通用文案，未落实 spec 承诺的「当前请求较多，请稍后重试」 | **真缺陷**。已给专属文案；不新增 `RunFailureCode`（那会动 sse-events 契约与前端投影） |
| F-3 | **上一阶段有一条假测试**：`inFlight("sess_1")` 查的 key 从未存在（实际是 `"sess"`），断言恒真 | **假测试**。抽常量统一；重新自证后删 `lease.close()` 这次真的会红 |

### F-3 的教训

T03 阶段我做过自证，但只证了限流拒绝那条（改 `RATE_LIMITED`→`FORBIDDEN` 会红），**没单独证释放那条**。于是一条恒真的断言混了过去。

**自证必须逐条做** —— 不能因为同一测试类里有一条会红，就认为整类有效。这是本次会话第四次遇到"绿但无效"（前三次：字节码门禁、FQN 正则、T03 的限流测试初版）。

### 两条 SHOULD 经实测排除（不是靠推理放过）

| 疑点 | 取证 | 结论 |
|---|---|---|
| `registry.counter()` 每次新建 meter 拖慢主链路 | 写 `SimpleMeterRegistry` 探针 | **非缺陷**：同名同标签返回同一实例（`c1 == c2`），registry 里只有 1 个 meter。Micrometer 按 name+tags 缓存 |
| provider 侧无埋点 | grep | **范围外**（spec 目标是 hub 侧），但是微服务形态的真实缺口，已记入遗留 |

### 我在评审中修正了自己的一处误判

F-1 我最初判为缺陷（"等待不占资源，不该占名额"）。核对后发现等待占着 Gateway 线程与 HTTP 连接 —— **把它当免费才是误判**。改为"写清语义"而非改实现。

与阶段 2 的 M-3 相反：那次是我的**前提**错了（以为比对跑在 provider 侧），这次是我的**价值判断**错了。两种都得核对代码才能发现。

### 阶段 4 后的五套回归

`e2e-backend` **161** / `e2e-provider` **15** / `e2e-frontend` **7** / `deploy-verify` **12** / 前端单测 **107**；`pnpm -C .harness run ci` **0**。
