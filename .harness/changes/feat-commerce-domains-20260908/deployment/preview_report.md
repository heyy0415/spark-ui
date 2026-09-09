# Deploy Verify Report — feat-commerce-domains-20260908

日期：2026-09-09 · HEAD `dc5aef5` · 命令：`STRATO_PORT=8091 bash .harness/scripts/deploy-verify.sh`（8080 被 IDE 手动实例占用，脚本自起 8091 并经 STRATO_BACKEND 代理，不触碰用户实例）

| 项 | 结果 |
|---|---|
| 后端健康 | `{"status":"UP"}`；启动自检 7/7（contracts 26 examples、gateway idempotency、confirmation coverage 3 tools、inline actions 69、plan 6 messages + 3 条校验器反例、token、refund idempotent） |
| 注册 | `startup registration done: 12 tools from 4 sources` |
| 前端预览 :4173 | 200；代理 `/actuator/health` 200 |
| 端到端一条 Run（经预览代理） | 发起序列 9 帧 → 确认 → `run.completed`；run-summary COMPLETED；日志无用户原文 |
| 预览页 console.error | chat / notfound 均 0；`#agent-input` 渲染 |
| 体积报告 | `bundle_size.txt` 已写 |
| **deploy-verify** | **12 passed / 0 failed** |

同批验收：
- e2e-backend 规则模式 109/109（`e2e-backend-rule.out`）；LIVE 模式 114/114（`e2e-backend-live.out`；planner=spring-ai 12 次；日志 / change 目录 0 处网关地址 / 密钥 / 模型名）
- e2e-frontend 35/35（`STRATO_FRONT_BASE=http://localhost:5199`；8 契约示例 × 1280 / 375 组件数逐一匹配；自然语言驱动链路截图 `ui-order-table.png` / `ui-logistics.png` / `ui-product-table.png`）

不部署、不发包、无远端。
