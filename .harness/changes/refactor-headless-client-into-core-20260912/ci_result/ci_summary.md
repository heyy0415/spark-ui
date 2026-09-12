# CI 验证 — refactor-headless-client-into-core-20260912

**本仓未配置 GitHub Actions**（用户决策：单人仓库）。阶段 6 记为 **SKIP**，以本地实跑的全量门禁替代。

## 门禁：`pnpm -C .harness run ci`

退出码 **0**（九步全过，逐项见同批次的 `feat-provider-http-transport-20260912/ci_result/ci_summary.md`——两个 change 在同一分支上，门禁是同一次运行）。

## 本 change 专属的验收

| 项 | 结果 |
|---|---|
| 前端单测总数 ≥ 迁移前（100） | **106 passed**（+6 为新增 `runStore` 测试） |
| 69 个迁移用例逐文件对齐 | ✓ `runView` 精确 24 → 24（评审 S-2 要求的数字断言） |
| `@tanstack` 残留 | 无命中（排除 node_modules / lockfile） |
| `apps/chat/src/entities` / `shared/api` | 已删除 |
| git rename 识别 | **11 个文件**（纯搬迁的机械证据） |
| `@spark-ui/core` dist | 248 KB，三入口 |

## 三入口产物依赖闭包（本 change 的核心性质）

逐入口跟着相对 import 走完整个 chunk 图，用 `es-module-lexer` 解析（非正则——rollup 会把只为副作用保留的外部依赖编译成无 `from` 的裸导入）：

| 入口 | 外部依赖 |
|---|---|
| `./client` | **zod** 一个 |
| `./react` | react, zod（**无 antd**） |
| `.` | antd, antd-mobile, react, zod |

zod 正确外部化（未打进包，否则宿主与本包 schema 实例不一致）；`client` 与 `react` 共享同一 chunk，代码只有一份。

该性质已加进 `verify-pack` 的 (h) 检查，双向自证（两种 import 形态注入均变红）。

## 回归

`e2e-backend` **161** / `e2e-frontend` **7** / `deploy-verify` **12** —— 数字与本 change 前完全一致，证明纯重构行为零变化。
