---
mode: plan
verdict: REVISION REQUIRED
reviewer: expert-reviewer（plan 模式）
round: 1/3
inputs:
  - request_analysis/spec.md
  - request_analysis/tasks.md
rules:
  - .harness/rules/project-structure.md
  - .harness/rules/coding-standard.md
  - .harness/rules/contracts.md
  - .harness/rules/dev-workflow.md（阶段 1 门禁）
---

# Spec Review v1 — test-frontend-unit-tests-20260911

> 独立性原则：未读 coding 目录；只看 spec / tasks 本身，并对其声称的事实核对了源码与工具链：`spark-ui/package.json`、`pnpm-workspace.yaml`、`.npmrc`、`pnpm-lock.yaml`、`packages/core/{package.json,tsconfig.json,tsconfig.build.json,vite.config.ts}`、`apps/chat/{package.json,tsconfig.json,vite.config.ts}`、`tsconfig.base.json`、`tsconfig.node.json`、`scripts/{check-deps,verify-pack}.mjs`、`scripts/verify-examples.ts`、`.oxlintrc.json`、全部 9 个被测源文件、`.harness/scripts/ci.mjs`、`code-review/SKILL.md`；npm registry 上 `vitest@5.0.0` / `vitest@4.1.11` / `vite@8.2.2` 的 peer 与 engines 元数据；并在 `/tmp` 临时工程里用本机 pnpm 10.34.5 / Node 20.20.2 实测了 `pnpm install`、vitest 运行、`import.meta.env` 取值与 0 用例退出码（探针已删除，仓库无残留）。

## 0. 阶段 1 门禁与 plan 模式必查项

| 必查项 | 结果 | 备注 |
|---|---|---|
| spec 含 背景 / 范围 / 非目标 / 验收标准 / 风险 5 章节 | 通过 | §1 / §2 / §3 / §6 / §7 |
| tasks 每个 task 含 目标 / 输入 / 输出 / 验收 / 依赖 | 通过 | T01–T05 齐全，另有「所属端」 |
| 「非目标」存在且非空 | 通过 | §3 五条，边界清楚（不测渲染 / hook 本体 / 不改实现） |
| 每条验收可被命令 / 断言校验 | **部分不通过** | §6 六条本身可校验；但 T01「0 用例亦可退出 0」按 vitest 实际行为不成立（S-1）；§2.2 夹具读取方式与 §2.1 的 typecheck 约束互斥，导致 §6.5 在编码阶段必然失败（M-2） |
| 风险 ≥1 失败模式 + 缓解 | **部分不通过** | §7 六条；但第一条「vitest 5 × vite 8」的失败模式判断错了：真正失败点是 `@types/node` peer 与 Node engines，且是确定性失败而非「首跑若失败」（M-1） |
| 每个 task 标注所属端 | 通过 | `harness` 端在 tasks 开头已定义（沿用上一 change 的 I-1 建议） |
| contracts task 先于依赖者 | 通过（不适用） | §5 契约 NONE，无 contracts task |
| 跨端结构列出契约文件 | 通过（不适用） | 无跨端结构变更；测试只消费既有契约示例 |
| 每个 task ≤ 0.5 天 | **边缘** | T04 四文件 ≥ 30 用例偏重（S-2） |

结论：2 条 MUST FIX → `REVISION REQUIRED`。两条都是 T01 工具链层面的事实错误，修订为文档级改动，不涉及方案推翻。

---

## 1. 点名核对的六个问题

### 1.1 vitest 5.0.0 peer 与 `strict-peer-dependencies=true`

核对事实（npm registry）：
- `vitest@5.0.0`：`peerDependencies.vite = ^6.4.0 || ^7.0.0 || ^8.0.0`（非 optional，8.2.2 满足）；`@types/node = ^22.0.0 || >=24.0.0`（optional）；jsdom / happy-dom / `@vitest/*` 全部 optional。`engines.node = ^22.12.0 || ^24.0.0 || >=26.0.0`。
- `vitest@4.1.11`：`vite = ^6.0.0 || ^7.0.0 || ^8.0.0`；`@types/node = ^20.0.0 || ^22.0.0 || >=24.0.0`；`engines.node = ^20.0.0 || ^22.0.0 || >=24.0.0`。
- 本仓库：根 devDeps `@types/node ^20.19.0`（lockfile 解析为 20.19.43）；`engines.node >=20.19.0`；本机 Node v20.20.2；pnpm 10.34.5。

optional peer 在 strict 模式下的处理：**缺失不报错，但只要图里能解析到一个版本、且版本不在范围内，就按 unmet peer 报错**。pnpm 默认 `resolve-peers-from-workspace-root=true`，workspace 根的 devDeps 会被用来解析子包的 peer——现有 `pnpm-lock.yaml` 里 `packages/core` / `apps/chat` 的 `vite@8.2.2(@types/node@20.19.43)` 正是这个机制的证据（两个子包自己都没声明 `@types/node`）。因此 vitest 5 装进 core / chat 后，其 `@types/node` peer 会解析到根的 20.19.43 → 越界。

实测（临时工程，`.npmrc` 与本仓库相同，`@types/node ^20.19.0 + vite 8.2.2 + vitest 5.0.0`）：

```
ERR_PNPM_PEER_DEP_ISSUES  Unmet peer dependencies
└─┬ vitest 5.0.0
  └── ✕ unmet peer @types/node@"^22.0.0 || >=24.0.0": found 20.19.43
```

同条件换 `vitest@4.1.11`：`pnpm install` 退出 0，`vitest run` 通过。另外 vitest 5 的 `engines.node` 不含 Node 20——本仓库 `engines`、本机、`@types/node` 主版本全在 20，选 5.x 等于隐含一次 Node 22 升级。**spec §2.1「vitest 5.x」与 §7 第一条缓解都需要改**，见 M-1。

### 1.2 vitest 复用 `apps/chat/vite.config.ts`

实测（vitest 5.0.0，函数式 `defineConfig(({ command }) => …)`，带 `server.fs.allow` / `proxy` / `strictPort`）：`command = 'serve'`，`mode = 'test'`；`import.meta.env` 为 `{ MODE: 'test', DEV: true, PROD: false }`，`typeof DEV === 'boolean'`——满足 `env.ts` 的 `z.string()` / `z.boolean()`；`server.*` 对 vitest 无副作用。`command === 'build'` 分支不触发，`@spark-ui/core` 经 core `package.json` `exports` 走 `src`。spec §2.1 / §7 表述正确；`verify-examples` / `verify-transport` 已经在 vite-node（同一 vite 解析链）下 import `@spark-ui/core` 根 barrel（含 antd / antd-mobile 的 `SparkThemeProvider`）并在 Node 环境通过，可作为「core barrel 在 node 环境可加载」的既有证据。建议把这三点写成 T01 的验证点而非只作风险描述（L-1）。

### 1.3 `types: []` 与测试文件的类型解析

- 显式 `import { describe, it, expect } from 'vitest'` 不依赖 `types`，core（`types: []`）与 chat（`types: ["vite/client"]`）均可解析；spec 未提 `globals: true`，一致。
- **但 spec §2.2「测试改为 `node:fs` 读绝对路径」在两个包里都过不了 typecheck**。实测在 `packages/core/src/lib/__probe.test.ts` 与 `apps/chat/src/shared/api/__probe.test.ts` 写 `import { readFileSync } from 'node:fs'` + `import { fileURLToPath } from 'node:url'`，两包 `tsc -p tsconfig.json --noEmit` 均报 `TS2591: Cannot find name 'node:fs'`（探针已删除）。原因：`@types/node` 只在根 `tsconfig.node.json` 的 `types: ["node"]` 里生效，core / chat 的 tsconfig 都不含它。而 spec §2.1 明确「`tsconfig.json`（typecheck）仍包含测试文件」，与 §6.5「typecheck 对测试文件退出 0」互斥。见 M-2。
- 附带：vitest 的 cwd = 被 `pnpm -r run test` 调起的**子包目录**（实测 `process.cwd()` = 工程根），与 `verify-examples.ts` 假定的「cwd = spark-ui」不同；即使解决了类型问题，也不能照抄 `join(process.cwd(), '..', '.harness', …)`，须以 `import.meta.url` 定位。

### 1.4 oxlint `no-restricted-imports` 与测试文件

- 同目录相对导入 `./runView`、`./httpClient`、`./uiSchema`、`../schema/uiSchema`（core）都不落入任何受限 pattern；`useAgentRun.test.ts` 经 `@entities/agent-run` 索引导入合法；`runView.test.ts` 需要的 `SseEvent` / `RunFailureCode` 类型可从 `@entities/agent-run` 索引 `import type`。无冲突。
- 需要注意的是夹具读取：chat 的 `../../*` 与 `@contracts/*`（限 `pages/**` / `scripts/**`）两条 pattern 在 `apps/chat/src/**` 全部生效，所以 chat 测试**既不能相对路径穿到 `.harness/contracts`，也不能用 `@contracts/*`**；core 的 override 只保留 antd 与 `@spark-ui/core/*` 两条，`../../*` 在 core 内是允许的。这与 1.3 合并为 M-2 一并给出可行路径。
- oxlint `env` 只有 browser + es2022；测试里用 `process` / `Buffer` 会看 `no-undef` 是否启用（当前 correctness 集合未见显式开启，风险低），用 `ReadableStream` / `Response` / `TextEncoder` 无此问题。L-2。

### 1.5 工作量

- T01：实际含「选版本 + 解 peer + lockfile + 两包脚本 + build exclude + 验证 dist 干净」，修掉 M-1 后 ≤ 0.5 天。
- T02（3 文件 ≥ 20）、T03（2 文件 ≥ 20）：在 0.5 天内；T03 的假 fetch 用 `new Response(new ReadableStream(...))` 即可，无计时依赖。
- **T04（4 文件 ≥ 30）偏重**：`runView.test.ts` 一项就要覆盖 10 种事件 + M1 / M2 / S8 三个修过的边界 + 空视图 + `skeletonVariant`（约 15–18 条），且 `ui.replace` / `ui.patch` 夹具是在 `exactOptionalPropertyTypes` 下手写的 `UiSchema`；`types.test.ts` 的正反例都要契约合法（`run_` / `tc_` 前缀、ISO 时间、strict 多余字段）。合计接近 0.6–0.8 天。见 S-2。
- T05：文档五处 + `ci` 一行 + 四条验收命令，≤ 0.5 天。

### 1.6 验收数值一致性

- 文件数：T02 3 + T03 2 + T04 4 = 9，与 §6.1「≥ 9 个文件」一致。
- 用例数：T02 ≥ 20 + T03 ≥ 20 + T04 ≥ 30 = 70，与 §6.1「≥ 70」一致（恰好等于下限之和，按 tasks 最低要求交付即满足）。
- 若按 S-2 拆 T04，两个子任务的下限之和须仍 ≥ 30。I-1。

---

## 2. 意见清单

### MUST FIX

**M-1**
- 位置：spec §2.1「`pnpm-workspace.yaml` catalog 增 `vitest`（5.x，peer `vite ^8`）」；§7 风险表第一行「vitest 5 与 vite 8.2.2 / TS 7 组合较新 → peer 范围含 `^8.0.0`；首跑若失败降到与 vite 8 兼容的最近版本」；tasks T01 输出「catalog 增 `vitest`」。
- 问题：(1) `vitest@5.0.0` 的 optional peer `@types/node ^22.0.0 || >=24.0.0` 会经 pnpm `resolve-peers-from-workspace-root` 解析到根 devDeps 的 `@types/node@20.19.43`；`.npmrc` `strict-peer-dependencies=true` 下 `pnpm install` **确定性失败**（`ERR_PNPM_PEER_DEP_ISSUES`，已实测），不是「首跑若失败」。(2) `vitest@5.0.0` `engines.node = ^22.12.0 || ^24.0.0 || >=26.0.0`，本仓库 `engines.node >=20.19.0`、本机 Node 20.20.2、`@types/node` 主版本 20；选 5.x 等于顺带升级 Node 与 `@types/node`，超出本 change 范围且未写入 spec。(3) 风险表把失败点归因到 `vite ^8` 兼容性，方向错了。
- 建议：二选一并写进 spec §2.1 与 §7：(a)【推荐】catalog 用 `vitest: 4.1.11`（peer `vite ^8` ✓、`@types/node ^20` ✓、`engines.node ^20` ✓，已实测 strict 模式安装并运行通过），非目标补一句「不升级 Node / `@types/node` 主版本，vitest 5 待 Node 22 升级 change 一起做」；(b) 坚持 5.x 则必须同 change 把根 `@types/node` 升到 `^22.12.0`（vite 8 peer `^20.19.0 || >=22.12.0` 允许）、`engines.node` 升到 `>=22.12.0`，并核对 `tsconfig.node.json` / `ci.mjs` / Dockerfile / e2e 脚本的 Node 版本——这已不是「单测基建」的范围。不接受用 `peerDependencyRules.allowedVersions` 放行 `@types/node`：那是类型定义与运行时不一致的静默放水。T01 输出同步写具体版本号。
- 分级：MUST FIX

**M-2**
- 位置：spec §2.2 末段「用例中的 UI Schema 夹具优先从 `.harness/contracts/examples/*.json` 读取（… 测试改为 `node:fs` 读绝对路径）」；§2.1「`tsconfig.json`（typecheck）仍包含测试文件」；§6.5「typecheck … 对测试文件同样退出 0」；§7「必要时从契约示例 JSON 经 Zod parse」；tasks T02 输入列出 `ui-schema*.json`。
- 问题：`packages/core/tsconfig.json`（`types: []`）与 `apps/chat/tsconfig.json`（`types: ["vite/client"]`）都不含 `@types/node`，测试里 `import … from 'node:fs'` / `'node:url'` 在两包 typecheck 均报 `TS2591`（已实测）。同时 chat 的 oxlint 禁 `../../*` 与 `@contracts/*`（限 pages / scripts），chat 测试没有任何合规路径读到契约示例；另外 vitest 的 cwd 是子包目录而非 `spark-ui`，「绝对路径」若沿 `process.cwd()` 推导也会错。按现文编码，§6.5 必然失败，而 spec 又规定「规则不放宽」，两条约束互斥。
- 建议：spec 明确选一条并删掉「`node:fs` 读绝对路径」：(a)【推荐，改动最小】core 测试用相对路径静态 import JSON（`import ex from '../../../../.harness/contracts/examples/ui-schema.example.json'`；`resolveJsonModule: true` 已开、core 的 oxlint override 允许 `../../*`、`tsconfig.build.json` 已排除测试不会进 d.ts）；chat 测试**不读示例文件**，改为手写最小夹具 `satisfies UiSchema` / `satisfies SseEvent`（`reduceEvent` 只吃 TS 类型、不跑 Zod，夹具无需契约合法；`types.test.ts` 需契约合法的正例可直接从 core 测试同款 JSON 之外手写一个最小 `run.started` 等）。(b) 若坚持 chat 也读示例：在 `.oxlintrc.json` 加 `apps/chat/src/**/*.test.ts` override 放行 `@contracts/*`（vite alias 与 tsconfig `paths` 都已就位），并把 spec §2.1「规则不放宽」改为「仅放行测试文件对 `@contracts/*` 的只读 JSON 导入」——这是显式、可 grep 的放行。任一方案都不需要给 core / chat 的 tsconfig 加 `types: ["node"]`（会让 src 源码也能悄悄用 Node 全局，削弱浏览器纯度）。
- 分级：MUST FIX

### SHOULD

**S-1**
- 位置：tasks T01 验收「`pnpm -C spark-ui run test` 退出 0（0 用例亦可）」。
- 问题：vitest（4.x / 5.x 均如此）在没有匹配到任何测试文件时打印 `No test files found, exiting with code 1` 并退出 1（已实测）；T01 完成时刻按字面必然失败。
- 建议：T01 在 core 与 chat 各放一个最小 smoke 测试（例如 `componentRegistry.test.ts` 的一条 `REGISTRY_KEYS` 断言，后续 T02 扩写），验收改为「两包各 ≥ 1 用例，退出 0」；不建议加 `--passWithNoTests`——那会让「误把测试文件放到未被 include 的目录」静默变绿。
- 分级：SHOULD

**S-2**
- 位置：tasks T04。
- 问题：四个文件 ≥ 30 用例，其中 `runView.test.ts` 独占约 15–18 条且需在 `exactOptionalPropertyTypes` 下手写 `UiSchema` 夹具，`types.test.ts` 正反例全部要契约合法；估 0.6–0.8 天，超出「≤ 0.5 天」。
- 建议：拆为 T04a「归约与必填校验」（`runView.test.ts` + `useAgentRun.test.ts`，≥ 18 用例，含 `reduceEvent` 10 事件各 ≥ 1）与 T04b「请求构造与契约投影」（`agentRunApi.test.ts` + `types.test.ts`，≥ 12 用例）；T05 依赖改为 T02 / T03 / T04a / T04b。
- 分级：SHOULD

**S-3**
- 位置：spec §2.1「`ci` 脚本在 `typecheck` 之后插入 `pnpm test`」vs tasks T01 输出「根 `test` 与 `ci` 更新」vs T05 输出「`ci` 脚本插入 `pnpm test`」。
- 问题：`ci` 脚本改动同时列在 T01 与 T05 输出里，归属不清；若 T01 已插入，T02–T04 期间任一测试失败都会让整条 `ci` 红，而 T05 才是「测试成为门禁」的节点。
- 建议：`ci` 插入只保留在 T05；T01 只加各包 `test` script 与根 `test` fan-out。spec §2.1 该句改为「T05 把 `pnpm test` 接入 `ci`」。
- 分级：SHOULD

**S-4**
- 位置：spec §2.2 `runStatusText.test.ts` 行「streaming 取最后一个未完成工具」；§3「不改任何被测实现」。
- 问题：实现是从后往前找第一个 `status !== 'succeeded'` 的工具——`failed` 也算「未完成」并显示「正在 X…」。spec 用「未完成」描述会让编码者按直觉写 `status === 'running' | 'selected'` 的断言，与实现冲突后只能改测试或改实现（后者违反 §3）。
- 建议：spec 明确「最后一个 `status !== 'succeeded'` 的工具（含 `failed`）」并加一条用例锁定该行为；若认为这是 bug，按 §3 记入 summary 另开 change，不在此改。
- 分级：SHOULD

### LOW

**L-1**
- 位置：spec §2.1「`import.meta.env` 由 vitest 注入 `MODE=test / DEV / PROD`」；§7「若不满足则在 `vite.config.ts` `test.env` 补」。
- 问题：已实测 vitest 注入 `{ MODE: 'test', DEV: true, PROD: false }`，`z.boolean()` 满足，spec 表述正确；但「`test.env` 补」这条兜底若真要用，`vite` 的 `defineConfig` 类型没有 `test` 字段，必须把两处 `vite.config.ts` 的 import 换成 `vitest/config`，且根 `tsconfig.node.json` 的 typecheck 会连带受影响——spec 没写。
- 建议：§7 该行改为「已验证 vitest 默认注入满足；不需要 `test.env`」，把「`command === 'serve'` / `MODE=test` / env parse 通过」列为 T01 的验证点。若未来确需 `test` 配置块，再单独说明 `vitest/config` 的切换。
- 分级：LOW

**L-2**
- 位置：spec §2.1「oxlint 与 prettier 覆盖测试文件，规则不放宽」。
- 问题：`.oxlintrc.json` `env` 只有 browser + es2022，测试里若用 `process` / `Buffer`（例如断言 `process.cwd()` 或用 `Buffer.from` 造 chunk）可能触发 `no-undef` 类规则；用 `TextEncoder` / `ReadableStream` / `Response` 则无此问题。
- 建议：spec §2.2 补一句「假 fetch 与 chunk 只用 Web 标准 API（`Response` / `ReadableStream` / `TextEncoder`），不用 Node 专有全局」，与 environment: node 但保持浏览器语义的目标一致。
- 分级：LOW

**L-3**
- 位置：spec §2.2 `runView.test.ts` 行；`runView.ts` `beginTurn` 的 `id = t${Date.now().toString(36)}_${seq}`。
- 问题：回合 `id` 依赖 `Date.now()` 与模块级 `seq`，跨用例非确定；spec「断言精确到值」若被套用到 `id` 上会产生脆弱测试。
- 建议：spec 注明「`ChatTurn.id` 只断言唯一性 / 非空，不断言值」。
- 分级：LOW

**L-4**
- 位置：spec §6.3「体积基线不变」。
- 问题：`verify-pack (f)` 的判定是 `dist ≤ 基线 × 1.1`，不是相等；「不变」不可被现有命令校验。
- 建议：改为「`verify-pack (f)` 通过且 `scripts/verify-pack.baseline.json` 无 diff」。
- 分级：LOW

**L-5**
- 位置：spec §2.2 `httpClient.test.ts` 行「schema 不符抛 `HttpError` 并 `console.error`」vs §7「不断言日志文本」。
- 问题：两处不矛盾但容易被读成矛盾。
- 建议：改为「`vi.spyOn(console, 'error')` 断言被调用一次，不断言参数文本」。
- 分级：LOW

### INFO

**I-1**
- 位置：spec §6.1 与 tasks 下限。
- 问题：3 + 2 + 4 = 9 文件、20 + 20 + 30 = 70 用例，与 §6.1 完全一致；按 S-2 拆分后需保持 T04a + T04b ≥ 30。
- 分级：INFO

**I-2**
- 位置：spec §2.2 全表。
- 问题：逐条对照源码，表中列出的不变式与实现一致：`FormPropsSchema.fields.min(1)`、`Card.items.max(32)`、`Table.cells ≤ 16 键`、`Result.status` 四枚举、`Timeline.time` ISO、`inlineAction.intent` 禁 `://` 与 `<`、`confirmationToken.min(16)`、`PROPS_SCHEMAS` / `desktopRegistry` / `mobileRegistry` 均 `Object.freeze`、`RunFailureCode` 恰 5 个、`RunSummary` 两条 superRefine、`parseFrame` 空 data → `null`、`consumeSse` 尾帧回调、`request` 仅有 body 时加 `Content-Type`、`missingRequiredFields` 对 `undefined` / `''` 判缺、`skeletonVariant` 实际只返回三种（类型里的 `'form'` 无分支）。无失实条目。
- 分级：INFO

**I-3**
- 位置：spec §2.1「`check-deps.mjs` 已跳过 `.test.ts`」。
- 问题：核对 `scripts/check-deps.mjs:29`，`walk` 排除 `.test.ts` / `.test.tsx`，属实。
- 分级：INFO

**I-4**
- 位置：spec §2.3。
- 问题：`coding-standard.md` 现止于 §9，新增 §10 编号正确；`code-review/SKILL.md` 前端段（第 22–28 行）当前无 `test` 行，插入位置在 `typecheck` 之后与 §4 顺序一致；`02-feature-spec.md` / `04-shared-spec.md` 均有「## 必备」节可追加。
- 分级：INFO

---

## 3. 结论

- **verdict: REVISION REQUIRED**
- MUST FIX：M-1（vitest 5.0.0 在 `strict-peer-dependencies=true` + 根 `@types/node ^20` 下 `pnpm install` 确定性失败，且 engines 不含 Node 20；改用 4.1.11 或显式纳入 Node 22 升级）、M-2（`node:fs` 读契约示例在 core / chat 两包 typecheck 均失败，chat 亦无合规导入路径；改为 core 相对 JSON import + chat 手写夹具，或显式放行测试文件的 `@contracts/*`）。
- 两条均已给出可直接落地的替代写法，修订后进入 v2 评审；建议同轮处理 S-1（T01 验收按 vitest 实际行为改写）与 S-3（`ci` 插入归属 T05），否则编码阶段会出现门禁误判。
