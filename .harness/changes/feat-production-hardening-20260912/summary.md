# Change Summary: feat-production-hardening-20260912

| 字段 | 值 |
|---|---|
| Change ID | feat-production-hardening-20260912 |
| 类型 | feat |
| 状态 | PUSHED（阶段 8 待用户确认） |
| 负责人 | Platform Owner Agent |
| 涉及端 | spark-rooter（runtime / gateway / starter / **新模块 spark-rooter-redis** / host-demo）+ harness（脚本 / 门禁 / 规则 / CI）+ 根 README；**契约零变更**；**spark-ui 零改动** |
| 目标 | 生产可用性收口：4 blocker/high 修复 + 多副本（Redis）+ 断连终止 + 健康探针 + 压测 + 自发包路径 + CI + README 重写。**不发包** |
| 起止时间 | 2026-09-12 ~ — |
| Spec | [request_analysis/spec.md](request_analysis/spec.md) |
| Tasks | [request_analysis/tasks.md](request_analysis/tasks.md) |

## 阶段进度表

| # | 阶段 | 状态 | 评审轮次 | 产出 | 时间 |
|---|---|---|---|---|---|
| 1 | 需求分析 | DONE | — | spec.md（审计 10 项 + 6 项用户决策 + 7 强制章节）、tasks.md（T01–T12） | 2026-09-13 |
| 2 | 需求评审 | DONE | 1/3 | [review/spec_review_v1.md](request_analysis/review/spec_review_v1.md)：**REVISION REQUIRED → 2 MUST FIX（readiness 熔断映射会死锁；inputSchemas 重取破坏 Run 自包含）+ 3 SHOULD + 2 LOW，全部落实为 spec v1.1**。自评审，独立性不满足（评审 agent 被用户中止） | 2026-09-13 |
| 3 | 编码实现 | DONE | — | [coding/coding_report_v1.md](coding/coding_report_v1.md)：T01–T11 完成，45 文件；新模块 `spark-rooter-redis`（17 条真实 Redis 测试）；契约零变更；spark-ui 零改动。三处 spec 偏差已注明理由（存储自清而非接口加 evict、`spark.storage.type`、断连 e2e 断言口径） | 2026-09-13 |
| 4 | 编码评审 | DONE | 1/2 | [coding/code_review_v1.md](coding/code_review_v1.md)：**APPROVED WITH FIXES** —— 3 MUST FIX 已修并双向自证（Redis 等待方超时后轮询不停；`RunSnapshot` 滚动升级不兼容；`complete()` 里记忆抛异常让 Run 停在 EXECUTING）；1 SHOULD 实测排除（压测 1022s 是量具假象）；1 SHOULD 记后续。自评审，独立性不满足 | 2026-09-13 |
| 5 | 代码推送 | DONE | — | 提交 `fc2e0da` 于分支 `feat/production-hardening`，**已 push** 到 `origin`（138 文件，+5492 / −286）。PR 链接：https://github.com/heyy0415/spark-ui/pull/new/feat/production-hardening | 2026-09-13 |
| 6 | CI 验证 | DONE（本地）/ GitHub 待看 | — | 本地 `pnpm -C .harness run ci` 9 步 0 → [ci_result/ci_summary.md](ci_result/ci_summary.md)。**GitHub Actions 首次在本分支 push 后触发，本地无法验证其结果**——需在 PR 页看一眼 | 2026-09-13 |
| 7 | 部署验证 | DONE | — | [deployment/preview_report.md](deployment/preview_report.md)：deploy-verify **14 / 0**、e2e-backend **162 / 0**、e2e-provider **15 / 0**、e2e-frontend **7**、**e2e-multi-instance 21 / 0**（新）；压测 [deployment/load_test.md](deployment/load_test.md)。planner=fake-e2e。HITL ④ 五项投产参数见报告末 | 2026-09-13 |
| 8 | 用户确认 | TODO | — | — | — |

## 契约变更
- NONE。核对：`run-summary.currentUi` 形状不变（来源从编排器缓存改为 Run 字段）；`sse-events` 不增 `RunFailureCode`（断连用 `INTERNAL_ERROR`）；`tool-search` 六字段不动（`sideEffect` 走 `ToolMetaRegistry`）。

## 经验沉淀

| # | 错误 | 沉淀去向 |
|---|---|---|
| 1 | 首版 `RunSnapshot` 原样带了 `Run.message`（用户原话进 Redis） | e2e-multi-instance 第 8 条断言当场红；`agent-safety.md` §3 加「用户原话不进共享存储」 |
| 2 | bash `$REDIS_DB；`（全角分号紧跟）被当成变量名，`set -u` 报 unbound；`check-shell` 未抓到 | 脚本全部改 `${VAR}`。shellcheck 对非 ASCII 相邻字符不报——**已知盲区**，写进 `e2e-multi-instance.sh` 头注释 |
| 3 | 压测报告首版有 1022s 的假"最大延迟"（本机临时端口耗尽，服务端毫无痕迹） | 量具加客户端硬超时 + `wall` 实测耗时 + `client_timeout` 单列；报告里写明失真源 |
| 4 | `e2e-backend` 与 `deploy-verify` 并行跑在同一 change 目录，共用 `backend.log` 互相截断，15 条假红 | `deploy-verify` skill 加「不要并行」注意事项 |
| 5 | `complete()` 里 `remember → save` 顺序：内存版对象引用掩盖了顺序 bug，只在共享存储下暴露 | 加 `ObservableRunRepository` 钉住"落库那一刻的状态"；`backend-standard.md` §7b 写明「终态先落库再做锦上添花」 |
| 6 | Redis 等待方超时后 future 未 cancel，轮询跑到 key 消失 | `claimOrAwait` 超时分支 `cancel(false)`；`cancelledWaiterStopsPolling` 用 poller 队列长度做证据 |
| 7 | spec 评审自评：readiness 把熔断映射 OUT_OF_SERVICE 会锁死熔断器（M-1）；`inputSchemas` 重取 search 破坏 Run 自包含（M-2） | 两条都是"能工作 ≠ 语义正确"。单测 `openCircuitStaysUpAndOnlyShowsInDetails` 锁住 M-1 |
