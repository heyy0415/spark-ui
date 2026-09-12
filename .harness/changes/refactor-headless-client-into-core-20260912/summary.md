# Change Summary: refactor-headless-client-into-core-20260912

| 字段 | 值 |
|---|---|
| Change ID | refactor-headless-client-into-core-20260912 |
| 类型 | refactor |
| 状态 | **DONE**（8 阶段全部完成；提交在分支 `feat/provider-http-transport`，未 push） |
| 负责人 | Platform Owner Agent |
| 涉及端 | spark-ui（packages/core + apps/chat）+ harness 规则；契约文件零改动 |
| 起止时间 | 2026-09-12 ~ 2026-09-12 |
| Spec | [request_analysis/spec.md](request_analysis/spec.md) |
| Tasks | [request_analysis/tasks.md](request_analysis/tasks.md) |

## 阶段进度表

| # | 阶段 | 状态 | 评审轮次 | 产出 | 时间 |
|---|---|---|---|---|---|
| 1 | 需求分析 | DONE | — | spec.md v2, tasks.md v2（阶段 1 核实修正两处设计：Transport 抽象已存在、env 有两层兜底） | 2026-09-12 |
| 2 | 需求评审 | DONE | 1/3 | spec_review_v1.md（**APPROVED**，0 MUST FIX / 3 SHOULD，全部已落实） | 2026-09-12 |
| 3 | 编码实现 | DONE | — | [coding/coding_report_v1.md](coding/coding_report_v1.md)（T01–T08 全完成；spark-ui ci 0、harness ci 0、106 单测、e2e 161/7/12 全绿） | 2026-09-12 |
| 4 | 编码评审 | DONE | 1/2 | [coding/code_review_v1.md](coding/code_review_v1.md)（0 MUST FIX 遗留；自评审，**不满足独立性**，通道受限） | 2026-09-12 |
| 5 | 代码推送 | DONE（本地提交） | — | 提交 `5fe1f87` 于分支 `feat/provider-http-transport`；**未 push**（禁止直接 push 主分支，远端推送待用户在目标分支策略下执行） | 2026-09-12 |
| 6 | CI 验证 | SKIP | — | 未配置 GitHub Actions（用户决策：单人仓库）；本地 `pnpm -C .harness run ci` 退出 **0**（九步全过），见 [ci_result/ci_summary.md](ci_result/ci_summary.md) | 2026-09-13 |
| 7 | 部署验证 | DONE | — | [deployment/preview_report.md](deployment/preview_report.md)：deploy-verify **12 passed**、e2e-backend **161**、e2e-frontend **7**、前端单测 **106**；console.error 0、health UP | 2026-09-13 |
| 8 | 用户确认 | DONE | — | 用户确认交付。提交 `5fe1f87`（未 push） | 2026-09-13 |

## 契约变更
- **NONE**（契约文件零改动，`check-contracts` 0）。但 `contracts.md` §1 的**前端投影位置**变更：ui-schema 在 core / 其余在 chat `entities/` → **全部前端投影都在 core**（ui-schema 在 `src/schema/`，另 5 个在 `src/client/contracts.ts`）。这是规则文档变更，不是契约变更。
- 阶段 4 修正：spec 与本文件原先都写「9 个投影」，**实际只有 6 个**。`tool-manifest` / `tool-search` / `tool-invoke` 是后端内部契约，前端无投影。

## 迁移基线（阶段 3 开始前实测）

| 位置 | 用例数 |
|---|---|
| `packages/core` | 31 |
| `apps/chat` | **69**（6 个待迁文件：runView 24 / sse 16 / types 8 / agentRunApi 7 / httpClient 7 / useAgentRun 7） |
| 合计 | **100** |

迁移后 chat 的 69 个应全部落到 core，**总数仍须是 100**——这是「纯重构、行为零变化」的数字判据（评审 S-2）。

**实测结果**：69 个逐文件对齐，`runView` 精确 24 → 24。总数 100 → **106**（+6 为新增 `client/runStore.test.ts`，替代 TanStack Query 的状态容器）。

| 迁移前 | 迁移后 | 用例 |
|---|---|---|
| `entities/agent-run/api/agentRunApi.test.ts` | `client/api.test.ts` | 7 → 7 |
| `entities/agent-run/model/types.test.ts` | `client/contracts.test.ts` | 8 → 8 |
| `features/agent-chat/api/useAgentRun.test.ts` | `react/useSparkRun.test.ts` | 7 → 7 |
| `features/agent-chat/model/runView.test.ts` | `client/runView.test.ts` | **24 → 24** |
| `shared/api/httpClient.test.ts` | `client/http.test.ts` | 7 → 7 |
| `shared/api/sseClient.test.ts` | `client/sse.test.ts` | 16 → 16 |

## 经验沉淀

按 Hashimoto 法则，每条都已升级为门禁 / 红线，不只是记录。

1. **headless 绑定曾 import 渲染层入口**。`useSparkRun.test.ts` 报 antd-mobile 的 `SyntaxError`，根因不是测试配置而是设计问题：`useSparkRun.ts` 从 `'../index'` 取类型，把 antd-mobile 拉进模块图。→ `check-deps.mjs` 第 3 组检查 + 红线 12。两点特意区别于既有检查：**含测试文件**（坑就出在测试文件）、**`import type` 也算违规**（vite / vitest 按模块图解析，type-only 同样加载入口）。
2. **自证全绿 ≠ 门禁有效**。新增 (h) 依赖闭包检查后自证不变红——rollup 把只为副作用保留的外部依赖编译成**无 `from` 的裸导入** `import "react";`，手写正则漏掉。→ 改用 `es-module-lexer`，重新自证两种形态均变红。**教训：自证必须"看到红"才算通过；全绿有两种可能，不能默认是"代码没问题"那种。**
3. **文档里的数字和导出名必须当场 grep**。我写「9 个投影」（实际 6 个）、编造了 3 个不存在的导出名、承诺了 peer optional 但配置里没有。这类错误不会让任何门禁变红，只会误导后来人。→ 涉及具体数字 / 导出名 / 行为承诺时一律核对代码；能指向真源的就别抄数字（如 `verify-pack.mjs` 之于导出清单）。
4. **门禁脚本引用被删模块，要区分"改 import"和"换层重写"**。`verify-transport.ts` 有条断言的**落点已换层**（env 回落从 core 搬到宿主），只改 import 会让它在错误的层验一个不再成立的命题——看起来还在守，实际守空了。→ 拆成 core 侧与宿主侧两层验。
5. **规则会因架构演进而过时地禁掉合法用法**。oxlint `@spark-ui/core/*` 是单入口时代写的，会连新的 `./client` / `./react` 一起禁。→ 精确化为只禁 `src` / `dist` 内部路径；**该禁的是"绕过公共 API"这个行为，不是所有子路径**。
6. **声明入口 ≠ 产出入口**。`publishConfig` 加了两个入口但 vite `lib.entry` 仍是单入口字符串，产物里是死链。→ 「四项必须在 `exports` / `publishConfig` / `lib.entry` 三处同时存在」写进 `project-structure.md`，`verify-pack` (h) 逐入口验产物。
