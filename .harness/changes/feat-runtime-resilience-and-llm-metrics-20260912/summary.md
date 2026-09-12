# Change Summary: feat-runtime-resilience-and-llm-metrics-20260912

| 字段 | 值 |
|---|---|
| Change ID | feat-runtime-resilience-and-llm-metrics-20260912 |
| 类型 | feat |
| 状态 | PUSHED |
| 负责人 | Platform Owner Agent |
| 涉及端 | spark-rooter（spi / runtime / gateway / web-mvc / starter）+ harness 文档；契约零改动 |
| 起止时间 | 2026-09-12 ~ 2026-09-12 |
| Spec | [request_analysis/spec.md](request_analysis/spec.md) |
| Tasks | [request_analysis/tasks.md](request_analysis/tasks.md) |

## 阶段进度表

| # | 阶段 | 状态 | 评审轮次 | 产出 | 时间 |
|---|---|---|---|---|---|
| 1 | 需求分析 | DONE | — | spec.md v2, tasks.md v2（含 JDK 反射实测验证 ScheduledThreadPoolExecutor 队列不可设界） | 2026-09-12 |
| 2 | 需求评审 | DONE | 1/3 | spec_review_v1.md（**APPROVED**，0 MUST FIX / 3 SHOULD，全部已落实进 v2） | 2026-09-12 |
| 3 | 编码实现 | DONE | — | coding_report_v1.md（T01–T07 完成） | 2026-09-12 |
| 4 | 编码评审 | DONE | 1/2 | code_review_v1.md（**APPROVED**，0 MUST FIX / 2 SHOULD，**两条均已在本 change 内落实**） | 2026-09-12 |
| 5 | 代码推送 | DONE | — | 本地 commit（push 待用户执行） | 2026-09-12 |
| 6 | CI 验证 | DONE | — | `pnpm -C .harness run ci` 9 步退出 0（阶段 6 的门禁定义，GitHub Actions 已按用户决定移除） | 2026-09-12 |
| 7 | 部署验证 | DONE | — | e2e-backend 161 passed / e2e-frontend 7 passed / deploy-verify 12 passed，**三套零回归** | 2026-09-12 |
| 8 | 用户确认 | DONE | — | 用户「确认交付」。遗留：`spark.llm.*` 指标本轮从未用真实模型验证（假规划器不经 `LlmPlanner`），需配 `SPARK_LLM_*` 真 key 才能取证 | 2026-09-13 |

## 契约变更
- NONE（§2.2 论证了为何不新增 `RunFailureCode` 枚举值：前端对该场景的处理与 `INTERNAL_ERROR` 无差异，用 `withUserText` 区分文案即可）

## 经验沉淀
- （留空，每发现一个 Agent 错误后**先**在此追加一行，再决定是否升级到 Skill / Rule）

## 关键成果

三个「能跑通 → 敢承接流量」的缺口，每个都有可量化的实测证据：

| 改造 | 改造前 | 改造后（实测） |
|---|---|---|
| 线程池无界（第 6 项） | 过载时任务静默排队，SSE 挂到 90s 超时 | 并发 6 请求 → 4 个**立刻**收到「当前请求较多，请稍后重试」 |
| LLM 无熔断（第 7 项） | 网关挂掉时每请求各等 3 分钟，线程池占满 | 阈值 2 后 `outcome=circuit_open durationMs=0`——从等 3 秒变 0 秒 |
| 零 metrics（第 8' 项） | 不知道一次对话花多少 token / 时间 | `LLM_METRICS` 一行一次调用，含 outcome / 耗时 / token |

配套：`read-timeout` 从写死 3 分钟改为可配（默认 90s，与 `sse-timeout` 对齐）；三处线程池符合公司 Java 规范「禁止 Executors 工厂、必须有界 + 拒绝策略 + 命名线程」。

## 遗留债务

- **`outcome=planned` 的端到端 LIVE 验证**：需真实模型密钥。已用 `LogLlmMetricsSinkTest` 覆盖 `orDash` 的 null / 非 null 两分支，但「真实 token 数字经 `recordUsage` 写入日志」这条完整链路未实测。
- **`toolExecutor` 拒绝的用户文案有偏差**：显示「工具调用失败」而实际原因是系统过载。行为正确（失败而非挂死，已实测），文案含「请稍后重试」。若将来 Gateway 要区分过载再改。
- **熔断状态是进程内的**：多副本各自独立熔断，需第 9 项 Redis（已明确不做）。单副本完全有效。

## 经验沉淀

- **评审阶段的推理要在编码阶段变成实证**。评审 S-2 推断「熔断器看到的一次失败 = 3 次真实网关请求（Spring AI 内部重试）」并据此把阈值从 5 降到 2；编码期 `LLM call failed attempt` 计数实测为 **6 次 = 2 × 3**，把推理钉成了事实。若当初按 5，熔断前会有 15 次真实请求。**教训**：spec 里基于"框架内部行为"的推断，编码时要找一个可观测量去确认。
- **不要把测试方法的问题当成代码 bug**。并发发 3 个请求时第 3 个显示 `transport_error` 而非 `circuit_open`，我起初怀疑熔断有 bug。查毫秒级时间线后确认：该请求在熔断打开前 1.8 秒就已通过 `shouldSkip()`，**在途请求不被中途取消**是正常语义。改用严格串行后得到干净结果。如果当时去"修"这个现象（比如加取消在途请求的逻辑），反而引入真问题。
- **`DOMAIN_WORDS` 红线抓到我一次**：`toolQueue` 的 javadoc 里写了「退款链是 3 步」举例。**领域举例最容易从注释渗进内核**——写内核注释时想举例说明，顺手就用了示例领域的词。红线在这里起了实际作用，不是形式主义。
- **`durationMs=0` 比「日志里有 circuit open」更有说服力**。验收标准应尽量选能量化价值的观测量，而不只是「功能存在」的证据。
