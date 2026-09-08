# Architecture（架构）

## 一句话

> Strato UI 负责交互，Agent Runtime 负责理解与规划，Tool Registry 负责能力发现与治理（控制面），Tool Gateway 负责安全执行（执行面），领域服务负责确定性业务执行。

## 四层总览

```
┌──────── fronted/ apps/chat + @strato-ui/core（Strato UI） ─┐
│ chat 应用壳 + 引擎包：Schema Renderer + Registry（白名单）   │
│ 输入采集、动态表单、确认卡片、结果展示、SSE 流式更新        │
└───────────────────────┬───────────────────────────────────┘
                        │ IntentRequest / ActionRequest（HTTP）
                        │ SSE 事件流
┌───────────────────────▼───────────────────────────────────┐
│               backed/agent-runtime                        │
│ 领域路由（规则 → 模型分类）→ 工具发现 → 实体检查 → 规划   │
│ Policy / Permission → Run 状态机 → SSE 输出               │
└──────────────┬────────────────────────────┬──────────────┘
               │ 查询能力（控制面）          │ 执行调用（执行面）
┌──────────────▼──────────────┐   ┌─────────▼──────────────┐
│   backed/tool-registry      │   │   backed/tool-gateway  │
│ Manifest、版本、权限、风险、  │   │ 鉴权、Schema 校验、寻址、│
│ 状态、Owner；不转发调用       │   │ 超时/重试、幂等、审计    │
└──────────────┬──────────────┘   └─────────┬──────────────┘
               │ 注册（CI/CD）                │ 调用
┌──────────────▼─────────────────────────────▼──────────────┐
│               backed/domains/*  领域服务                   │
│         order-service │ refund-service │ …                 │
└───────────────────────────────────────────────────────────┘
```

## 运行链路（以"给订单 10001 退款"为例）

1. 前端 `POST /agent/runs`，携带 `IntentRequest`（消息、pageContext、clientCapabilities）。pageContext 视为不可信。
2. Runtime 创建 Run，SSE 推 `run.started`。领域路由：关键词规则命中 `refund`（未命中时由模型在该用户可见领域内分类，越界视为 none）。缺页面实体时在此提示用户并结束。
3. Runtime 以服务身份 + principal 查询 Registry `POST /internal/tool-registry/search`，拿到过滤后的候选工具。
4. LLM（Spring AI `ChatClient`，OpenAI 兼容接口，内部工具执行关闭）在候选内选择并给出计划；计划存后端 Run，不下发前端。
5. 低风险只读工具（`refund.eligibility.check`、`refund.preview`）经 Gateway `POST /internal/tool-gateway/invoke` 自动执行，每次调用 SSE 推 `tool.selected` → `tool.started` → `tool.completed`。
6. 高风险工具（`refund.create`）需确认：Runtime 生成 UI Schema（订单 `Card` + 退款摘要 `Card` + `Form` + `confirm-refund` action，含不透明 `confirmationToken`），SSE 推 `ui.replace` + `confirmation.required`，Run 进入 `WAITING_CONFIRMATION`。
7. 用户在前端确认，`POST /agent/runs/{runId}/actions/{actionId}` 携带 Token 与 formData。
8. Runtime 用 Token 找回计划，重新校验权限、金额、订单状态、有效期，再经 Gateway 执行 `refund.create`。
9. SSE 推 `ui.replace`（`Result`）与 `run.completed`。全程 `runId` / `toolCallId` 贯穿审计。

## 前端 FSD 分层

`fronted/` 是 pnpm workspace：`packages/core`（`@strato-ui/core`，Strato UI 渲染引擎，可发包）+ `apps/chat`（唯一应用，FSD `app → pages → features → entities → shared` 单向）。Renderer、ComponentRegistry 与全部白名单封装位于 `fronted/packages/core/src/`，按端型分 `components/desktop/`（antd）与 `components/mobile/`（antd-mobile）；端型由宿主挂载的 `StratoDeviceProvider` 一次性决定；ui-schema 的 Zod 投影真源也在 core，chat 的其余契约投影组合引用它。

## 后端模块依赖

```
app → 全部模块（唯一可依赖 domains/* 的非领域模块）
agent-runtime → { tool-registry(api), tool-gateway(api) }
tool-gateway → tool-registry(ToolResolver) ；通过 platform-spi 的 ToolHandler SPI 调用领域实现，pom 不依赖 domains/*
domains/* → platform-spi , contracts-java（实现 ToolHandler）
所有模块 → contracts-java , platform-spi
```

## 状态管理边界（前端）

| 状态类型 | 工具 |
|---|---|
| 服务端状态（Run、UI Schema） | TanStack Query + SSE 订阅写入 cache |
| 跨页面客户端状态（当前租户 / 用户、主题） | Zustand |
| 同页面 UI 状态 | useState / useReducer |
| 路由状态 | React Router |

## 通信协议

- 普通请求：HTTP JSON；Agent 过程输出：SSE；UI 描述：版本化 JSON Schema；工具契约：JSON Schema；追踪：`traceId` 头透传（OpenTelemetry 接入为后续 change）。

## 改动边界

- 改 `.harness/contracts/` → 两端都受影响，先跑 `check-contracts`，再 typecheck / compile 暴露不一致。
- 新增工具 → 领域服务写 Manifest 并注册；Runtime、Gateway 不需改代码。
- 新增 UI 组件 → Schema enum + 前端注册表 + 后端生成逻辑，三处同 change。
