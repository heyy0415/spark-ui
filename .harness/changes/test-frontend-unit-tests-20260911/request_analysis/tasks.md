# Tasks: test-frontend-unit-tests-20260911

「所属端」除 spark-ui 外，`harness` 指 `.harness/rules`、`.harness/skills`、`.harness/scripts` 等工程基础设施与文档。每个 task ≤ 0.5 天。

## T01 vitest 接入

- **目标**：两个包可写 `*.test.ts` 并由 `pnpm test` 执行；测试不进 dist。
- **所属端**：spark-ui
- **输入**：`pnpm-workspace.yaml`、根 / core / chat `package.json`、`packages/core/tsconfig.build.json`
- **输出**：catalog 增 `vitest: 4.1.11`；core / chat devDeps 加 `vitest: catalog:`；各包 `test` script（`vitest run --passWithNoTests`，无测试文件时 vitest 默认退出 1）；根 `test` script；`tsconfig.build.json` exclude 测试；`pnpm install` 更新 lockfile。`ci` 脚本的插入放 T05。
- **验收**：`pnpm -C spark-ui install` 退出 0；`pnpm -C spark-ui run test` 退出 0；`pnpm -C spark-ui run build:core` 后 `find packages/core/dist -name '*.test.*' | wc -l` = 0。
- **依赖**：无

## T02 core 单测

- **目标**：ui-schema 投影、状态文案、注册表有断言。
- **所属端**：spark-ui
- **输入**：`packages/core/src/{schema/uiSchema.ts, lib/runStatusText.ts, registry/componentRegistry.ts}`、`.harness/contracts/examples/ui-schema*.json`
- **输出**：`schema/uiSchema.test.ts`、`lib/runStatusText.test.ts`、`registry/componentRegistry.test.ts`
- **验收**：≥ 20 用例，0 失败；覆盖 spec §2.2 core 三行全部条目。
- **依赖**：T01

## T03 chat 传输层单测

- **目标**：SSE 分帧 / 流消费与 HTTP 客户端有断言。
- **所属端**：spark-ui
- **输入**：`apps/chat/src/shared/api/{sseClient.ts, httpClient.ts}`
- **输出**：`shared/api/sseClient.test.ts`、`shared/api/httpClient.test.ts`；假 fetch 用 `ReadableStream` 分 chunk 推送
- **验收**：≥ 20 用例，0 失败；覆盖 spec §2.2 对应两行；无 `setTimeout` / sleep。
- **依赖**：T01

## T04a chat 回合状态机单测

- **目标**：`runView.ts` 归约与回合生命周期有断言。
- **所属端**：spark-ui
- **输入**：`features/agent-chat/model/runView.ts`
- **输出**：`features/agent-chat/model/runView.test.ts`（手写 `satisfies SseEvent` 夹具）
- **验收**：≥ 18 用例，0 失败；`reduceEvent` 10 种事件各 ≥ 1 条；覆盖 spec §2.2 runView 行全部条目。
- **依赖**：T01

## T04b chat 校验与请求构造单测

- **目标**：必填校验、请求构造、契约投影有断言。
- **所属端**：spark-ui
- **输入**：`features/agent-chat/api/useAgentRun.ts`、`entities/agent-run/{api/agentRunApi.ts, model/types.ts}`
- **输出**：`useAgentRun.test.ts`、`agentRunApi.test.ts`、`types.test.ts`
- **验收**：≥ 12 用例，0 失败；覆盖 spec §2.2 对应三行。
- **依赖**：T01

## T05 规则、文档与 CI 门禁

- **目标**：测试成为前端门禁并写进规范。
- **所属端**：harness + spark-ui
- **输入**：spec §2.3 文件、根 `package.json` `ci` 脚本
- **输出**：`coding-standard.md` §10；`project-structure.md` §1 一行；`code-review/SKILL.md` 前端段；`02-feature-spec.md` / `04-shared-spec.md` 必备项；`ci` 脚本插入 `pnpm test`。
- **验收**：`pnpm -C spark-ui run ci` 退出 0；`pnpm -C .harness run ci` 退出 0；人为让一个断言失败后 `pnpm -C spark-ui run test` 非 0（还原）；`pnpm -C .harness run doctor` 退出 0。
- **依赖**：T02、T03、T04a、T04b
