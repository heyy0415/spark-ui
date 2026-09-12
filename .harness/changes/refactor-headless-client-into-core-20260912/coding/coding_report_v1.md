# 编码报告 v1 — refactor-headless-client-into-core-20260912

## 1. 结论

8 个 task 全部完成，验收全绿。这是一次**纯重构**：把原先散落在 `apps/chat` 的运行时客户端（契约投影、HTTP、SSE、事件归约、状态容器、React 绑定）下沉到 `@spark-ui/core`，拆出三个入口，`apps/chat` 退化为纯消费者。

机械证据：`git status` 把 11 个文件识别为 **rename**（`RM`），34 文件改动共 **364 插入 / 368 删除**（净 −4 行）——代码是搬过去的，不是重写的。

## 2. 最终结构

```
packages/core/src/
├── index.ts        入口 '.'        渲染层（react + antd / antd-mobile）
├── client/         入口 './client'  headless：仅 zod，零框架
│   ├── contracts.ts   intent-request / action-request / sse-events / run-summary / error-response 的 Zod 投影
│   ├── http.ts        request() + HttpError
│   ├── sse.ts         consumeSse() + parseFrame()
│   ├── api.ts         getRun / buildIntentRequest / buildActionRequest / AGENT_RUNS_PATH / actionPath / Transport
│   ├── runView.ts     reduceEvent 等 10 种事件归约
│   └── runStore.ts    createRunStore（新增，替代 TanStack Query）
└── react/          入口 './react'   useSparkRun（client + useSyncExternalStore）
```

依赖方向单向：`./react → ./client`、`. → schema/registry`。

`apps/chat/src` 剩 23 个文件：路由、页面、一个 `AgentChatPanel`、env / lib / ui 少量本地件。`entities/` 与 `shared/api/` 整层删除。

## 3. 逐 task 结果

| task | 结果 | 要点 |
|---|---|---|
| T01 契约投影下沉 | DONE | `types.ts` → `client/contracts.ts`；8 用例同迁 |
| T02 HTTP / SSE 下沉 | DONE | `baseUrl` 由「可选 + 内部读 env」改为**类型上必填**（含同源 `''`）——headless 层不读 `import.meta.env`，回落策略归宿主 |
| T03 runView 下沉 | DONE | 24 用例原样同迁，一个没少（评审 S-2 的数字断言） |
| T04 状态容器 + React 绑定 | DONE | 新增 `createRunStore`（25 行，零依赖）+ `useSparkRun`；移除 `@tanstack/react-query` |
| T05 包配置与守护 | DONE | vite `lib.entry` 改对象形式三入口；`exports` / `publishConfig` 四项对齐；`check-deps.mjs` 加 headless 边界检查 |
| T06 chat 改为消费者 | DONE | 删 6 源 + 6 测试文件与 3 个空目录；删失效别名 `@entities` |
| T07 规则与文档同步 | DONE | contracts.md / coding-standard.md / project-structure.md / architecture.md / core README |
| T08 全链路回归 | DONE | 见 §4 |

## 4. 验收实测

| 门禁 | 目标 | 实测 |
|---|---|---|
| `pnpm -C spark-ui run ci` | 0 | **0** |
| `pnpm -C .harness run doctor` | 0 | **0 errors, 0 warnings** |
| `pnpm -C .harness run ci`（仓库根全量，含后端 Maven） | 0 | **0** |
| `pnpm -C spark-ui run test` | ≥ 100（迁移前） | **106 passed / 10 文件** |
| `e2e-frontend.sh` | 7 passed | **7 passed** |
| `e2e-backend.sh` | 161 passed, 0 failed | **161 passed, 0 failed** |
| `deploy-verify.sh` | 12 passed, 0 failed | **12 passed, 0 failed** |
| `grep -rn '@tanstack'`（排除 node_modules / lockfile） | 无命中 | **无命中** |

### 单测逐文件对齐（证明 69 个迁移用例零丢失）

| 迁移前 | 迁移后 | 用例 |
|---|---|---|
| `entities/agent-run/api/agentRunApi.test.ts` | `client/api.test.ts` | 7 → 7 |
| `entities/agent-run/model/types.test.ts` | `client/contracts.test.ts` | 8 → 8 |
| `features/agent-chat/api/useAgentRun.test.ts` | `react/useSparkRun.test.ts` | 7 → 7 |
| `features/agent-chat/model/runView.test.ts` | `client/runView.test.ts` | **24 → 24** |
| `shared/api/httpClient.test.ts` | `client/http.test.ts` | 7 → 7 |
| `shared/api/sseClient.test.ts` | `client/sse.test.ts` | 16 → 16 |
| core 原有 3 文件 | 原地不动 | 31 → 31 |
| — | `client/runStore.test.ts`（新增） | +6 |
| | | **100 → 106** |

## 5. 为什么删掉 TanStack Query

原实现只用到 `setQueryData` / `getQueryData` 两个 API，把它当状态容器用；缓存失效、重试、后台刷新、`staleTime` 一个都没用上。原因是 **SSE 是推送模型**：视图状态完全由事件序列决定，不存在「数据过期需要重新获取」这一语义。

为这点功能让 headless 层背一个 React 专属依赖，会让「非 React 宿主只装 `./client`」这个目标直接落空。替换成 25 行的 `createRunStore`（接口形状对齐 `useSyncExternalStore` 的 `subscribe` / `getSnapshot`，但自身不依赖 React）。

已在 `coding-standard.md` §4 登记该决策与「将来若出现真正的请求-缓存-失效场景，以 change 引入」的复原条件。

## 6. 过程中暴露并修掉的真实缺陷

### 6.1 headless 绑定曾依赖渲染层（本次最有价值的发现）

`useSparkRun.test.ts` 起初报 `SyntaxError: Unexpected token ':'`，来自 antd-mobile。根因不是测试配置，而是 `useSparkRun.ts` 从 `'../index'`（渲染层入口）取契约类型，把整个 antd-mobile 拉进了模块图。

这不是测试问题，是**设计问题**：headless 绑定不该依赖渲染层。修法是直指具体模块（`'../schema/uiSchema'`、`'../registry/types'`）。

按 Hashimoto 法则编码为门禁（`check-deps.mjs` 第 3 组检查）+ 红线 12。两点特意与既有检查不同：

1. **含测试文件一起扫**——坑就出在测试文件上，而原检查跳过 `*.test.ts`；
2. **`import type` 也算违规**——vite / vitest 按模块图解析，type-only 同样会加载该入口。原 `runtimeImports()` 跳过 type-only（对「运行时依赖」语义正确），故这条检查用独立正则。

自证三条（插入违规 → 红，恢复 → 绿）：

```
✗ packages/core/src/react/useSparkRun.ts: must not import the render-layer entry "../index" …   ← import type 也被拦住
✗ packages/core/src/client/runStore.test.ts: must not import the render-layer entry "../index" … ← 测试文件也被扫
✗ packages/core/src/client/runView.ts: headless client must not import "react" …
```

### 6.2 声明了入口但产物里没有

`publishConfig` 加了 `./client` / `./react`，但 vite `lib.entry` 还是单入口字符串，`pnpm pack` 出来的包里这两个入口是死链。改为对象形式三入口。已把「四项必须在 `exports` / `publishConfig` / `lib.entry` 三处同时存在」写进 `project-structure.md`。

### 6.3 oxlint 规则过时地禁掉了合法入口

`{"group": ["@spark-ui/core/*"]}` 是 core 只有单入口时写的，会连 `./client` / `./react` 一起禁。精确化为只禁 `src` / `src/*` / `dist` / `dist/*`（真正该禁的是绕过公共 API），4 处 override 同步。

### 6.4 两个门禁脚本引用了已删除的模块

`verify-examples.ts`（`@entities/agent-run`）与 `verify-transport.ts`（`@shared/api`）在 T06 删目录后才暴露。

`verify-transport.ts` 不只是改 import：它原有一条断言「缺省 `baseUrl` 时回落 `env.VITE_API_BASE_URL` 而不是 `''`」（评审 M-1），而**该不变量的落点已经换层**——core 侧 `baseUrl` 必填、不读 env，回落搬到了 `AgentChatPanel`。故拆成两层验：core 侧验前缀拼接与 `''` 同源语义，宿主侧验 `baseUrl ?? env.VITE_API_BASE_URL` 这行还在。5 项全绿。

### 6.5 文档里我自己写错的两处（已修正）

- 写「前端 9 个投影全在 core」→ 实际只有 **6 个**（ui-schema + 5 个）。`tool-manifest` / `tool-search` / `tool-invoke` 是 Runtime ↔ Registry ↔ Gateway 的后端内部契约，前端拿不到也不该拿到。已修正 `contracts.md` §1、`coding-standard.md` §2、core README 三处。
- core README 曾列出 `ToolManifestSchema` 等三个**不存在的导出**。已按真实导出逐一核对改写。

另：`project-structure.md` 原写「index.ts 唯一公共入口（17 运行时 + 19 类型）」，实测为 19 + 23（与本次改造无关地漂移过）。改为不带数字的表述 + 指向 `verify-pack.mjs` 这个真源，避免同类腐烂再发生。

## 7. 未做的事

- **不发包**（用户决策）。`README.md` 与 `project-structure.md` 都注明「本仓仅提供代码，需要 npm 产物者自取源码构建」。`verify-pack` 仍保留——它验的是「包结构自洽」，对自取源码构建的人同样有意义。
- `apps/chat` 的 UI 代码一行未动（T06 明确要求），仅换 import 来源。
- `entities` 层在 FSD 规则与 `check-deps.mjs` 层级表里**保留位次**（当前为空）。它是改动前就存在的分层约定，不属本次范围；将来 chat 有自己的领域实体时按原方向重建。

## 8. 基线调整说明

`verify-pack.baseline.json`：47 KB → 80 KB。这是**代码位置变更**而非体积增长——同一批代码从 `apps/chat`（不计入包体积）移入 `packages/core`（计入）。chat 侧相应减少。已在 baseline 文件内注明理由。
