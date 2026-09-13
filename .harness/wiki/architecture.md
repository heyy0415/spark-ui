# Architecture（架构）

## 一句话

> Spark UI 负责交互，Agent Runtime 负责理解与规划，Tool Registry 负责能力发现与治理（控制面），Tool Gateway 负责安全执行（执行面），领域服务负责确定性业务执行。

## 形态：Spring Boot Starter（embedded）

spark-rooter 不是一个独立部署的平台，而是一个 **Starter 依赖**：任何 Java 服务引入 `com.sparkrooter:spark-rooter-spring-boot-starter`，在自己的 `@Service` 方法上加 `@SparkTool`，启动时扫描器推导 Manifest 并注册，前端 `spark-chat`（或任何嵌入 `@spark-ui/core` 的页面）即可用自然语言驱动这些工具。领域服务也可作为独立 provider 进程经 HTTP 接入（`spark-provider-spring-boot-starter`，见下文「两种拓扑」）；服务发现未做，provider 坐标走配置化 base URL。

> 想按一条请求的实际执行顺序读代码，看 [walkthrough.md](walkthrough.md)：从前端 SSE 分帧到 Gateway 八道工序、确认令牌、多副本，每一跳都标了 `file:line`。

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

`spark-ui/` 是 pnpm workspace：`packages/core`（`@spark-ui/core`）+ `apps/chat`（参考宿主，FSD `app → pages → features → shared` 单向）。

`@spark-ui/core` 有三个入口，依赖单向 `./react → ./client`、`. → schema/registry`：

| 入口 | 内容 | 依赖 |
|---|---|---|
| `./client` | **headless**：契约 Zod 投影、`request` / `consumeSse`、事件归约 `reduceEvent`、状态容器 `createRunStore` | 仅 zod |
| `./react` | `useSparkRun`（`createRunStore` + `useSyncExternalStore`） | + react |
| `.` | Renderer、ComponentRegistry、白名单封装（`components/desktop` antd、`components/mobile` antd-mobile）、主题与端型 Provider | + react、antd、antd-mobile |

全部前端契约投影（ui-schema 在 `src/schema/`，其余在 `src/client/contracts.ts`）与运行时状态都在 core，`apps/chat` 不再有 `entities/` 与 `shared/api/`——它只剩路由、页面与一个 `AgentChatPanel`。宿主自带渲染时只装 `./client`，不会被拖进 React / antd（`scripts/check-deps.mjs` 守护：`client/` 禁 react、禁 `import.meta.env`；`client/` 与 `react/` 都禁 import 渲染层入口 `../index`）。端型由宿主挂载的 `SparkDeviceProvider` 一次性决定。

## 两种部署拓扑

| | 单体内嵌 | 分布式微服务 |
|---|---|---|
| 领域服务 | 与内核同进程 | 独立进程（provider） |
| Manifest 的 `protocol` | `in-process` | `http` + `provider` 坐标 |
| 引入坐标 | `spark-rooter-spring-boot-starter` | provider 侧 `spark-provider-spring-boot-starter` |
| 工具调用 | 进程内反射（`InProcessToolTransport`） | HTTP（`HttpToolTransport`） |
| 示例 | `examples/host-demo` | `examples/provider-demo` + host-demo 当 hub |

两者可**同时存在于一个 hub**：Gateway 按 `manifest.protocol()` 分派，未装配对应协议时 `TOOL_NOT_FOUND` 而**绝不回落本地**（否则远程工具会被就近执行成同名本地工具）。

hub 没配 `spark.providers.tokens.*` 时不接受远程工具，也不装配 HTTP 客户端——单体宿主行为与引入本能力之前完全一致。

规划、治理、确认链全在 hub；provider 只做「声明工具 + 执行工具」，不含 Agent Runtime、Registry、Gateway、LLM 客户端。跨进程的安全边界见 `rules/agent-safety.md` §8。

## 后端模块依赖

```
starter → web-mvc, runtime, registry, gateway, contracts, spi（AutoConfiguration.imports；全部默认实现 @ConditionalOnMissingBean，Bean 名前缀 sparkRooter*）
redis → runtime, gateway, spi, contracts, starter（可选；spark.storage.type=redis 时把四个状态存储换成 Redis，hub 可多副本）
provider-starter → spi, contracts（薄依赖：不含 Spring AI / runtime / registry / gateway / web 栈）
web-mvc → runtime, registry, gateway
runtime → { registry(api), gateway(api) }, contracts, spi
gateway → spi（ToolResolver / ToolHandler / AuditSink / ToolAccessPolicy / RunContextPropagator）, contracts
registry → spi, contracts
examples/domains/* → spi, contracts, demo-support（@SparkTool；互不 import；跨领域读订单经 spi OrderSnapshotProvider）
examples/host-demo → starter + examples/domains/*（独立工程，本地仓坐标）
```

宿主可替换端口（定义同类型 Bean 即覆盖）：`RunRepository` / `ConfirmationTokenStore` / `IdempotencyStore` / `ConversationMemory`（默认内存；引 `spark-rooter-redis` + `spark.storage.type=redis` 换成 Redis，多副本用）、`ToolRegistryRepository`（默认内存）、`AuditSink`（默认日志）、`LlmClient`（默认 Spring AI；未配置模型为 `UnavailablePlanner`，所有请求直接失败）、`SessionIdResolver`（**无默认**：缺 Bean 拒绝启动，`spark.runtime.demo-session-resolver=true` 才放行演示实现）、`ToolAccessPolicy`（默认全放行）、`RunContextPropagator`（默认 no-op，宿主强烈建议实现）、`ProviderAuth`（**无默认**：不配 `spark.providers.tokens.*` 则不接受远程工具）、`ProviderEndpointResolver`（默认取 Manifest 的 `baseUrl`；接注册中心的宿主自行替换）、`ProviderIdempotencyStore`（provider 侧，默认进程内；多实例部署要强一致需换共享存储）。

## 状态管理边界（前端）

| 状态类型 | 工具 |
|---|---|
| 运行时会话状态（Run、UI Schema） | `@spark-ui/core/client` 的 `createRunStore()`，SSE 事件经 `reduceEvent` 归约后 dispatch；React 侧 `useSyncExternalStore` 订阅。**不用 TanStack Query**：SSE 是推送模型，视图状态由事件序列唯一决定，无「数据过期需重取」语义 |
| 跨页面客户端状态 | 当前无；需要时以 change 引入 |
| 同页面 UI 状态 | useState / useReducer |
| 路由状态 | React Router |

## 通信协议

- 普通请求：HTTP JSON；Agent 过程输出：SSE；UI 描述：版本化 JSON Schema；工具契约：JSON Schema；追踪：`traceId` 头透传（OpenTelemetry 接入为后续 change）。

## 改动边界

- 改 `.harness/contracts/` → 两端都受影响，先跑 `check-contracts`，再 typecheck / compile 暴露不一致。
- 新增工具 → 宿主 `@Service` 加一个 `@SparkTool` 方法 + record；Manifest 自动推导，Runtime、Gateway 不需改代码。内核不含领域词汇（`check-module-deps` 的 `DOMAIN_WORDS` 红线守护），新领域无需改内核。
- 新增 UI 组件 → Schema enum + 前端注册表 + 后端生成逻辑，三处同 change。
