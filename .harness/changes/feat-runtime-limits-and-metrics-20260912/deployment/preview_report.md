# 部署验证 — feat-runtime-limits-and-metrics-20260912

本 change 全是**加约束与埋点**，最大风险是误伤既有链路。故验收核心是「数字与改造前完全一致」。

| 套件 | 本 change 前 | 实测 | 判定 |
|---|---|---|---|
| `e2e-backend` | 161 passed | **161 passed, 0 failed** | 一致 |
| `e2e-provider` | 15 passed | **15 passed, 0 failed** | 一致 |
| `e2e-frontend` | 7 passed | **7 passed** | 一致 |
| `deploy-verify` | 12 passed | **12 passed, 0 failed** | 一致 |
| 前端单测 | 106 | **107** | +1（新增契约断言；原定「前端零改动」因 `error-response` 加枚举值而不成立） |

## 门禁项

| 项 | 结果 |
|---|---|
| 后端 `/actuator/health` | `{"status":"UP"}` |
| 启动自检 | 9 项全 OK |
| SSE 事件序列 | 完整走到 `run.completed` |
| 预览页 `console.error` | **0** |
| bundle 未恶化 | 前端仅改一个 enum + 一条测试，`verify-pack` 通过 |

## 三项自保阈值未误伤既有链路

| 阈值 | 既有链路的实际值 | 是否触发 |
|---|---|---|
| 计划步数上限 6 | 实测 1 / 2 / 3 步 | **未触发**（有专门的"误伤哨兵"用例：上限调到 ≤ 3 则红） |
| 单会话并发 4 | 既有链路单会话并发 1 | **未触发**（backend.log 零 `session busy`） |
| 审计失败兜底 | 默认 `LogAuditSink` 不抛 | 行为不变 |

## HITL 确认点 ④：部署参数

本 change **不涉及生产部署**，交付物是代码。投产时需人工确认：

| 项 | 说明 |
|---|---|
| `spark.gateway.max-concurrent-per-session` | 默认 4，与 `toolQueue=64` 挂钩（需 16 个并发会话才占满池）。**改 toolQueue 时同步复核** |
| 监控栈选型 | 要 `/actuator/prometheus` 需自行加 `micrometer-registry-prometheus`。本项目只出 Micrometer 指标，不替宿主选栈 |
| 指标基数 | 已硬编码低基数标签。宿主若自定义 `ToolMetricsSink` / `RunMetricsSink`，**须自行保证不把 ID 作标签** |
| `AuditSink` 实现 | javadoc 已写明「不应抛异常」与合规取舍（审计丢失可据 ERROR 日志补账，重复扣款不可逆）。换写库/Kafka 实现时须自己兜住异常 |

三项均在 README「可观测性」「已知限制」与 `backend-standard.md` 有对应条目。
