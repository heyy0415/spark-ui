# Spec: test-frontend-unit-tests-20260911

> 改造清单第 2 项：前端单元测试基建。纯函数与传输层用 vitest 离线验证，不引入 DOM 环境，不改被测实现。

## 1. 背景

- `spark-ui/` 共约 2600 行 TS/TSX，`*.test.*` 为 0。现有验证只有契约 / 架构门禁脚本（`verify-examples`、`verify-transport`、`check-deps`、`check-registry`、`verify-pack`）与需要真后端 + 真模型 + 本机 Chrome 的 `e2e-frontend.mjs`。
- 分支最密集的三处纯逻辑没有任何可重复断言：`features/agent-chat/model/runView.ts`（10 种 SSE 事件 × 回合状态机，含评审 M1 / M2 / S8 修过的边界）、`shared/api/sseClient.ts`（`parseFrame` 分帧与 `consumeSse` 跨 chunk 拼接）、`features/agent-chat/api/useAgentRun.ts` 的 `missingRequiredFields`（决定是否消耗一次性令牌）。`@spark-ui/core` 的 `uiSchema.ts` 投影与 `runStatusText.ts` 同样零覆盖。
- 上一 change 已把后端单测接入 `ci`；前端补齐后 `pnpm -C .harness run ci` 两端都含单测。

## 2. 范围（In Scope）

### 2.1 vitest 接入

- `pnpm-workspace.yaml` catalog 增 `vitest: 4.1.11`（vitest 5 的 `engines.node` 与 optional peer `@types/node` 都要求 Node 22+，本仓库 `engines.node >=20.19`、本机 Node 20、根 `@types/node ^20`，`strict-peer-dependencies=true` 下 `pnpm install` 会失败；4.1.x peer `vite ^8` 且兼容 Node 20，评审 v1 已实测安装运行通过），`packages/core` 与 `apps/chat` 的 devDependencies 各加 `"vitest": "catalog:"`。不引入 jsdom / happy-dom / testing-library：本 change 只测纯函数与以假 `fetch` 驱动的传输层，`environment: node`。
- 各包 `package.json` 增 `"test": "vitest run"`；根 `package.json` 增 `"test": "pnpm -r run test"`，`ci` 脚本在 `typecheck` 之后插入 `pnpm test`。vitest 复用各包既有 `vite.config.ts`（chat 的路径别名、`@spark-ui/core` serve 模式解析到 core src；`import.meta.env` 由 vitest 注入 `MODE=test / DEV / PROD`，满足 `env.ts` 的 Zod）。
- 测试文件与被测源码同目录、`*.test.ts` 后缀。`packages/core/tsconfig.build.json` 增 `exclude: ["src/**/*.test.ts"]`，避免 d.ts 进 dist（`verify-pack` (a) 会拒绝）；`tsconfig.json`（typecheck）仍包含测试文件，测试代码同受 strict / `exactOptionalPropertyTypes` 约束。`check-deps.mjs` 已跳过 `.test.ts`；oxlint 与 prettier 覆盖测试文件，规则不放宽。

### 2.2 测试清单

| 包 | 测试文件 | 覆盖的不变式 |
|---|---|---|
| chat | `features/agent-chat/model/runView.test.ts` | `beginTurn` 追加回合且收口上一回合的 `waiting_confirmation`（M2）；`beginConfirm` / `cancelConfirm` / `failIfStillStreaming`（M1，只对 streaming 生效）；`reduceEvent` 对 10 种事件各一条：`run.started` 写 runId、`message.delta` 追加、`tool.selected/started/completed` 状态迁移与 `summary` 可选、`ui.replace` 覆盖、`ui.patch` 同 screenId 合并 / 异 screenId 忽略 / 无屏忽略、`confirmation.required` 置 pendingActionId、`run.completed` / `run.failed`；旧流残帧（runId 不符）丢弃（S8）；空视图上 reduce 为 no-op；`skeletonVariant` 三分支 |
| chat | `shared/api/sseClient.test.ts` | `parseFrame`：`event:` / `data:` 多行拼接 / `id:` / 冒号后有无空格 / 纯注释帧 null / 无 event null / data 非 JSON 保留原文 / data 为空 null；`consumeSse`：帧跨 chunk 拼接、`\r\n\r\n` 归一、忽略 `: ping`、流尾无空行残帧也回调、非 2xx 抛 `HttpError` 且 body 解析（JSON / 非 JSON）、空 body 抛 `HttpError`、URL = baseUrl + path、`AbortSignal` 透传给 fetch |
| chat | `shared/api/httpClient.test.ts` | 2xx + schema 通过返回数据；非 2xx 抛 `HttpError`（status / body）；HTML 错误页不逃出 `SyntaxError`；schema 不符抛 `HttpError` 并 `console.error`；`Content-Type` 只在有 body 时加 |
| chat | `features/agent-chat/api/useAgentRun.test.ts` | `missingRequiredFields`：无屏 → 空；非 Form 组件忽略；required 且 `undefined` / `''` 缺失按 label 返回；已填 / 非 required 不缺；Form props 不合规跳过；`FormIncompleteError.name` 与 `missing` |
| chat | `entities/agent-run/api/agentRunApi.test.ts` | `buildIntentRequest` / `buildActionRequest` 对越界输入抛 ZodError（message 超 2000、formData 超 16 键、token 短于 16）；`actionPath` 对 runId / actionId URL 编码 |
| chat | `entities/agent-run/model/types.test.ts` | `SseEventSchema` 拒绝未知 event、拒绝多余字段（strict）、`run.failed.code` 只接受 5 个枚举；`RunSummarySchema` FAILED 必须带 failureCode、WAITING_CONFIRMATION 必须带 currentUi |
| core | `schema/uiSchema.test.ts` | `parseUiSchema` 接受契约示例形态；`components[].type` 白名单外拒绝；每种组件 props 违规（Form 空 fields、Card items > 32、Table cells > 16 键、Result status 越界、Timeline time 非 ISO）拒绝；`inlineAction.intent` 含 `://` 或 `<` 拒绝；`actions[].confirmationToken` 短于 16 拒绝；`PROPS_SCHEMAS` 与 `COMPONENT_TYPES` 键一致且冻结 |
| core | `lib/runStatusText.test.ts` | 四种状态文案；streaming 从后往前取第一个 `status !== 'succeeded'` 的工具（含 `failed`），全部 succeeded 或无工具时「理解问题」；completed 带步数；failed 用 text 回退默认 |
| core | `registry/componentRegistry.test.ts` | desktop / mobile 注册表键集合相同且等于 `COMPONENT_TYPES`；`Object.isFrozen`；`REGISTRY_KEYS` 与之一致 |

- 断言精确到值（事件序列、字段值、错误类型与 `status`），不使用 `isNotNull` 类弱断言；`consumeSse` 用 `ReadableStream` 假 fetch 分 chunk 推送，不依赖计时。
- 夹具来源：两个包的 tsconfig 都没有 `@types/node`（core `types: []`，chat `types: ["vite/client"]`），测试不得 import `node:fs`。**core** 测试用相对路径静态 import 契约示例 JSON（`../../../../../.harness/contracts/examples/ui-schema.example.json`（从 `packages/core/src/schema/` 起 5 级），`resolveJsonModule` 已开，core 的 oxlint override 不禁 `../../*`，`tsconfig.build.json` 已排除测试故不进 d.ts）；**chat** 测试手写 `satisfies UiSchema / SseEvent` 的最小夹具（chat 的 oxlint 禁 `../../*` 与 `@contracts/*`，不为测试放宽规则）。夹具里的工具 / 实体用中性词（`demo.item.*`）。

### 2.3 规则与文档

- `coding-standard.md` 新增 §9「单元测试」（原 §9 提交与变更改为 §10）：vitest、同目录 `*.test.ts`、纯函数与传输层必测、不测 antd 渲染细节、禁 `any` 同样适用、夹具优先契约示例。
- `project-structure.md` §1 补一行：测试与源码同目录，`tsconfig.build.json` 排除，`check-deps` 跳过。
- `code-review/SKILL.md` 前端段加 `pnpm -C spark-ui run test`。
- `.harness/skills/coding-skill/specs/02-feature-spec.md` / `04-shared-spec.md` 各加「必备：纯函数模块配同目录测试」一句。

## 3. 非目标（Out of Scope）

- 不测 React 组件渲染（`SchemaRenderer`、五个白名单组件、`AgentChatPanel`），不引入 jsdom / testing-library；渲染由 `e2e-frontend.mjs` 与 `/dev/schema` playground 覆盖。
- 不测 `useAgentRun` hook 本体（依赖 TanStack Query + React 运行时），只测其导出的纯函数。
- 不迁 Playwright（第 3 项）、不配 GitHub Actions（第 4 项）、不做 headless 客户端下沉（第 15 项）。
- 不改任何被测实现；发现 bug 记入 summary 另开 change。
- 不改契约、不改 `verify-pack.baseline.json`（dist 不应变化）。
- 不升级 Node / `@types/node` 大版本，不为测试放宽 oxlint 规则或 tsconfig `types`。

## 4. 核心场景

- 开发者改动 `runView.ts` 后 `pnpm -C spark-ui run test` 秒级得到结果，无需起后端 / Chrome。
- `pnpm -C .harness run ci` 前端步骤顺序：build:core → typecheck → **test** → lint → format:check → verify-examples → verify-transport → build:chat → verify-pack；任一失败非 0。
- 运行链路不变。

## 5. 契约影响

- **NONE**。

## 6. 验收标准

1. `pnpm -C spark-ui run test` 退出码 0；vitest 汇总 ≥ 9 个文件、≥ 70 个用例、0 失败。
2. `pnpm -C spark-ui run ci` 退出码 0；`pnpm -C .harness run ci` 退出码 0。
3. `pnpm -C spark-ui run build:core && pnpm -C spark-ui run verify-pack` 退出码 0，dist 内无 `*.test.d.ts`（`find spark-ui/packages/core/dist -name '*.test.*' | wc -l` = 0），体积基线不变。
4. 人为让一个断言失败后 `pnpm -C spark-ui run test` 退出码非 0（验证后还原）。
5. `pnpm -C spark-ui run typecheck`、`lint`、`format:check` 对测试文件同样退出 0。
6. `pnpm -C .harness run doctor` 退出码 0。

## 7. 风险与权衡

| 风险 | 缓解 |
|---|---|
| vitest 版本与 Node 20 / `@types/node ^20` / `strict-peer-dependencies` 的兼容 | 钉 4.1.11（评审 v1 实测可装可跑）；不升 Node、不升 `@types/node` 大版本（见 §3） |
| 测试 d.ts 混进 dist 破坏 `verify-pack` | `tsconfig.build.json` exclude；验收第 3 条直接 `find` |
| `exactOptionalPropertyTypes` 让夹具构造繁琐 | 夹具用 satisfies / 显式类型，必要时从契约示例 JSON 经 Zod parse 得到已窄化对象 |
| `env.ts` 在测试环境 parse 失败 | vitest 注入 `MODE / DEV / PROD` 均为正确类型；若不满足则在 `vite.config.ts` `test.env` 补，不改 `env.ts` |
| 首次安装 vitest 需联网 | 与后端一致，`ci` 前端步骤本就在线 |
| 测试锁死实现细节导致脆弱 | 只断言导出函数的输入输出与错误类型，不断言日志文本 |
