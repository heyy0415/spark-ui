# 代码评审 v1 — feat-production-hardening-20260912

## 0. 独立性声明

**自评审，不满足独立性要求。** 评审 agent 通道本 change 内被中止过一次（阶段 2），阶段 4 未再尝试——同一会话里再派也是同一个模型在看自己写的东西。

降偏差的做法与前几个 change 一致，加一条：

1. 每条 MUST FIX 都先写出会红的断言或跑真实进程取证；
2. "没问题"的判断也取证，不靠推理；
3. **重新检查阶段 3 的自证是否有效**——本轮抓到 T07 的量具本身有假象（见 §3）；
4. **专挑 spec 评审时作者说"最不确定"的地方复核**（Redis 轮询、快照演进、complete 顺序）。

## 1. 结论

**APPROVED WITH FIXES** —— 3 项 MUST FIX 已修并回归；1 项 SHOULD 经实测排除；1 项 SHOULD 记入后续。

回归：后端单测 **275 / 0**（含 Redis 18 条真实 Redis）；`e2e-backend` **162 / 0**；`e2e-provider` **15 / 0**；`e2e-frontend` **7**；`deploy-verify` **14 / 0**；`e2e-multi-instance` **21 / 0**；前端单测 **107**；`pnpm -C .harness run ci` 见 `ci_result/ci_summary.md`。

## 2. MUST FIX（已修）

### F-1 ｜ Redis 幂等表的等待方超时后轮询不停

**位置**：`RedisIdempotencyStore.poll()` / `InvokeToolUseCase.claimOrAwait():328`

**核对**：`claimOrAwait` 对 `Awaiting` 做 `future.get(remain)`，超时抛 `GatewayException` 后**没有 cancel future**。内存版无所谓（future 只是个对象）；Redis 版的 future 由 50ms 周期的轮询任务完成，`poll()` 的退出条件是 `f.isDone()`——没人 cancel 它就一直 `isDone()==false`，轮询跑到 key 消失（最长 `claimTtl` 5 分钟）。

**后果**：每个超时的等待方留一条 5 分钟的调度任务。同 key 并发提交是异常路径，量不大，但这是"加了共享存储又引入新泄漏"的同类问题，而且出现在最不该出问题的地方（本 change 就是来堵泄漏的）。

**修法**：`claimOrAwait` 超时分支加 `a.future().cancel(false)`。**自证**：`RedisIdempotencyStoreTest.cancelledWaiterStopsPolling`——cancel 后两个周期，poller 队列为空；去掉 `poll()` 里的 `isDone` 检查该用例会红。

### F-2 ｜ `RunSnapshot` 在滚动升级时不兼容

**位置**：`RunSnapshot`（record，经 `PlatformMapper` 序列化）

**核对**：`PlatformMapper` 开了 `FAIL_ON_UNKNOWN_PROPERTIES`（契约的 `additionalProperties:false` 对应）。多副本滚动升级期间新旧副本共存：新副本给 `RunSnapshot` 加了字段并写入 Redis → 旧副本 `find()` 反序列化抛异常 → 那条 Run 在旧副本上 500。反向（新读旧）record 缺字段为 null，没问题。

**修法**：`RunSnapshot` 加 `@JsonIgnoreProperties(ignoreUnknown = true)`。这不违反契约口径——`RunSnapshot` 是内部存储格式，不是跨端契约。

### F-3 ｜ `complete()` 里 `remember` 抛异常会让 Run 停在 EXECUTING

**位置**：`RunOrchestrator.complete():755-765`

**核对**：改造前顺序是 `transition → remember → clearStepOutputs → save`。`remember` 要调 `memory.find/put`——Redis 实现会走网络，可能抛。抛了就跳过 `save`，共享存储里的 Run 永远是 EXECUTING；另一副本的 `GET` 看到一个永远"执行中"的 Run，`runTtl` 到期前都不会消失。内存版不受影响（对象引用已经是 COMPLETED），所以现有测试全绿——**这是只在多副本下暴露的顺序 bug**。

**修法**：`save` 提前到 `remember` 之前；`remember` 包 try-catch 记 WARN。记忆是锦上添花，丢一次的代价是下一轮「第二个」解析不到，远小于 Run 状态错。

## 3. 经实测排除的 SHOULD

| 疑点 | 取证 | 结论 |
|---|---|---|
| 压测里 1022s 的 max 是服务端 SSE 挂死 | 后端日志 0 timeout、0 ERROR；小规模复现 5 轮 max 78ms；本机 `TIME_WAIT` 与临时端口范围核对 | **非缺陷，是量具假象**：短连接风暴耗尽临时端口后 `connect()` 卡住。已给脚本加 100s 硬超时与 `wall` 实测耗时，重跑 `client_timeout=0` |

这一条值得单独说：**第一版压测报告里的数字是错的**（那个 max），而且错得像服务端问题。如果不追下去，README 会写"极端情况有 17 分钟延迟"这种吓人又不实的话。量具先要可信，报告才有意义。

## 4. SHOULD（记入后续，本 change 不修）

**S-1 ｜ http provider 工具没有 `ToolMeta`，断连优化对它们不生效。** spec §2.4 已知、README 已知限制已写。修法是仿 `ToolNameSink` 加风险回填 spi 端口，独立 change。

## 5. 逐项核对

| 项 | 结论 |
|---|---|
| 契约零变更 | ✓ `run-summary.currentUi` 形状不变（来源换了）；`sse-events` / `tool-search` / 其余 7 个未动；`check-contracts` 0 |
| agent-safety §3 确认互斥 | ✓ 删 `confirmLocks` 后靠令牌原子消费；内存 32 线程 + Redis 32 线程两条并发测试；`e2e-multi-instance` 第 5 步跨副本重放被拒 |
| Run 自包含 | ✓ `confirmOnAnotherReplicaSucceedsWithSharedRepository`：副本 B 只共享三个存储就完成确认；e2e 第 3 步 B 审计有 refund.create、A 没有 |
| 用户原话不进共享存储 | ✓ e2e 第 8 条当场抓到首版泄漏，修后 0 命中 |
| 内存实现有界 | ✓ 令牌 / 幂等两处自证（删清扫 → 红） |
| Redis 不降级到内存 | ✓ `storage.type=redis` 但装配不到 → WARN 不静默；Redis 连不上 → 500 + Spring 自带 health DOWN |
| readiness 语义 | ✓ 熔断 OPEN 仍 UP（单测锁住）；无模型实测根 health DOWN / readiness 503；Dockerfile 改打 liveness |
| 断连语义 | ✓ 只读终止 / 写照跑 / 元数据缺失视为写 三条单测；e2e 守"到终态不悬挂" |
| 自发包路径 | ✓ `-P '!examples'` 实测 9 artifact + parent；`release` 产 sources/javadoc；门禁双向自证 |
| 门禁新增 | ✓ Redis 反向依赖 / examples 回顶层 各红一次 |
| 压测结论 | ✓ 三个默认值与实测吻合不改；javadoc「估算」0 残留 |
| README 数字 | ✓ 12 项逐一核对（`coding_report` §4），首稿 32 KB 改 30 KB |
| 口号词门禁 | ✓ 0 命中 |
| CI 可运行 | ✓ **事后实证**：GitHub run `34753846814` 全部步骤 success。首跑红三次（shellcheck 版本差 / mvnw 内网地址 / host-demo 离线缺 actuator），修复见 PR #3 / #4 与 summary 经验沉淀 #8 |

## 6. 遗留与已知限制

1. **本评审非独立**（§0）。
2. ~~CI 工作流未在 GitHub 上跑过~~ → 已跑通（run `34753846814`）。首跑暴露了三个"本机有、runner 没有"的隐含依赖，其中 mvnw 指向内网 Nexus 是**开源可用性缺陷**（clone 后第一步就挂），已修并加 doctor 门禁。
3. **provider 工具的断连优化不生效**（S-1）。
4. **Redis 版 Awaiting 是 50ms 轮询**，不是推送。正常链路零轮询；只在同 key 并发提交时出现。
5. **`spark.storage.redis.claim-ttl` 与工具 `timeoutMs` 的关系靠文档约束**，没有启动期校验（要遍历全部 Manifest 取最大 timeoutMs，而 http 工具是启动后才推来的）。写进了配置表与 javadoc 的加粗提示。
6. **e2e-backend 与 deploy-verify 不能并行跑在同一 change 目录**（共用 `backend.log`）。本轮踩到一次假阳性（15 条红），单独重跑全绿。应记入 `deploy-verify` skill 的注意事项。
