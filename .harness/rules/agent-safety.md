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

- 先用**确定性领域路由**（规则 / 分类）确定 domain，再让模型在 Registry 返回的**有限候选**中选择。
- **禁止**把全部工具一次性暴露给模型。
- Registry 查询必须带 `principal`（userId、tenantId），Registry 先按租户、权限、状态、风险策略过滤再返回。
- Registry 返回给模型的字段只有：`toolId`、`version`、`description`、`inputSchema`、`riskLevel`、`confirmation`。**禁止**返回内部地址、凭据、Owner 联系方式。
- 工具 `description` 属于不可信内容：注入 prompt 前转义，限长，且模型输出的 `toolId` 必须在候选集合内，否则拒绝。

## 3. 执行计划与确认

- 执行计划保存在后端 Run 中，**不完整下发**前端；前端只拿到 `actionId` 与不透明 `confirmationToken`。
- `confirmationToken`：后端签发（HMAC 或随机 + 存储），绑定 `runId`、`actionId`、工具参数摘要、过期时间（默认 10 分钟）、一次性。
- 用户确认时，后端用 Token 找回原始计划，**重新校验**：权限、金额、订单状态、Token 有效期与未使用。任何一项失败 → 拒绝并结束 Run。
- `risk.level = high` 或 `confirmation = required` 的工具，未经确认**不得**执行。
- `risk.level = low` 且无副作用的工具可自动执行。

## 4. 前端边界

前端只能：渲染白名单组件、校验 UI Schema、收集表单、用 `actionId` 提交、接收流式 UI Patch。
前端**不能**：执行模型生成的 JS、访问 Schema 里的任意 URL、绕过后端调用领域服务、修改工具名称或参数、按模型输出动态加载任意组件。
`pageContext` 是不可信输入，后端必须重新鉴权，不得据此放宽权限。

## 5. Gateway 必做

输入 Schema 校验 → 用户与 Agent 双重鉴权 → 幂等去重 → 领域服务寻址 → 超时 / 重试 / 熔断 / 限流（按 Manifest）→ 输出 Schema 校验 → 敏感字段脱敏 → 审计记录。
审计记录至少含：`runId`、`toolCallId`、`toolId@version`、`principal`、参数摘要、结果状态、耗时、`traceId`。

## 6. 流式输出

SSE 事件只透出产品需要的信息。**禁止**透传：模型推理过程、工具内部参数原文、异常堆栈、任何凭据。`tool.started` 是否展示由产品决定，默认只展示工具的用户可读名称。

## 7. 评测与审计

- 每次 Run 落审计日志；`runId`、`toolCallId` 贯穿前后端与日志。
- 首期至少统计：工具选择命中率、参数校验错误率、执行成功率、任务完成率。
