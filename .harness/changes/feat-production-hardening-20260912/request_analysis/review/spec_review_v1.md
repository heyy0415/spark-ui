# Spec Review v1 — feat-production-hardening-20260912

- **verdict**: REVISION REQUIRED → 2 MUST FIX / 3 SHOULD / 2 LOW，**全部已在同一轮落实进 spec v1.1**（见文末）
- **mode**: plan
- **独立性声明**：自评审。subagent 通道再次不可用（用户中止了评审 agent 调用）。降偏差做法：五个疑点都是作者事先列出的"自己最不确定处"，每条都回到代码取证（引 file:line），不靠推理。

## MUST FIX

### M-1 ｜ §2.5 readiness 把熔断 OPEN 映射为 OUT_OF_SERVICE 会死锁

`LlmCircuitBreaker.state()`（`LlmCircuitBreaker.java:109-115`）只读 `openedAtEpochMs`，**不做时间驱动的 OPEN → HALF_OPEN 转换**；转换只发生在 `shouldSkip()`（`:62-80`），而它只被真实规划请求调用。

推演：网关抖动 → 全部 Pod 熔断（同一上游）→ readiness OUT_OF_SERVICE → K8s 摘掉全部 Pod → **没有请求进来 → 没人调 `shouldSkip()` → 永不 HALF_OPEN → readiness 永不恢复**。这不是"雪崩"，是死锁。

处置：readiness **只反映静态不可用**（`planner=unavailable`，配置缺失，不会自愈）；熔断是瞬态且靠流量自愈的状态，进 `details.circuit=open` 但状态仍 UP。spec §2.5 已改。

### M-2 ｜ §2.2 `inputSchemas` 改为确认路径重取 `search`，破坏"Run 自包含"

`typedArgs`（`RunOrchestrator.java:704-726`）拿不到 schema 时 `getOrDefault(toolId, createObjectNode())` → 所有参数按字符串下发 → Gateway 的 inputSchema 校验对 integer 参数报 `INPUT_INVALID` → 用户看到 `TOOL_EXECUTION_FAILED`，而真实原因是"确认落到另一副本时工具被宿主策略下线"，排查不到。

更根本的问题：B3 的目标就是让 Run 携带确认所需的一切；重取 `search` 引入了对注册表状态**跨时间一致**的隐含依赖。

处置：`attachPlan` 时把**计划各步骤**（含 recheck 工具——它必在计划内，`:328-340` 强制）的 inputSchema 快照进 `Run.stepSchemas`（toolId → JSON 字符串）。3 步 ≈ 1.5 KB，可接受。澄清路径 `invoke(c.toolId(), Map.of())` 参数为空，无需 schema。spec §2.2 表格已改。

## SHOULD

### S-1 ｜ §2.2 确认路径 `transition(EXECUTING)` 后应立即 `runs.save`

`confirm()` 在 `:289` 迁移到 EXECUTING 但直到 `complete()` / `fail()` 才 save。内存版同一对象引用无感；Redis 版每次 `find` 是新副本，另一副本的 `GET /agent/runs/{id}` 在执行窗口内会看到过时的 `WAITING_CONFIRMATION`。

**安全性核实**：这个窗口不影响并发确认的正确性——另一副本的第二个确认请求会因状态过时而通过前置校验，但 `tokens.consume`（Redis `GETDEL`）拿到 nil → `TokenUnknown` → `rejectRequest`，而 `rejectRequest` **不改 Run**（`:394` javadoc + 代码核实：无 transition / save）。令牌原子消费确实是唯一需要的互斥，删 `confirmLocks` 的分析成立。

处置：`:289` 后加 `runs.save(run)`，一行。tasks T02 已加。

### S-2 ｜ §2.3 Redis `claim` 的 owner 崩溃语义要与内存版对齐

内存版：owner 失败 → `release` → future 异常完成 → 等待者 `catch (ExecutionException)` 重新 claim（`InvokeToolUseCase.java:333-338`）。Redis 版：owner 崩溃 → key 在 `PX` 后过期 → 等待者轮询 `GET` 得 nil。**必须把 nil 当作"已 release"让等待者重 claim**，否则等待者会一直等到自己的 deadline 才 TIMEOUT。

另：claim key 的 TTL 取 `timeoutMs + 5s` 余量——Gateway 的 `f.get(timeoutMs)` 超时后会 `release`，正常路径下 owner 总在 key 过期前动作；余量防止 owner 刚 complete 而 key 先过期的竞态。

处置：spec §2.3 已补；T03 契约测试增「owner 消失 → 等待者重 claim 成为 Owner」。

### S-3 ｜ §2.4 断连优化对 http provider 工具形同不存在，须进已知限制

`ToolMetaRegistry` 只由 `SparkToolScanner` 填（`SparkToolScanner.java:91`），扫的是本进程 Bean；http 注册路径（`RegisterToolUseCase`）不产生 `ToolMeta`。故 provider 工具的 `sideEffect` 永远取不到 → 按 fail-safe 视为写 → 不终止。

处置：写进 README 已知限制与 javadoc。不在本 change 修：修法是仿 `ToolNameSink`（spi 端口，registry 调、runtime 实现，`StartupManifestRegistrar.java` 已有先例）加一个风险回填端口，属独立 change。记 LOW-2。

## LOW

- **L-1** 终态时清空 `Run.stepOutputs` 再 save：它只服务确认屏，终态后无用；`currentUi` 保留（`GET` 需要）。减小 Redis 存储。T02 已加。
- **L-2** 后续 change：`ToolRiskSink`（或扩 `ToolNameSink`）让 http 工具的 `sideEffect` 进 `ToolMetaRegistry`。

## 逐项核对（plan 模式 checklist）

- [x] 「非目标」章节存在且非空（§3，11 项）
- [x] 每条验收标准可被命令或断言校验（§6：单测名 / 脚本数字 / grep）
- [x] 风险章节 ≥1 失败模式与缓解（§7，8 条；含 agent-safety §3 的互斥前提）
- [x] 每个 task 标注所属端；无契约 task（§5 核对无 Schema 变更——`run-summary.currentUi` 形状不变，`sse-events` 不增码，`tool-search` 六字段不动）
- [x] 涉及跨端结构的 task 列出契约文件：无跨端结构变更
- [ ] 每个 task ≤ 0.5 天：**T03（Redis 模块）与 T11（README 重写）明显超**。接受：T03 拆开会让四个实现分散在不同提交里难以整体回滚；T11 是用户明确要求的单一交付物。在 tasks.md 标注。

## 结论

两条 MUST FIX 都指向同一类错误：**把"能工作"当成"语义正确"**——readiness 映射能编译能跑，但会把集群锁死；重取 search 能跑通 e2e，但把 Run 的自包含性偷偷换成了对注册表的时序依赖。两条都已落实进 spec v1.1；无需第二轮。
