# Preview Report — feat-intent-routing-20260908（阶段 7）

日期：2026-09-08 · 命令：`pnpm -C .harness run deploy-verify` · 结果：**12 passed / 0 failed**
环境：本机，无 LLM 环境变量（规则规划器 + noop 分类器）；后端 `app.jar`；前端 `apps/chat` `vite preview` :4173 代理 :8080。`deployment/` 全部产物由脚本一次性冻结；LIVE 模式 e2e 的产物已被随后的规则模式运行覆盖，不含任何端点 / 密钥 / 模型名（全树 grep 0 命中）。

## 后端
| 项 | 结果 |
|---|---|
| `GET /actuator/health` | UP |
| 启动自检 | **5/5**（新增 `gateway idempotency claim`） |
| 用户原文出现在日志 | 0 次 |

## 前端预览（生产构建）
| 页面 | HTTP | console.error | 首屏 |
|---|---|---|---|
| `/?page=order-detail&entityType=order&entityId=10001` | 200 | 0 | `#agent-input` 已渲染 |
| `/does-not-exist` | 200 | 0 | NotFound |

## 示例 Run
发起 → `confirmation.required`；确认 → `run.completed`；summary `COMPLETED`；`backend.log` 含该 runId 且有 `route runId=… source=rule`。

## 本 change 新行为的部署期证据（来自同一次 e2e 冻结的 `backend.log` / `route*_events.log`）
- 无实体「退钱」→ `message.delta("请先在页面上选择一个订单，再发起退款")` + `run.completed`，该 runId 无审计行。
- 「订单」无实体 → `order.list.search` 回退 → `run.completed`。
- 同 key 两次 `refund.create`（订单 10004）→ 审计 `succeeded` 1、`replayed` 1。

## HITL ④
不部署、不发包。生产启用模型分类只需配置 `STRATO_LLM_*` 三个环境变量，无代码改动。
