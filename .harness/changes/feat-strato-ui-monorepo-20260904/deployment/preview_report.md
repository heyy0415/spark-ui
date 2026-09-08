# Preview Report — feat-strato-ui-monorepo-20260904（阶段 7）

日期：2026-09-08 · 命令：`pnpm -C .harness run deploy-verify` · 结果：**12 passed / 0 failed**
环境：本机，无 LLM 环境变量（规则规划器）；后端 `backed/app/target/app.jar`；前端 `apps/chat` `vite preview` :4173（吃 `@strato-ui/core` dist）代理到 :8080。
`deployment/` 由 `scripts/lib/change-dir` 定位到本 change，全部产物由该脚本一次性生成并冻结。

## 后端
| 项 | 结果 |
|---|---|
| `GET /actuator/health` | `{"status":"UP"}` |
| 启动自检 | 4/4 OK |
| 用户原文出现在日志 | 0 次 |

## 前端预览（生产构建）
| 页面 | HTTP | console.error | 首屏 | 截图 |
|---|---|---|---|---|
| `/?page=order-detail&entityType=order&entityId=10001` | 200 | 0 | `#agent-input` 已渲染（`agent-input=1`） | `preview-chat.png` |
| `/does-not-exist`（NotFound） | 200 | 0 | — | `preview-notfound.png` |
| `/actuator/health`（经预览代理） | 200 | — | — | — |

## 示例 Run（经预览代理，脱敏）
- 发起：`run.started → tool.selected → tool.started → tool.completed ×2 → ui.replace → confirmation.required`
- 确认：`… → ui.replace → run.completed`；`GET /agent/runs/{runId}` → `COMPLETED`；`backend.log` 含该 runId。

## 体积
见 `bundle_size.txt`（chat 与 core 两处 dist + app.jar）；与首期对比见 `ci_result/ci_summary.md`，未恶化。

## HITL ④ 部署参数
本 change 不部署、不发包（spec §3 非目标）。`npm publish` 的 registry / token / 流水线与部署环境待用户在后续 change 决定。
