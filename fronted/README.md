# fronted — Strato Agent Tool Platform 前端（Generate UI）

React 19 / TypeScript 7 strict / Vite 6 / TanStack Query / Zod / antd 6（桌面）+ antd-mobile 5（移动）。

> 一句话：前端只负责交互。它把用户意图与页面上下文发给 Agent Runtime，消费 SSE 事件流，并**只渲染白名单组件**描述的 UI Schema；不执行任何模型生成的代码，不自行决定调用哪个工具。

## 目录（FSD，单向依赖 `app → pages → features → entities → shared`）

| 层                              | 内容                                                                                           |
| ------------------------------- | ---------------------------------------------------------------------------------------------- |
| `src/app`                       | 路由（`/`、`/agent`、DEV-only `/dev/schema`）、Providers（QueryClient → Device → AppTheme）    |
| `src/pages/agent`               | 智能助手页：从 URL 读 `page / entityType / entityId` 组成 pageContext                          |
| `src/pages/schema-playground`   | 开发用：直接渲染契约示例（`?example=` 取 `confirm` / `result` / `unknown`）                    |
| `src/features/agent-chat`       | `useAgentRun`（发起 / 确认、SSE 归约为 `AgentRunView`）、`AgentChatPanel`                      |
| `src/entities/agent-run`        | 契约的 Zod 投影（intent / action / ui-schema / run-summary / sse-events / error）与纯 API 构造 |
| `src/shared/api`                | `httpClient`（`HttpError`）、`sseClient`（fetch + ReadableStream 分帧）                        |
| `src/shared/ui/generate`        | `SchemaRenderer`、`desktopRegistry` / `mobileRegistry`、7 个组件的双端实现、`UnknownComponent` |
| `src/shared/ui/theme`、`device` | antd / antd-mobile 主题封装与端型判定（`MOBILE_MAX_WIDTH = 768`）                              |

**antd / antd-mobile 只允许在 `src/shared/ui/**` 内 import**，`@contracts/*` 只读别名只允许在 `src/pages/**` 与 `scripts/**` 使用；两条都由 oxlint `no-restricted-imports` 机械拦截。

## 命令

```bash
pnpm install
pnpm dev              # http://localhost:5173，/agent/runs 与 /actuator 代理到 8080
pnpm ci               # typecheck + lint(oxlint + check-deps + check-registry) + format:check + build
pnpm verify-examples  # 用 Zod 投影校验 .harness/contracts/examples（期望 "16 examples OK"）
```

`check-registry.mjs` 保证 `ui-schema.schema.json` 的 componentType enum、desktopRegistry、mobileRegistry、`PROPS_SCHEMAS` 以及 `desktop/`、`mobile/` 下的实现文件五方一致。

## 与后端联调

1. 启动后端（见 [backed/README.md](../backed/README.md)），确认 `curl localhost:8080/actuator/health`。
2. `pnpm dev`，打开 `http://localhost:5173/agent?page=order-detail&entityType=order&entityId=10001`。
3. 输入「帮我把这个订单退款」→ 两条工具进度 → 确认卡片（OrderCard + RefundConfirmCard + Form）→ 选原因 → 确认 → ResultCard。

自动化版本：`pnpm -C .harness run e2e-frontend`（需要本机 Google Chrome，后端 8080 与 vite 5173 已启动），产出截图到 change 的 `deployment/`。

## 环境变量

| 变量                | 默认         | 说明                                                      |
| ------------------- | ------------ | --------------------------------------------------------- |
| `VITE_API_BASE_URL` | `''`（同源） | 后端端点已带完整前缀（`/agent/runs`），生产可指向网关地址 |

首期身份固定为 `X-Tenant-Id: tenant_001` / `X-User-Id: user_001`（`pages/agent/AgentPage.tsx`），真实登录为后续 change。

## 安全边界（agent-safety §4）

- 未知 `componentType` 渲染 `UnknownComponent` 占位并 `console.error`，其余组件照常渲染。
- 组件 props 经 Zod 校验后才交给实现；UI Schema 不含 URL / HTML 字段。
- 确认动作只回传 `confirmationToken` 与表单值；表单键白名单由后端校验。
- pageContext 只是提示，不参与鉴权。
