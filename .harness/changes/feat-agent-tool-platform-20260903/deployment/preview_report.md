# Preview Report — feat-agent-tool-platform-20260903（阶段 7）

日期：2026-09-04 · 命令：`pnpm -C .harness run deploy-verify` · 结果：**12 passed / 0 failed**
环境：本机（macOS），无 LLM 环境变量 → 规则规划器；后端 `backed/app/target/app.jar`（JDK 21），前端 `vite preview` :4173 代理到 :8080。
本目录全部产物由该脚本**一次性生成并冻结**（backend.log、run_events.log、confirm_events.log、run_summary_done.json、preview-*.png、bundle_size.txt 同一次运行）。

## 后端
| 项 | 结果 |
|---|---|
| `GET /actuator/health` | `{"status":"UP"}` |
| 启动自检 | 4/4 OK（contracts / plan / confirmation token / refund.create idempotent） |
| 用户原文出现在日志 | 0 次 |
| 审计行 | 4（recheck + refund.create 各经 Gateway；`refund.eligibility.check`、`refund.preview` 首轮） |

## 前端预览
| 页面 | HTTP | console.error | 截图 |
|---|---|---|---|
| `/` | 200 | 0 | `preview-home.png` |
| `/agent?page=order-detail&entityType=order&entityId=10001` | 200 | 0 | `preview-agent.png` |
| `/actuator/health`（经预览代理） | 200 | — | — |

## 示例 Run（经预览代理，脱敏：不含 token 与用户原文）
- 发起：`run.started → tool.selected → tool.started → tool.completed → tool.selected → tool.started → tool.completed → ui.replace → confirmation.required`
- 确认：`tool.selected → tool.started → tool.completed → tool.selected → tool.started → tool.completed → ui.replace → run.completed`
- `GET /agent/runs/{runId}` → `COMPLETED`；`backend.log` 含该 runId 的 `plan attached` 行。

## 体积（`bundle_size.txt`）
| 产物 | 大小 |
|---|---|
| 前端最大 chunk `index-*.js` | 254,794 B（gzip 79.13 kB，见 ci_summary） |
| 前端次大 `useSize-*.js` / `Table-*.js` | 252,069 B / 226,258 B（均 lazy） |
| `app.jar` | 36,026,316 B |

首个版本，无 baseline；本表即 baseline。单 chunk gzip 均 < 250 kB 门禁。

## 阶段 4 评审遗留（N5）闭环
上两轮评审指出 `deployment/` 里日志与事件不是同一次运行。本次由单一脚本生成，`backend.log has this run` 断言为 1，问题闭环。

## HITL ④ 部署参数
首期仅本地验证，未部署到任何环境；环境 / 域名 / 灰度比例待用户在阶段 8 一并决定。
