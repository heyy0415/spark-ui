# Architecture（架构）

## 一句话

> Spark UI 负责交互，Agent Runtime 负责理解与规划，Tool Registry 负责能力发现与治理（控制面），Tool Gateway 负责安全执行（执行面），领域服务负责确定性业务执行。

## 形态：Spring Boot Starter（embedded）

spark-rooter 不是一个独立部署的平台，而是一个 **Starter 依赖**：任何 Java 服务引入 `com.sparkrooter:spark-rooter-spring-boot-starter`，在自己的 `@Service` 方法上加 `@SparkTool`，启动时扫描器推导 Manifest 并注册，前端 `spark-chat`（或任何嵌入 `@spark-ui/core` 的页面）即可用自然语言驱动这些工具。分布式（远程工具、服务发现）只预留了 `ToolTransport` / `ToolProviderDiscovery` 端口，本期只有进程内实现。

```
┌──────── spark-ui/ apps/chat + @spark-ui/core（Spark UI） ─────────┐
│ 只发自然语言（输入框 / chip / 行内指令）；只渲染 5 个官方组件映射   │
└───────────────────────┬───────────────────────────────────────────┘
                        │ IntentRequest{conversationId, message, clientCapabilities} / ActionRequest；SSE
┌───────────────────────▼─────────── 宿主工程（任意 Spring Boot 服务） ─────────┐
│ 宿主拦截器 / 登录态 → SessionIdResolver（会话键）→ RunContextPropagator（跨线程）  │
│ ┌──────────────────── spark-rooter-spring-boot-starter（自动装配） ──────────┐ │
│ │ web-mvc：/agent/runs SSE、/internal/**（可选）                              │ │
│ │ runtime：会话装载 → 全部候选 → 模型规划 → PlanValidator 校验 → 编排        │ │
│ │          → 令牌（conversationId + sessionId）→ 屏 / 澄清屏 → SSE            │ │
│ │ registry：@SparkTool 推导的 Manifest；按 status（+ 宿主 ToolAccessPolicy）过滤│ │
│ │ gateway：校验 → 幂等 → 经 Spring 代理调用 @SparkTool 方法 → 校验 → 审计     │ │
│ └────────────────────────────────┬───────────────────────────────────────────┘ │
│                                  │ 反射调用（代理对象，宿主 @Aspect / @PreAuthorize 生效）│
│ ┌────────────────────────────────▼───────────────────────────────────────────┐ │
│ │ 宿主 @Service：@SparkTool 方法 + record In/Out（@SparkParam / @SparkDefault）│ │
│ │ + ScreenBuilder / ConfirmationRecheck（屏与重校验策略留在领域）              │ │
│ └────────────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────┘
```

## 运行链路（以「订单 10001 退款」为例）

1. 前端 `POST /agent/runs`，只带 `conversationId` / `message` / `clientCapabilities`。宿主拦截器已把用户放进自己的 ThreadLocal；`SessionIdResolver` 解析出 `sessionId`；`RunContextPropagator.capture()` 后切到 `agent-run-*` 线程 `restore`。
2. Runtime 创建 Run，SSE 推 `run.started`。装载会话上下文（记忆实体、最近列表行 ID、上一轮挂起原话，只有 ID 与短文本）。
3. Registry `search`（不带身份，domain 为空 = 全部可发现工具，宿主 `ToolAccessPolicy` 可再过滤）拿候选，连同原话与会话上下文交给模型；模型按注解里的 description / verbs / entity 选工具、填参数（「第二个」直接从最近列表行取 ID）。缺实体则输出 `clarify`，Runtime 出**澄清屏**（调 `clarifiesEntity` 的列表工具，每行按钮 intent 带 ID）。
4. `PlanValidator` 核实模型输出：toolId ∈ 候选、参数键 ⊆ inputSchema 且值过 JSON Schema、实体参数值原样出自原话或上下文并匹配 pattern、需确认步骤前置齐全且不填可信参数、`@SparkDefault` 补齐；不合规喂回模型重试一次，仍不合规 → `TOOL_SELECTION_INVALID`。
5. 只读前置步骤经 Gateway 自动执行：Gateway 经 Spring **代理**反射调 `RefundTools.eligibility(in)`，宿主方法级切面照常触发。
6. 高风险 `refund.create` 需确认：领域 `ScreenBuilder` 出确认屏（Card + Card + Form），令牌绑定 `runId + actionId + argsDigest + conversationId + sessionId`，SSE `ui.replace` + `confirmation.required`。
7. 用户确认 → 令牌双校验 → 领域 `ConfirmationRecheck` 重校验 → 执行 → 结果屏 → `run.completed` → 会话记忆写入 `{domain, entities, lastTable}`。
8. 宿主权限：`guest` 删除订单 → 前置步骤通过 → 确认 → Gateway 代理调用 `delete` → 宿主 `@Aspect` 拒绝 → `TOOL_EXECUTION_FAILED`，审计 `failed`。安全上拦住，体验上晚一步（默认取舍；要提前拒绝就实现 `ToolAccessPolicy`）。

## 前端 FSD 分层

`spark-ui/` 是 pnpm workspace：`packages/core`（`@spark-ui/core`，Spark UI 渲染引擎，可发包）+ `apps/chat`（唯一应用，FSD `app → pages → features → entities → shared` 单向）。Renderer、ComponentRegistry 与全部白名单封装位于 `spark-ui/packages/core/src/`，按端型分 `components/desktop/`（antd）与 `components/mobile/`（antd-mobile）；端型由宿主挂载的 `SparkDeviceProvider` 一次性决定；ui-schema 的 Zod 投影真源也在 core，chat 的其余契约投影组合引用它。

## 后端模块依赖

```
starter → web-mvc, runtime, registry, gateway, contracts, spi（AutoConfiguration.imports；全部默认实现 @ConditionalOnMissingBean，Bean 名前缀 sparkRooter*）
web-mvc → runtime, registry, gateway
runtime → { registry(api), gateway(api) }, contracts, spi
gateway → spi（ToolResolver / ToolHandler / AuditSink / ToolAccessPolicy / RunContextPropagator）, contracts
registry → spi, contracts
examples/domains/* → spi, contracts, demo-support（@SparkTool；互不 import；跨领域读订单经 spi OrderSnapshotProvider）
examples/host-demo → starter + examples/domains/*（独立工程，本地仓坐标）
```

宿主可替换端口（定义同类型 Bean 即覆盖）：`RunRepository` / `ConfirmationTokenStore` / `IdempotencyStore` / `ToolRegistryRepository` / `ConversationMemory`（默认内存）、`AuditSink`（默认日志）、`LlmClient`（默认 Spring AI；未配置模型为 `UnavailablePlanner`，所有请求直接失败）、`SessionIdResolver`（**无默认**：缺 Bean 拒绝启动，`spark.runtime.demo-session-resolver=true` 才放行演示实现）、`ToolAccessPolicy`（默认全放行）、`RunContextPropagator`（默认 no-op，宿主强烈建议实现）。

## 状态管理边界（前端）

| 状态类型 | 工具 |
|---|---|
| 服务端状态（Run、UI Schema） | TanStack Query + SSE 订阅写入 cache |
| 跨页面客户端状态 | 当前无；需要时以 change 引入 |
| 同页面 UI 状态 | useState / useReducer |
| 路由状态 | React Router |

## 通信协议

- 普通请求：HTTP JSON；Agent 过程输出：SSE；UI 描述：版本化 JSON Schema；工具契约：JSON Schema；追踪：`traceId` 头透传（OpenTelemetry 接入为后续 change）。

## 改动边界

- 改 `.harness/contracts/` → 两端都受影响，先跑 `check-contracts`，再 typecheck / compile 暴露不一致。
- 新增工具 → 宿主 `@Service` 加一个 `@SparkTool` 方法 + record；Manifest 自动推导，Runtime、Gateway 不需改代码。内核不含领域词汇（`check-module-deps` 的 `DOMAIN_WORDS` 红线守护），新领域无需改内核。
- 新增 UI 组件 → Schema enum + 前端注册表 + 后端生成逻辑，三处同 change。
