# 部署验证 — refactor-headless-client-into-core-20260912

纯重构，**验收即"数字与改造前完全一致"**。

| 套件 | 本 change 前 | 实测 | 判定 |
|---|---|---|---|
| `deploy-verify.sh` | 12 passed | **12 passed, 0 failed** | 一致 |
| `e2e-backend.sh` | 161 passed | **161 passed, 0 failed** | 一致 |
| `e2e-frontend.sh` | 7 passed | **7 passed** | 一致 |
| 前端单测 | 100 | **106** | +6（新增 `runStore` 测试；69 个迁移用例逐文件对齐） |

## 门禁项

| 项 | 结果 |
|---|---|
| 后端 `/actuator/health` | `{"status":"UP"}` |
| 启动自检 | 9 项全 OK |
| 预览页 `console.error` | **0** |
| 聊天页渲染 `#agent-input` | ✓ |
| 一条 Run 走通至 `run.completed` | ✓ |
| bundle 未恶化 | `@spark-ui/core` dist 248 KB，`verify-pack` 断言 ≤ baseline × 1.1 |

## bundle baseline 的说明

`verify-pack.baseline.json` 47 KB → 80 KB。这是**代码位置变更而非体积增长**：同一批代码从 `apps/chat`（不计入包体积）移入 `packages/core`（计入），chat 侧相应减少。已在 baseline 文件内注明理由。

## HITL 确认点 ④：部署参数

**不涉及**。本 change 只改前端代码组织，无新增配置项、无部署参数变更。
