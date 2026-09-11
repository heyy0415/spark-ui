# spark-ui — Spark 前端 workspace

pnpm workspace，两个包：

| 包               | 路径            | 说明                                                                                                                                                               |
| ---------------- | --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `@spark-ui/core` | `packages/core` | **Spark UI 渲染引擎**，可 npm 发包。把后端下发的 UI Schema 渲染为白名单组件（antd 桌面 / antd-mobile 移动）。见 [packages/core/README.md](packages/core/README.md) |
| `spark-chat`     | `apps/chat`     | 唯一应用：一个聊天页（路由 `/`）。每条用户消息一个回合：右侧用户气泡，左侧助手气泡内是 core 的 `RunStatus` → 骨架 → 屏；历史回合只读保留，会话只在内存             |

React 19 / TypeScript 7 strict / Vite 8 / TanStack Query / Zod 4 / antd 6 / antd-mobile 5 / oxlint / prettier / vitest。版本由 `pnpm-workspace.yaml` 的 `catalog` 统一。

> 一句话：前端只负责交互。它**始终只发自然语言**（输入框、示例 chip、Table / Card 的行内指令都原样作为一条新消息发送）给 Agent Runtime，消费 SSE 事件流，并**只渲染白名单组件**描述的 UI Schema；不发页面上下文 / 身份 / 业务字段，不执行任何模型生成的代码，不自行决定调用哪个工具。多级界面（列表 → 详情 → 返回）由后端在屏里预写的 `intent` 驱动。

## 命令（在 `spark-ui/` 下）

```bash
pnpm install
pnpm run dev              # apps/chat dev server http://localhost:5173（自动检查 core dist，缺失则提示先 build:core）
pnpm run build:core       # packages/core → dist（vite lib + tsc d.ts）
pnpm run build            # build:core → build:chat
pnpm run test             # vitest：core + chat 单元测试（纯函数 / 传输层，无 DOM）
pnpm run ci               # build:core → typecheck → test → lint → format:check → verify-examples → verify-transport → build:chat → verify-pack
pnpm run verify-examples  # 用 Zod 投影校验 .harness/contracts/examples（27 examples OK）
pnpm run verify-pack      # pnpm pack 解包后断言：文件清单 / exports / 17 导出名 / d.ts 双 EOPT 消费 / antd 未打包 / 体积基线
```

`pnpm -C spark-ui run ci` 是 Harness `ci.mjs` 的前端入口。

## `@spark-ui/core` 如何被解析

| 场景                                  | 解析到                                                                          |
| ------------------------------------- | ------------------------------------------------------------------------------- |
| tsc / oxlint / `vite dev` / vite-node | core **src**（`package.json` 的 `exports` 指 `./src/index.ts`，改源码即热更新） |
| `apps/chat` 的 `vite build`           | core **dist**（`vite.config.ts` 正则精确 alias），生产吃可发布产物              |
| `pnpm pack` / publish                 | `publishConfig` 覆盖 `exports` / `types` 为 dist                                |
| `@spark-ui/core/style.css`            | 始终 dist；chat 的 `predev` / `prebuild` 守护其存在                             |

## 目录（`apps/chat/src`，FSD 单向 `app → pages → features → entities → shared`）

| 层                        | 内容                                                                                                                                                                                          |
| ------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `app`                     | 路由（`/` = chat、DEV-only `/dev/schema`）、Providers（QueryClient → `SparkDeviceProvider` → `SparkThemeProvider tokens`）、`global.css`（首行 import core 的 style.css）、`styles/tokens.ts` |
| `pages/chat`              | 生成 `conversationId`，挂 `AgentChatPanel`；无 URL 参数、无页面上下文                                                                                                                         |
| `pages/schema-playground` | 开发用：直接渲染契约示例（`?example=` 取 `confirm` / `result` / `unknown`）                                                                                                                   |
| `features/agent-chat`     | `useAgentRun`（发起 / 确认、SSE 归约为 `AgentRunView`）、`AgentChatPanel({conversationId, baseUrl?, fetch?})`——宿主要带登录态就注入自己的 `fetch`                                             |
| `entities/agent-run`      | intent / action / run-summary / sse-events / error 的 Zod 投影（ui-schema 投影来自 core）                                                                                                     |
| `shared/api`              | `httpClient`（`HttpError`）、`sseClient`（fetch + ReadableStream 分帧）                                                                                                                       |
| `shared/ui/Button`        | 纯 CSS 按钮                                                                                                                                                                                   |

**红线**（oxlint + `scripts/check-deps.mjs` + `scripts/check-registry.mjs` 机械守护）：

- `apps/chat` 任何文件不得 import `antd` / `antd-mobile` / `@ant-design`，只能用 `@spark-ui/core` 包入口；禁止 `@spark-ui/core/src/*` 深路径。
- antd / antd-mobile 只允许出现在 `packages/core/src/components/**` 与 `theme/**`。
- 注册表键集合 == 契约 `componentType` enum == desktop / mobile 实现文件。
- `@contracts/*` 只读别名只在 `apps/chat/src/pages/**` 与 `scripts/**`。

## 与后端联调

1. 启动后端（见 [spark-rooter/README.md](../spark-rooter/README.md)），确认 `curl localhost:8080/actuator/health`。
2. `pnpm run dev`，打开 `http://localhost:5173/`。
3. 输入「帮我把订单 10001 退款」→ 两条工具进度 → 确认屏（订单 Card + 退款摘要 Card + Form）→ 选原因 → 确认 → Result。再试「看看我的订单」→ 点某行「查看物流」；「有什么商品」→「查看商品」→ Card 底部「返回列表」；直接说「申请售后」→ 澄清屏点选。

自动化版本：`pnpm -C .harness run e2e-frontend`（需要本机 Google Chrome，后端 8080 与 vite 5173 已启动），产出截图到当前 change 的 `deployment/`。

## 环境变量

| 变量                | 默认         | 说明                                                      |
| ------------------- | ------------ | --------------------------------------------------------- |
| `VITE_API_BASE_URL` | `''`（同源） | 后端端点已带完整前缀（`/agent/runs`），生产可指向网关地址 |

前端没有身份概念：身份在宿主工程（后端 `SessionIdResolver` / 拦截器）。示例宿主用可选请求头 `X-Demo-User` 模拟用户；要带登录态就给 `AgentChatPanel` 注入自己的 `fetch`。

## 发包（占位）

本 change 只保证 `pnpm run verify-pack` 通过，未接 registry。发布流程、`license`、版本管理为后续 change；`packages/core` 的 `prepublishOnly` 会先跑 verify-pack 挡误发。
