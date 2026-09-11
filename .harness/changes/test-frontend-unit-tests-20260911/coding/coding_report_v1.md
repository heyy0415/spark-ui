# Coding Report v1 — test-frontend-unit-tests-20260911

## 1. 改动文件

### spark-ui

| 文件 | 变更 |
|---|---|
| `pnpm-workspace.yaml` | catalog 增 `vitest: 4.1.11`（附注：vitest 5 要 Node 22+） |
| `package.json` | `test: pnpm -r run test`；`ci` 在 typecheck 后插入 `pnpm test` |
| `packages/core/package.json`、`apps/chat/package.json` | devDeps `vitest: catalog:`；`test: vitest run --passWithNoTests` |
| `pnpm-lock.yaml` | 新增 vitest 依赖树 |
| `packages/core/tsconfig.build.json` | `exclude: ["src/**/*.test.ts"]`，测试 d.ts 不进 dist |
| `apps/chat/vite.config.ts` | `defineConfig` 改自 `vitest/config`；新增 `test` 段：`environment: node`、`css: false`、`deps.optimizer.ssr` 预打包 `antd-mobile` 并把 `.css` 用 esbuild `empty` loader 置空 |
| `packages/core/src/schema/uiSchema.test.ts` | 18 用例：契约示例接受、白名单 / strict / 版本 / screenId、五组件 props 越界、inlineAction、令牌长度、PROPS_SCHEMAS 与 COMPONENT_TYPES 一致且冻结 |
| `packages/core/src/lib/runStatusText.test.ts` | 9 用例：四状态文案、`status !== 'succeeded'` 语义、TOOL_STATUS_TEXT 键集合 |
| `packages/core/src/registry/componentRegistry.test.ts` | 3 用例：两端键集合、冻结、lazy 组件非字符串 |
| `apps/chat/src/features/agent-chat/model/runView.test.ts` | 26 用例：回合生命周期（M1 / M2）、10 种事件归约、残帧丢弃（S8）、ui.patch 三分支、skeletonVariant |
| `apps/chat/src/shared/api/sseClient.test.ts` | 16 用例：parseFrame 8 类输入、consumeSse 跨 chunk / CRLF / ping / 尾帧 / 空流 / 请求形态 / 三类错误 |
| `apps/chat/src/shared/api/httpClient.test.ts` | 7 用例：2xx 校验、Content-Type 条件、非 2xx、HTML 错误页、schema 不符 + console.error 一次、signal / headers、HttpError 字段 |
| `apps/chat/src/features/agent-chat/api/useAgentRun.test.ts` | 7 用例：missingRequiredFields 六分支、FormIncompleteError |
| `apps/chat/src/entities/agent-run/api/agentRunApi.test.ts` | 7 用例：intent / action 请求越界、actionPath 编码 |
| `apps/chat/src/entities/agent-run/model/types.test.ts` | 8 用例：10 事件接受、未知事件 / 多余键 / 模式 / 枚举拒绝、run-summary 条件必填 |
| `README.md` | 技术栈加 vitest；命令表加 `pnpm run test`，`ci` 顺序更新 |

### harness

| 文件 | 变更 |
|---|---|
| `.harness/rules/coding-standard.md` | 新增 §9 单元测试（原 §9 提交与变更改为 §10） |
| `.harness/rules/project-structure.md` | §1 Spark UI 专项加一行测试位置约定 |
| `.harness/skills/code-review/SKILL.md` | 前端段加 `pnpm -C spark-ui run test` |
| `.harness/skills/coding-skill/specs/02-feature-spec.md`、`04-shared-spec.md` | 必备项加同目录测试 |

## 2. 新增 / 删除的公共出口

- 无运行时 API、契约、端点变更。`@spark-ui/core` dist 内容与体积不变（verify-pack (f) 47 KB 基线通过）。
- 新增 npm script：根 `test`、core / chat `test`。

## 3. 关键决策

- **vitest 4.1.11 而非 5.x**：5.x `engines.node ^22.12` 且 optional peer `@types/node ^22`，`strict-peer-dependencies=true` 下无法安装（评审 v1 M-1 实测）。
- **antd-mobile 的 CJS 全局 CSS 问题**：chat 测试经 `@spark-ui/core` 入口连带加载 antd-mobile，其 `cjs/global/index.js` 顶层 `require("./global.css")` 在 Node 直接加载失败。依次试过 `server.deps.inline`（数组 / true）、`ssr.noExternal`、alias 到 es 入口，均不生效；`deps.optimizer.ssr.include` + `esbuildOptions.loader['.css'] = 'empty'` 可行。已在 vite.config 注释记录，避免后人重走。
- **不加 `@types/node`、不放宽 oxlint**：core 测试用 5 级相对路径静态 import 契约示例 JSON；chat 测试全部手写 `satisfies` 夹具。评审 v2 指出的 4 级路径错误已按 5 级修正。
- **`Array#sort` 改 `expect.arrayContaining` + 长度断言**：oxlint unicorn 规则禁 `sort()`，core 的 lib 目标又无 `toSorted` 类型。
- **跳过 `useAgentRun` hook 本体**（依赖 TanStack Query 与 React 运行时），只测其导出的纯函数，与 spec §3 一致。
- **SelfCheck / e2e 不动**：spec §3 非目标。

## 4. agent-safety 六条边界自查

| § | 结论 |
|---|---|
| §1–§3 | 未改后端；无涉及 |
| §4 前端边界 | 测试只验证既有边界：注册表冻结、白名单外拒绝、inlineAction 禁 URL / `<`、令牌不透明且只回传、必填校验不消耗令牌。未改任何渲染 / 传输实现 |
| §5 / §6 | 契约投影测试覆盖 10 种 SSE 事件 strict、run.failed 枚举、多余键拒绝，等价于前端对流式输出的边界 |

## 5. 门禁结果

| 命令 | 退出码 |
|---|---|
| `pnpm -C spark-ui run test` | 0（9 文件 / 99 用例 / 0 失败） |
| `pnpm -C spark-ui run typecheck` / `lint` / `format:check` | 0 / 0 / 0 |
| `pnpm -C spark-ui run build:core` 后 `find dist -name '*.test.*'` | 0 个 |
| 人为改一处断言后 `pnpm -C spark-ui run test` | 1（已还原） |
| `pnpm -C spark-ui run ci` | 0（含 verify-pack (f) 47 KB 基线） |
| `pnpm -C .harness run ci`、`doctor` | 见 `coding/review/code_review_v1.md` |

## 6. 已知限制 / 后续

- 用例数 99 > spec §6.1 的 ≥ 70；各 task 下限（T02 ≥20 → 30，T03 ≥20 → 23，T04a ≥18 → 26，T04b ≥12 → 22）全部满足。
- `ChatTurn.id` 含 `Date.now()`，测试只断言唯一性不断言值。
- 未发现被测实现 bug。
