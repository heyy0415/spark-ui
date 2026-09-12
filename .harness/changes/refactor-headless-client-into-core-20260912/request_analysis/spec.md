# Spec: refactor-headless-client-into-core-20260912

> 改造清单第 15 项：把聊天的「运行时逻辑」从 `apps/chat` 下沉到 `@spark-ui/core`，并出一个零框架依赖的 `@spark-ui/core/client` 入口。
>
> 用户已明确本仓库**不发包**（「仅提供代码，需要的人自己拿源码去发」）。所以本项的价值不是「让别人 npm 装了能用」，而是：`apps/chat` 不该囤着本该属于渲染引擎的 SSE 解析、事件归约与动作提交逻辑——那是 spark 协议的一部分，不是某个应用的私有实现。

## 1. 背景

### 1.1 当前分工失衡

`@spark-ui/core` 只出渲染层（`SchemaRenderer` / `ActionBar` / `RunStatus` / 五个白名单组件 / 主题）。而「怎么跟后端说话」全在 `apps/chat` 里：

| 文件 | 行数 | 性质 |
|---|---|---|
| `entities/agent-run/model/types.ts` | 206 | **契约投影**（8 个契约的 Zod schema） |
| `features/agent-chat/model/runView.ts` | 192 | **SSE 事件归约状态机**（10 种事件 → 视图） |
| `features/agent-chat/api/useAgentRun.ts` | 175 | React hook：订阅 SSE、提交动作、必填校验 |
| `shared/api/sseClient.ts` | 116 | SSE 分帧（fetch + ReadableStream，原生 EventSource 不支持 POST body） |
| `shared/api/httpClient.ts` | 68 | fetch 封装 + Zod 响应校验 |
| `entities/agent-run/api/agentRunApi.ts` | 36 | 三个端点 |

前两项尤其不该在应用层：契约投影的真源分工已经写在 `contracts.md` §1（ui-schema 在 core，其余 8 个在 chat），而**这个分工本身就是权宜之计**——8 个契约里只有 ui-schema 被 core 用到，其余是「chat 恰好是唯一消费者」才放在那。事件归约同理，它是 sse-events 契约的行为投影。

### 1.2 两个下沉障碍（已核实）

| 障碍 | 现状 | 处理 |
|---|---|---|
| `env`（`import.meta.env`） | **两层兜底**：`AgentChatPanel.tsx:43` 的 `baseUrl ?? env.VITE_API_BASE_URL`（应用层，正确）+ `sseClient.ts:39` / `httpClient.ts:47` 的同款 `??`（传输层） | 去掉**传输层**那两处。它们实际是死代码——`Transport.baseUrl` 已是必填 `string`（`agentRunApi.ts:9`），调用方永远传了值。应用层那处保留 |
| `@tanstack/react-query` | `useAgentRun.ts` 用它当状态容器（7 处 `setQueryData`，1 处 `useQuery` 只为读缓存） | 改 `useSyncExternalStore`，**core 不新增 peer 依赖** |

react-query 在这里没有用到它的真正能力（缓存失效、重试、后台刷新）——SSE 是推送模型，视图状态完全由事件序列决定。用它只是图个「跨组件共享状态」。

### 1.3 阶段 1 的两处核实（影响设计）

1. **`Transport` 抽象已存在**（`agentRunApi.ts:9`：`{ baseUrl: string; fetch: typeof fetch }`），且 `AgentChatPanel:41-46` 已用 `useMemo` 正确构造它。所以「解耦 env」不需要新设计接口，只是**删掉传输层多余的 `??` 兜底**（`Transport.baseUrl` 必填，那两处永不生效）。
2. **6 个测试文件需随代码迁移**：`types.test.ts`、`agentRunApi.test.ts`、`httpClient.test.ts`、`sseClient.test.ts`、`runView.test.ts`、`useAgentRun.test.ts`。它们是「行为零变化」的主要证据，不能丢。

## 2. 范围（In Scope）

### 2.1 core 的新结构

```
packages/core/src/
├── index.ts              # 渲染层入口（现有 17 运行时 + 19 类型导出，不动）
├── client/               # 新增：零框架依赖的 headless 层
│   ├── index.ts          # @spark-ui/core/client 的入口
│   ├── contracts.ts      # 8 个契约的 Zod 投影（从 chat 的 entities 迁入）
│   ├── sse.ts            # SSE 分帧（从 shared/api/sseClient.ts 迁入）
│   ├── http.ts           # fetch + Zod 校验（从 shared/api/httpClient.ts 迁入）
│   ├── api.ts            # 三个端点（从 entities/agent-run/api 迁入）
│   ├── runView.ts         # 事件归约状态机（从 features/agent-chat/model 迁入）
│   └── runStore.ts        # 新增：零依赖的可订阅 store（替代 react-query 的角色）
└── react/                # 新增：薄 React 绑定
    ├── index.ts
    └── useSparkRun.ts    # 用 useSyncExternalStore 包 runStore
```

- **`client/` 禁止 import react 与 antd**，由 `check-deps.mjs` 机械守护（新增规则）。
- `react/useSparkRun.ts` 允许 import react（它就是 React 绑定），但不得 import antd。
- `packages/core/package.json` 的 `exports` 新增 `./client` 与 `./react` 两个子入口；`publishConfig` 同步（虽不发包，但保持 `verify-pack` 的一致性检查有效）。

### 2.2 `runStore`：替代 react-query 的最小实现

```
createRunStore() → {
  getSnapshot(): AgentRunView
  subscribe(listener: () => void): () => void
  dispatch(next: AgentRunView | ((prev) => AgentRunView)): void
}
```

- 纯 JS，约 25 行。`getSnapshot` 返回不可变快照，`dispatch` 后通知订阅者。
- **为什么不继续用 react-query**：现有代码只用了 `setQueryData` / `getQueryData`（状态容器），没用缓存失效、重试、后台刷新中的任何一个。SSE 是推送模型，视图状态完全由事件序列决定，不存在「数据过期需要重新获取」这个概念。留着它意味着 core 要么新增一个 peer 依赖，要么把 hook 留在 chat——两者都不可接受。
- `useSparkRun` 用 `useSyncExternalStore(store.subscribe, store.getSnapshot)`，这是 React 18+ 为外部 store 提供的官方接口，无 tearing 问题。

### 2.3 `apps/chat` 改为消费者

- 删除迁走的六个文件；`entities/agent-run/` 与 `features/agent-chat/model/` 目录随之清空（若只剩 index.ts 则一并删）。
- `AgentChatPanel.tsx` 改为 `import { useSparkRun } from '@spark-ui/core/react'`，其余 UI 代码（输入框、消息列表、滚动、骨架屏）**保持不动**——它们是应用的布局，不是协议的一部分。
- `shared/config/env.ts` 保留（chat 仍需读 `VITE_API_BASE_URL`）。**`AgentChatPanel` 构造 `transport` 的方式不变**——阶段 1 核实发现它已经在 `:41-46` 用 `useMemo` 构造 `{ baseUrl: baseUrl ?? env.VITE_API_BASE_URL, fetch: hostFetch ?? fetch }`，这个分层本来就是对的：env 兜底属于应用层。
- 移除 `@tanstack/react-query` 依赖与 `AppProviders` 里的 `QueryClientProvider`（核实后确认无其他用途再删）。

### 2.4 契约投影真源的迁移

`contracts.md` §1 的表格要改：

| 层 | 迁移前 | 迁移后 |
|---|---|---|
| 前端投影 | ui-schema 在 core；其余 8 个在 `apps/chat/src/entities/*/model/types.ts` | **全部 9 个在 core**（ui-schema 在 `schema/`，其余在 `client/contracts.ts`） |

这是本 change 对规则的实质改动：契约投影不再分散在两处，真源统一。

### 2.5 机械守护

- `check-deps.mjs` 新增：`packages/core/src/client/**` 不得 import `react` / `react-dom`；不得出现 `import.meta.env`。
  - **禁 antd 已有覆盖**（阶段 1 核实）：`.oxlintrc.json` 的 `packages/core/src/**` override 已把 antd / antd-mobile / @ant-design 列入 `no-restricted-imports`，新增的 `client/` 与 `react/` 自动继承。只需补「禁 react」与「禁 import.meta.env」两条。
- `verify-pack.mjs` 的导出清单加 `./client` 与 `./react` 两个入口的断言（各自的运行时导出名）。
- `check-registry.mjs` 不受影响（组件注册表未动）。

## 3. 非目标（Out of Scope）

- **不做 SSE 重连 / 会话恢复**（第 16 项）：本 change 只搬迁与解耦，不改行为。`getRun` 仍是死代码，保留原样。
- **不做 mock 传输**（第 17 项）：`client/` 的 transport 可替换是自然结果，但不在本 change 提供 mock 实现。
- **不做 embed 封装 / Vue 示例**（第 18、19 项）。
- **不改前端 UI**：输入框、消息列表、骨架屏、滚动全部不动。
- **不改后端与契约**：契约文件零改动，只改前端投影的**位置**。
- **不发包**：`exports` 与 `publishConfig` 同步是为了让 `verify-pack` 的一致性检查继续有效，不代表要发布。
- 不改 `SchemaRenderer` 等渲染层导出。

## 4. 核心场景

- `apps/chat` 的行为**完全不变**：7 个 Playwright e2e 用例、161 条后端 e2e、12 条部署验证全部零回归。这是本 change 唯一的正确性判据——纯重构，行为不该有任何变化。
- 结构上：`@spark-ui/core/client` 可被任何 TS 工程 import（不依赖 React）；`@spark-ui/core/react` 给 React 宿主用。

## 5. 契约影响

- **NONE**（契约文件零改动）。但 `contracts.md` §1 的**投影位置表**要改（见 §2.4）——这是规则文档变更，不是契约变更。

## 6. 验收标准

1. `pnpm -C .harness run ci` 退出 0（9 步）；`pnpm -C .harness run doctor` 退出 0。
2. `pnpm -C spark-ui run ci` 退出 0（含 typecheck / 单测 / lint / format / verify-examples / verify-pack）。
3. **行为零回归**（纯重构的核心判据）：
   - `SPARK_PORT=8091 bash .harness/scripts/e2e-frontend.sh` → **7 passed**
   - `SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh` → **161 passed, 0 failed**
   - `SPARK_PORT=8091 bash .harness/scripts/deploy-verify.sh` → **12 passed, 0 failed**
4. `grep -rn 'import.meta.env' spark-ui/packages/core/src` **无命中**。
5. `grep -rnE "from '(react|react-dom|antd|antd-mobile)'" spark-ui/packages/core/src/client` **无命中**。
6. `grep -rn '@tanstack/react-query' spark-ui` 除 lock 文件外**无命中**（依赖已移除）。
7. **迁移后的单测用例数不减**（阶段 1 实测基线，评审 S-2）：6 个待迁测试共 **69 个用例** —— `runView` 24、`sse` 16、`types` 8、`agentRunApi` 7、`httpClient` 7、`useAgentRun` 7。迁移后这 69 个必须全部存在且通过（`pnpm -C spark-ui run test` 的总用例数不得低于迁移前）。写死数字是为了防止迁移时顺手删掉「看起来多余」的边界用例——e2e 只覆盖用户可见路径，`ui.patch` 的 screenId 不匹配、`message.delta` 的边界分支只有单测能守护。
8. `spark-ui/apps/chat/src/entities/agent-run/` 与 `features/agent-chat/model/` 下不再有契约投影或归约逻辑（`find` 确认文件已删）。

## 7. 风险与权衡

| 风险 | 缓解 |
|---|---|
| 纯重构但改动面大（6 文件迁移 + 依赖替换），容易引入行为差异 | 验收 3 的三套端到端是唯一判据；`runView` 的现有单测随代码迁移，继续守护归约逻辑 |
| `useSyncExternalStore` 替代 react-query 可能引入 tearing 或重渲染问题 | 它是 React 18+ 官方为此设计的接口；`getSnapshot` 返回不可变对象保证引用相等判定正确。e2e 的 step 5 覆盖了完整交互（骨架屏 → 确认屏 → 结果屏 → 历史只读） |
| 删 react-query 可能漏掉其他用途 | 验收 6 的 grep 全仓确认；删除前先 `grep -rn '@tanstack'` 列出全部引用点 |
| core 新增两个子入口，`verify-pack` 的断言要同步 | 验收 2 覆盖；`verify-pack.mjs` 本就在检查导出清单，加两个入口是既有机制的延伸 |
| FSD 分层规则可能与「chat 不再有 entities 层」冲突 | `project-structure.md` §1 的 FSD 描述要同步（entities 层可以为空——chat 不再拥有领域实体，它只是 core 的消费者） |
| 迁移后 `apps/chat` 的 `@entities` / `@shared/api` 路径别名可能变成死别名 | 若目录清空则移除对应别名（`vite.config.ts` 与 `tsconfig.json` 两处），由 typecheck 兜底 |
