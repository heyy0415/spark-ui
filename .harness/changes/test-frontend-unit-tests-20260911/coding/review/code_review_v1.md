# Code Review v1 — test-frontend-unit-tests-20260911

- **mode**: execution
- **verdict**: **APPROVED**（0 条 MUST FIX；4 条 SHOULD、7 条 LOW、4 条 INFO）
- 评审对象：git 工作区全部未提交改动（14 个已跟踪文件 + 9 个新增 `*.test.ts`）
- 依据：`.harness/rules/{coding-standard,project-structure,contracts,agent-safety}.md`、`request_analysis/spec.md`、`tasks.md`；coding report 仅用于定位文件，结论均独立核实。

## 0. 评审者自行执行的验证

| 命令（`spark-ui/` 下） | 退出码 | 观察 |
|---|---|---|
| `pnpm run test` | 0 | core 3 文件 / 31 用例，chat 6 文件 / 68 用例，合计 9 / 99 / 0 失败 |
| `pnpm run typecheck` | 0 | core / chat / tsconfig.node 三段全过（测试文件在 `tsconfig.json` include 内，受 strict + EOPT 约束） |
| `pnpm run lint` | 0 | oxlint --deny-warnings + check-deps + check-registry |
| `pnpm run format:check` | 0 | |
| `find packages/core/dist -name '*.test.*' \| wc -l` | — | 0 |
| 探针 1：临时配置删除 `test.deps` 后跑 chat 测试 | — | 3 个 suite 加载失败：`antd-mobile/cjs/global/index.js:3 SyntaxError: Unexpected token ':'`（即 `require("./global.css")`）；失败的正是运行时 import `@spark-ui/core` 值的 3 个文件（`useAgentRun.test` / `types.test` / `agentRunApi.test`），只 `import type` 的 `runView.test` 不受影响 |
| 探针 2：只删 `esbuildOptions.loader['.css']` | — | 同样 3 个 suite 失败 → `.css` 置空是必要条件，注释所述属实 |
| 全仓 `ci` / `doctor` | 引用 `ci_result/ci_stage4.txt` | `CI_EXIT=0`、`DOCTOR_EXIT=0`；探针配置文件已删除，`git status` 无残留 |

## 1. 前端红线（coding-standard §1 / §8 / §9，project-structure §1 / §4）

逐文件 grep + 人工阅读，结论：

- `any`：0 处。非空断言 `!`：0 处（全部用 `if (!x) throw` 或 `?.` 收窄）。`console.log` / `@ts-ignore` / `@ts-expect-error` / `setTimeout` / sleep：0 处。
- FSD 方向（`check-deps` 跳过 `.test.ts`，人工核对）：
  - `features/agent-chat/model/runView.test.ts`：`@spark-ui/core`（type）、`@entities/agent-run`（type，经 index）、`./runView`。features → entities 单向，未穿透 `@entities/*/model/*`。
  - `features/agent-chat/api/useAgentRun.test.ts`：`@spark-ui/core`（type）、`./useAgentRun`。
  - `entities/agent-run/{api,model}/*.test.ts`、`shared/api/*.test.ts`：只 import 同 slice 相对路径 + vitest + zod。`sseClient.test` import `./httpClient` 属同目录。
  - 无任何 `@features/*/model/*`、`../../*`、`@contracts/*`、antd 导入。
- core 三个测试：只 import 本包相对路径（`./uiSchema`、`../registry/types`、`../schema/uiSchema`）+ vitest + 5 级相对路径契约示例 JSON。无别名、无 `node:*`。
- 契约 JSON import 不进 d.ts：`tsconfig.build.json` exclude 生效（dist 0 个 test 文件；verify-pack (a)(c)(e)(f) 全过，见 §6）。

## 2. `apps/chat/vite.config.ts`

| # | 位置 | 问题 | 建议 | 分级 |
|---|---|---|---|---|
| 2.1 | `vite.config.ts:4` | `defineConfig` 改自 `vitest/config`。核实 `ci_stage4.txt` 的 `build:chat`：`2478 modules transformed`、`dist/assets/index-DNo901ce.js 353.50 kB`，与上一 change（`test-backend-unit-tests-and-fixes-20260911/ci_result/ci_and_e2e_stage4.txt`）逐字节同名同体积，chunk hash 一致 → 产物未变。`serve` / `preview` 段未动。 | 无需修改 | INFO |
| 2.2 | `vite.config.ts:53-64` | 每次 `pnpm test` 打印 Vite 8 弃用警告：`optimizeDeps.esbuildOptions ... deprecated. Please use optimizeDeps.rolldownOptions`。探针 2 证明该 loader 是测试可跑的必要条件；一旦 vitest / vite 升级移除 `esbuildOptions` 透传，chat 侧 3 个 suite 会直接加载失败。 | 在 `summary.md`「经验沉淀」与 §7 风险表登记该依赖；升级 vitest / vite 时的检查项写进 `code-review/SKILL.md` 或 catalog 注释。可选的更稳做法：测试模式下用 `test.alias` 把 `antd-mobile` 指向空 stub 模块（chat 测试从不渲染），去掉对 esbuild loader 的依赖。 | SHOULD |
| 2.3 | 同上 | 与真实环境差异：antd-mobile 被 ssr 预打包且 CSS 置空；`css: false`。本 change 不测组件渲染，chat 测试只沿 `@spark-ui/core` 入口取 Zod schema（`FormPropsSchema` / `UiSchemaSchema`），antd-mobile 运行时行为不在断言路径上 → **可接受**。但这意味着一旦有人在 chat 侧写渲染测试，此配置不成立。 | 在 coding-standard §9 已写「不测渲染」，够用；建议注释里再补一句「渲染测试需另配 jsdom，勿沿用本段」 | LOW |
| 2.4 | 同上 | 注释称「试过 server.deps.inline / ssr.noExternal 都不生效」——本评审只验证了现配置是必要条件，未复现替代方案不可行。 | 不阻塞；如 2.2 采用 alias stub 方案则此注释可简化 | INFO |

## 3. 测试质量

### 3.1 通用

- 弱断言：全仓 0 处 `toBeDefined` / `toBeTruthy` / `toBeFalsy` 单独使用。仅 `uiSchema.test.ts:32` `rows.length toBeGreaterThan(0)`、`componentRegistry.test.ts:26-27` `typeof === 'object'` 偏弱，见下表。
- `vi.spyOn(console, 'error')` 只出现在 `httpClient.test.ts:84`，`afterEach(vi.restoreAllMocks)`（:22-24）还原；无 `vi.mock` 全局篡改。
- 计时：0 处；`consumeSse` 用 `ReadableStream` 同步 enqueue 后 close，符合 spec §2.2。
- `as` 断言：`(e as HttpError)` 在 `httpClient.test` 三处均紧跟 `toBeInstanceOf(HttpError)`（:61→62、:76→77、:92→93），有运行时依据；`sseClient.test.ts:158` 例外，见 3.4。`headers as Record<string,string>` 是对自己构造的 `init.headers` 取值，可接受。

### 3.2 `runView.test.ts` 对照 spec §2.2

| spec 条目 | 用例 | 结论 |
|---|---|---|
| `beginTurn` 追加回合且收口上一回合 `waiting_confirmation`（M2） | :67-79（前一回合 → completed、pendingActionId null、新回合 streaming） | ✓ 精确到值 |
| `beginConfirm` / `cancelConfirm` | :93-116 | ✓ |
| `failIfStillStreaming` 只对 streaming 生效（M1） | :118-130：streaming → failed + `INTERNAL_ERROR` 文案；completed 不变；空视图不变 | ✓ 但缺 `waiting_confirmation` 分支，见 3.3 |
| `reduceEvent` 10 事件各一条 | run.started :139、message.delta :146、tool.selected/started/completed :153-188、completed 无 summary :190、ui.replace :219、ui.patch 同屏合并 :226 / 异屏 + 无屏 :241、confirmation.required :259、run.completed + run.failed :268 | ✓ 10/10 |
| 旧流残帧丢弃（S8） | :281-290（message.delta 与 run.failed 均被丢） | ✓ |
| 空视图 reduce no-op | :134（`toBe(v)` 引用相等） | ✓ |
| `skeletonVariant` 三分支 | :315-321 | ✓ |
| 额外 | 新 run.started 重绑 :292；run.started 之前的事件作用于未绑定回合 :300；回合 id 唯一 :88 | 有价值的补充 |

### 3.3 - 3.7 具体意见

| # | 位置 | 问题 | 建议 | 分级 |
|---|---|---|---|---|
| 3.3 | `runView.test.ts:118-130` | M1 最关键的真实路径是：服务端发出 `confirmation.required` 后正常关闭 SSE，`useAgentRun.finalize()` 调 `failIfStillStreaming`，此时回合为 `waiting_confirmation`，**必须不被改成 failed**（否则确认屏被打断）。当前只验证了 completed 与空视图，未覆盖 `waiting_confirmation`。 | 增一条：`confirmation.required` 后 `failIfStillStreaming` 状态仍为 `waiting_confirmation` 且 `pendingActionId` 保留 | SHOULD |
| 3.4 | `sseClient.test.ts:157-162` | `const err = e as HttpError` 之前没有 `toBeInstanceOf(HttpError)`（同文件 :145 有）。若实现回归为抛其它错误，`err.status` 为 undefined 使 `toBe(502)` 仍失败，用例不算失效，但违反「断言须有运行时依据」。 | 补 `expect(e).toBeInstanceOf(HttpError)` | LOW |
| 3.5 | `sseClient.test.ts:84-87` | 跨 chunk 用例切点在 `run.st\|arted` 与 JSON 中间，确为 token 内部切分 ✓。未覆盖两类边界：(a) 分隔符 `\n\n` 或 `\r\n` 被切成两 chunk；(b) 多字节 UTF-8（中文 `message.delta`）在字节层被切开——`decoder.decode(value, { stream: true })` 正是为此存在，是最容易被「优化」掉的一行。 | 增两条：`['event: a\ndata: 1\n', '\n']` 与把 `encoder.encode('data: {"text":"中文"}')` 的字节数组从中间切开 | LOW |
| 3.6 | `uiSchema.test.ts:32`、`:62` | `rows.length toBeGreaterThan(0)` 与 `toThrowError()` 不指定错误类型。 | 断言示例的确切行数；`toThrow(ZodError)`（core devDeps 已有 zod） | LOW |
| 3.7 | `componentRegistry.test.ts:24-29` | 标题说「lazy component (object with $$typeof)」，断言只有 `typeof === 'object'`。 | 断言 `'$$typeof' in desktopRegistry[key]`，或删掉标题里的 `$$typeof` | LOW |
| 3.8 | `types.test.ts:75-86` | 标题「only accepts the five failure codes」，实际只验 1 个接受 + 1 个拒绝。 | 遍历 `RunFailureCodeSchema.options` 五个值逐个 `success === true` | LOW |

## 4. 契约投影 vs `.harness/contracts/*.schema.json`（抽查 5 条，均逐字对照原文）

| 契约 | Schema 原文 | Zod 投影 | 测试 | 结论 |
|---|---|---|---|---|
| `action-request.formData` | `maxProperties: 16`、`propertyNames.pattern ^[a-zA-Z][a-zA-Z0-9_]*$`、值 string≤512 / number / boolean | `types.ts:80-85` 完全一致 | `agentRunApi.test.ts:44-56` 17 键拒绝、`1bad` 键拒绝 | ✓ |
| `action-request.confirmationToken` / `ui-schema.action.confirmationToken` | `minLength 16, maxLength 256` | `types.ts:89`、`uiSchema.ts:177` | `agentRunApi.test:38`（短）、`uiSchema.test:140-151`（短 / 257 长） | ✓ |
| `ui-schema.inlineAction.intent` | `pattern ^(?!.*(://\|<)).*$`，`maxLength 200` | `uiSchema.ts:58-62` | `uiSchema.test:128-136` | ✓ |
| `sse-events.runFailed.code` | 5 值 enum | `types.ts:27-33` | `types.test:75-86` | ✓（见 3.8） |
| `ui-schema.tableRow.cells` / `cardProps.items` / `timelineProps.items[].time` / `resultProps.status` | `maxProperties 16` / `maxItems 32` / `format date-time` / 4 值 enum | `uiSchema.ts:83-85, 72, 132, 117` | `uiSchema.test:82-126` | ✓ |

契约影响 NONE 属实：`.harness/contracts/` 无 diff，`check-contracts` 与 `sync-contracts: 37 files in sync` 通过。

## 5. 与 spec 的偏差

| # | 位置 | 问题 | 建议 | 分级 |
|---|---|---|---|---|
| 5.1 | `coding-standard.md:60-68` | spec §2.3 与 tasks T05 写的是「新增 **§10**」，实现改为新增 §9、原 §9「提交与变更」顺延为 §10。全仓 grep `coding-standard §N`：引用 §9 的 3 处（`project-structure.md:59`、`02-feature-spec.md:36`、`04-shared-spec.md:25`）全部指向新的「单元测试」，语义正确；`docs/AUTHORING-GUIDE.md:652` 引用 §8 未受影响；不存在把「提交与变更」写成 §9 的旧引用。偏差无副作用，但 coding report 与 spec 不一致处需在 summary 标注。 | summary.md 阶段 3 行或「经验沉淀」注明「章节号与 spec 不同：§9 单元测试 / §10 提交」 | LOW |
| 5.2 | spec §2.3 五处文档 | `coding-standard.md` ✓、`project-structure.md` §1 ✓、`code-review/SKILL.md` 前端段 ✓、`02-feature-spec.md` ✓、`04-shared-spec.md` ✓；另改了 `spark-ui/README.md`（技术栈 + 命令表 + ci 顺序），spec 未列但属必要同步。 | 无 | INFO |
| 5.3 | 用例数 | 99 ≥ 70；T02 30 ≥ 20、T03 23 ≥ 20、T04a 26 ≥ 18、T04b 22 ≥ 12。 | 无 | INFO |
| 5.4 | `summary.md`「经验沉淀」 | 仍为模板占位。本 change 至少有两条 Agent 层面的教训已被评审 / 编码抓出：(a) spec v1 选了 vitest 5，与 Node 20 + `strict-peer-dependencies` 冲突（spec_review_v1 M-1）；(b) antd-mobile CJS `require("./global.css")` 在 Node 下不可加载，只能靠 ssr 预打包 + 弃用中的 `esbuildOptions`（见 2.2）。按 CLAUDE.md「Hashimoto 法则」应先落到这里，再决定是否升级为 Skill / Rule 检查项。 | 在「经验沉淀」追加两行；(a) 已通过 catalog 注释固化，(b) 建议补进 `frontend-doctor` 或 catalog 注释 | SHOULD |
| 5.5 | `spec.md §3` 「不改任何被测实现；发现 bug 记入 summary」 | 被测实现 diff 为 0 ✓。评审阅读中注意到 `runView.ts:183` `skeletonVariant` 返回类型含 `'form'` 但无任何分支产生它（死联合成员），测试因此只能覆盖三分支。非本 change 范围。 | 记入 summary 待办，不在本 change 修 | INFO |

## 6. `packages/core/tsconfig.build.json` exclude 与 verify-pack

`ci_stage4.txt:214-230`：(a) tarball 仅 package.json / README.md / dist（43 entries）✓；(c) runtime exports 19 与 spec 清单一致 ✓；(e) consumer.ts 在 EOPT true / false 两种下都能对 dist d.ts 通过 ✓；(f) 47 KB ≤ 基线 47 KB × 1.1 ✓。本地 `find dist -name '*.test.*'` = 0。`exclude` 只匹配 `src/**/*.test.ts`，若将来出现 `.test.tsx` 会漏——与 coding-standard §9「不测渲染、只 `*.test.ts`」一致，暂不需扩展。

| # | 位置 | 问题 | 建议 | 分级 |
|---|---|---|---|---|
| 6.1 | `tsconfig.build.json:13` | exclude 仅 `*.test.ts`；`check-deps.mjs:29` 同时跳过 `.test.tsx`，两处口径不一。 | 为一致性可把 exclude 写成 `src/**/*.test.{ts,tsx}`；LOW，因规则层已禁 `.test.tsx` | LOW |

## 7. agent-safety 六条核对

未改后端、未改任何运行时实现；本 change 对 §4 前端边界是**加固**：注册表冻结与白名单等价（`componentRegistry.test`）、白名单外 type 拒绝、`inlineAction` 禁 `://` / `<`、令牌只做长度校验不解析、必填校验在发请求前完成（`useAgentRun.test` 头注释点明「决定是否消耗一次性令牌」）；§6 流式：`SseEventSchema` strict 拒绝多余键（`types.test:50` 用 `stack` 字段作反例，正对应「禁止透传堆栈」）。§1 / §2 / §3 / §5 无涉及。

## 8. 汇总

- **MUST FIX**：无。
- **SHOULD**：2.2（esbuildOptions 弃用风险登记 / 替代方案）、3.3（M1 补 `waiting_confirmation` 分支）、5.4（经验沉淀两条）。
- **LOW**：2.3、3.4、3.5、3.6、3.7、3.8、5.1、6.1。
- **INFO**：2.1、2.4、5.2、5.3、5.5。

verdict：**APPROVED**。SHOULD 项建议在阶段 5 推送前一并处理（改动都在测试文件 / summary / 注释，不触及实现，无需再开评审轮）。
