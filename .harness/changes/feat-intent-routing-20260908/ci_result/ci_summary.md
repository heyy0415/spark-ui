# CI Summary — feat-intent-routing-20260908（阶段 6）

日期：2026-09-08 · 命令：`rm -rf fronted/*/dist && pnpm -C .harness run ci`（本地执行，仓库无远端 CI）· 提交：`34927be`

| 步骤 | 退出码 | 摘要 |
|---|---|---|
| check-contracts | 0 | 契约零变化 |
| check-module-deps | 0 | 新规则：三模块禁 import 对方 `application`（负例已证会红） |
| fronted ci | 0 | verify-pack 14 ✓，dist 39 KB（基线 40 KB，-2.5%） |
| backed `mvnw verify` | 0 | app.jar 36,052,588 B（上一 change 36,026,813 B，+0.07%） |
| **合计** | **0** | `ci: all steps passed (exit 0)` |

## 前端 bundle
chat `index-*.js` 251,990 B（上一 change 252,164 B，-0.07%）；其余 antd chunk 不变。前端本 change 只做内部去重 / 冻结，无功能改动。

## 补充验收（阶段 4 已记录）
- e2e-backend 规则模式 62 / 62；LIVE 模式 62 / 62（`source=model` 1）
- e2e-frontend 21 / 21
- doctor 0 errors（含新增 L1 一致性检查）
