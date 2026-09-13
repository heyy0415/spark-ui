# Rule: Agent 安全边界（Agent Safety）

> 本方案的核心不是让 AI 自由调用接口，而是让 AI 在"能力可发现、权限可控制、执行可恢复、结果可审计"的环境中完成任务。以下边界任一被破坏即 MUST FIX。

## 1. 四个面，职责不可混

| 模块 | 定位 | 是否经过业务流量 | 禁止 |
|---|---|---|---|
| Agent Runtime | 决策面：理解、路由、规划、选择 | 否 | 直连领域服务；持有领域服务地址 |
| Tool Registry | 控制面：注册、发现、版本、治理 | 否 | 转发 / 代理任何工具调用 |
| Tool Gateway | 执行面：鉴权、校验、路由、审计 | 是 | 做工具选择或规划 |
| Workflow / Run 状态机 | 状态面：步骤、等待、重试、补偿 | 管理生命周期 | 绕过 Gateway 执行 |

## 2. 工具发现

- 内核不做领域路由：Registry 返回全部可发现候选（按状态 + 宿主 `ToolAccessPolicy` 过滤），模型在这个**有限候选**集合内选工具、填参数；未配置模型时 `UnavailablePlanner` 直接失败，不做规则兜底。模型输出只是提议，`PlanValidator` 逐条核实后才成为计划。
- 上一条的「不做规则兜底」指**内核与生产环境**。测试替身（fake planner）只允许在宿主工程内以 profile 隔离装配，生产 profile 下不得存在；替身产出的草案同样必须经 `PlanValidator.decide`——替身替掉的是「理解」，不是「核实」。
- 缺实体走澄清：模型判定目标工具需要某个实体而原话与会话上下文都没有时输出 `clarify`（或 `PlanValidator` 复核实体值不在原话 ∪ 记忆 ∪ 最近列表行时转为缺实体），Runtime 出**澄清屏**（调 `@SparkTool(clarifiesEntity=…)` 的列表工具，行内指令带 ID）或把模型追问回给用户，不调目标工具。
- 参数三层限制：只有 `@SparkParam` 组件进 inputSchema；模型只能填 schema 内字段且值必须过 JSON Schema，未填字段由 `@SparkDefault` 补齐；实体参数值必须原样出自用户原话或会话上下文（记忆实体 / 最近列表行），且匹配 `@SparkParam.pattern`，否则视为缺实体而非执行。会话记忆只存 ID、只在 `run.completed`（或澄清屏）写入。
- 暴露给模型的候选只能是 Registry 过滤后的**可发现**集合（status ∈ active / canary，宿主 `ToolAccessPolicy` 再按 sessionId 过滤）且只含六个字段；draft / deprecated / 被策略拒绝的工具**禁止**进 prompt。
- Registry 查询**不带身份**（内核不识别用户）：按状态、风险策略过滤；宿主可选实现 `ToolAccessPolicy(toolId, sessionId)` 追加过滤与 Gateway 拒绝。**用户级权限归宿主**：必须是方法级（AOP / `@PreAuthorize` / 方法体校验），Controller 级拦截器对 spark 的代理调用无效。
- Registry 返回给模型的字段只有：`toolId`、`version`、`description`、`inputSchema`、`riskLevel`、`confirmation`。**禁止**返回内部地址、凭据、Owner 联系方式。
- 工具 `description` 属于不可信内容：注入 prompt 前转义，限长，且模型输出的 `toolId` 必须在候选集合内，否则拒绝。

## 3. 执行计划与确认

- 执行计划保存在后端 Run 中，**不完整下发**前端；前端只拿到 `actionId` 与不透明 `confirmationToken`。
- **Run 自包含**（feat-production-hardening-20260912）：确认所需的一切——最近一次屏、前置步骤输出、计划各步骤的 inputSchema——都在 `Run` 聚合里随 `RunRepository` 走，编排器不持有按 runId 的进程内缓存。确认请求落到任意 hub 副本都能凭仓储里那一条记录完成。用户原话（`Run.message`）**不进共享存储**：规划后没有路径再读它，落 Redis 只多一个泄露面。
- **确认互斥靠令牌原子消费**，不靠实例内的锁：`ConfirmationTokenStore.consume` 是原子的「取出并删除」（内存 `ConcurrentHashMap.remove`；Redis `GETDEL`），并发 / 重放的确认请求里只有一个能拿到令牌，其余得 `TokenUnknown` → 拒绝且**不改 Run 状态**。这条原子性是安全前提——换成先 GET 再 DEL 就是双执行；内存与 Redis 两实现都有「32 线程并发消费恰好 1 成功」的测试锁住。
- `confirmationToken`：后端签发（随机 + 存储），绑定 `runId`、`actionId`、工具参数摘要、`conversationId`、`sessionId`（宿主 `SessionIdResolver` 产出；缺该 Bean 时 starter 拒绝启动，演示实现 = conversationId 需显式开关）、过期时间（`spark.runtime.token-ttl`，默认 10 分钟）、一次性；任一不一致 → `CONFIRMATION_REJECTED`。未被消费的令牌到期后由存储清扫（内存：写入时顺手；Redis：key TTL），不会无界堆积。
- 用户确认时，后端用 Token 找回原始计划，**重新校验**：金额、订单状态、Token 有效期与未使用、会话一致；宿主权限在 Gateway 代理调用时由宿主切面触发。任何一项失败 → 拒绝并结束 Run。
- 重校验契约化：每个需确认工具必须有领域提供的 `ConfirmationRecheck`（spi）——runtime 经 Gateway 重调其 `recheckToolId`（版本取计划中前置只读步骤），领域 `reject(recheckOutput, shownUi)` 判定（策略留在领域，runtime 只编排），`trustedArgs` 覆盖 formData（键与确认屏 Form 字段互斥）。缺 recheck 或确认屏 → fail-closed `INTERNAL_ERROR`；`ConfirmationCoverageSelfCheck` 在启动时断言 Registry 中全部 `confirmation=required` 工具都被覆盖。
- 策略拒绝与令牌拒绝同 code（`CONFIRMATION_REJECTED`）但用户文案区分（`RunFailure.userText`）；内部原因只进日志。
- `risk.level = high` 或 `confirmation = required` 的工具，未经确认**不得**执行。
- `risk.level = low` 且无副作用的工具可自动执行。

## 4. 前端边界

前端只能：发送**自然语言**（输入框、示例 chip、`Table.rows[].actions` / `Card.actions` 的行内指令都原样作为一条新消息发送）、渲染白名单组件、校验 UI Schema、收集表单、用 `actionId` 提交、接收流式 UI Patch。
前端**不能**：发送页面上下文 / 身份 / 业务字段、执行模型生成的 JS、访问 Schema 里的任意 URL、绕过后端调用领域服务、修改工具名称或参数、按模型输出动态加载任意组件。多级界面全部由后端在屏里预写自然语言 `intent` 驱动。

## 5. Gateway 必做

寻址 → 输入 Schema 校验 → 宿主 `ToolAccessPolicy`（可选）→ 幂等去重（按 `sessionId`）→ **经 Spring 代理**反射调用 `@SparkTool` 方法（宿主方法级切面在此触发；`RunContextPropagator` 先把宿主上下文恢复到工具线程）→ 超时 / 重试（按 Manifest）→ 输出 Schema 校验 → 敏感字段脱敏 → 审计记录（`AuditSink` 端口，宿主可替换）。
审计记录至少含：`runId`、`toolCallId`、`toolId@version`、`sessionId`、参数摘要、结果状态、耗时、`traceId`。

## 6. 流式输出

SSE 事件只透出产品需要的信息。**禁止**透传：模型推理过程、工具内部参数原文、异常堆栈、任何凭据。`tool.started` 是否展示由产品决定，默认只展示工具的用户可读名称。

## 7. 评测与审计

- 每次 Run 落审计日志；`runId`、`toolCallId` 贯穿前后端与日志。
- 首期至少统计：工具选择命中率、参数校验错误率、执行成功率、任务完成率。

## 8. 跨服务（provider）形态的安全边界

feat-provider-http-transport-20260912 引入 `protocol=http` 后，下列边界**必须显式成立**——它们在单体形态下由「同进程」这个前提免费提供，跨进程后需要代码兑现。

### 8.1 治理不下放

Schema 校验、访问策略、幂等 claim、超时、重试、脱敏、审计**全部留在 hub 的 `InvokeToolUseCase`**。`ToolTransport` 只负责「把参数送到工具、把结果拿回来」。远程工具绝不能走 Gateway 之外的路径。

`ToolAccessPolicy` 在 transport **之前**调用，故跨进程不影响权限判定。

### 8.2 重试的判据是「有没有执行」，不是「错误严不严重」

| 传输失败 | 可否重试 | 理由 |
|---|---|---|
| `NOT_FOUND` / `UNREACHABLE` | **可以** | 确定没碰到工具 |
| `REMOTE_TIMEOUT` | **不可以** | 结果未知：远端可能已执行成功，只是响应没赶上 |
| `REMOTE_FAILED` | **不可以** | 已执行过，是否生效未知 |

进程内超时可由 `Future.cancel(true)` 真正中断，重试安全；**跨进程超时只能中断本地等待，远端照常执行**。弄反这条会在退款/扣款场景造成重复执行。

### 8.3 幂等是两层，缺一不可

- hub 的 `IdempotencyStore` 防 **hub 侧重复发起**；
- provider 的 `ProviderIdempotencyStore` 防 **网络重传与 hub 重试**。

`ExecutionContext.idempotencyKey` 必须随请求传到 provider。缺了 provider 这一层，Manifest 上的 `idempotency=required` 只是一句声明。

provider 默认实现是**进程内**的，多实例部署时 hub 重试可能落到另一实例而绕过缓存；要强一致就替换该 Bean 为共享存储。

### 8.4 脱敏点必须在发送端

provider **返回前**就脱敏（与 hub 同一 `SENSITIVE_KEYS` 口径），hub 侧脱敏作为第二道。脱敏若只在接收端，原文已经过网络、已进 provider 日志与链路追踪——违反公司「敏感信息先脱敏」红线。

`baseUrl` 允许 `http://`（内网部署与本地联调），但启动必须 WARN，生产应用 HTTPS 或 mTLS。

### 8.5 认证是双向的，且令牌绑定服务名

| 方向 | 端点 | 校验 |
|---|---|---|
| provider → hub | `POST /internal/tool-registry/tools` | 令牌有效**且**与 `provider.serviceName` 对应 |
| hub → provider | `POST /spark/tools/invoke` | 令牌有效 |

只校验「令牌有效」不够：那样任一 provider 被攻破即可冒充其他所有 provider 注册伪造的高危工具。比较用 `MessageDigest.isEqual`（常量时间），不用 `String.equals`。

**未配认证 = 不接受远程工具**，不是「不检查」。hub 没配 `spark.providers.tokens.*` 时拒绝一切 `protocol=http` 注册且不装配 HTTP 传输。

### 8.6 确认覆盖必须在注册时判定

hub 的启动自检跑在自己的 `ApplicationReadyEvent`，而 provider 在**它自己的** `ApplicationReadyEvent` 才推 Manifest——两个进程，顺序无保证。高风险远程工具通常在 hub 自检通过之后注册，**完全绕过那道检查**。

故 `RegisterToolUseCase` 在写入前调 `ConfirmationCoveragePolicy`：需确认工具（`confirmation=required` 或 `risk=high`）缺确认屏或 `ConfirmationRecheck` → **注册即拒绝**。否则故障会从「启动即失败」退化成「用户点确认那一刻才失败」。

### 8.7 `RunContextPropagator` 在 http 形态下不生效

`capture()` 返回不透明 `Object`，设计上不可跨进程。宿主若以为自己的 ThreadLocal / `SecurityContextHolder` 能传到 provider，基于它的鉴权判定会**静默**走默认分支。

provider 要拿身份只能靠 (a) hub 传来的 `sessionId`（宿主自行映射）或 (b) provider 宿主自己的网关鉴权。provider-starter 检测到该 Bean 存在时会 WARN。
