# Plan Review v2 — feat-agent-tool-platform-20260903

- mode: plan
- reviewer: expert-reviewer (independent sub-agent, round 2)
- date: 2026-09-03
- targets:
  - `.harness/changes/feat-agent-tool-platform-20260903/request_analysis/spec.md`（v2）
  - `.harness/changes/feat-agent-tool-platform-20260903/request_analysis/tasks.md`（v2）
- rules / specs consulted: `expert-reviewer/SKILL.md`、`request-analysis/SKILL.md`、`project-structure.md`、`contracts.md`、`agent-safety.md`、`backend-standard.md`、`coding-standard.md`、`00-contract-spec.md`、`05-styling-spec.md`、`06-backend-module-spec.md`、`wiki/domain-model.md`、`wiki/api-contracts.md`、`wiki/architecture.md`；仓库实况：`scripts/check-contracts.mjs`、`scripts/check-module-deps.mjs`、`scripts/ci.mjs`、`scripts/mvn.mjs`、`fronted/.oxlintrc.json`、`fronted/package.json`、`fronted/scripts/check-deps.mjs`、`fronted/src/**` 现状、`summary.md`；上一轮：`review/spec_review_v1.md`。
- 独立性声明：未阅读任何编码 Agent 自评（`summary.md` 仅用于核对阶段状态与契约清单，未采信其"经验沉淀"作为结论依据）；未修改 spec.md / tasks.md。

## v1 Findings Disposition

| # | 状态 | 依据 |
|---|---|---|
| F01 缺「输入」 | **RESOLVED** | tasks.md 22 个 task（T01–T04、T05a/b、T06–T09、T10a/b、T11–T14、T15a/b、T16、T17a/b、T18）逐条核对，均含 目标 / 所属端 / 输入 / 输出 / 验收 / 依赖 六行。 |
| F02 ThemeProvider antd 例外 | **RESOLVED** | spec §2.3："`shared/ui/theme/AppThemeProvider.tsx`…经 `@shared/ui` 导出；`app/providers/` 只 import 封装"；T12 验收："spec §6.3 第 2 条 grep 无输出（无任何例外）"；`05-styling-spec.md` 第 6 行已改为 `shared/ui/theme/AppThemeProvider.tsx`。全文无 oxlint override 字样。 |
| F03 tool-search 六字段白名单 | **RESOLVED** | spec §2.2："响应项**只含** `toolId`、`version`、`description`、`inputSchema`、`riskLevel`、`confirmation` 六字段"；T04 目标："`additionalProperties: false` 且属性恰为…六字段"；T04 验收含"response 项多一个 `baseUrl`"反例；spec §6.2 第 4 条改为按 `tool-search.schema.json` 的 `response` 校验。 |
| F04 验收依赖 app 却到 T11 才有 | **RESOLVED** | T05a 目标："`app` 最小主类 + actuator"，验收 "`/actuator/health` 含 `"status":"UP"`"；T11 收窄为"完整装配 + application.yml + README"。（残留一处衔接细节见 N05。） |
| F05 依赖行与依赖图不一致 | **PARTIALLY RESOLVED** | T13 依赖改为 `T03`、T12 依赖改为 `—`，与依赖图一致。但 T05a「依赖：T04」而依赖图第 193–194 行仍画 `T03 ──┐ / T04 ──┼→ T05a`，且 T05a「输入」根本不需要任何契约；T15a 缺 T12（见 N03、N04）。 |
| F06 task > 0.5 天 | **PARTIALLY RESOLVED** | T05 → T05a/T05b，T10 → T10a/T10b，T15 → T15a/T15b，T17 → T17a/T17b 均已拆。T09、T10a 仍偏大（见 N14）。 |
| F07 订单状态重校验路径 | **RESOLVED** | spec §2.2："**订单状态重校验经 Gateway 再次调用 `refund.eligibility.check`**，不直连领域服务"；T10a 目标同义；spec §4.1 第 120 行时序含该调用。 |
| F08 ToolHandler 归属 / 循环依赖 | **RESOLVED** | spec §2.2 新增 `platform-spi` 模块承载 `ToolHandler` / `ToolResolver` / `PrincipalPermissionResolver`；`project-structure.md` §2 已含 `platform-spi` 与完整依赖方向；spec §7 第 4 行风险条目对应。 |
| F09 缺可执行探针 | **RESOLVED** | §6.2 第 9 条改为"审计日志 `toolId=refund.create status=succeeded` 行数为 1"；第 10 条改为"`refund.status.get{orderId:"10001"}` 返回 `refunds.length == 1`"；第 12 条改为 `grep -c "帮我把这个订单退款"` 为 0；T01 验收改为与 spec §4.1 JSON `diff`，且该 JSON 已粘入 spec §4.1。（日志文件路径未定，见 N15。） |
| F10 UI 人工验收无固定脚本 | **RESOLVED** | spec §6.3 第 4 条给出 5 步脚本：URL、输入文本、视口 1280 / 375、`querySelectorAll('[class^=ant-]')` / `[class^=adm-]` 断言、4 张截图文件名；"console 无 error"（步骤 3）与"console.error 恰为 1"（步骤 5）已分场景。 |
| F11 `$defs` 根结构与 check-contracts 不匹配 | **RESOLVED** | spec §5 新增「根结构」列：tool-search / tool-invoke 根为 `object {request, response}` 各 `$ref` `$defs`；sse-events 根为 `oneOf` 10 事件，示例拆为 `sse-events.{run-started,…}.example.json` 10 个，与 `check-contracts.mjs` 第 75 行 `e.startsWith(`${stem}.`)` 匹配；T03 验收"11 行 ✓"与脚本输出方式一致。 |
| F12 权限来源未落位 | **PARTIALLY RESOLVED** | spec §2.2 定义 `PrincipalPermissionResolver`（platform-spi）与内存权限表（`user_001` 三权限、`user_002` 两权限）；§6.2 第 4 条补 `user_002` 负例。但**实现类**与 `application.yml` 的产出时点未分配到 T06 之前的任何 task（见 N05）。 |
| F13 wiki 领域服务 HTTP 端点 | **RESOLVED** | `wiki/api-contracts.md`「领域服务（模拟，进程内 ToolHandler，首期不暴露 HTTP）」已与 spec §3 一致。 |
| F14 组件 / 工具清单未枚举、T10 未点名契约 | **RESOLVED** | spec §2.3 列 7 个组件名；§2.2 表列 6 个 toolId + version + risk + confirmation + sideEffect；T10b 输入列 `intent-request` / `action-request` / `sse-events`。 |
| F15 事件发射不自洽 | **RESOLVED** | spec §4.0「事件发射规则」新增；§4.1 三次工具调用均为 `tool.selected → tool.started → tool.completed`；§6.2 第 7 / 8 条序列与之一致（9 个 / 8 个事件）。（措辞小瑕疵见 N20。） |
| F16 description 转义 / 限长 | **RESOLVED** | T04 目标 "`description.maxLength = 500`"；T09 目标 "`PromptBuilder`（候选 description 转义 + 截断 500）"。 |
| F17 转人工偏差 + STRATO_LLM_MODEL | **RESOLVED** | spec §3 末条显式标注对 `backend-standard.md` §3 的有意简化；`backend-standard.md` §7 现已含 `STRATO_LLM_MODEL`。 |
| F18 CLAUDE.md 与 dev-workflow 阶段数不一致 | **RESOLVED**（change 外） | 仓库 `CLAUDE.md` 第 7、23 行现为"8 阶段流程"/"8 阶段定义"，与 `dev-workflow.md` 一致。 |

## Checklist

- [x] 「非目标」章节存在且非空 —— spec §3 共 12 条，新增第 12 条（转人工通道）明确与 rule 的偏差。
- [ ] 每条验收标准都可被命令或断言校验 —— 绝大多数已命令化。但 §6.2 第 15 条（platform-spi 禁 `com.fasterxml`）与 §2.2 的 `JsonNode` 签名互斥，**按构造必失败**（N01）；T05b 验收 "16 examples validated" 与 §5 共 19 个示例不符（N13）；T15a / T15b / T16 的"渲染 … 出现 `ant-card` / `ant-form` / `adm-` 类名"在其依赖满足时没有可运行的宿主（N09）。
- [x] 风险章节列出 ≥1 个失败模式与缓解措施 —— spec §7 共 9 行，每行有影响 + 缓解，agent-safety 相关 3 行。
- [ ] 每个 task 标注所属端，且 contracts task 排在依赖它的 task 之前 —— 22 个 task 均有「所属端」；编号顺序 contracts 先行成立。但 T05a 依赖行与依赖图不一致、T15a 缺 T12 依赖（N03、N04）。
- [ ] 涉及跨端结构的 task 列出对应契约文件 —— T01–T04、T05b、T06、T08、T10a、T10b、T13、T15a 均点名 schema。但 `GET /agent/runs/{runId}` 的响应结构（T10b 产出、T13 `getRun()` 消费）在 spec §5 八个文件中**没有**对应 Schema（N02）。
- [ ] 每个 task 工作量 ≤ 0.5 天 —— T09、T10a 仍明显超出（N14）。
- [x] （request-analysis SKILL 第 4 步）每个 task 含六要素 —— 22 / 22 齐全。
- [x] spec.md 含 7 个强制章节 —— 背景 / 范围 / 非目标 / 核心场景 / 契约影响 / 验收标准 / 风险与权衡。
- [x] 「契约影响」列出文件名 —— §5 表 8 个 schema + 19 个示例文件名。

## New Findings

### N01 — platform-spi 的 `JsonNode` 签名与 §6.2 第 15 条的 `com.fasterxml` grep 互斥，验收按构造必失败
- **位置**：spec.md §2.2 第 22 行（"`ToolHandler` SPI（`toolId`、`version`、`handle(JsonNode args, ExecutionContext) → JsonNode`）"）；spec.md §6.2 第 15 条（"`grep -rln "org.springframework\|com.fasterxml" backed/*/src/main/java/**/domain/ backed/platform-spi/src` 无输出"）；tasks.md T05a 输出 `backed/platform-spi/src/main/java/com/strato/spi/*.java`
- **问题**：`JsonNode` 即 `com.fasterxml.jackson.databind.JsonNode`。`ToolHandler.java` 必然 `import com.fasterxml.jackson.databind.JsonNode`，于是 §6.2 第 15 条对 `backed/platform-spi/src` 的 grep 一定有输出。同一份 spec 的设计与验收自相矛盾；编码 Agent 只能选择违反其一。`project-structure.md` §2 对 platform-spi 的约束是"无 Spring 依赖"，并未禁 Jackson；`backend-standard.md` §2 反而要求自由 JSON 用 `JsonNode`。
- **建议**：二选一并同步 T05a 验收：(a) §6.2 第 15 条拆成两条——`domain/` 包禁 `org.springframework|com.fasterxml`；`platform-spi` 仅禁 `org.springframework`（与 project-structure §2 一致）；(b) 若坚持 platform-spi 零第三方依赖，把 SPI 签名改为 `handle(String argsJson, ExecutionContext) → String`，由 Gateway 负责 JsonNode 转换，并在 spec §2.2 改写。推荐 (a)。
- **分级**：MUST FIX

### N02 — `GET /agent/runs/{runId}` 响应为跨端结构，却无契约文件（project-structure 红线 8）
- **位置**：spec.md §2.2 第 26 行（"三个端点（§4）"）、§5 契约表（仅 8 个文件）；tasks.md T10b 目标（"`GET /agent/runs/{runId}`"）、T13 目标（"`getRun()` 纯 API"）；`wiki/api-contracts.md` 第 11 行（"Run 摘要（state、当前 UI Schema）"）；`project-structure.md` §4 红线 8
- **问题**：该端点由前端 `entities/agent-run/api/agentRunApi.ts` 消费，属前端 ↔ Runtime 跨端结构。spec §5 八个 schema 中没有任何一个描述它（`ui-schema` 只是其中一个字段）。红线 8："跨端数据结构在 `.harness/contracts/` 中无对应 Schema"→ 任一触发即 MUST FIX。T13 也无法为它写 Zod 投影（"与契约逐字段一致"无契约可依）。
- **建议**：(a) 新增 `run-summary.schema.json`（`runId`、`conversationId`、`state` enum 六值、`currentUi?` `$ref` ui-schema、`createdAt` / `updatedAt` date-time）+ `examples/run-summary.example.json`，spec §5 变 9 个文件，§6.1 改为 `9 schemas OK`，T01 或 T03 承接，T05b / T13 数量同步；或 (b) 首期删除 `GET /agent/runs/{runId}` 与 `getRun()`（前端只靠 SSE cache），并在 `wiki/api-contracts.md` 标注为后续 change。任选其一，但不能保持现状。
- **分级**：MUST FIX

### N03 — T05a 依赖行与依赖图不一致，且该依赖并无内容依据
- **位置**：tasks.md T05a「依赖：T04（版本号锁定前先查 Maven Central…）」；T05a「输入：backend-standard §1、project-structure §2、06-backend-module-spec」；依赖图第 193–194 行（`T03 ──┐` / `T04 ──┼→ T05a`）
- **问题**：T05a 的输入不含任何契约，其产出（POM、SPI 接口、最小 app）也不消费 schema；依赖 T04 只会把 Phase B 起点无故推后到 Phase A 全部完成。依赖图更画了 T03 → T05a，与依赖行不同——这正是 v1 F05 指出的同类问题换了位置。括注"查 Maven Central"与 T04 无关。
- **建议**：T05a 依赖改为 `—`（可与 Phase A 并行）；依赖图改为 `T03 ─┐ T04 ─┼→ T05b`，T05a 单独起点 `T05a → T05b`。
- **分级**：SHOULD

### N04 — T15a 缺 T12 依赖；T17a 依赖行与依赖图不一致
- **位置**：tasks.md T15a「依赖：T13」、「输入」未列 T12；T15a 验收"出现 `ant-card` 类名"；T16「依赖：T15b」；依赖图第 198 行 `T12 ──→ T17a`；T17a「依赖：T14、T16」
- **问题**：T15a 实现桌面 `Card` / `ResultCard` 需要 antd 已安装（T12 产出 `fronted/package.json`）；T16 需要 antd-mobile。按依赖行调度，T15a 可在 T12 之前启动而无法编译。依赖图把 T12 挂在 T17a 上，T17a 依赖行又没有 T12——图与行再度不一致。
- **建议**：T15a 依赖改为 `T12、T13`；依赖图改为 `T12 ─┐ T13 ─┼→ T15a`，删除 `T12 → T17a` 边。
- **分级**：SHOULD

### N05 — `PrincipalPermissionResolver` 实现与权限表配置的产出时点晚于其首个消费者
- **位置**：spec.md §2.2 第 22 行（platform-spi 只含接口）、第 28 行（"`application.yml` 只含非敏感配置（含内存权限表）"归 `app`）；tasks.md T05a 输出（仅接口）、T06 验收（"`user_002` 搜索少 1 个工具"）、T08 验收（"`user_002` 调 `refund.create` → 403"）、T11 目标（"`application.yml` 含内存权限表"）
- **问题**：T06 / T08 的验收都要求 `user_001` / `user_002` 权限差异生效，但 (a) 内存实现类没有出现在任何 task 的「输出」中；(b) 承载权限表的 `application.yml` 在 T11 才产出。T06 执行时要么无法通过验收，要么临时自造实现（后续与 T11 冲突）。同理，T05a 的"最小 app"若不预先在 `app/pom.xml` 依赖全部子模块并扫描 `com.strato`，T06 的 HTTP 验收也无宿主。
- **建议**：T05a 输出追加 `backed/app/src/main/java/com/strato/app/infra/InMemoryPrincipalPermissionResolver.java` + `backed/app/src/main/resources/application.yml`（权限表），并写明"`app/pom.xml` 已依赖 8 个子模块、`@SpringBootApplication(scanBasePackages="com.strato")`"；T11 目标改为"补齐其余非敏感配置 + README"。
- **分级**：SHOULD

### N06 — `RegistryRegistrar` 的注册机制未定义，最直觉实现触发 domains → registry 依赖
- **位置**：spec.md §2.2 第 27 行（"启动时经 `RegistryRegistrar` 注册"）、§4.3（"refund-service 启动 → `POST /internal/tool-registry/tools`"）；tasks.md T07 目标 / 验收（"两模块 pom 不含 gateway / registry / runtime 依赖"）；`project-structure.md` §2（`domains/* → contracts-java , platform-spi`）
- **问题**：领域模块不能依赖 `tool-registry`，那么 `RegistryRegistrar` 只能 (a) 在进程内对 `localhost` 自发 HTTP——必须等 `ApplicationReadyEvent` 后 Web 容器就绪，且端口需可配置；或 (b) 经 `platform-spi` 的注册端口（如 `ToolManifestSource` 由领域模块提供，Registry 在启动时拉取）。两种做法在依赖方向、启动顺序、409 语义上都不同，spec 与 T07 均未选定。
- **建议**：spec §2.2 / §4.3 明确一种：推荐 (b) `platform-spi` 增加 `ToolManifestSource { List<JsonNode> manifests(); }`，领域模块实现并暴露 Bean，`tool-registry` 在 `ApplicationReadyEvent` 遍历注册；§4.3 的 409 验收改为经 HTTP 再次 POST 同版本。若选 (a)，写明监听 `ApplicationReadyEvent` 与 `server.port` 读取方式。
- **分级**：SHOULD

### N07 — `ToolRegistryClient` / `ToolGatewayClient` 端口与实现未落到任何 task
- **位置**：`backend-standard.md` §4（"Agent Runtime 只能通过 `ToolGatewayClient` 接口执行工具，通过 `ToolRegistryClient` 接口发现工具"）；spec.md §2.2 第 26 行（"Registry 搜索"）；tasks.md T09 输出（domain / application / infra，未列端口）、T10a 目标（提到 `ToolGatewayClient` 但输出中无该文件）
- **问题**：两个端口接口及其 infra 实现（单进程下是进程内适配还是 HTTP 到 `localhost`）没有归属；这决定 `agent-runtime/pom.xml` 是否依赖 `tool-registry` / `tool-gateway`（`project-structure.md` §2 允许 `(api)` 依赖），也决定 T10a 验收能否在无 Web 容器时执行。
- **建议**：T09 输出增加 `application/port/{ToolRegistryClient,ToolGatewayClient,LlmClient}.java`；T10a 或 T11 输出增加 `infra/{InProcessToolRegistryClient,InProcessToolGatewayClient}.java`（调用对方模块 `api` 包公开用例接口），并在 spec §2.2 写一句"首期进程内适配，HTTP 适配为后续 change"。
- **分级**：SHOULD

### N08 — 端型 Context 放在 `app/providers`，`shared/ui/generate` 无法合法读取（FSD 反向依赖）
- **位置**：spec.md §2.3（"`app/providers/DeviceProvider`：视口宽度 < 768 为 mobile"）；tasks.md T12 输出（`fronted/src/app/providers/DeviceProvider.tsx`）、T15a 目标（"`componentRegistry.ts`（`desktopRegistry` / `mobileRegistry`，按端型…）"）；`fronted/scripts/check-deps.mjs` 第 15 行（`'shared/': ['@app/', …]` 禁止）；`project-structure.md` §1（"端型由 `app/` 层…通过 Provider 下发"）
- **问题**：`SchemaRenderer` / 注册表位于 `shared/ui/generate/`，要按端型选注册表就必须消费 Device 上下文；若该 Context 定义在 `app/providers`，`shared` import `@app/*` 会被 `check-deps.mjs` 判为违规，`pnpm lint` 失败。计划未说明 Context 的定义位置。
- **建议**：T12 输出改为 `fronted/src/shared/ui/device/{DeviceContext.ts,useDevice.ts}`（定义 `'desktop' | 'mobile'` Context 与 Hook，经 `@shared/ui` 导出）+ `fronted/src/app/providers/DeviceProvider.tsx`（仅做一次性判定并 `<DeviceContext.Provider>`）；spec §2.3 同步。
- **分级**：SHOULD

### N09 — T15a / T15b / T16 的渲染类验收在其依赖满足时没有可运行宿主
- **位置**：tasks.md T15a 验收（"渲染 `ui-schema.result.example.json` 出现 `ant-card` 类名；未知 type 渲染占位且 `console.error` 恰 1 次"）、T15b 验收（"出现 `ant-form` 与 `ant-btn-dangerous`"）、T16 验收（"视口 375 渲染 … 出现 `adm-` 类名"）；`?debugSchema=unknown` 定义在 T17b 目标中
- **问题**：这些验收需要一个能把示例 JSON 喂给 `SchemaRenderer` 的页面或脚本，但唯一的调试开关 `?debugSchema` 属于 T17b（依赖 T17a → T16 → T15b → T15a）。T15a 执行时 `pages/agent` 不存在，验收无固定步骤可执行。
- **建议**：把开发用渲染宿主前移：T15a 输出增加 `fronted/src/pages/schema-playground/`（仅 `import.meta.env.DEV` 注册路由 `/dev/schema?example={result|confirm|unknown}`，从 `.harness/contracts/examples/` 经 Vite `?raw`/JSON import 读取），T15a / T15b / T16 验收写为"打开 `/dev/schema?example=…`，`document.querySelectorAll('[class^=ant-card]').length > 0`"；T17b 的 `?debugSchema=unknown` 改为复用该页面或删除。
- **分级**：SHOULD

### N10 — `argsDigest` 与用户 `formData` 的合并规则未定义
- **位置**：spec.md §4.1 第 118–122 行（`formData:{reason:"DAMAGED"}` → `Gateway.invoke(refund.create, {orderId,"amount":"128.00",reason}, …)`）；tasks.md T10a 目标（"`argsDigest` = 工具参数 SHA-256"）；`agent-safety.md` §3（"绑定…工具参数摘要"）、§4（前端不能"修改工具名称或参数"）
- **问题**：签发 Token 时 `reason` 尚未知，确认时 `reason` 由前端提供。若 `argsDigest` 覆盖完整参数则永远不匹配；若不覆盖 `reason`，则需要明确哪些字段允许由 `formData` 补入、如何校验（否则前端可通过 `formData` 注入 `amount`）。spec / tasks 均未写。
- **建议**：spec §4.1 或 T10a 目标增加："`argsDigest` = SHA-256(计划固定参数 JSON 规范化序列化)；`formData` 仅允许补入 UI Schema `Form.props.fields[]` 声明的字段名，其余键拒绝（`CONFIRMATION_REJECTED`）；合并后参数再经 Gateway `inputSchema` 校验"。
- **分级**：SHOULD

### N11 — 审计行字段少于 agent-safety §5 的最小集
- **位置**：spec.md §6.2 第 11 条（"含 `runId=`、`toolCallId=`、`toolId=`、`version=`、`status=`、`durationMs=`"）；tasks.md T08 验收（"审计行含 6 个字段"）；`agent-safety.md` §5（"至少含：`runId`、`toolCallId`、`toolId@version`、`principal`、参数摘要、结果状态、耗时、`traceId`"）
- **问题**：缺 `principal`、参数摘要（`argsDigest`）、`traceId` 三项；spec §7 声称"验收标准全部命令化"但对审计的验收低于规则下限。
- **建议**：§6.2 第 11 条与 T08 验收改为 9 个字段：追加 `principal=`、`argsDigest=`、`traceId=`。
- **分级**：SHOULD

### N12 — Gateway 的重试 / 熔断 / 限流既未实现也未列入非目标
- **位置**：spec.md §2.2 第 25 行（"`ToolHandler` 调用（超时按 Manifest）"）、§3 非目标；tasks.md T08 目标（"超时按 Manifest `execution.timeoutMs`，用 `CompletableFuture.orTimeout`"）；`agent-safety.md` §5（"超时 / 重试 / 熔断 / 限流（按 Manifest）"为必做）；`backend-standard.md` §6；`wiki/domain-model.md`（`execution.maxRetries`）
- **问题**：Manifest 契约含 `maxRetries`，规则把重试 / 熔断 / 限流列为 Gateway 必做，但计划只实现超时，也没有在 §3 明示舍弃。评审 execution 时将无法判定是遗漏还是有意。
- **建议**：T08 目标补"按 `execution.maxRetries` 重试（仅 `idempotency = required` 或无副作用工具）"；§3 追加"熔断 / 限流：首期不实现，后续 change"。
- **分级**：SHOULD

### N13 — T05b 验收的示例数量与 spec §5 不符
- **位置**：tasks.md T05b 验收（"`contracts: 8 schemas loaded, 16 examples validated`"）；spec.md §5「示例文件」列（1+1+1+2+10+2+1+1 = 19 个）；tasks.md T13 验收（"intent / action / ui-schema×2 / sse-events×10 / error"= 15，前端不投影 tool-*，此数正确）
- **问题**：按 §5 清单，后端应加载 19 个示例，验收硬编码 16 永远不成立。若 N02 采纳新增 `run-summary`，则为 9 schema / 20 example。
- **建议**：改为与 §5 一致的数字，或改为"示例数 == `examples/*.example.json` 文件数"的动态断言。
- **分级**：SHOULD

### N14 — T09、T10a 仍超过 0.5 天
- **位置**：tasks.md T09 目标（Run 聚合 + 状态机 + `DomainRouter` + `LlmClient` 端口 + `SpringAiLlmClient` + `RuleBasedLlmClient` + `PromptBuilder` + `ToolSelectionValidator`，共 8 个类含 Spring AI 接入与结构化输出）；T10a 目标（`ConfirmationTokenService` + `RunOrchestrator` + UI Schema 生成 + 确认重校验 + `RunEventSink`）
- **问题**：T09 同时承担领域模型、规则路由与两套 LLM 客户端；T10a 除编排外还隐含"生成 UI Schema（OrderCard + RefundConfirmCard + ResultCard）"这一整块未单列的工作。
- **建议**：T09 → T09a（`Run` 聚合 / 状态机 / `DomainRouter` / 三个端口接口）+ T09b（`SpringAiLlmClient` / `RuleBasedLlmClient` / `PromptBuilder` / `ToolSelectionValidator`）；T10a 拆出 `UiSchemaBuilder`（输入示例 ui-schema 两份，验收"生成物通过 `ui-schema.schema.json` 校验"）。
- **分级**：SHOULD

### N15 — 后端日志文件路径与 jar 文件名未固定
- **位置**：spec.md §6.2 第 3 条（"`java -jar backed/app/target/app.jar`"）、第 12 条（"后端日志文件 `grep -c …`"）；tasks.md T10b 验收（"录事件到 `deployment/run_events.log`"）
- **问题**：Spring Boot 默认 jar 名为 `app-{version}.jar`，需 `<finalName>app</finalName>`；默认日志只到 stdout，"后端日志文件"不存在。
- **建议**：T05a 目标加 `finalName=app`；§6.2 第 3 条改为 `java -jar backed/app/target/app.jar > deployment/backend.log 2>&1 &`，第 9、11、12 条统一对 `deployment/backend.log` grep。
- **分级**：LOW

### N16 — 前端"临时 node 脚本 `Schema.parse`"缺运行器
- **位置**：spec.md §6.3 第 3 条；tasks.md T13 验收、T14 验收（"临时脚本"）
- **问题**：`entities/agent-run/model/types.ts` 是 TS + 路径别名，`node` 无法直接执行；`fronted/package.json` devDependencies 无 `tsx` / `vite-node`。
- **建议**：写明 `pnpm -C fronted exec vite-node scripts/verify-examples.ts`（并把 `vite-node` 或 `tsx` 加入 T12 的安装清单），脚本文件落 `fronted/scripts/`。
- **分级**：LOW

### N17 — 大量验收依赖"启动自检日志"，即在生产代码里内置测试
- **位置**：tasks.md T05b、T07、T09、T10a 验收（"启动自检日志…"）
- **问题**：在 §3 排除单测的前提下这是可接受的权衡，但未约定开关；若无条件执行，生产启动会跑 Token 过期 / 重放等自检并写日志。
- **建议**：spec §2.2 加一句："自检逻辑集中于 `app` 模块 `SelfCheckRunner`，仅在 `strato.selfcheck.enabled=true`（默认 true，README 说明生产关闭）时运行"。
- **分级**：LOW

### N18 — wiki / 下位 spec 与 v2 的小幅漂移
- **位置**：`wiki/architecture.md` 第 40 行（"SSE 推 `tool.selected` / `tool.completed`"，缺 `tool.started`）；`06-backend-module-spec.md` 第 4 行模块清单无 `platform-spi`；tasks.md T18 目标未点名这两处
- **问题**：spec §2.4 声称 wiki 已同步，architecture.md 主体确已含 platform-spi，但该行事件序列与 §4.0 规则不一致；06-spec 的模块清单也未更新。
- **建议**：T18 目标显式列出这两处修正。
- **分级**：LOW

### N19 — HTTP 错误 `code` 枚举与缺头行为未定义
- **位置**：spec.md §6.2 第 5、6 条（409 / 400 只要求"通过 `error-response` 校验"）；tasks.md T08 验收（403）；`backend-standard.md` §7（"所有对外端点默认需要 `X-Tenant-Id` 与用户身份头"）
- **问题**：`error-response.code` 的取值（如 `TOOL_VERSION_CONFLICT`、`REQUEST_INVALID`、`FORBIDDEN`、`UNAUTHENTICATED`）未在 spec 列出，T06 / T08 / T10b 可能各自命名；缺 `X-Tenant-Id` 时应返回 401 亦无验收。
- **建议**：spec §4.2 末尾加一张 HTTP 错误码表（status → code），§6.2 增加"无 `X-Tenant-Id` 调 `POST /agent/runs` 返回 401"。
- **分级**：LOW

### N20 — §4.0 "`tool.selected` 每个计划步骤一次"与确认后重校验调用不自洽
- **位置**：spec.md §4.0 第 78 行；§4.1 第 120 行（重校验 `refund.eligibility.check` 也发 `tool.selected`）；§6.2 第 8 条
- **问题**：重校验不是计划步骤，但 §4.1 / §6.2 都为它发 `tool.selected`。规则文字与序列不一致。
- **建议**：§4.0 改为"每次工具调用（含确认后的重校验）在 `tool.started` 前发一次 `tool.selected`"。
- **分级**：LOW

### N21 — "4 步计划"含义含糊
- **位置**：spec.md §4.1 第 109 行（"计划：eligibility.check, preview, [confirm], create"）；tasks.md T09 验收（"`RuleBasedLlmClient` 对示例 intent 返回 4 步计划"）
- **问题**：`[confirm]` 不是工具；计划是 3 个工具步骤 + 1 个确认标记，还是 4 个步骤对象？影响 T09 验收断言与 `Step` 结构。
- **建议**：明确 `Plan.steps[]` 为 3 个工具步骤，`refund.create` 步骤带 `requiresConfirmation=true`；T09 验收改为"3 步计划且第 3 步 `requiresConfirmation`"。
- **分级**：LOW

### N22 — T05a 未提 `-Xlint:all -Werror`
- **位置**：tasks.md T05a 目标（"spotless、enforcer"）；`backend-standard.md` §1
- **问题**：编译选项是构建门禁的一部分，未列入父 POM 任务。
- **建议**：T05a 目标补 `maven-compiler-plugin` `-Xlint:all -Werror`。
- **分级**：LOW

### N23 — `project-structure.md` 红线 3 与 §1 自相矛盾（Harness 缺陷，非本 change）
- **位置**：`project-structure.md` §1（"`app/` # 启动入口、Provider、**路由声明**"）vs §4 红线 3（"前端 `pages/` 之外声明路由"→ MUST FIX）；现有 `fronted/src/app/router/router.tsx`；tasks.md T17b 输出（修改 `app/router/router.tsx`）
- **问题**：T17b 按 §1 与现状在 `app/router` 加路由，字面上触发红线 3。属规则自身矛盾，记录以免 execution 评审误判。
- **建议**：另起 chore 修正红线 3 措辞为"页面组件在 `pages/` 之外声明"或删除。
- **分级**：INFO

### N24 — `idempotencyKey` 格式仅在 wiki 中定义
- **位置**：`wiki/domain-model.md`（"`{runId}-{toolId}-{seq}`"）；spec.md §4.1 第 122 行（仅写 `idempotencyKey`）；tasks.md T10a
- **问题**：spec / tasks 未复述格式，编码时可能另创；不影响正确性。
- **建议**：T10a 目标加一句"`idempotencyKey = {runId}-{toolId}-{seq}`（domain-model.md）"。
- **分级**：INFO

## §2.4「Harness 修订已落地」核验

逐项对照实际文件：`project-structure.md` §2 含 `platform-spi` 与完整依赖方向、红线 5 含 `tool-gateway`——**真**；`05-styling-spec.md` 主题封装为 `shared/ui/theme/AppThemeProvider.tsx`——**真**；`backend-standard.md` §7 含 `STRATO_LLM_MODEL`——**真**；`check-module-deps.mjs` 检查 `agent-runtime / tool-registry / tool-gateway / platform-spi / contracts-java` 五模块——**真**；`wiki/architecture.md` 含 platform-spi、`wiki/api-contracts.md` 领域服务改为进程内——**真**（残留小漂移见 N18）。未发现虚假陈述。

## Verdict

**REVISION REQUIRED**

| 分级 | 数量 | 编号 |
|---|---|---|
| MUST FIX | 2 | N01、N02 |
| SHOULD | 12 | N03、N04、N05、N06、N07、N08、N09、N10、N11、N12、N13、N14 |
| LOW | 8 | N15、N16、N17、N18、N19、N20、N21、N22 |
| INFO | 2 | N23、N24 |

v1 结论：18 条中 14 条 RESOLVED、3 条 PARTIALLY RESOLVED（F05、F06、F12）、0 条 NOT RESOLVED。回退路径：阶段 2 → 阶段 1（评审轮次 2/3）。修订后请以 `spec_review_v3.md` 复审，本文件与 v1 均保留。
