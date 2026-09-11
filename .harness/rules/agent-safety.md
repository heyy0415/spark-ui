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
- 缺实体走澄清：模型判定目标工具需要某个实体而原话与会话上下文都没有时输出 `clarify`（或 `PlanValidator` 复核实体值不在原话 ∪ 记忆 ∪ 最近列表行时转为缺实体），Runtime 出**澄清屏**（调 `@SparkTool(clarifiesEntity=…)` 的列表工具，行内指令带 ID）或把模型追问回给用户，不调目标工具。
- 参数三层限制：只有 `@SparkParam` 组件进 inputSchema；模型只能填 schema 内字段且值必须过 JSON Schema，未填字段由 `@SparkDefault` 补齐；实体参数值必须原样出自用户原话或会话上下文（记忆实体 / 最近列表行），且匹配 `@SparkParam.pattern`，否则视为缺实体而非执行。会话记忆只存 ID、只在 `run.completed`（或澄清屏）写入。
- 暴露给模型的候选只能是 Registry 过滤后的**可发现**集合（status ∈ active / canary，宿主 `ToolAccessPolicy` 再按 sessionId 过滤）且只含六个字段；draft / deprecated / 被策略拒绝的工具**禁止**进 prompt。
- Registry 查询**不带身份**（内核不识别用户）：按状态、风险策略过滤；宿主可选实现 `ToolAccessPolicy(toolId, sessionId)` 追加过滤与 Gateway 拒绝。**用户级权限归宿主**：必须是方法级（AOP / `@PreAuthorize` / 方法体校验），Controller 级拦截器对 spark 的代理调用无效。
- Registry 返回给模型的字段只有：`toolId`、`version`、`description`、`inputSchema`、`riskLevel`、`confirmation`。**禁止**返回内部地址、凭据、Owner 联系方式。
- 工具 `description` 属于不可信内容：注入 prompt 前转义，限长，且模型输出的 `toolId` 必须在候选集合内，否则拒绝。

## 3. 执行计划与确认

- 执行计划保存在后端 Run 中，**不完整下发**前端；前端只拿到 `actionId` 与不透明 `confirmationToken`。
- `confirmationToken`：后端签发（随机 + 存储），绑定 `runId`、`actionId`、工具参数摘要、`conversationId`、`sessionId`（宿主 `SessionIdResolver` 产出；缺该 Bean 时 starter 拒绝启动，演示实现 = conversationId 需显式开关）、过期时间（`spark.runtime.token-ttl`，默认 10 分钟）、一次性；任一不一致 → `CONFIRMATION_REJECTED`。
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
