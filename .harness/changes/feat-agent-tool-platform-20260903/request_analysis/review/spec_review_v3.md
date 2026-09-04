# Plan Review v3 — feat-agent-tool-platform-20260903

- mode: plan
- reviewer: expert-reviewer (independent sub-agent, round 3/3)
- date: 2026-09-03
- targets:
  - `.harness/changes/feat-agent-tool-platform-20260903/request_analysis/spec.md`（v3）
  - `.harness/changes/feat-agent-tool-platform-20260903/request_analysis/tasks.md`（v3）
- rules / specs consulted: `expert-reviewer/SKILL.md`、`request-analysis/SKILL.md`、`project-structure.md`、`contracts.md`、`agent-safety.md`、`backend-standard.md`、`coding-standard.md`、`00-contract-spec.md`、`05-styling-spec.md`、`06-backend-module-spec.md`、`wiki/domain-model.md`、`wiki/api-contracts.md`、`wiki/architecture.md`；仓库实况：`.harness/scripts/{check-contracts,check-module-deps,ci,mvn,harness-doctor}.mjs`、`.harness/package.json`、`fronted/.oxlintrc.json`、`fronted/package.json`、`fronted/tsconfig*.json`、`fronted/vite.config.ts`、`fronted/scripts/check-deps.mjs`、`fronted/src/**` 现状、`.harness/contracts/`（当前为空）、`backed/`（当前为空）；上一轮：`review/spec_review_v2.md`。
- 独立性声明：未阅读编码 Agent 的任何自评（未打开 `summary.md`、`coding/`、`ci_result/`）；结论只基于 spec.md / tasks.md 文本、规则文件与仓库实况；未修改 spec.md / tasks.md。

## v2 Findings Disposition

| # | 状态 | 依据（引文） |
|---|---|---|
| N01 platform-spi JsonNode vs `com.fasterxml` grep | **RESOLVED** | spec §2.2："`platform-spi`：接口层，**零 Spring 依赖，允许 Jackson**"；§6.2 末条拆为两句："`backed/*/src/main/java/**/domain/` 无输出；`grep -rln "org.springframework" backed/platform-spi/src` 无输出"；T05a 验收同。 |
| N02 `GET /agent/runs/{runId}` 无契约 | **RESOLVED** | spec §5 新增 `run-summary.schema.json` 行，"合计 9 schema、20 example"；T01 目标含 `run-summary`；T13 含 `RunSummary`；`contracts.md` §2、`wiki/api-contracts.md` 第 11 行均已含 `run-summary`。 |
| N03 T05a 依赖行 / 依赖图 | **RESOLVED** | T05a「依赖：—」；依赖图 `T05a ─────→ T05b`，`T04 ─┐ / T03 ─┐` 汇入 T05b；速查 `T05a←—；T05b←T03,T04,T05a`。 |
| N04 T15a 缺 T12；T17a 图/行不一致 | **RESOLVED** | T15a「依赖：T12、T13」；依赖图 `T12 ─┬→ T13` 与 `└─────→ T15a`；T17a「依赖：T14、T16」，图中已无 `T12 → T17a` 边。 |
| N05 权限实现时点 | **RESOLVED** | T05a 输出含 `app/infra/InMemoryPrincipalPermissionResolver.java`、`application.yml`（权限表）、主类 `scanBasePackages="com.strato"`、"pom 依赖全部子模块"；T11 收窄为"补齐非敏感配置 + README"。 |
| N06 `RegistryRegistrar` 机制 | **RESOLVED** | spec §2.2："`ToolManifestSource { List<JsonNode> manifests(); }`（领域模块暴露 Bean，Registry 启动时拉取…）"；Registry "`ApplicationReadyEvent` 时遍历所有 `ToolManifestSource`"；§4.3 409 改为"经 HTTP 再 `POST`"。 |
| N07 端口接口归属 | **RESOLVED** | spec §2.2："端口接口 `LlmClient`、`ToolRegistryClient`、`ToolGatewayClient` 定义在 `application/port/`，首期 infra 为进程内适配"；T09a 输出 `application/port`；T10b 输出 `infra/inprocess/**`。 |
| N08 Device Context 位置 | **RESOLVED** | spec §2.3："`shared/ui/device/{DeviceContext.ts,useDevice.ts}`…经 `@shared/ui` 导出；`app/providers/DeviceProvider.tsx` 只做一次性判定"；T12 输出同。 |
| N09 渲染验收无宿主 | **RESOLVED** | spec §2.3 新增 `pages/schema-playground`（`/dev/schema?example=`）；T15a 输出含该页面与 DEV 路由；T15a / T15b / T16 验收均改为 `/dev/schema?example=…`；T17b 已删除 `?debugSchema`。（实现细节风险见 P03、P08。） |
| N10 argsDigest / formData 合并 | **RESOLVED** | spec §2.2："`argsDigest = SHA-256(计划固定参数的规范化 JSON)`；确认时 `formData` 只允许补入 UI Schema `Form.props.fields[]` 声明的字段名，其余键 → `CONFIRMATION_REJECTED`"。（白名单来源与 §4.1 确认屏组件的衔接见 P01。） |
| N11 审计字段 | **RESOLVED** | spec §6.2："审计行含 9 字段：`runId=`…`principal=`、`argsDigest=`…`traceId=`"；T08 目标 / 验收"审计行 9 字段"。 |
| N12 重试 / 熔断 / 限流 | **RESOLVED** | spec §2.2："按 `execution.maxRetries` 重试，仅当 `idempotency = required` 或 `sideEffect = false`"；§3："Gateway 熔断与限流；首期只做超时与按 Manifest 重试"。 |
| N13 T05b 示例数 | **RESOLVED** | T05b 验收："`selfcheck: contracts 9 schemas, 20 examples OK`"，与 §5 "9 schema、20 example" 一致。 |
| N14 T09 / T10a 超 0.5 天 | **RESOLVED** | 拆为 T09a / T09b、T10a / T10b / T10c；`UiSchemaBuilder` 单列于 T10a。（新的偏大 task 见 P07。） |
| N15 日志 / jar 路径 | **RESOLVED** | spec §6 约定："`java -jar backed/app/target/app.jar > deployment/backend.log 2>&1 &`…日志类断言均对 `deployment/backend.log`"；spec §2.2 / T05a `<finalName>app</finalName>`。（`deployment/` 相对基准见 P10。） |
| N16 前端脚本运行器 | **RESOLVED** | spec §2.3 / §6.3："`pnpm -C fronted exec vite-node scripts/verify-examples.ts`"；T12 目标安装 `vite-node`。（lint 兼容性见 P06。） |
| N17 自检开关 | **RESOLVED** | spec §2.2："`SelfCheckRunner`（启动自检，`strato.selfcheck.enabled` 默认 true，README 说明生产关闭）"；T05a `application.yml` 含该键。 |
| N18 wiki / 06-spec 漂移 | **RESOLVED** | `wiki/architecture.md` 第 40 行现为 "`tool.selected` → `tool.started` → `tool.completed`"；`06-backend-module-spec.md` 第 4 行模块清单含 `platform-spi`。 |
| N19 错误码 / 缺头 | **RESOLVED** | spec §4.2 新增 HTTP `error-response.code` 六值表；§6.2："缺 `X-Tenant-Id` 调 `POST /agent/runs` → 401 `UNAUTHENTICATED`"；T10c 目标"缺头 → 401；`@RestControllerAdvice` 输出 §4.2 错误码"。 |
| N20 `tool.selected` 措辞 | **RESOLVED** | spec §4.0："**每次工具调用**（含确认后的重校验）在 `tool.started` 前发一次 `tool.selected`"。 |
| N21 "4 步计划" | **RESOLVED** | spec §2.2："`steps[]` 为 3 个工具步骤，`refund.create` 步骤 `requiresConfirmation = true`"；T09b 验收 "`selfcheck: plan 3 steps, step3 requiresConfirmation OK`"。 |
| N22 `-Xlint:all -Werror` | **RESOLVED** | T05a 目标："父 POM（Java 21、Boot 3.5.x BOM、Spring AI 1.1.x BOM、`-Xlint:all -Werror`、spotless、enforcer ≥ 21）"。 |
| N23 红线 3 自相矛盾 | **RESOLVED**（Harness 侧） | `project-structure.md` §4 红线 3 现为"前端在 `app/router/` 与 `pages/` 之外声明路由…"，与 §1 及 T15a / T17b 修改 `app/router/router.tsx` 一致。 |
| N24 `idempotencyKey` 格式 | **RESOLVED** | spec §2.2 与 T09a 目标均写 "`idempotencyKey = {runId}-{toolId}-{seq}`"。 |

24 / 24 RESOLVED，0 PARTIALLY，0 NOT RESOLVED。

## Checklist

### plan（expert-reviewer SKILL）
- [x] 「非目标」章节存在且非空 —— spec §3 共 13 条，含熔断 / 限流、进程内适配、转人工简化等有意舍弃项。
- [x] 每条验收标准都可被命令或断言校验 —— §6.1 / §6.2 / §6.4 全部为命令 + 退出码 / grep 计数 / HTTP 状态 / Schema 校验；§6.3 第 4 条为固定 5 步人工脚本（URL、视口、`querySelectorAll` 断言、截图文件名）。tasks 24 个 task 的验收均为命令 / grep / HTTP / 文件或引用上述脚本；唯一软化措辞是 T10b "评审核对"（P14，LOW）。个别命令的可执行性瑕疵见 P10、P11（LOW），不影响可校验性本身。
- [x] 风险章节列出 ≥1 个失败模式与缓解措施 —— spec §7 共 9 行，每行含影响 + 缓解；agent-safety 相关 3 行（LLM 越权、Token 重放 / formData 注入、职责渗透）。
- [x] 每个 task 标注所属端，且 contracts task 排在依赖它的 task 之前 —— 24 个 task 均有「所属端」（contracts 4、backed 11、fronted 8、harness 1）；T01–T04 编号与 Phase 顺序均先于全部消费者。依赖行的两处遗漏（T13 缺 T01；T04 验收隐含 T03）不破坏"排在之前"，见 P04。
- [x] 涉及跨端结构的 task 列出对应契约文件 —— T01–T04、T05b、T06、T08、T10a、T10c、T13、T15a 均点名 schema；`GET /agent/runs/{runId}` 已有 `run-summary`。
- [ ] 每个 task 工作量 ≤ 0.5 天 —— T15a（渲染器 + 注册表 + types.ts + 2 组件 + playground 页面 + DEV 路由 + check-registry 脚本 + lint 接入）明显超出；T05a 偏重。见 P07（SHOULD）。

### request-analysis SKILL
- [x] spec.md 含 7 个强制章节 —— 背景 / 范围 / 非目标 / 核心场景 / 契约影响 / 验收标准 / 风险与权衡。
- [x] 每个 task 含六要素 —— 逐条核对 T01、T02、T03、T04、T05a、T05b、T06、T07、T08、T09a、T09b、T10a、T10b、T10c、T11、T12、T13、T14、T15a、T15b、T16、T17a、T17b、T18 共 24 个，均有 目标 / 所属端 / 输入 / 输出 / 验收 / 依赖。
- [x] 验收标准全部可程序化校验 —— 同上。
- [x] 「非目标」非空。
- [x] 「契约影响」列出文件名 —— §5 表 9 个 schema + 20 个示例文件名。

### 依赖行 ↔ 依赖图 ↔ 速查行
逐条比对 24 条速查项与各 task「依赖」行：**24 / 24 一致**。速查行 / 依赖行与 ASCII 图比对：23 / 24 一致；唯一差异是 `T13←T03` 在图中无对应边（Phase C 只画了 `T12 ─┬→ T13`，而其他跨 Phase 边如 `T10c → T14`、`T11 → T17b` 均已画出）。见 P04。

### agent-safety 逐项
- §1 四面：Runtime 不直连领域（经 `ToolGatewayClient` 端口）；Registry "无转发端点、无出向 HTTP 客户端"（T06 grep `RestClient|WebClient|HttpClient`）；Gateway 不做规划；状态机在 Runtime。**符合**。
- §2 发现：`DomainRouter` 先路由；Registry 按 tenant / permission / status 过滤；响应项恰六字段（`additionalProperties: false`）；`description.maxLength = 500` + `PromptBuilder` 转义截断；`ToolSelectionValidator` 候选内校验。**符合**。
- §3 确认：Token 绑定 runId / actionId / argsDigest / 10 分钟 / 一次性；确认时重校验权限、订单状态（经 Gateway 再调 `eligibility.check`）；`formData` 键白名单。**符合**（白名单来源与确认屏组件的衔接见 P01）。
- §4 前端：只渲染白名单、Zod 校验、`actionId` 提交；`React.lazy` 固定路径；`pageContext` 后端不信任。**符合**。
- §5 Gateway：输入校验 → 鉴权 → 幂等 → 寻址 → 超时 / 重试 → 输出校验 → 脱敏 → 审计（9 字段）。**符合**；"用户与 Agent 双重鉴权"中 Agent 侧未着墨，见 P13（LOW）。
- §6 流式：§4.0 末条明确不透传推理原文、参数原文、堆栈、凭据；§6.2 grep 用户原文为 0。**符合**。
- antd 仅在 `shared/ui/**`：spec §6.3 第 2 条 grep 无例外；`.oxlintrc.json` override 仅放开 `src/shared/ui/**`。**符合**。

### backend-standard §1 / §7
Java 21、Boot 3.5.x、Spring AI 1.1.x（`internalToolExecutionEnabled(false)`）、`-Xlint:all -Werror`、spotless、enforcer、`mvnw`、`mvn verify` 门禁：T05a / T09b 覆盖。三个 `STRATO_LLM_*` 环境变量 + 缺失回退 WARN：spec §2.2；`X-Tenant-Id` / `X-User-Id` 缺头 401：T10c。**符合**。

### contracts 根结构 vs `check-contracts.mjs`
`sse-events` 根 `oneOf` 10 事件，示例 `sse-events.{run-started,…}.example.json` 命中脚本第 75 行 `e.startsWith(`${stem}.`)`；`tool-manifest.refund-create.example.json`、`ui-schema.result.example.json` 同理；9 个 stem 互不为前缀，无串扫。跨文件 `$ref`（`run-summary → ui-schema`、`sse-events → ui-schema`）在脚本中先 `addSchema` 全部再 `compile`，可解析。**可校验**。

### 跨文档一致性
- 7 组件：spec §2.3、§4.1 `clientCapabilities.components`、T02、`domain-model.md`「UI Schema」名称与顺序一致。
- 6 工具：spec §2.2 表、`domain-model.md`「首期领域与工具」、`api-contracts.md` 领域服务表一致；risk / confirmation 一致。
- 9 schema / 20 example：spec §2.1、§5、§6.1（`9 schemas OK`）、§6.2（selfcheck 9 / 20）、T04、T05b、T18（"9 个文件"）、`contracts.md` §2（9 行）一致；前端 16 = 1+1+2+1+10+1 正确。
- 端点：Runtime 3、Registry 3、Gateway 1，spec / `api-contracts.md` / `06-backend-module-spec.md` 一致。
- 事件名 10 个：spec T03、`contracts.md` §2、`api-contracts.md` 一致。
- 错误码：仅 spec §4.2 定义（wiki 不重复），内部一致；SSE 3 值 enum 的覆盖面见 P12。
- Run state 6 值：spec §5 与 `domain-model.md` 一致。

### spec §2.4 声明核验
逐项对照实际文件：`project-structure.md` §2 含 platform-spi 与依赖矩阵、红线 5 含 tool-gateway、红线 3 已改为"`app/router/` 与 `pages/` 之外"——**真**；`05-styling-spec.md` 主题封装 `shared/ui/theme/AppThemeProvider.tsx`——**真**；`06-backend-module-spec.md` 模块清单含 platform-spi——**真**；`backend-standard.md` §7 含 `STRATO_LLM_MODEL`——**真**；`contracts.md` §2 含 `run-summary`——**真**；`check-module-deps.mjs` 覆盖五模块——**真**；`wiki/architecture.md` 第 40 行事件序列含 `tool.started`、依赖图含 platform-spi——**真**；`wiki/api-contracts.md` 领域服务进程内 + `run-summary`——**真**。未发现虚假陈述。

### DEV-only playground 合规性
`pages/schema-playground` + `app/router/router.tsx` 中按 `env.DEV` 注册路由：红线 3 允许在 `app/router/` 与 `pages/` 声明路由；`01-page-spec.md` 允许页面做参数解析与组合 shared UI；`pages → shared` 方向合法；`env.DEV` 已由 `shared/config/env.ts` Zod 校验导出。**不违反任何规则**。实现层面的三个具体障碍（lint `../../*`、无 `.harness` 别名、Vite `server.fs.allow`）见 P03。

## New Findings

### P01 — `formData` 键白名单来源为 `Form.props.fields[]`，但 §4.1 确认屏不含 `Form` 组件
- **位置**：spec.md §2.2 第 33 行（"`formData` **只允许**补入 UI Schema `Form.props.fields[]` 声明的字段名，其余键 → `CONFIRMATION_REJECTED`"）；§4.1 第 121 行（"UiSchemaBuilder 生成 OrderCard + RefundConfirmCard + action confirm-refund"）、第 125 行（`formData:{reason:"DAMAGED"}`）；tasks.md T02 目标（"`Form.props.fields[]` 定义字段名与类型"）、T10a 目标（"`formData` 键白名单来自 `Form.props.fields[]`"）；`05-styling-spec.md`（"`Form` 封装：字段定义来自契约 `props.fields[]`，提交只回传 `formData`"）
- **问题**：三处都把白名单来源限定为类型为 `Form` 的组件，而主链路确认屏被描述为 `OrderCard + RefundConfirmCard`，没有 `Form`。若 `ui-schema.example.json`（T02）与 `UiSchemaBuilder`（T10a）按 §4.1 字面生成，白名单为空，`reason` 成为"白名单外键" → `CONFIRMATION_REJECTED`，§6.2 第 10 条与 §6.3 第 5 步不可达。存在合规解法（确认屏加入 `Form{fields:[reason]}`，`RefundConfirmCard` 只做摘要），但需偏离 §4.1 的组件清单，且 §6.3 第 5 步"选原因"落在哪个组件上未定。
- **建议**：二选一并三处同步：(a) §4.1 第 121 行与 `ui-schema.example.json` 改为 `OrderCard + RefundConfirmCard + Form{fields:[{name:"reason", type:"select", options:[…]}]} + action`；或 (b) 白名单来源改为"当前屏内所有可收集字段的组件声明的字段（`Form.props.fields[]` 与 `RefundConfirmCard.props.fields[]`）"，并在 T02 为 `RefundConfirmCard.props` 定义 `fields[]`。阶段 3 编写 T02 示例前必须落定；如采用 (a) 请在 coding_report 标注对 §4.1 的偏差。
- **分级**：SHOULD（临界 MUST FIX：按 §2.2 + §4.1 字面实现会使核心验收不可达，但存在与全部规则兼容的解法）

### P02 — `shared/ui/generate` 以 T13 的 `UiSchemaSchema` 为输入，存在 shared → entities 运行时依赖风险
- **位置**：tasks.md T15a 输入（"T13 `UiSchemaSchema`"）、目标（"`types.ts`、`SchemaRenderer`"）；spec §2.3（`shared/ui/generate/types.ts`）；`05-styling-spec.md`（"`types.ts` # 各组件 props 的 Zod 投影"）；`fronted/scripts/check-deps.mjs` 第 16 行（`'shared/': ['@app/', '@pages/', '@features/', '@entities/']`）；`coding-standard.md` §2（"`entities/{x}/model/types.ts` 是该实体类型的唯一真源；其他模块只 import，不重复定义"）
- **问题**：`UiSchemaSchema` 是 Zod 值，若 `SchemaRenderer` 以值形式 `import { UiSchemaSchema } from '@entities/agent-run'`，`check-deps.mjs` 判违规，`pnpm lint` 失败（T15a 验收、§6.3 第 1 条）。若改为在 `shared/ui/generate/types.ts` 再定义一份整屏 Zod，则违反"唯一真源"。计划未写明两者的分工。
- **建议**：spec §2.3 / T15a 目标加一句："`SchemaRenderer` 只 `import type { UiSchema } from '@entities/agent-run'`（类型导入允许跨层），接收**已由调用方 Zod 校验**的对象；`shared/ui/generate/types.ts` 只定义各组件 `props` 的 Zod（契约中 `props` 为自由 JSON，此处为前端内部约束），各封装组件在渲染时 `parse` 自己的 `props`。"
- **分级**：SHOULD

### P03 — playground 从 `.harness/contracts/examples/` JSON import 与现有 lint / 别名 / Vite 配置冲突，所需配置改动不在任何 task 输出中
- **位置**：spec.md §2.3（"从 `.harness/contracts/examples/` 以 JSON import 读取示例"）；tasks.md T15a 输出（无 `vite.config.ts`、`tsconfig.app.json`、`.oxlintrc.json`）；`fronted/.oxlintrc.json` 第 66–70 行（`"group": ["../../*"]` 禁止）；`fronted/tsconfig.app.json` paths / `fronted/vite.config.ts` alias（仅 `@`、`@app`、`@pages`、`@features`、`@entities`、`@shared`，全部指向 `src/`）；Vite `server.fs.allow` 默认为工作区根（仓库根无 `package.json` / `pnpm-workspace.yaml`，因此工作区根 = `fronted/`）
- **问题**：从 `fronted/src/pages/schema-playground/` 到 `.harness/contracts/examples/` 需要 `../../../../.harness/...`，被 `no-restricted-imports` 的 `../../*` 拦截 → `pnpm lint` 失败；无别名可用；即便通过 lint，`pnpm dev` 下 Vite 会以 `/@fs/` 提供该文件并因超出 `server.fs.allow` 返回 403 → §6.3 第 2–4 步页面无法加载示例。三项配置改动均未列入 T15a 输出。
- **建议**：T15a（或 T12）输出追加：`fronted/tsconfig.app.json` paths 增加 `"@contracts/*": ["../.harness/contracts/*"]`；`fronted/vite.config.ts` alias 增加 `'@contracts': alias('../.harness/contracts')` 并设置 `server.fs.allow: [alias('.'), alias('../.harness/contracts')]`；`project-structure.md` §1 别名清单补 `@contracts/*`（只读、仅 `*.json`）。页面内写 `import confirm from '@contracts/examples/ui-schema.example.json'`。
- **分级**：SHOULD

### P04 — T13 依赖行缺 T01；依赖图缺 `T03 → T13` 边；T04 验收 "9 schemas OK" 隐含 T03 未在依赖中
- **位置**：tasks.md T13「输入：T01–T03 契约与示例」「依赖：T03、T12」；速查 `T13←T03,T12`；依赖图 Phase C 行（`T12 ─┬→ T13`，无 T03 / T01 入边）；T04「依赖：T01」、验收（"check-contracts 输出 `9 schemas OK`"）；T13 验收（"`16 examples OK`"含 intent / action / run-summary / error 4 个 T01 示例）
- **问题**：T01 不是 T13 的祖先（T01 → T04 → T05b 链不经过 T13），按依赖行调度 T13 可在 T01 完成前启动，`16 examples OK` 不可达。T03 不是 T04 的祖先，T04 完成时可能只有 8 个 schema，"9 schemas OK" 不成立。图中其他跨 Phase 边均已画出，唯 `T03 → T13` 缺失。Phase 顺序"contracts → backed → fronted"在实践上掩盖了前者，但依赖行是调度真源。
- **建议**：T13 依赖改为 `T01、T03、T12`，速查与图同步（加 `T01, T03 → T13`）；T04 依赖改为 `T01、T03`，或验收改为"`pnpm -C .harness check-contracts` 退出码 0 且对本 task 3 个 schema 及 4 个示例输出 ✓"。
- **分级**：SHOULD

### P05 — `refund.create` 幂等自检会改变领域内存状态，可能使 §6.2 第 13 条 / 主链路不可达
- **位置**：tasks.md T07 目标（"`refund.create` 按 `idempotencyKey` 去重；`SelfCheckRunner` 增加幂等自检项"）、验收（"`selfcheck: refund.create idempotent OK`"）；spec §6.2 第 13 条（"`refund.status.get{orderId:"10001"}` 返回 `refunds.length == 1`"）、第 12 条（"`grep -c "toolId=refund.create .*status=succeeded"` 为 1"）；spec §2.2（`strato.selfcheck.enabled` 默认 true）；T07 目标（"订单 10001 / 10002 / 10003"）
- **问题**：自检要真正调用 `refund.create` 两次才能证明幂等；若使用订单 10001，启动后该订单已有 1 笔退款，主链路的 `eligibility.check` 可能判不可退，且第 13 条会得到 `refunds.length == 2`。若自检经 Gateway 调用（T07 之后接入），审计行 `toolId=refund.create status=succeeded` 也会多于 1。自检使用的订单与调用路径均未指定。
- **建议**：T07 目标补一句："幂等自检直接调用 `ToolHandler`（不经 Gateway、不产生审计行），固定使用订单 `10003`，`10001` / `10002` 保留给验收链路"；spec §2.2 `SelfCheckRunner` 条目同步。
- **分级**：SHOULD

### P06 — `fronted/scripts/verify-examples.ts` 的输出方式与 `no-console` 配置冲突
- **位置**：spec.md §6.3 第 3 条（"`vite-node scripts/verify-examples.ts` 输出 `16 examples OK`"）；tasks.md T13 输出 / 验收；`fronted/.oxlintrc.json` 第 40–45 行（`no-console` 仅 allow `warn` / `error`）、第 91–99 行（override 只对 `scripts/**/*.mjs` 关闭 `no-console`）；`fronted/package.json` `lint` = `oxlint --deny-warnings`（扫描整个 `fronted/`）
- **问题**：`.ts` 脚本用 `console.log('16 examples OK')` 会被 lint 报错 → `pnpm -C fronted run ci` 失败（§6.3 第 1 条、T13 之后所有 ci 验收）。`scripts/check-registry.mjs` 因是 `.mjs` 不受影响。
- **建议**：T12 或 T13 输出追加 `fronted/.oxlintrc.json`：override `files` 改为 `["scripts/**/*.{mjs,ts}"]`；或脚本改用 `process.stdout.write`。同时注意 `tsconfig.node.json` 只 include `vite.config.ts`，该脚本不会被 `typecheck` 覆盖——可接受，但建议在 T13 验收注明"该脚本以 `vite-node` 运行时校验，不在 typecheck 范围"。
- **分级**：SHOULD

### P07 — T15a 明显超过 0.5 天；T05a 偏重
- **位置**：tasks.md T15a 目标（`types.ts`（7 组件 props Zod）+ `SchemaRenderer` + `componentRegistry`（lazy 双表）+ `UnknownComponent` + 桌面 `Card` / `ResultCard` + `pages/schema-playground` + DEV 路由 + `check-registry.mjs` + lint 接入）；T05a 目标（父 POM + `mvnw` + 8 个模块 pom + 6 个 SPI 类型 + 主类 + actuator + 权限实现 + `application.yml` + `SelfCheckRunner` 骨架 + 版本核对）
- **问题**：T15a 聚合了渲染核心、两个业务组件、一个页面、一个脚本和构建接入五类工作，估算 1–1.5 天；T05a 含 Maven Central 版本核对与首次构建调试，估算 0.5–1 天。
- **建议**：拆出 T15c「playground 页面 + DEV 路由 + `check-registry.mjs` + lint 接入」（依赖 T15a），T15b / T16 的 `/dev/schema` 验收改依赖 T15c；T05a 可把 `InMemoryPrincipalPermissionResolver` + `application.yml` + `SelfCheckRunner` 骨架挪到 T05b 前的 T05a'，或接受 T05a 为唯一 ≤ 1 天的例外并在 tasks.md 头部注明。
- **分级**：SHOULD

### P08 — `?example=unknown` 的夹具来源与 Zod 拦截路径未定义
- **位置**：spec.md §2.3（"`/dev/schema?example={result|confirm|unknown}`，从 `.harness/contracts/examples/` 以 JSON import 读取"）；§6.3 第 4 步；tasks.md T15a 验收（"`?example=unknown` 出现占位且 console.error 恰 1"）；T13 目标（`UiSchema` Zod "与契约逐字段一致"，即 `type` 为 7 值 enum）
- **问题**：含未知 `type` 的示例不能放在 `.harness/contracts/examples/`（会使 `check-contracts` 失败），因此 `unknown` 夹具只能在前端本地；且若 playground 先经 `UiSchemaSchema.parse`，未知 `type` 在到达 `SchemaRenderer` 前就被拒，`UnknownComponent` 路径不可达。
- **建议**：T15a 输出增加 `fronted/src/pages/schema-playground/fixtures/unknown.json`，并写明"playground 对 `unknown` 夹具跳过 Zod，直接以 `UiSchema` 结构传给 `SchemaRenderer`（DEV 专用，允许 `as UiSchema`，需注释说明）"；或让 `SchemaRenderer` 在注册表查找层以 `string` 处理 `type`。
- **分级**：LOW

### P09 — `SelfCheckRunner` 的多次修改不在相应 task 的输出中
- **位置**：tasks.md T05b / T07 / T09b / T10a 目标（"`SelfCheckRunner` 增加…自检项"）与各自输出（仅本模块目录）；T05a 输出 `app/{StratoApplication,SelfCheckRunner}.java`
- **问题**：4 个 task 都要改 `app` 模块文件却未列出，且 `app` 直接 import 各模块内部类会与"跨模块只依赖对方 `api` 包"相冲。
- **建议**：`platform-spi` 增加 `SelfCheck { String name(); void run(); }` 接口，各模块在自己的 `infra/selfcheck/` 提供 Bean（列入各自输出），`SelfCheckRunner` 只遍历 `List<SelfCheck>`。
- **分级**：LOW

### P10 — `deployment/` 相对基准未定；T09b 第二条 grep 缺文件操作数
- **位置**：spec.md §6 约定（"`> deployment/backend.log`"）、§6.3（"截图存 `deployment/`"）；tasks.md T10c 验收（"`deployment/run_events.log`"）、T09b 验收（"`grep -c "selfcheck: invalid toolId rejected OK"` 为 1"）；仓库根无 `deployment/`，change 目录下有 `.harness/changes/feat-agent-tool-platform-20260903/deployment/`
- **问题**：从仓库根执行时 `deployment/` 不存在；T09b 第二条 grep 未指定文件会读 stdin 挂起。
- **建议**：§6 约定写成绝对于仓库根的路径 `.harness/changes/feat-agent-tool-platform-20260903/deployment/backend.log`（或 `export DEPLOY=…` 后统一 `$DEPLOY/backend.log`）；T09b 补文件名。
- **分级**：LOW

### P11 — §6.2 第 17 条 `**` 通配在默认 bash 下无效
- **位置**：spec.md §6.2 第 17 条（"`grep -rln "org.springframework\|com.fasterxml" backed/*/src/main/java/**/domain/`"）；tasks.md T09a 验收（"`domain/` 包无 Spring / Jackson import（`grep`）"）
- **问题**：无 `globstar` 时 `**` 等同 `*`，`backed/<mod>/src/main/java/<一层>/domain/` 匹配不到 `com/strato/<mod>/domain`，且 `backed/domains/<mod>/…` 不在 `backed/*/src` 下；命令因路径不存在而"无输出"，验收空转。`check-module-deps.mjs`（第 2 条）已正确覆盖。
- **建议**：改为 `grep -rln --include=*.java "org.springframework\|com.fasterxml" backed | grep "/domain/"` 无输出，或直接删除该条并保留第 2 条。
- **分级**：LOW

### P12 — SSE `run.failed.data.code` 三值 enum 无通用执行失败码
- **位置**：spec.md §4.2 SSE 表（`CONFIRMATION_REJECTED`、`TOOL_SELECTION_INVALID`、`TOOL_OUTPUT_INVALID`）；tasks.md T03 目标（"`run.failed.data.code` enum = spec §4.2 SSE 表 3 值"）；spec §2.2 Gateway（超时、重试、`DomainException`）
- **问题**：工具超时、重试耗尽、领域异常、Registry 空候选以外的内部错误都没有可用 code；闭合 enum 迫使实现误用 `TOOL_OUTPUT_INVALID` 或违反 Schema。不影响首期验收路径。
- **建议**：增加 `TOOL_EXECUTION_FAILED`（超时 / 领域异常 / 重试耗尽）与 `INTERNAL_ERROR`，T03 enum 改为 5 值。
- **分级**：LOW

### P13 — Gateway "用户与 Agent 双重鉴权"中 Agent 侧与 principal 来源未定
- **位置**：`agent-safety.md` §5（"用户与 Agent 双重鉴权"）；spec.md §2.2 Gateway（仅 "`PrincipalPermissionResolver` 鉴权"）；§6.2 第 7 条（"`user_002` 调 `refund.create` → 403"）；`backend-standard.md` §7（请求头身份）；spec §2.2（Runtime → Gateway 为进程内适配，无 HTTP 头）
- **问题**：进程内调用没有 `X-User-Id` 头，principal 只能来自 `tool-invoke.request.executionContext`；HTTP 直调时头与 body 可能不一致，哪个为准未写；Agent / 服务身份完全未提及也未列入非目标。
- **建议**：spec §2.2 补："Gateway / Registry 以请求体 `principal` / `executionContext.principal` 为准；`/internal/*` 的 HTTP 头仅用于 401 门禁；Agent 服务身份校验为后续 change"，并把后者加入 §3。
- **分级**：LOW

### P14 — T10b 验收 "评审核对" 可命令化
- **位置**：tasks.md T10b 验收（"`agent-runtime/pom.xml` 对 registry / gateway 仅依赖其 `api`（评审核对）"）
- **问题**：Maven 无法按包切分依赖，该断言实为 import 约束，可 grep。
- **建议**：改为 `grep -rn "import com.strato.\(registry\|gateway\).\(application\|domain\|infra\)" backed/agent-runtime/src` 无输出。
- **分级**：LOW

### P15 — T06 未写明注册时按 `tool-manifest.schema.json` 校验 Manifest
- **位置**：tasks.md T06 目标 / 验收；`backend-standard.md` §3（"入站请求体：校验失败返回 `400`"）；T07（6 个 Manifest 从资源文件读取）
- **问题**：领域模块资源里的 6 个 Manifest 不在 `check-contracts` 覆盖范围，若 Registry 注册时不校验，`if high then required` 等约束在运行时失守。
- **建议**：T06 目标补"注册（HTTP 与 `ToolManifestSource`）均经 `SchemaValidator` 按 `tool-manifest.schema.json` 校验，失败 400 / 启动失败"；验收加一个缺 `risk` 字段的反例 → 400。
- **分级**：LOW

### P16 — 零散实现提示（不影响验收）
- **位置**：tasks.md T05a（"7 个子模块 pom" 与单列的 `app` 合计应为 8 个模块 pom）；T05a `-Xlint:all -Werror`（`DomainException extends RuntimeException` 会触发 `[serial]` 警告，需 `serialVersionUID` 或 `-Xlint:all,-serial`）；spec §7（未列 antd-mobile 5 对 React 19 的兼容风险，与 §6.3 第 5 步"console 无 error"相关）；`api-contracts.md` 第 21 行（`GET /tools/{toolId}/versions` 响应为 `tool-manifest[]`，无独立 schema，建议在 spec §5 注明"数组元素按 `tool-manifest`"）；playground 的 `example` query 参数需 Zod 校验（`coding-standard.md` §2）；`UnknownComponent` 的 `console.error` 需带语义前缀（§8）。
- **建议**：编码时逐项处理；不需要改 spec。
- **分级**:INFO

### P17 — Harness 自身：金额单位在两份规则中不一致
- **位置**：`coding-standard.md` §3（"金额：`string`（单位 = "分"）"）vs `wiki/domain-model.md`「隐性约束」（"金额单位为"元"字符串，两位小数，形如 `"128.00"`"）与 `contracts.md` §3 的 `pattern ^-?\d+(\.\d{1,2})?$`；spec §4.1 采用 `"128.00"`
- **问题**：spec 与契约 / wiki 一致（元、两位小数），与 coding-standard 冲突；属规则漂移，非本 change 缺陷。
- **建议**：另起 chore 把 `coding-standard.md` §3 改为"单位 = 元，两位小数字符串，与 `contracts.md` §3 pattern 一致"。
- **分级**：INFO

## Verdict

**APPROVED**

| 分级 | 数量 | 编号 |
|---|---|---|
| MUST FIX | 0 | — |
| SHOULD | 7 | P01、P02、P03、P04、P05、P06、P07 |
| LOW | 8 | P08、P09、P10、P11、P12、P13、P14、P15 |
| INFO | 2 | P16、P17 |

v2 的 24 条全部 RESOLVED。本轮无 MUST FIX：未发现按计划实现必然违反规则或使某条验收不可达的缺陷。7 条 SHOULD 中 P01（`formData` 白名单来源与确认屏组件清单不衔接）临界 MUST FIX，**必须在阶段 3 编写 T02 `ui-schema.example.json` 与 T10a 之前落定**，如偏离 §4.1 组件清单需在 coding_report 显式标注偏差；P02–P06 为具体可执行的落地障碍（FSD 反向依赖、lint / 别名 / Vite 配置、依赖行遗漏、自检副作用、no-console），建议在阶段 3 开工前以 v4 小修订一次性吸收，否则阶段 4 execution 评审将按 MUST FIX 处理其中造成 `pnpm lint` / 验收失败者。本文件与 v1、v2 均保留。
