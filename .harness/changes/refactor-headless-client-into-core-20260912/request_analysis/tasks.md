# Tasks: refactor-headless-client-into-core-20260912

编码顺序：先搬无依赖的底层（契约投影 → http/sse → api → runView），再造 store 与 hook，最后改 chat 与规则。**纯重构，行为零变化**——每个 task 后跑 typecheck + 单测，全部完成后跑三套端到端。

> **阶段 1 已核实**：`@tanstack/react-query` 仅服务 `useAgentRun` 一处（另三处是 `AppProviders.tsx` / `queryClient.ts` 的脚手架），可整体移除。**6 个测试文件共 69 个用例**需随代码迁移：`runView` 24、`sse` 16、`types` 8、`agentRunApi` 7、`httpClient` 7、`useAgentRun` 7。
>
> **v2 修订**（依据 `review/spec_review_v1.md`）：新增 **T00**（先建子入口）——原 T01 的「临时 re-export」会让 `apps/chat` 用深路径 import core 的 `src/`，违反 `project-structure.md` §1 红线 2（chat 只能用包入口）。子入口前置后，每个 task 结束时 chat 都能用正式路径，无需任何过渡措施（评审 S-3）。S-1 核实 oxlint 已覆盖禁 antd，`check-deps` 只需补禁 react 与禁 import.meta.env。S-2 的验收改为 69 个用例的数字断言。

## T00 先建两个子入口（空壳）

- **目标**：让后续每个 task 都能用正式的 `@spark-ui/core/client` 路径，消除「临时 re-export」这类过渡态。
- **所属端**：spark-ui（packages/core）
- **输入**：`packages/core/package.json` 现有 `exports`（只有 `.` 与 `./style.css`）
- **输出**：
  - `packages/core/src/client/index.ts`、`src/react/index.ts` —— 先各放一行 `export {};`（空模块，合法且可被 import）
  - `package.json` 的 `exports` 加 `./client`、`./react`（指向 `src/`，与现有 `.` 的开发期策略一致）；`publishConfig` 同步指向 `dist/`
- **验收**：`pnpm -C spark-ui run typecheck` 0；`node -e "require.resolve"` 不适用（ESM），改为在 chat 里临时写一行 `import '@spark-ui/core/client'` 后 typecheck 通过，然后删掉该行
- **依赖**：无

## T01 契约投影迁入 core

- **目标**：8 个契约的 Zod 投影从 chat 迁到 core，与已在 core 的 ui-schema 汇合。
- **所属端**：spark-ui（packages/core + apps/chat）
- **输入**：`apps/chat/src/entities/agent-run/model/types.ts`（206 行）及其单测
- **输出**：
  - 新增 `packages/core/src/client/contracts.ts`（内容搬迁；原文件对 `@spark-ui/core` 的 import 改为相对路径 `../schema`）
  - 单测迁到 `packages/core/src/client/contracts.test.ts`
  - `client/index.ts` 导出契约投影；`apps/chat` 改为从 `@spark-ui/core/client` 引用（T00 已建好入口，**无需临时措施**）
- **验收**：`pnpm -C spark-ui run typecheck` 0；`pnpm -C spark-ui run test` 0
- **依赖**：T00

## T02 http / sse 迁入 core 并去掉 env 依赖

- **目标**：两个传输层文件迁入 core，`baseUrl` 改为必须由调用方传入。
- **所属端**：spark-ui
- **输入**：`shared/api/httpClient.ts`（68 行，`:47` 读 env）、`shared/api/sseClient.ts`（116 行，`:39` 读 env）及两个单测
- **输出**：
  - `packages/core/src/client/http.ts`、`client/sse.ts`
  - **`baseUrl` 从可选改为必填**（去掉 `?? env.VITE_API_BASE_URL` 兜底）——core 不得读 `import.meta.env`
  - 两个单测迁到 `client/http.test.ts`、`client/sse.test.ts`
- **验收**：typecheck 0；test 0；`grep -rn 'import.meta.env' packages/core/src` 无命中
- **依赖**：T00（可与 T01 并行）

## T03 api 与 runView 迁入 core

- **目标**：三个端点与事件归约状态机迁入 core。
- **所属端**：spark-ui
- **输入**：`entities/agent-run/api/agentRunApi.ts`（36 行）、`features/agent-chat/model/runView.ts`（192 行）及两个单测
- **输出**：`client/api.ts`、`client/runView.ts`，单测同迁
- **验收**：typecheck 0；test 0。`runView.test.ts` 的全部用例通过——它守护 10 种 SSE 事件的归约，是行为不变的主要证据
- **依赖**：T01（runView 依赖契约类型）、T02（api 依赖 http）

## T04 runStore + useSparkRun

- **目标**：用零依赖的可订阅 store 替代 react-query 的状态容器角色。
- **所属端**：spark-ui（packages/core）
- **输入**：`useAgentRun.ts` 里 react-query 的 7 处 `setQueryData` / `getQueryData` 用法
- **输出**：
  - `client/runStore.ts`：`createRunStore()` → `{ getSnapshot, subscribe, dispatch }`，约 25 行纯 JS
  - `react/useSparkRun.ts`：`useSyncExternalStore(store.subscribe, store.getSnapshot)` 包装，保留原 `useAgentRun` 的对外签名（`start` / `submitAction` / `view` / 错误状态）
  - `client/runStore.test.ts`（新增）：订阅通知、快照不可变、多订阅者
  - `useAgentRun.test.ts` 迁为 `react/useSparkRun.test.ts`
- **验收**：typecheck 0；test 0（含迁移后的 hook 单测）
- **依赖**：T03

## T05 core 的子入口与机械守护

- **目标**：`@spark-ui/core/client` 与 `/react` 两个子入口可用，并加红线守护。
- **所属端**：spark-ui
- **输入**：`packages/core/package.json` 现有 `exports`；`scripts/check-deps.mjs`；`scripts/verify-pack.mjs`
- **输出**：
  - 两个入口文件的导出补全（T00 建的是空壳）
  - `check-deps.mjs` 新增两条：`packages/core/src/client/**` 不得 import react / react-dom；不得出现 `import.meta.env`（**禁 antd 无需新增**——oxlint 的 `packages/core/src/**` override 已覆盖，阶段 1 核实）
  - `verify-pack.mjs` 加两个子入口的导出名断言
- **验收**：
  - `pnpm -C spark-ui run lint` 0（含 check-deps 新规则）
  - `pnpm -C spark-ui run verify-pack` 0
  - **守护自证**：临时在 `client/sse.ts` 加一行 `import { useState } from 'react'` → `check-deps` 必须报错；再试 `import.meta.env` 同样报错；还原后复绿
- **依赖**：T04

## T06 apps/chat 改为消费者

- **目标**：chat 只保留 UI 与布局，运行时逻辑全部来自 core。
- **所属端**：spark-ui（apps/chat）
- **输入**：`AgentChatPanel.tsx`；`AppProviders.tsx`、`queryClient.ts`；`package.json`
- **输出**：
  - `AgentChatPanel.tsx` 改用 `useSparkRun`，显式传 `baseUrl: env.VITE_API_BASE_URL`；**UI 代码不动**
  - 删除已迁走的 6 个源文件与 6 个测试文件；清空的目录一并删（`entities/agent-run/`、`features/agent-chat/model/`、`shared/api/`）
  - 删 `queryClient.ts`，`AppProviders.tsx` 去掉 `QueryClientProvider`
  - `package.json` 移除 `@tanstack/react-query`
  - `vite.config.ts` 与 `tsconfig.json` 移除失效的路径别名（由 typecheck 兜底判断哪些失效）
- **验收**：
  - `pnpm -C spark-ui run ci` 0
  - `grep -rn '@tanstack' spark-ui --include='*.ts*' --include='*.json' | grep -v node_modules | grep -v pnpm-lock` 无命中
  - `find spark-ui/apps/chat/src/entities spark-ui/apps/chat/src/features/agent-chat/model` 不存在或为空
- **依赖**：T05

## T07 规则与文档同步

- **目标**：契约投影真源与 FSD 描述与代码一致。
- **所属端**：harness + spark-ui
- **输入**：`contracts.md` §1 的投影位置表；`project-structure.md` §1 的 FSD 描述与前端布局树；`packages/core/README.md`
- **输出**：
  - `contracts.md` §1：前端投影改为「全部 9 个在 core」
  - `project-structure.md` §1：core 的目录树加 `client/` 与 `react/`；chat 的 FSD 描述说明 entities 层已空（它只是 core 的消费者）
  - `packages/core/README.md`：说明三个入口的分工（渲染层 / headless / React 绑定）
- **验收**：`pnpm -C .harness run doctor` 0（含文档路径存在性检查）；`pnpm -C .harness run ci` 0
- **依赖**：T06

## T08 全链路回归

- **目标**：证明纯重构行为零变化。
- **所属端**：harness
- **输入**：三套验收脚本
- **输出**：无代码改动
- **验收**：
  - `pnpm -C spark-ui run test` 的总用例数 **≥ 迁移前**（69 个迁移用例全部在列，评审 S-2）
  - `SPARK_PORT=8091 bash .harness/scripts/e2e-frontend.sh` → **7 passed**
  - `SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh` → **161 passed, 0 failed**
  - `SPARK_PORT=8091 bash .harness/scripts/deploy-verify.sh` → **12 passed, 0 failed**
- **依赖**：T07
