# Tasks: feat-agent-tool-platform-20260903

> v3.1 — v3 经 `review/spec_review_v3.md` APPROVED；本版吸收其 SHOULD/LOW：T15a 拆出 T15c；T13 / T04 依赖补齐；SelfCheck 接口化；playground 别名与夹具；幂等自检隔离。25 task。T05a 为唯一允许 ≤ 1 天的例外（首次构建含版本核对）。
> 编码顺序：contracts → backed → fronted。每个 task ≤ 0.5 天。所属端：contracts / backed / fronted / harness。

## Phase A — 契约

### T01 基础契约：error-response、intent-request、action-request、run-summary
- **目标**：通用错误（`code` enum = spec §4.2 HTTP 表 6 值）、前端 → Runtime 两个请求、`GET /agent/runs/{runId}` 响应 `run-summary`（`state` enum 6 值，`currentUi?` `$ref` ui-schema）。
- **所属端**：contracts
- **输入**：spec §4.1 请求体原文、§4.2 错误码表、§5 表、`rules/contracts.md`、`specs/00-contract-spec.md`
- **输出**：`.harness/contracts/{error-response,intent-request,action-request,run-summary}.schema.json` + 4 个 `examples/*.example.json`
- **验收**：`pnpm -C .harness run check-contracts` 对这 4 个 schema 及示例 ✓；`examples/intent-request.example.json` 与 spec §4.1 JSON `diff` 无差异
- **依赖**：T02（run-summary `$ref` ui-schema）

### T02 UI Schema 契约
- **目标**：`ui-schema.schema.json`：`components[].type` enum 恰为 7 名；`actions[] { id, type ∈ {submit, cancel}, label, style ∈ {default, primary, danger}, confirmationToken? }`；`Form.props.fields[] { name, type ∈ {text, select, number}, label, options? }`；`examples/ui-schema.example.json` 为确认屏，**必含** `OrderCard + RefundConfirmCard + Form{fields:[reason]}` + `confirm-refund` action；无 URL / HTML。
- **所属端**：contracts
- **输入**：spec §2.3 白名单、§5 表、`wiki/domain-model.md`「UI Schema」、`rules/contracts.md` §4
- **输出**：`ui-schema.schema.json`、`examples/ui-schema.example.json`、`examples/ui-schema.result.example.json`
- **验收**：check-contracts ✓；`grep -c "http" .harness/contracts/examples/ui-schema*.json` 为 0；enum 长度 7（`node -e` 断言）
- **依赖**：—

### T03 SSE 事件契约
- **目标**：`sse-events.schema.json` 根 `oneOf` 10 事件 `{event, data}`；`ui.replace` / `ui.patch` data `$ref` ui-schema；`run.failed.data.code` enum = spec §4.2 SSE 表 5 值。
- **所属端**：contracts
- **输入**：spec §4.0、§4.2、§5、T02 产出
- **输出**：`sse-events.schema.json` + 10 个 `examples/sse-events.{event-name}.example.json`
- **验收**：check-contracts 对该 schema 输出 11 行 ✓
- **依赖**：T02

### T04 工具治理契约：tool-manifest、tool-search、tool-invoke
- **目标**：`tool-manifest`：`risk` / `authorization` / `execution` / `owner` / `status`，`description.maxLength = 500`，两条 `if/then`。`tool-search`、`tool-invoke`：根 `{request, response}`；`tool-search.response.tools[]` 项 `additionalProperties: false` 恰六字段。
- **所属端**：contracts
- **输入**：spec §2.2 工具表、§5、`rules/agent-safety.md` §2、`rules/contracts.md` §5、T01 产出（error-response 复用）
- **输出**：3 个 schema + 4 个示例（`tool-manifest`×2、`tool-search`、`tool-invoke`）
- **验收**：check-contracts 输出 `9 schemas OK`；两条反例（high + never；response 多 `baseUrl`）经 ajv 被拒，命令记入 coding_report
- **依赖**：T01、T03

## Phase B — 后端

### T05a Maven 骨架、platform-spi、最小可启动 app
- **目标**：父 POM（Java 21、Boot 3.5.x BOM、Spring AI 1.1.x BOM、`-Xlint:all -Werror`、spotless、enforcer ≥ 21）、`mvnw`、7 个子模块 pom；`platform-spi` 全部接口（`ToolHandler`、`ToolManifestSource`、`ToolResolver`、`PrincipalPermissionResolver`、`SelfCheck`、`ExecutionContext`、`Principal`）；`app`：主类 `scanBasePackages="com.strato"`、pom 依赖全部子模块、`<finalName>app</finalName>`、actuator、`infra/InMemoryPrincipalPermissionResolver`、`application.yml`（权限表、`strato.selfcheck.enabled`）、`SelfCheckRunner`（遍历 `List<SelfCheck>` Bean，受 `strato.selfcheck.enabled` 控制）。
- **所属端**：backed
- **输入**：`rules/backend-standard.md` §1 §7、`rules/project-structure.md` §2、`specs/06-backend-module-spec.md`、spec §2.2 权限表
- **输出**：`backed/pom.xml`、`backed/mvnw*`、`backed/.mvn/`、7 个 `pom.xml`、`backed/platform-spi/src/main/java/com/strato/spi/*.java`、`backed/app/src/main/java/com/strato/app/{StratoApplication,SelfCheckRunner}.java`、`backed/app/src/main/java/com/strato/app/infra/InMemoryPrincipalPermissionResolver.java`、`backed/app/src/main/resources/application.yml`
- **验收**：`node .harness/scripts/mvn.mjs -q -B verify` 退出码 0（版本号锁定前先查 Maven Central，记入 coding_report）；`java -jar backed/app/target/app.jar` 后 health UP；`pnpm -C .harness run check-module-deps` ✓；`grep -rln "org.springframework" backed/platform-spi/src` 无输出
- **依赖**：—

### T05b contracts-java
- **目标**：9 组 record DTO + `SchemaValidator`（networknt；资源插件复制 `.harness/contracts/*.schema.json` 与 `examples/`）；`infra/selfcheck/ContractsSelfCheck` Bean。
- **所属端**：backed
- **输入**：T01–T04 全部 schema 与示例、T05a、`rules/backend-standard.md` §2 §3
- **输出**：`backed/contracts-java/src/main/java/com/strato/contracts/**`（含 `infra/selfcheck/ContractsSelfCheck.java`）、`backed/contracts-java/pom.xml`
- **验收**：`mvn verify` ✓；`grep -c "selfcheck: contracts 9 schemas, 20 examples OK" $DEPLOY/backend.log` 为 1
- **依赖**：T03、T04、T05a

### T06 Tool Registry
- **目标**：内存 Registry 三端点；`ApplicationReadyEvent` 遍历 `ToolManifestSource` 注册；实现 `ToolResolver`；搜索按 tenant / `PrincipalPermissionResolver` / status 过滤，响应项恰六字段；409 → `TOOL_VERSION_CONFLICT`。
- **所属端**：backed
- **输入**：T05b DTO 与 `SchemaValidator`、T05a 接口与权限实现、`tool-manifest` / `tool-search` schema、`rules/agent-safety.md` §2、`specs/06-backend-module-spec.md`「Tool Registry 专项」
- **输出**：`backed/tool-registry/src/main/java/com/strato/registry/{api,application,domain,infra}/**`、`README.md`
- **验收**：HTTP 注册示例 Manifest → 201，再注册 → 409 `TOOL_VERSION_CONFLICT`；`user_001` 搜索响应通过 `tool-search.response` 校验；`user_002` 少 1 个；`grep -rn "RestClient\|WebClient\|HttpClient" backed/tool-registry/src` 无输出
- **依赖**：T05b

### T07 模拟领域服务：order-service、refund-service
- **目标**：内存数据（订单 10001 / 10002 / 10003）；6 个 `ToolHandler`；两个 `ToolManifestSource` 读取 `src/main/resources/tool-manifests/*.json`；`refund.create` 按 `idempotencyKey` 去重；`infra/selfcheck/RefundIdempotencySelfCheck`（直接调用 `ToolHandler`、不经 Gateway、固定订单 `10003`）。
- **所属端**：backed
- **输入**：T05a SPI、T05b DTO、spec §2.2 工具表、`specs/06-backend-module-spec.md`
- **输出**：`backed/domains/order-service/**`、`backed/domains/refund-service/**`
- **验收**：启动日志 6 行 `registered tool`；`grep -c "selfcheck: refund.create idempotent OK" $DEPLOY/backend.log` 为 1；两模块 pom 不含 gateway / registry / runtime（`grep`）
- **依赖**：T06

### T08 Tool Gateway
- **目标**：`POST /internal/tool-gateway/invoke`：输入校验 → 鉴权 → 幂等 → 寻址 → 调用（`orTimeout`；按 `maxRetries` 重试，仅 `idempotency = required` 或 `sideEffect = false`）→ 输出校验 → 脱敏 → 审计行 9 字段（MDC `runId` / `toolCallId` / `traceId`）。
- **所属端**：backed
- **输入**：T05a SPI、T05b DTO、T06 `ToolResolver`、T07 handlers、`tool-invoke` schema、`rules/agent-safety.md` §5、`specs/06-backend-module-spec.md`「Tool Gateway 专项」
- **输出**：`backed/tool-gateway/**`、`README.md`
- **验收**：缺 `orderId` → 400 `REQUEST_INVALID`；`user_002` 调 `refund.create` → 403 `FORBIDDEN`；正常响应通过 `tool-invoke.response`；审计行含 9 字段；check-module-deps ✓
- **依赖**：T07

### T09a Agent Runtime：领域模型、状态机、端口
- **目标**：`Run` 聚合与状态机（幂等迁移）、`Plan` / `Step`（`requiresConfirmation`）、`DomainRouter`（关键词规则表）、端口接口 `application/port/{LlmClient,ToolRegistryClient,ToolGatewayClient,RunEventSink}`；`idempotencyKey = {runId}-{toolId}-{seq}`。
- **所属端**：backed
- **输入**：T05b DTO、`rules/backend-standard.md` §4、`wiki/domain-model.md`「Run / Step」、`specs/06-backend-module-spec.md`「Agent Runtime 专项」
- **输出**：`backed/agent-runtime/src/main/java/com/strato/runtime/{domain,application/port}/**`
- **验收**：`mvn verify` ✓；`grep -rln --include=*.java "org.springframework\|com.fasterxml" backed/agent-runtime | grep "/domain/"` 无输出
- **依赖**：T05b

### T09b Agent Runtime：LLM 客户端
- **目标**：`SpringAiLlmClient`（Spring AI 1.1.x `ChatClient`，`internalToolExecutionEnabled(false)`，结构化输出，`spring.ai.openai.*` 绑定 `STRATO_LLM_*`）、`RuleBasedLlmClient`（回退）、`PromptBuilder`（description 转义 + 截断 500）、`ToolSelectionValidator`（候选内校验，重试 1 次）；`infra/selfcheck/PlanSelfCheck`。
- **所属端**：backed
- **输入**：T09a 端口、`rules/backend-standard.md` §1 §7、`rules/agent-safety.md` §2、spec §4.2
- **输出**：`backed/agent-runtime/src/main/java/com/strato/runtime/infra/{llm,selfcheck}/**`
- **验收**：`mvn verify` ✓；`grep -c "selfcheck: plan 3 steps, step3 requiresConfirmation OK" $DEPLOY/backend.log` 为 1；`grep -c "selfcheck: invalid toolId rejected OK" $DEPLOY/backend.log` 为 1；`grep -rn "alibaba\|langchain4j" backed/ --include=pom.xml` 无输出
- **依赖**：T09a

### T10a Agent Runtime：UiSchemaBuilder 与 ConfirmationTokenService
- **目标**：`UiSchemaBuilder`（生成确认屏与结果屏 UI Schema，经 `SchemaValidator` 校验）、`ConfirmationTokenService`（随机 token + 内存 + 10 分钟 + 一次性 + `argsDigest` + `formData` 键白名单来自当前屏 `Form.props.fields[]`）；`infra/selfcheck/TokenSelfCheck`。
- **所属端**：backed
- **输入**：T09a、T02 `ui-schema` 契约、`rules/agent-safety.md` §3、spec §2.2 argsDigest 规则
- **输出**：`backed/agent-runtime/src/main/java/com/strato/runtime/application/{UiSchemaBuilder,ConfirmationTokenService}.java`、`infra/selfcheck/TokenSelfCheck.java`
- **验收**：`mvn verify` ✓；`grep -c "selfcheck: token expired/replayed/digest-mismatch/extra-key rejected OK" $DEPLOY/backend.log` 为 1；生成物通过 `ui-schema.schema.json`（自检日志）
- **依赖**：T09a

### T10b Agent Runtime：RunOrchestrator 与进程内适配
- **目标**：`RunOrchestrator`（执行计划、低风险自动、高风险等待、确认后经 Gateway 重校验 `refund.eligibility.check` 再执行）；`infra/{InProcessToolRegistryClient,InProcessToolGatewayClient}`（调用对方模块 `api` 包公开用例）。
- **所属端**：backed
- **输入**：T09b、T10a、T06 / T08 的 `api` 包接口、spec §4.0 §4.1
- **输出**：`backed/agent-runtime/src/main/java/com/strato/runtime/application/RunOrchestrator.java`、`infra/inprocess/**`
- **验收**：`mvn verify` ✓；check-module-deps ✓；`grep -rn "import com.strato.\(registry\|gateway\)\." backed/agent-runtime/src | grep -v "\.api\."` 无输出
- **依赖**：T08、T09b、T10a

### T10c Agent Runtime：SSE 端点
- **目标**：`POST /agent/runs`、`POST /agent/runs/{runId}/actions/{actionId}`（`SseEmitter` 实现 `RunEventSink`，15s ping）、`GET /agent/runs/{runId}`（`run-summary`）；请求头 → principal，缺头 → 401；`@RestControllerAdvice` 输出 §4.2 错误码。
- **所属端**：backed
- **输入**：T10b、`intent-request` / `action-request` / `sse-events` / `run-summary` 契约、`wiki/api-contracts.md`
- **输出**：`backed/agent-runtime/src/main/java/com/strato/runtime/api/**`、`infra/SseRunEventSink.java`
- **验收**：spec §6.2 第 8–14 条全部通过（事件录入 `$DEPLOY/run_events.log`）
- **依赖**：T10b

### T11 后端收口
- **目标**：`application.yml` 补齐非敏感配置；`backed/README.md`（启动、环境变量、无 key 回退、selfcheck 开关）；全量核对 spec §6.2。
- **所属端**：backed
- **输入**：T06、T07、T08、T10c
- **输出**：`backed/app/src/main/resources/application.yml`、`backed/README.md`
- **验收**：`node .harness/scripts/mvn.mjs -q -B verify` ✓；spec §6.2 全部条目通过并记入 coding_report
- **依赖**：T10c

## Phase C — 前端

### T12 依赖、主题、端型
- **目标**：安装 antd 6 / antd-mobile 5 / @ant-design/icons / vite-node；`tsconfig.app.json` paths 与 `vite.config.ts` alias 增加只读 `@contracts/*` → `../.harness/contracts/*`，`server.fs.allow` 放行该目录；`shared/ui/theme/AppThemeProvider.tsx`；`shared/ui/device/{DeviceContext.ts,useDevice.ts}`；`app/providers/DeviceProvider.tsx`（一次性判定并提供 Context）；`AppProviders` 接入。
- **所属端**：fronted
- **输入**：`rules/coding-standard.md` §4、`specs/05-styling-spec.md`、`specs/04-shared-spec.md`、`rules/project-structure.md`「Generate UI 专项」
- **输出**：`fronted/package.json`、`fronted/tsconfig.app.json`、`fronted/vite.config.ts`、`fronted/src/shared/ui/{theme,device}/**`、`fronted/src/shared/ui/index.ts`、`fronted/src/app/providers/{DeviceProvider,AppProviders}.tsx`
- **验收**：`pnpm -C fronted run ci` ✓；spec §6.3 第 2 条 grep 无输出
- **依赖**：—

### T13 entities/agent-run：Zod 投影与纯 API
- **目标**：6 个 Zod schema（IntentRequest、ActionRequest、UiSchema、SseEvent、ErrorResponse、RunSummary）与契约逐字段一致；`getRun()`；`fronted/scripts/verify-examples.ts`。
- **所属端**：fronted
- **输入**：T01–T03 契约与示例、T12（vite-node）、`specs/03-entity-spec.md`、`rules/contracts.md` §1
- **输出**：`fronted/src/entities/agent-run/{model/types.ts,api/agentRunApi.ts,index.ts}`、`fronted/scripts/verify-examples.ts`
- **验收**：typecheck ✓；`pnpm -C fronted exec vite-node scripts/verify-examples.ts` 输出 `16 examples OK`（脚本以 vite-node 运行时校验，不在 typecheck 范围；`.oxlintrc.json` 已对 `scripts/**/*.ts` 关闭 no-console）
- **依赖**：T01、T03、T12

### T14 shared/api/sseClient
- **目标**：`fetch` + `ReadableStream` SSE 解析器（POST body、多行 data、分帧、注释帧、`AbortSignal`），每帧回调 `{event, data: unknown}`。
- **所属端**：fronted
- **输入**：spec §2.3 §4.0、`specs/04-shared-spec.md`
- **输出**：`fronted/src/shared/api/sseClient.ts`、`index.ts`
- **验收**：typecheck ✓；对后端 `POST /agent/runs` 实测 ≥ 9 帧且每帧通过 `SseEventSchema`（vite-node 脚本，记入 coding_report）
- **依赖**：T13、T10c

### T15a shared/ui/generate：Renderer、注册表、基础组件
- **目标**：`types.ts`（**只**定义 7 组件 `props` 的 Zod）、`SchemaRenderer`（`import type { UiSchema }`，注册表查找层以 `string` 处理 `type`）、`componentRegistry.ts`（按 `useDevice()` 选表，`React.lazy` 固定路径）、`UnknownComponent`；桌面 `Card` / `ResultCard`；`mobile/*.tsx` 占位导出。
- **所属端**：fronted
- **输入**：T02 契约、T12（antd、useDevice）、T13 `UiSchema` 类型、`specs/05-styling-spec.md`「Generate UI 封装层」、`rules/project-structure.md`「Generate UI 专项」
- **输出**：`fronted/src/shared/ui/generate/{types.ts,SchemaRenderer.tsx,componentRegistry.ts,UnknownComponent.tsx}`、`desktop/{Card,ResultCard}.tsx`、`mobile/*.tsx`
- **验收**：`pnpm -C fronted typecheck` ✓；`grep -rn "from '@entities" fronted/src/shared | grep -v "import type"` 无输出
- **依赖**：T12、T13

### T15c schema-playground 与 check-registry
- **目标**：`pages/schema-playground`（`env.DEV` 路由 `/dev/schema?example={result|confirm|unknown}`；`result` / `confirm` 经 `@contracts/examples/*.json`；`unknown` 用 `fixtures/unknown.json`，跳过 Zod 直接传 `SchemaRenderer`）；`fronted/scripts/check-registry.mjs` 接入 `lint`。
- **所属端**：fronted
- **输入**：T15a、T12 别名配置、`specs/01-page-spec.md`、spec §2.3
- **输出**：`fronted/src/pages/schema-playground/**`（含 `fixtures/unknown.json`）、`fronted/src/app/router/router.tsx`（DEV 路由）、`fronted/scripts/check-registry.mjs`、`fronted/package.json`
- **验收**：`pnpm -C fronted lint` ✓（含 check-registry）；`/dev/schema?example=result` 出现 `[class^=ant-card]`；`?example=unknown` 出现占位且 console.error 恰 1
- **依赖**：T15a

### T15b shared/ui/generate：桌面剩余 5 组件
- **目标**：antd 实现 `Form`（字段来自 `props.fields[]`，只回传 `formData`）、`Table`、`ConfirmationCard`、`OrderCard`、`RefundConfirmCard`。
- **所属端**：fronted
- **输入**：T15c、`ui-schema.example.json`、`specs/05-styling-spec.md`
- **输出**：`fronted/src/shared/ui/generate/desktop/{Form,Table,ConfirmationCard,OrderCard,RefundConfirmCard}.tsx`
- **验收**：`pnpm -C fronted run ci` ✓；`/dev/schema?example=confirm` 出现 `ant-form` 与 `ant-btn-dangerous`
- **依赖**：T15c

### T16 shared/ui/generate：7 组件移动实现
- **目标**：antd-mobile 实现同名 7 组件，props 复用 `types.ts`。
- **所属端**：fronted
- **输入**：T15b、`specs/05-styling-spec.md`
- **输出**：`fronted/src/shared/ui/generate/mobile/*.tsx`
- **验收**：check-registry ✓；视口 375 `/dev/schema?example=confirm` 出现 `adm-` 且无 `ant-`
- **依赖**：T15b

### T17a features/agent-chat
- **目标**：`useAgentRun`（`startRun` mutation 建 SSE 订阅，事件写入 `['agent-run', runId]` cache，`ui.replace` 覆盖 / `ui.patch` 合并，`confirmation.required` 置状态）、`useSubmitAction`、`AgentChatPanel`。
- **所属端**：fronted
- **输入**：T13、T14、T16、`specs/02-feature-spec.md`、`rules/coding-standard.md` §4
- **输出**：`fronted/src/features/agent-chat/{api,model,ui}/**`、`index.ts`
- **验收**：`pnpm -C fronted run ci` ✓；`grep -rn "fetch(" fronted/src/features` 无输出
- **依赖**：T14、T16

### T17b pages/agent、首页入口、人工验收
- **目标**：`pages/agent/AgentPage.tsx`（`pageContext` 从 URL query 读并 Zod 校验）；路由 `/agent`；首页入口。
- **所属端**：fronted
- **输入**：T17a、`specs/01-page-spec.md`、spec §6.3 人工验收脚本
- **输出**：`fronted/src/pages/agent/**`、`fronted/src/app/router/router.tsx`、`fronted/src/pages/home/HomePage.tsx`
- **验收**：`pnpm -C fronted run ci` ✓；spec §6.3 脚本 5 步完成并产出 4 张截图
- **依赖**：T17a、T11

## Phase D — 收口

### T18 文档同步
- **目标**：`wiki/api-contracts.md` 与实际端点一致；`fronted/README.md`；`summary.md` 契约变更段列 9 个文件；`rules/backend-standard.md` §7 若有新增环境变量同步。
- **所属端**：harness
- **输入**：T11、T17b 实际产出
- **输出**：上述文件
- **验收**：`pnpm -C .harness run doctor` 0 errors；`pnpm -C .harness run ci` 退出码 0
- **依赖**：T11、T17b

## 依赖图

```
Phase A:  T02 → T01 → T04        T02 → T03 → T04
Phase B:  T05a ─────────────────→ T05b → T06 → T07 → T08 ─┐
          T03, T04 ─────────────→ T05b → T09a → T09b ──────┼→ T10b → T10c → T11
                                          T09a → T10a ─────┘
Phase C:  T01, T03, T12 → T13 ─┬→ T14 ←── T10c
          T12, T13 ─────────────┴→ T15a → T15c → T15b → T16 ─┐
                                   T14 ─────────────────────┼→ T17a → T17b ←── T11
Phase D:  T11, T17b → T18
```

依赖行速查：T01←T02；T02←—；T03←T02；T04←T01,T03；T05a←—；T05b←T03,T04,T05a；T06←T05b；T07←T06；T08←T07；T09a←T05b；T09b←T09a；T10a←T09a；T10b←T08,T09b,T10a；T10c←T10b；T11←T10c；T12←—；T13←T01,T03,T12；T14←T13,T10c；T15a←T12,T13；T15c←T15a；T15b←T15c；T16←T15b；T17a←T14,T16；T17b←T17a,T11；T18←T11,T17b。
