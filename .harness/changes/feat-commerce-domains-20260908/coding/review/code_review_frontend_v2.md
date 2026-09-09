# Code Review（前端 + 文档）— feat-commerce-domains-20260908 v2

- **mode**: execution
- **评审范围**: `git diff a598f43..HEAD -- fronted .harness/scripts/e2e-frontend.mjs .harness/rules backed/README.md`（回修提交 `c880f08`，`21df695` 为后端回修不在本文范围）
- **独立性**: 未读 `coding/coding_report_v1.md`；只对照 v1 findings 与代码 / 脚本实际行为
- **未触碰**: 8080 / 5173 未启动、未运行 e2e；所有探针文件已删除，结束时 `git status --porcelain` 为空

## 0. 机械校验实测（真实退出码）

| 命令 | 结果 |
|---|---|
| `pnpm -C fronted run typecheck` | exit 0 |
| `pnpm -C fronted run lint`（oxlint --deny-warnings + check-deps + check-registry） | exit 0 |
| `pnpm -C fronted run ci`（含 build + verify-pack，dist 已重建） | exit 0；verify-pack `(b) peerDependencies = 5 expected peers` ✓、`(f) dist 38 KB ≤ baseline 38 KB × 1.1` ✓ |
| `grep -rn "@ant-design/icons" fronted --exclude-dir=node_modules --exclude-dir=dist` | 仅 `pnpm-lock.yaml` 6 处命中，全部位于 `antd@6.6.2` 的 dependencies 段（antd 自身的传递依赖），非本仓库直接声明；`package.json` × 3 / `pnpm-workspace.yaml` catalog / `verify-pack PEERS` / core README 均 0 |
| `git status --porcelain`（评审结束） | 空 |

## 1. v1 findings 逐项核对

| # | 分级 | 状态 | 证据 |
|---|---|---|---|
| F-01 | MUST FIX | **已关闭** | `packages/core/package.json` peer / dev 各删 1 行；`apps/chat/package.json` dependencies 删 1 行；`pnpm-workspace.yaml` catalog 删 `@ant-design/icons`；`pnpm-lock.yaml` importers 段同步（-9 行）；`verify-pack.mjs:77` `PEERS` 5 项、`:146` 文案改「5 expected peers」；core README 安装命令与 peer 表删行。ci exit 0，verify-pack (b)/(g) 通过。lockfile 残留仅为 antd 传递依赖，符合预期。 |
| F-02 | SHOULD | **已关闭** | `check-registry.mjs:114-125`：新增 `statSync().isDirectory()` → `fail` + `continue`；specifier 收集覆盖 `^(import|export) … from`、副作用 `import 'x'`、`import(…)`、`require(…)`。实测 7 个探针全部红且 exit 1：`Card.helper.tsx` + `export * from 'lodash'`（2 错：文件名 + 引用）、Card.tsx 追加 `await import('lodash')` / `require('lodash')` / `export { pick } from 'lodash'` / 多行 `import {\n pick,\n} from 'lodash'` / `import type { X } from 'lodash'`、子目录 `desktop/sub/`（2 错，不再 EISDIR 崩溃）。恢复后 baseline exit 0。 |
| F-03 | SHOULD | **已关闭（主路径）**，残留边界见 N-02 | `runView.ts:52-53` `beginTurn`：`phase === 'waiting_confirmation'` → `ui: null`，否则保留；`pendingActionId: null`、`failure: null`、`tools: []` 均清。相位推演：(a) waiting_confirmation → 新 turn：ui 清空，旧 Form 消失，若新 run 无 ui.replace 结束则 `completed && !ui` 显示「已完成」✓；(b) completed → 保留旧 ui（Table 可继续点行内指令）✓；(c) failed → 保留旧 ui。提交路径唯一是 `ActionBar`（`AgentChatPanel.tsx:147` 仅 `phase === 'waiting_confirmation'` 渲染），`submitAction` 用 `action.confirmationToken` 来自 `view.ui.actions`。因此在 (b)/(c) 下旧 Form 只能填不能提交，无 token 泄漏。残留：cancel（`useAgentRun.ts:141-147` → completed 但 ui 仍是确认屏）或确认后 `run.failed` 时，旧确认 Form 会随后续轮次持续保留（不可提交、但可见可填），见 N-02。 |
| F-04 | SHOULD | **已关闭** | desktop `Table.tsx:25` / mobile `Table.tsx:23` `key={i}`；`data-intent` 仍为原文。 |
| F-05 | SHOULD | **已关闭** | `uiSchema.ts:81-83` `.refine(keys ≤ 16)`；vite-node 实测：16 键 `success: true`、17 键 `false`、`order-table` 示例仍通过。 |
| F-06 | SHOULD | **已关闭** | `e2e-frontend.mjs:149` 期望表 `{confirm 3, result 1, order-table 1, product-table 1, order-detail 3, logistics 2, aftersale-confirm 2, delete-confirm 1}`；用 node 读取 8 个 `.harness/contracts/examples/ui-schema.*.example.json` 的 `components.length` 逐个核对：`ui-schema.example.json`=3（对应 playground 键 `confirm`）、result=1、order-table=1、product-table=1、order-detail=3、logistics=2、aftersale-confirm=2、delete-confirm=1，全部一致。新增 `[role="alert"][data-component-id]` = 0 断言（:161,167）与 `mismatched` 列表比对（:166，`'[]'` vs `JSON.stringify`，字符串相等可用）。 |
| F-07 | SHOULD | **已关闭（脚本）**，文档残留见 N-03 | `e2e-frontend.mjs:122` 改为 `['Card', 'Card', 'Form'].length`。`grep OrderCard\|RefundConfirmCard\|LogisticsTimeline\|ProductCard` 在 `.harness/scripts` 为 0；`.harness/rules` 命中 3 处均为「禁止业务命名」的反例 / 变更记录（合理保留）；`fronted/README.md:60` 仍写「确认卡片（OrderCard + RefundConfirmCard + Form）… → ResultCard」，本次未改。 |
| F-08 | SHOULD | **已关闭** | `backed/README.md:20` `| app |` 行移入表内，说明段落在表后（实读 :14-24）。 |
| F-09 | SHOULD | **已关闭** | `project-structure.md:34` 「17 运行时 + 19 类型导出」、`:35-36` schema/registry 职责改写；`verify-pack.mjs:11` 同步 19。 |
| F-10 | LOW | **未真正关闭**（见 N-01） | `AgentChatPanel.tsx:34-51` 改为 `useCallback(…, [busy, start, pageContext])`。但 `start` 是 `useMutation` 返回对象，TanStack v5.102.8 源码 `useMutation.js:17-21` 每次渲染返回新对象 `{...result, mutate, mutateAsync}`，因此依赖项每帧变化，`send` 仍然每帧重建，`SchemaRenderer` 的 `useMemo([onFormChange, onIntent])` 依旧失效。功能无影响。 |
| F-11 | LOW | **已关闭** | `AgentChatPanel.tsx:94` `role="group" aria-label="示例问题"`。 |
| F-12 | LOW | **部分 / 语义变化** | 两端 Table 加 `disabled={handlers?.onIntent === undefined}`——这是「无 onIntent 处理器时禁用」（playground 场景），**不是** v1 描述的宿主 busy 期间禁用；chat 宿主始终传 `onIntent`，busy 时按钮仍可点、`send` 内静默 return。v1 已标「本期可接受」，维持 LOW 不阻塞；但需知这条改动没有解决原问题。 |

## 2. 新发现 / 残留

| # | 位置 | 问题 | 建议 | 分级 |
|---|---|---|---|---|
| N-01 | `fronted/apps/chat/src/features/agent-chat/ui/AgentChatPanel.tsx:50` | `useCallback` 依赖 `start`（useMutation 整体返回值），该对象每帧新建 → memo 无效，F-10 名义修复实际未生效。 | 依赖改为 `start.mutate`（v5 内部 `useCallback([observer])`，引用稳定）：`const { mutate } = start; … [busy, mutate, pageContext]`。`pageContext` 在 `ChatPage.tsx:13` 已 `useMemo`，无需处理。 | LOW |
| N-02 | `runView.ts:53`；`useAgentRun.ts:141-147` | `beginTurn` 只在 `waiting_confirmation` 清 ui。用户点「取消」（→ completed）或确认提交后 `run.failed`，ui 仍是确认屏（Card + Form）；此后每一轮新对话若无 ui.replace，旧确认 Form 持续可见可填（ActionBar 隐藏，不可提交，无安全问题）；同时 cancel 后 `completed && !ui` 的「已完成」不会显示。 | 在 cancel 分支同时置 `ui: null`；或让 `beginTurn` 依据「旧 ui.actions 中是否存在带 `confirmationToken` 的 confirm 动作」而非仅 phase 决定是否清屏。补 e2e：取消后发「有什么商品」→ `[data-screen-id="refund-confirmation"]` 为 0。 | LOW |
| N-03 | `fronted/README.md:60` | 联调步骤仍写 `OrderCard + RefundConfirmCard` / `ResultCard`（已删业务组件名）。F-07 的 stale-name grep 路径应把 `fronted/**/README.md` 纳入。 | 改为「确认屏（Card + Card + Form）→ … → Result」。 | LOW |
| N-04 | `fronted/scripts/verify-pack.mjs:8,157` | 文件头注释「6 peer」与行注释「6 个 peer 必须都在 catalog」未随 `PEERS` 改为 5；`:146` 文案已改，三处不一致。 | 两处改 5，或去掉数字写「全部 peer」。 | LOW |
| N-05 | `fronted/scripts/check-registry.mjs:121` | `^\s*(?:import|export)[^'"]*?\bfrom\s*['"]…['"]` 允许 `import`/`export` 与 `from` 之间任意非引号字符，`export const A = 1; // see from 'x'` 这类行尾注释会被误报为引用 `'x'`。当前 10 个组件文件无此模式（baseline exit 0），只会误红不会漏红，方向安全。 | 记录即可；若将来误报，可在正则前先剥离 `//` 注释。 | INFO |
| N-06 | `.harness/scripts/e2e-frontend.mjs` step 6/7 | 本轮未启动 8080 / 5173，e2e 新断言（step 6 每视口 3 项、step 7 9 项）未运行验证，只做了静态与期望值核对。 | 阶段 7 deploy-verify 时以真实退出码补证。 | INFO |

## 3. 回归检查

- `apps/chat` 移除 `@ant-design/icons` 后 `grep -rn "@ant-design" fronted/apps/chat/src fronted/packages/core/src` 0，typecheck / build 通过，无运行时消费者被误删。
- `Table` 两端 `disabled` 新属性在 `handlers` 存在时为 `false`，行为等价；`key={i}` 不影响 `data-intent` 定位（e2e step 7 用 `[data-intent="…"]`）。
- `cells` refine 不改变 `TableRow` 推导类型（`z.infer` 仍为 `Record<string, string>`），`index.ts` 17 运行时 / 19 类型导出与 verify-pack 清单一致（(c)/(e) 通过）。
- `beginTurn` 仅在一个 phase 上清 ui，`ui.patch` 对 `ui === null` 的守卫（`runView.ts:110`）已有，不会因新路径抛错。
- check-registry 新增目录检查发生在文件名检查之后，目录会先被文件名规则报一次再被目录规则报一次（探针 5 出 2 错），可读性可接受。

## 4. verdict

**APPROVED**

v1 的 1 条 MUST FIX 与 8 条 SHOULD（F-01 ~ F-09）全部有代码证据关闭，`pnpm -C fronted run ci` 真实退出码 0，check-registry 7 个绕过探针全部红。残留 N-01 ~ N-04 均为 LOW（memo 无效 / 文案过时 / 取消路径旧屏残留），不影响契约、安全边界与验收项，可在阶段 5/6 顺手处理或记入后续；N-06 的 e2e 实跑留待阶段 7。
