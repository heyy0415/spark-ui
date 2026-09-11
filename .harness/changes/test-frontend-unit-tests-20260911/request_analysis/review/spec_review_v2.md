---
mode: plan
verdict: APPROVED
reviewer: expert-reviewer（plan 模式）
round: 2/3
inputs:
  - request_analysis/spec.md（v1 修订后）
  - request_analysis/tasks.md（v1 修订后）
rules:
  - .harness/rules/project-structure.md
  - .harness/rules/coding-standard.md
  - .harness/rules/contracts.md
  - .harness/rules/dev-workflow.md（阶段 1 门禁）
previous: review/spec_review_v1.md（保留）
---

# Spec Review v2 — test-frontend-unit-tests-20260911

> 独立性原则：只重读修订后的 spec / tasks，并复核 v1 两条 MUST FIX 的替代方案是否真的可落地。本轮新增实测：在 `packages/core/src/schema/` 放置探针 `*.test.ts`，以相对路径静态 import `.harness/contracts/examples/ui-schema.example.json` 并调用 `parseUiSchema`，跑 core `tsc -p tsconfig.json --noEmit`、`oxlint --deny-warnings`、`prettier --check`（探针已删除，仓库无残留）。注意：当前工作树 `spark-ui/{package.json,pnpm-workspace.yaml,pnpm-lock.yaml,packages/core/{package.json,tsconfig.build.json},apps/chat/package.json}` 已有未提交改动，按独立性原则本评审未读取这些改动，只评 spec / tasks。

## 0. v1 意见回归

| v1 编号 | 分级 | 状态 | 核对 |
|---|---|---|---|
| M-1 vitest 5 装不上 / Node 20 | MUST FIX | **已解决** | §2.1 钉 `vitest: 4.1.11` 并写明原因；§3 新增「不升 Node / `@types/node` 大版本，不为测试放宽 oxlint / tsconfig `types`」；§7 第一行改为正确的失败点与缓解；T01 输出写明版本号并加 `pnpm -C spark-ui install` 退出 0 验收 |
| M-2 `node:fs` 读示例过不了 typecheck | MUST FIX | **已解决（路径深度有笔误，见 S-1）** | §2.2 末段明确「测试不得 import `node:fs`」；core 相对路径静态 import JSON、chat 手写 `satisfies` 夹具。机制已实测：core 探针 `import ex from '<相对路径>/.harness/contracts/examples/ui-schema.example.json'` + `parseUiSchema(ex)` + `ex.schemaVersion` 取值，tsc / oxlint / prettier 三项均退出 0 |
| S-1 T01「0 用例退出 0」不成立 | SHOULD | 已处理（取 v1 未推荐的分支） | T01 改为 `vitest run --passWithNoTests` 并注明原因。可接受，副作用见 L-1 |
| S-2 T04 超 0.5 天 | SHOULD | 已解决 | 拆 T04a（runView ≥ 18）/ T04b（三文件 ≥ 12）；T05 依赖更新 |
| S-3 `ci` 插入归属 | SHOULD | 已解决 | T01 明示「`ci` 脚本的插入放 T05」 |
| S-4 runStatusText 措辞 | SHOULD | 已解决 | §2.2 改为「从后往前取第一个 `status !== 'succeeded'` 的工具（含 `failed`）」 |
| L-1 / L-3 / L-4 / L-5 | LOW | 未处理 | 不阻塞，见下文沿用 |
| L-2 只用 Web 标准 API | LOW | 部分 | §2.2 已有 `ReadableStream` 假 fetch；未明写「不用 Node 专有全局」，沿用 |

## 1. 阶段 1 门禁与 plan 模式必查项

| 必查项 | 结果 | 备注 |
|---|---|---|
| spec 5 章节 | 通过 | §1 / §2 / §3 / §6 / §7 |
| tasks 每 task 5 项 | 通过 | T01 / T02 / T03 / T04a / T04b / T05 |
| 非目标非空 | 通过 | §3 六条 |
| 验收可校验 | 通过 | §6 六条均为命令 + 退出码 / 计数；T01 验收与 vitest 实际行为一致 |
| 风险 ≥1 + 缓解 | 通过 | §7 六行，第一行已按事实改写 |
| task 标注所属端 | 通过 | `harness` 端在开头定义 |
| contracts task 排序 | 不适用 | §5 NONE |
| 跨端结构列契约文件 | 不适用 | 无 |
| task ≤ 0.5 天 | 通过 | T04a（1 文件 ≥ 18，含 10 事件 + 3 边界 + skeleton）约 0.4 天；T04b 三小文件 ≥ 12 约 0.3 天 |
| 验收数值一致 | 通过 | 文件 3 + 2 + 1 + 3 = 9；用例 20 + 20 + 18 + 12 = 70，与 §6.1 一致 |

## 2. 意见清单

### MUST FIX

无。

### SHOULD

**S-1**
- 位置：spec §2.2 末段「`../../../../.harness/contracts/examples/ui-schema.example.json`」。
- 问题：从 `packages/core/src/schema/`（与 `lib/`、`registry/` 同深度）出发，`../` × 4 只到 `spark-ui/`，仓库根需 × 5。实测 4 级路径 `tsc` 报 `TS2307: Cannot find module`；5 级路径 tsc / oxlint / prettier 均通过。编码者若照抄会在 §6.5 处失败。
- 建议：改为 `../../../../../.harness/contracts/examples/ui-schema.example.json`（5 级），或在 spec 里只写「相对路径指向仓库根 `.harness/contracts/examples/`」不写具体级数。
- 分级：SHOULD

### LOW

**L-1**
- 位置：tasks T01「`vitest run --passWithNoTests`」。
- 问题：无测试文件时静默通过，意味着测试文件被误放到 vitest 默认 include 之外的目录（或命名不带 `.test.`）时门禁不会报警。
- 建议：可接受——§6.1「≥ 9 文件 / ≥ 70 用例」是 T05 的兜底；建议 T05 验收再加一句「vitest 汇总行的文件数 = 9」由人工核对，或在 §2.3 `coding-standard.md` §10 注明「新增测试后确认 vitest 汇总文件数递增」。
- 分级：LOW

**L-2**
- 位置：spec §6.3「体积基线不变」（v1 L-4 沿用）。
- 问题：`verify-pack (f)` 判定是 ≤ 基线 × 1.1，「不变」不可被命令校验。
- 建议：改为「`verify-pack` 通过且 `scripts/verify-pack.baseline.json` 无 diff」。
- 分级：LOW

**L-3**
- 位置：spec §7「若不满足则在 `vite.config.ts` `test.env` 补」（v1 L-1 沿用）。
- 问题：v1 已实测 vitest 注入 `{MODE:'test', DEV:true, PROD:false}` 满足 `env.ts`，该兜底不需要；若真要用需把 `defineConfig` 换成 `vitest/config`，spec 未写。
- 建议：改为「已验证默认注入满足；不需要 `test.env`」。
- 分级：LOW

**L-4**
- 位置：spec §2.2 runView 行「断言精确到值」；`runView.ts` `beginTurn` 的 `id` 含 `Date.now()`（v1 L-3 沿用）。
- 建议：注明 `ChatTurn.id` 只断言非空 / 唯一。
- 分级：LOW

**L-5**
- 位置：spec §2.2 httpClient 行「`console.error`」 vs §7「不断言日志文本」（v1 L-5 沿用）。
- 建议：写成「`vi.spyOn(console, 'error')` 断言被调用，不断言参数文本」。
- 分级：LOW

**L-6**
- 位置：spec §2.2 末段「夹具里的工具 / 实体用中性词（`demo.item.*`）」；T04a `skeletonVariant` 三分支。
- 问题：`skeletonVariant` 靠 toolId 正则 `\.list\.` / `\.(detail|eligibility|status)\.|\.preview$|\.logistics\.` 分支，中性词夹具需形如 `demo.item.list.x` / `demo.item.detail.x` 才能命中；`ToolIdSchema` 限 2–4 段小写点分隔，`demo.item.list.query` 合法。
- 建议：spec 或 T04a 备注一句「skeleton 夹具 toolId 用 `demo.item.list.query` / `demo.item.detail.get` / `demo.item.create`」以免编码者用不命中的中性 id 反复试。
- 分级：LOW

### INFO

**I-1**
- 位置：spec §2.2 chat 夹具「手写 `satisfies UiSchema / SseEvent`」。
- 核对：`runView.test.ts`（features/model）与 `useAgentRun.test.ts`（features/api）从 `@entities/agent-run` 索引 `import type { SseEvent }`、从 `@spark-ui/core` `import type { UiSchema }` 均合规；`types.test.ts` 与 `agentRunApi.test.ts` 同目录相对导入 `./types` / `../model/types` 不触发 `no-restricted-imports`。`reduceEvent` 只吃 TS 类型不跑 Zod，夹具无需契约合法；`types.test.ts` 正例需契约合法（`run_` / `tc_` 前缀、ISO 时间），spec 已隐含。
- 分级：INFO

**I-2**
- 位置：工作树状态。
- 观察：`spark-ui/` 下已有与 T01 高度相关的未提交改动（package.json × 3、lockfile、workspace yaml、tsconfig.build.json）。本评审未读取；阶段 3 编码报告应把这些列为 T01 产出，阶段 4 execution 评审再核对。
- 分级：INFO

## 3. 结论

- **verdict: APPROVED**
- MUST FIX：无。v1 的 M-1 / M-2 均按可落地方案修订并经实测验证。
- 建议在进入阶段 3 前顺手修 S-1（相对路径级数 4 → 5），否则 core 测试第一次 typecheck 即失败；其余 LOW 项可在编码时一并处理。
