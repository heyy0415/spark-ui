# Plan Review v1 — feat-agent-tool-platform-20260903

- mode: plan
- reviewer: expert-reviewer (independent sub-agent)
- date: 2026-09-03
- targets:
  - `.harness/changes/feat-agent-tool-platform-20260903/request_analysis/spec.md`
  - `.harness/changes/feat-agent-tool-platform-20260903/request_analysis/tasks.md`
- rules consulted: `project-structure.md`、`contracts.md`、`agent-safety.md`、`backend-standard.md`、`coding-standard.md`、`coding-skill/specs/00-contract-spec.md`、`05-styling-spec.md`、`06-backend-module-spec.md`；wiki：`domain-model.md`、`api-contracts.md`；仓库实况：`fronted/.oxlintrc.json`、`fronted/package.json`、`.harness/scripts/check-contracts.mjs`、`check-module-deps.mjs`、`skills/request-analysis/SKILL.md`。
- 独立性声明：未阅读任何编码 Agent 自评；未修改 spec.md / tasks.md。

## Checklist

- [x] 「非目标」章节存在且非空 —— spec §3 列 11 条，且逐条对应 §2 中可能膨胀的方向（IdP、持久化、MCP、OTel、多领域、i18n）。有效阻断范围蔓延。
- [ ] 每条验收标准都可被命令或断言校验 —— spec §6.2 第 4 条（"不含 baseUrl/internal/secret 字段"）、第 9 条（"退款记录仍为 1 条"）、第 10 条（"不含用户输入原文"）缺少可执行探针；§6.3 第 4–6 条为人工目测（UI-only，可接受但需固定步骤脚本）。见 F03 / F08 / F09 / F10。
- [x] 风险章节列出 ≥1 个失败模式与缓解措施 —— spec §7 共 8 行，每行有影响 + 缓解，且缓解大多指向机械校验（check-module-deps、check-registry、enforcer）。
- [ ] 每个 task 标注所属端，且 contracts task 排在依赖它的 task 之前 —— 18 个 task 均有「所属端」；编号顺序 contracts 先行成立。但 T05「依赖」行只写 T04，而其「输入」与依赖图都要求 T01–T04（含 T03）；T12 / T13 的依赖存在与内容无关的项。见 F05。
- [~] 涉及跨端结构的 task 列出对应契约文件 —— T05 / T06 / T08 / T13 / T14 明确命名 schema；T10（SSE 端点 + action-request 消费方）只写"事件按契约"，未点名 `sse-events` / `intent-request` / `action-request`。见 F14。
- [ ] 每个 task 工作量 ≤ 0.5 天 —— T05、T10、T15、T17 明显超出；T07 边界。见 F06。
- [ ] （request-analysis SKILL 第 4 步）每个 task 含六要素 —— 18 个 task 中 14 个缺「输入」。见 F01。

## Findings

### F01 — 14 / 18 个 task 缺少「输入」要素
- **位置**：tasks.md T03、T04、T06、T07、T08、T10、T11、T12、T13、T14、T15、T16、T17、T18
- **问题**：`skills/request-analysis/SKILL.md` 第 27–30 行规定每个 task 六要素（目标 / 所属端 / 输入 / 输出 / 验收 / 依赖），第 48 行 checklist "每个 task 含六要素"。逐条核对：仅 T01（"spec §5、contracts.md、00-contract-spec.md"）、T02（"spec §2.3、wiki/domain-model.md"）、T05（"T01–T04、backend-standard.md、06-backend-module-spec.md"）、T09（"T05、backend-standard.md §1"）有「输入」行；其余 14 个直接从「所属端」跳到「输出」。
- **建议**：为每个缺失 task 补一行 `- **输入**：`，至少写明 (a) 上游 task 产出物路径，(b) 所依据的 rule / spec 章节。例如 T06：`T05 的 contracts-java、tool-manifest/tool-search schema、agent-safety.md §2、06-backend-module-spec.md「Tool Registry 专项」`；T15：`T02 的 ui-schema enum、05-styling-spec.md「Generate UI 封装层」、04-shared-spec.md`。
- **分级**：MUST FIX

### F02 — T12 提出的 ThemeProvider antd 例外与 spec §6.3、coding-standard §4 及现有 oxlint 配置三方冲突
- **位置**：tasks.md T12「验收」；spec.md §6.3 第 2 条；`coding-standard.md` §4；`05-styling-spec.md` 总则第 3 行；`fronted/.oxlintrc.json` 第 82–85 行
- **问题**：
  - T12 验收："`grep -rn "from 'antd" fronted/src | grep -v shared/ui | grep -v app/providers/ThemeProvider` 无输出（ThemeProvider 为允许例外，oxlint override 加入）"。
  - spec §6.3 第 2 条："`grep -rn "from 'antd\|from 'antd-mobile" fronted/src ... | grep -v "src/shared/ui/"` 无输出" —— 无例外。同一 change 的 spec 与 tasks 验收互相矛盾。
  - coding-standard §4 原文："只允许在 `shared/ui/**` 内 import 这两个库"，措辞是"只允许"，无例外条款；现行 `.oxlintrc.json` 亦只对 `src/shared/ui/**` 放行。
  - `05-styling-spec.md` 第 4 行"它们只出现在 `shared/ui/**`"与第 6 行"`app/providers/ThemeProvider.tsx` 用 antd `ConfigProvider`"自身矛盾，但该 spec 是下位文件，不能推翻 rule。
  - T12 的做法是通过放宽 lint 让计划通过，方向与 Hashimoto 法则相反。
- **建议**：不加例外。将 `ConfigProvider` + antd-mobile CSS 变量注入封装为 `fronted/src/shared/ui/theme/ThemeProvider.tsx`（或 `AppThemeProvider`），通过 `@shared/ui` 导出；`app/providers/ThemeProvider.tsx` 仅 import `@shared/ui` 并读取 `global.css` 色值传入。这样 spec §6.3 第 2 条、coding-standard §4、oxlint 现状三者无需改动即一致。同时删除 T12 验收里的 `grep -v app/providers/ThemeProvider` 与"oxlint override 加入"。顺带在 T18 中修正 `05-styling-spec.md` 第 6 行措辞（属 Harness 修订，可记入 summary「经验沉淀」）。
- **分级**：MUST FIX

### F03 — tool-search 响应字段白名单未在 spec / tasks 任何位置定义，spec §6.2 第 4 条的 grep 探针不能证明合规
- **位置**：spec.md §6.2 第 4 条；tasks.md T04「目标」、T06「验收」；`agent-safety.md` §2 第 4 条
- **问题**：agent-safety §2 规定"Registry 返回给模型的字段只有：`toolId`、`version`、`description`、`inputSchema`、`riskLevel`、`confirmation`"。spec / tasks 对 `tool-search.schema.json` 的响应只写"请求与响应"（spec §5）和"返回字段白名单"（T06，未列字段）。验收用 `grep -c baseUrl` / "不含 baseUrl / internal / secret"：Manifest 中 `owner.team`、`authorization.permission`、`execution.timeoutMs`、`outputSchema`、`status` 均不含这三个词，泄漏也不会被抓到。字段集是跨端结构（Runtime ↔ Registry），必须由契约锁定，而契约任务 T04 未写明。
- **建议**：(1) T04「目标」明确 `tool-search` `$defs.response.items` 恰为上述 6 字段且 `additionalProperties: false`；(2) spec §6.2 第 4 条改为"响应体通过 `tool-search.schema.json` `$defs.response` 校验（该 schema 禁止额外字段）"，可保留 grep 作为冗余；(3) T06 验收同步。
- **分级**：MUST FIX

### F04 — Phase B 各 task 的验收依赖可运行的 Spring Boot 应用，而 `app` 模块直到 T11 才产出
- **位置**：tasks.md T06「验收」（"启动后 curl 注册…"）、T07（"启动日志显示 6 个工具注册成功"）、T08（"缺 orderId 返回 400"）、T09（"启动自检日志"）、T10（"用 `curl -N` 录事件"）；T11「目标」："`backed/app` Spring Boot 主类装配全部模块"
- **问题**：T05 输出列表中 `app/pom.xml` 只是"子模块空壳"，主类与装配在 T11；T11 依赖 T10。于是 T06–T10 的验收在其声明的依赖满足时**无法执行**——要么编码 Agent 跳过验收，要么在 T06 临时自造启动类（未计划的范围漂移，且后续与 T11 冲突）。这直接违反"门禁必须可程序化校验"的前提。
- **建议**：拆 T05 为 T05a（父 POM、mvnw、enforcer、7 个子模块 pom、**最小 `app` 主类 + `/actuator/health`**，验收 health UP）与 T05b（contracts-java 8 组 record + `SchemaValidator`）；T11 收窄为"完整装配 + `application.yml` + README + check-module-deps"。这同时解决 F06 中 T05 超时问题。
- **分级**：MUST FIX

### F05 — 依赖行与依赖图 / 输入不一致
- **位置**：tasks.md T05「依赖：T04」vs 「输入：T01–T04」vs 依赖图第 146–147 行（`T03 ─┐` 与 `T04 ───┼→ T05`）；T12「依赖：T02」；T13「依赖：T03、T12」
- **问题**：
  - T05 需要 8 组 record（含 ui-schema、sse-events，即 T02、T03），依赖图也画了 T03 → T05，但「依赖」行只写 T04。按依赖行调度会在 T03 未完成时启动 T05。
  - T12（安装 antd、Device/Theme Provider）依赖 T02（ui-schema 契约）——二者无内容关联；T13（Zod 投影）依赖 T12（antd 安装）——同样无关联，反而会把 Phase C 串行化。
- **建议**：T05 依赖改为 `T03、T04`；T12 依赖改为 `—`（或 T01，如需 fronted 与契约同一时点起步）；T13 依赖改为 `T03、T04`（若 T13 需要 error-response，T01 已含在链上）。更新依赖图第 149 行。
- **分级**：SHOULD

### F06 — 至少 4 个 task 超过 0.5 天
- **位置**：tasks.md T05、T10、T15、T17（T07 边界）
- **问题**：
  - T05：父 POM + mvnw + enforcer + 7 个子模块 pom + 8 组 record + networknt 校验器 + 资源复制插件配置。
  - T10：3 个端点 + `SseEmitter` 事件编码 + `ConfirmationTokenService`（过期/一次性/argsDigest）+ `RunOrchestrator`（计划执行、低风险自动执行、高风险生成 UI Schema、确认后重校验、经 `ToolGatewayClient` 执行）+ 15s ping。
  - T15：7 组件 props Zod + `SchemaRenderer` + 注册表 + 7 个 antd 桌面实现 + `UnknownComponent` + `check-registry.mjs` + lint 脚本接线。
  - T17：`useAgentRun`（SSE → Query cache、replace/patch 合并、状态机）+ `useSubmitAction` + `AgentChatPanel` + `AgentPage` + 路由 + 首页入口 + pageContext Zod + 手工验收截图。
- **建议**：
  - T05 → T05a / T05b（见 F04）。
  - T10 → T10a `ConfirmationTokenService` + `RunOrchestrator`（application 层，验收：自检日志显示 Token 过期/重放被拒）；T10b SSE 控制器 + GET run + ping（验收：spec §6.2 第 7–9 条）。
  - T15 → T15a `types.ts` + `SchemaRenderer` + `componentRegistry` + `UnknownComponent` + `check-registry.mjs` + `Card` / `ResultCard`；T15b `Form` / `Table` / `ConfirmationCard` / `OrderCard` / `RefundConfirmCard`。
  - T17 → T17a `features/agent-chat`（hooks + Panel，验收 typecheck + 对示例 SSE 帧的 cache 状态断言）；T17b `pages/agent` + 路由 + 首页入口 + 手工验收。
- **分级**：SHOULD

### F07 — 确认时"订单状态"重校验的实现路径未定义，存在 Runtime 直连领域服务的红线风险
- **位置**：spec.md §4.1 第 73 行（"校验 runId/actionId/未用/未过期/argsDigest/权限/订单状态"）；tasks.md T10「目标」（"确认时重校验后经 `ToolGatewayClient` 执行"）
- **问题**：`agent-safety.md` §3 要求重校验订单状态，`project-structure.md` §2 / 红线 5 禁止 `agent-runtime` 依赖 `domains/*`。计划未说明 Runtime 如何得知订单状态；最省事的实现（注入 order-service bean）即触发红线，`check-module-deps` 会在 T11 才报错。
- **建议**：T10 目标明确"订单状态重校验 = 经 Gateway 再次调用低风险工具 `refund.eligibility.check`（或 `order.detail.get`），结果不符则 `run.failed{CONFIRMATION_REJECTED}`"，并将该调用计入审计。
- **分级**：SHOULD

### F08 — `ToolHandler` / `ToolResolver` 接口归属模块未定，按字面理解会形成 Maven 循环依赖
- **位置**：tasks.md T06（"进程内接口 `ToolResolver` 供 Gateway 取寻址信息"）、T07（"实现 … 进程内 `ToolHandler`"）、T08（"`ToolResolver` 寻址 → `ToolHandler` 调用"）
- **问题**：若 `ToolHandler` 定义在 `tool-gateway`，则 `domains/*` → `tool-gateway`；而 `project-structure.md` §2 又写 `tool-gateway → domains/*`，两者同时成立即循环，Maven 拒绝构建。另外 `tool-gateway → tool-registry`（为取 `ToolResolver`）在 project-structure 依赖图中未出现（仅 `06-backend-module-spec.md` "寻址表由 Registry 内部接口提供"隐含允许）。
- **建议**：在 T05 输出中明确：`ToolHandler` SPI 与 `ToolResolver` 接口放 `contracts-java`（或新增极薄 `platform-spi` 模块）；`tool-gateway` 通过 Spring 注入 `List<ToolHandler>`，其 pom **不**依赖 `domains/*`；只有 `app` 依赖全部模块。将此依赖方向写入 spec §2.2，并在 T18 更新 `project-structure.md` §2 依赖图（加 `tool-gateway → tool-registry(ToolResolver)`）。
- **分级**：SHOULD

### F09 — 多条验收缺少可执行探针
- **位置**：spec.md §6.2 第 9、10 条；tasks.md T01「验收」
- **问题**：
  - §6.2 第 9 条"refund-service 内退款记录仍为 1 条"——内存存储、无查询端点，无法从外部断言。
  - §6.2 第 10 条"不含 `X-User-Id` 以外的用户输入原文"——"用户输入原文"不可 grep。
  - T01 验收"`intent-request` 示例即用户原文中的 JSON"——该 JSON 不在 spec / tasks 中，评审与编码 Agent 均无法核对。
- **建议**：第 9 条改为"经 Gateway 调 `refund.status.get`（或 `order.detail.get`）返回 `refunds.length == 1`"或"审计日志 `toolId=refund.create status=succeeded` 行数 == 1"；第 10 条改为"`grep -c '帮我把这个订单退款' <日志文件>` 为 0"；T01 将该 JSON 原文粘入 spec §4.1 或直接作为 `examples/intent-request.example.json` 的内容写进 spec。
- **分级**：SHOULD

### F10 — 前端 UI 验收为人工目测（可接受，但需固定脚本）
- **位置**：spec.md §6.3 第 4、5、6 条；tasks.md T17「验收」
- **问题**：三条均需人眼（页面依次出现卡片、`adm-` / `ant-` 类名、UnknownComponent 占位）。spec §3 已将 E2E 排除在门禁外，因此 UI-only 采用人工验收**可接受**；但当前描述无确定步骤（URL query、输入文本、断点宽度取值、截图命名）。
- **建议**：在 spec §6.3 追加"人工验收脚本"：`/agent?page=order-detail&entity=order/10001`、输入文本、DevTools 视口 375 / 1280、`document.querySelectorAll('[class^=adm-]').length` 断言、截图文件名 `deployment/ui-{desktop,mobile,unknown}.png`。第 6 条可改为 Vitest-free 的运行时断言：临时页面渲染固定 JSON 并检查 `console.error` 计数 == 1（UnknownComponent 按 project-structure §1 要求 `console.error`）——注意 §6.3 第 4 条"console.error == 0"与第 6 条"未知 type 渲染占位"不能在同一场景下同时成立，需分开表述。
- **分级**：SHOULD

### F11 — `$defs.request/response` 结构与 `check-contracts` 的示例校验方式不匹配
- **位置**：spec.md §5（tool-search / tool-invoke "`$defs.request` / `$defs.response`"）；tasks.md T03（"示例含全部 10 种事件的数组"）、T04；`.harness/scripts/check-contracts.mjs` 第 67、82 行
- **问题**：check-contracts 用 `ajv.compile(schema)` 对**根 schema** 校验示例。若 tool-search 根只有 `$defs` 而无 `type` / `oneOf`，根 schema 等价于 `{}`，任何示例都通过——验收"8 schemas OK"变成空校验。同理 sse-events 根为 10 事件 `oneOf`（单事件），而 T03 示例是"数组"，会校验失败；或若根改为数组，则 `ui.replace` 消费方引用不便。
- **建议**：T03 / T04 目标写明根结构：tool-search / tool-invoke 根为 `{ "type":"object", "required":["request","response"], "properties": { "request": {"$ref":"#/$defs/request"}, "response": {"$ref":"#/$defs/response"} } }`，示例即请求/响应对；sse-events 根为 `oneOf` 单事件，示例拆成 `sse-events.run-started.example.json` … 10 个文件（check-contracts 第 75 行已支持 `{stem}.*.example.json`）。
- **分级**：SHOULD

### F12 — 权限来源（principal → permissions）未在任何 task 中落位
- **位置**：spec.md §2.2（"鉴权（permission 匹配）"）、§6.2 第 4 条（`user_001` 返回 4 个工具）；tasks.md T06、T08
- **问题**：Registry 搜索按 permission 过滤、Gateway 按 permission 鉴权，都需要知道 `user_001@tenant_001` 拥有哪些 permission（含 `refund:create`）。spec §3 排除 IdP，但没有任何 task 负责内存权限表的定义与归属模块，T06 与 T08 可能各写一份。
- **建议**：在 T05b（contracts-java）或 T06 增加输出 `PrincipalPermissionResolver`（内存表，`application.yml` 非敏感配置），Gateway 与 Registry 共用；spec §6.2 第 4 条补充"`user_002`（无 `refund:create`）搜索返回 3 个工具"作为负例。
- **分级**：SHOULD

### F13 — wiki/api-contracts.md 与 spec 关于领域服务 HTTP 端点不一致
- **位置**：`wiki/api-contracts.md`「领域服务」表（`GET /orders/{id}`、`POST /refunds` 等）；spec.md §3（"MCP / RPC 协议适配；首期 Gateway 只有进程内 adapter"）、§7 第 5 行
- **问题**：wiki 声称领域服务暴露 HTTP 端点，spec 明确首期仅进程内 adapter、领域服务不暴露 HTTP。T18 目标只写"端点表与实际一致"，未点名此处。
- **建议**：T18 目标显式列出"删除或标注 `api-contracts.md` 领域服务 HTTP 端点为「后续 change」"。
- **分级**：SHOULD

### F14 — 组件 / 工具清单与契约文件在 spec 中未枚举；T10 未点名契约文件
- **位置**：spec.md §2.3（"7 个白名单组件"）、§5（"enum = 7 个白名单"）、§2.2（"6 个工具"）；tasks.md T02「输入」、T10
- **问题**：跨文档核对结果：7 组件名（Form / Card / Table / ResultCard / ConfirmationCard / OrderCard / RefundConfirmCard）在 tasks T15 与 `wiki/domain-model.md` 一致；6 工具名在 domain-model.md 与 spec §4.1 出现的 4 个一致；10 事件名在 spec / contracts.md / api-contracts.md 一致；端点路径在 spec / api-contracts.md / 06-backend-module-spec 一致。但 spec 本体从未列出 7 个组件名和 2 个 order 工具名，T02 的输入指向 "spec §2.3" 实际上只能从 wiki 拿到清单，真源漂移。T10 消费 intent-request / action-request / sse-events 三份契约却未列出文件名。
- **建议**：spec §5 ui-schema 行直接写出 7 个 enum 值；spec §2.2 列 6 个 `toolId` 及 risk/confirmation；T10 输入增加三份 schema 文件名。
- **分级**：LOW

### F15 — 事件发射策略在 §4.1 时序中不自洽
- **位置**：spec.md §4.1 第 62–66 行、第 74 行；§6.2 第 7 条
- **问题**：`refund.eligibility.check` 发 `tool.selected` → `tool.completed`（无 `tool.started`）；`refund.preview` 只发 `tool.completed`；确认后的 `refund.create` 发 `tool.started` → `tool.completed`（无 `tool.selected`）。三次工具调用三种模式，编码 Agent 无法推断规则，§6.2 第 7 条的顺序断言也随之含糊。
- **建议**：在 spec §4 前加一段"事件发射规则"：`tool.selected` 每个计划步骤一次；每次 Gateway 调用必发 `tool.started` + `tool.completed`；`ui.replace` 后紧跟 `confirmation.required`。据此修正 §4.1 与 §6.2 第 7 条。
- **分级**：LOW

### F16 — Manifest `description` 的转义 / 限长要求未落到任务
- **位置**：`agent-safety.md` §2 第 5 条、`contracts.md` §5 第 5 条；tasks.md T04、T09
- **问题**：规则要求 `description ≤ 500` 且注入 prompt 前转义；T04 未写 `maxLength: 500`，T09 未写 prompt 构造时的转义。
- **建议**：T04 目标追加 `description.maxLength = 500`；T09 目标追加"`PromptBuilder` 对候选 description 做转义与截断"。
- **分级**：LOW

### F17 — 后端 T09 对 backend-standard §1 的合规性
- **位置**：tasks.md T09；`backend-standard.md` §1、§3、§7
- **问题**：T09 明确 Spring AI 1.1.x `ChatClient`、`internalToolExecutionEnabled(false)`、`LlmClient` 端口、`STRATO_LLM_*` 环境变量、grep 禁 alibaba / langchain4j——与 §1 一致，**合规**。两点小偏差：(a) §3 写"LLM 输出校验失败重试一次后**转人工**"，spec §4.2 写"重试一次仍失败 → `run.failed{TOOL_SELECTION_INVALID}`"，首期以 run.failed 结束是合理简化，但应在 spec 中标注为有意偏差；(b) `STRATO_LLM_MODEL` 未列在 §7，属新增变量，T18 应同步 rule。
- **建议**：spec §4.2 加一句"首期'转人工' = 结束 Run 并返回 `TOOL_SELECTION_INVALID`，人工介入为后续 change"；T18 将 `STRATO_LLM_MODEL` 补入 backend-standard §7。
- **分级**：INFO

### F18 — Harness L1 记忆与本 change 前提不一致（非本 change 缺陷）
- **位置**：`CLAUDE.md`（"11 阶段流程"、"`pnpm run ci` = typecheck + lint + format:check + **test** + build"、"vitest `numTotalTests > 0`"）；`dev-workflow.md`（8 阶段）；spec.md §2.4（"8 阶段产出物"）、§3（测试不纳入门禁）；`fronted/package.json` `ci` 脚本无 test
- **问题**：spec 与 dev-workflow、package.json 一致，是 CLAUDE.md 过时。记录以免下一轮评审再被误判。
- **建议**：另起 chore change 修正 CLAUDE.md；本 change 不处理。
- **分级**：INFO

## Verdict

**REVISION REQUIRED**

| 分级 | 数量 | 编号 |
|---|---|---|
| MUST FIX | 4 | F01、F02、F03、F04 |
| SHOULD | 9 | F05、F06、F07、F08、F09、F10、F11、F12、F13 |
| LOW | 3 | F14、F15、F16 |
| INFO | 2 | F17、F18 |

回退路径：阶段 2 → 阶段 1（评审轮次 1/3）。修订后请以 `spec_review_v2.md` 复审，本文件保留。
