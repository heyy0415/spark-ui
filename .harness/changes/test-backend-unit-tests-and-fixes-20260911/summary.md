# Change Summary: test-backend-unit-tests-and-fixes-20260911

| 字段 | 值 |
|---|---|
| Change ID | test-backend-unit-tests-and-fixes-20260911 |
| 类型 | test |
| 状态 | PUSHED |
| 负责人 | Platform Owner Agent |
| 涉及端 | spark-rooter / spark-ui / harness（契约 Schema 无变更） |
| 起止时间 | 2026-09-11 ~ 2026-09-11 |
| Spec | [request_analysis/spec.md](request_analysis/spec.md) |
| Tasks | [request_analysis/tasks.md](request_analysis/tasks.md) |

## 阶段进度表

| # | 阶段 | 状态 | 评审轮次 | 产出 | 时间 |
|---|---|---|---|---|---|
| 1 | 需求分析 | DONE | — | spec.md, tasks.md | 2026-09-11 |
| 2 | 需求评审 | DONE | 2/3 | spec_review_v1.md（REVISION REQUIRED）→ v2（APPROVED）；HITL ② 按用户「开始，全部完成」授权通过 | 2026-09-11 |
| 3 | 编码实现 | DONE | — | coding_report_v1.md（19 测试类 / 131 用例，verify 0） | 2026-09-11 |
| 4 | 编码评审 | DONE | 1/2 | code_review_v1.md（APPROVED，0 MUST FIX；2 SHOULD 已吸收）；ci_result/ci_and_e2e_stage4.txt | 2026-09-11 |
| 5 | 代码推送 | DONE | — | 2a36634 | 2026-09-11 |
| 6 | CI 验证 | SKIP | — | 未配置 GitHub Actions（改造第 4 项）；本地 `pnpm -C .harness run ci` 退出 0 见 ci_result/ci_and_e2e_stage4.txt | 2026-09-11 |
| 7 | 部署验证 | PARTIAL | — | deployment/preview_report.md：8/12 通过；4 项失败全因本机无模型（UnavailablePlanner），与改动无关 | 2026-09-11 |
| 8 | 用户确认 | PENDING | — | 待用户书面「确认交付」 | — |

## 契约变更
- NONE（新增契约副本目录 `spark-rooter-contracts/src/main/resources/contracts/`，由 sync-contracts 从真源同步）

## 经验沉淀
- 阶段 1 spec 把「e2e 日志含 demo WARN」写成验收，但 e2e profile 下宿主 `DemoSessionIdResolver` 顶替默认 Bean，WARN 根本不会出现 → 评审 v1 M-1。教训：涉及 profile 的验收先查 `@Profile` 与 yml 文档结构，再写断言。
- `-q` 模式下 surefire 不打印 `Tests run:`，以 `target/surefire-reports/TEST-*.xml` 为证；验收措辞已改。
- `Jdk8Module` 只在 starter 类路径，runtime 测试夹具不能注册它；夹具 ObjectMapper 与 `platformMapper` 口径差异要在注释写明。
- 测试里的内存记忆 TTL 用例：新写入的 `at` 必须取当前时钟，否则自己先过期（`memoryPutSweepsExpiredEntries` 首次失败原因）。
- SelfCheck 与单测并存：SelfCheck 是 e2e / deploy-verify 的日志断言依赖，删除属行为变更，另开 change 决定去留。
