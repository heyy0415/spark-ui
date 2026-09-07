# Code Review v2 — feat-strato-ui-monorepo-20260904

| 字段 | 值 |
|---|---|
| mode | execution |
| 日期 | 2026-09-04 |
| 轮次 | 1 / 2（expert-reviewer 独立评审；`code_review_v1.md` 为机械项记录，未阅读） |
| 评审对象 | commits `cac3d5b..HEAD`（8 个：f146764 → fa70526）；`fronted/**`、`.harness/scripts/**`、`.harness/rules/**`、`.harness/skills/**`、`CLAUDE.md` / `AGENTS.md`、`.harness/contracts/ui-schema.schema.json` |
| 依据 | spec v3.1 / tasks v3.1；`spec_review_v{1,2,3}.md` 的 MUST FIX / SHOULD 落地；rules/{project-structure, coding-standard, contracts, agent-safety, dev-workflow}；skills/expert-reviewer execution 必查项 |
| 独立性 | **未读** `coding/coding_report_v1.md` 与 `coding/review/code_review_v1.md`。只看产出物、spec、规则；所有结论来自本机实际执行的命令（输出摘录附各条），植入反例只在 `/tmp` 副本或纯读推理，仓库文件零改动 |

---

## 0. verdict

**REVISION REQUIRED** — MUST FIX 1 条（M1），SHOULD 7 条（S1–S7），LOW 13 条，INFO 9 条。

总体：代码与构建链路质量高，spec 三轮评审的 8 条 MUST FIX 与 17 条 SHOULD 在实现中**全部落地**（§2 表）；`rm -rf packages/core/dist && pnpm -C fronted run ci` 与 `pnpm -C .harness run ci` 本机实测退出码均为 0；契约字段零变化；agent-safety §4 两列与代码逐行一致。唯一 MUST FIX 不在代码而在 **L1 记忆文件**：`CLAUDE.md` / `AGENTS.md` / `platform-owner.md` 的「硬约束」仍写着 *antd / antd-mobile 只在 `shared/ui/**` 内 import*，与 `coding-standard.md` §4、`project-structure.md` 红线 2 以及实现（antd 在 `packages/core/src/components/**`）直接冲突——按 L1 字面，当前实现是违规的；按规则，L1 是错的。两者必须归一，且 spec §6.1 的 grep 与 doctor 的路径检查都抓不到它（Hashimoto 落点见 M1 建议）。

---

## 1. 机械门禁实测（本机，2026-09-04 评审时）

| 命令 | 结果 |
|---|---|
| `rm -rf fronted/packages/core/dist && pnpm -C fronted run ci` | **rc=0**；`16 examples OK`；verify-pack 13 ✓（(a)(b)×5(c)(g)×2(d)(e)×2(f)）；dist 40 KB = 基线 |
| `pnpm -C .harness run ci` | **rc=0**：check-contracts 0 / check-module-deps 0 / fronted 0 / backed 0 |
| `pnpm -C .harness run doctor` | 0 errors, 0 warnings；「doc paths」「scripts: no hard-coded change directory」两项 ✓ |
| `pnpm -C .harness run check-contracts` | 9 schemas OK；`git diff cac3d5b..HEAD -- .harness/contracts` 仅 1 行 description 文本 |
| `git diff --stat -M 33da291~1 33da291` | 26 行全部 R100 rename，`0 insertions / 0 deletions` ✓（T02a 纯移动） |
| `git diff --stat cac3d5b..HEAD -- backed` | 空（后端零改动 ✓） |
| 8 个 commit footer | 全部含 `Change: feat-strato-ui-monorepo-20260904` ✓ |
| spec §6.1 第 4 条 stale-name grep | 0 行 ✓ |
| spec §6.2 三条 grep（eval/http、antd 越界、`--color-`/`getComputedStyle`） | 均 0 行 ✓ |
| spec §6.3 两条 grep（chat 内 antd、深路径） | 均 0 行 ✓ |
| `grep -c 'from "antd"' dist/index.js` / cssinjs 签名 | 1 / 0 ✓ |
| `grep -rn "from ['\"]antd\|@ant-design" dist/**/*.d.ts` | 0 行 ✓；d.ts 外部 import 只有 `zod`（2）与 `react`（内联 `import("react")` ×22） |
| `find dist -name 'vite-env*'` | 0 ✓ |
| `node fronted/scripts/check-registry.mjs` / `check-deps.mjs` | 均 ✓ |
| `node .harness/scripts/lib/change-dir.mjs` / `STRATO_CHANGE=nonexistent …` | 输出本 change deployment 路径 rc=0 / rc=2 ✓ |
| §6.4 e2e-frontend / deploy-verify | **本轮未重跑**（需后端 8080 与 dev server）；只核对了 `deployment/preview-console.log`（`agent-input=1`，两页 console.error=0）与 `bundle_size.txt` 形态与 §2.5 一致 |

---

## 2. spec 逐节符合性

| spec 节 | 要求 | 结果 | 证据 |
|---|---|---|---|
| §2.1 根 `ci` 顺序 | `build:core → typecheck → lint → format:check → verify-examples → build:chat → verify-pack` | ✅ | `fronted/package.json:23` 逐字一致 |
| §2.1 catalog / `.npmrc` | catalog 统一 6 个版本；`auto-install-peers` + `strict-peer-dependencies` | ✅（有增项） | `pnpm-workspace.yaml:6-17`；`.npmrc:1-2`。**spec 未预见**的 `peerDependencyRules.allowedVersions react/react-dom '19'`（L1） |
| §2.1 根 devDependencies 含 `@strato-ui/core: workspace:*`（v3.1 N7） | ✅ | `fronted/package.json:26` |
| §2.1 忽略 `**/dist/**`、`**/.verify-pack/**` | ✅ | `.oxlintrc.json:12`、`.prettierignore:6-7`、`fronted/.gitignore:6`；`git ls-files` 无 dist / .verify-pack 产物 |
| §2.1 统一 `--filter` 写法 | ✅ 全文无 `-F`；但 §6.1 第 3 条命令本身不可用（S1） |
| §2.2 布局 | 目录树逐项存在 | ✅ | `find fronted/packages/core/src` 与 spec 树一致；desktop / mobile 各 8 文件 |
| §2.2 公共 API 17 + 10 | ✅ | `src/index.ts` 逐名核对；verify-pack (c) 17 ✓；(e) 10 类型 ✓ |
| §2.2 `parseUiSchema == UiSchemaSchema.parse` | ✅ | `schema/uiSchema.ts:92-94` |
| §2.2 tokens 7 键可选 + 默认值 = global.css | ✅ | `theme/StratoThemeProvider.tsx:10-33` 与 `apps/chat/src/app/styles/global.css:4-14` 同值；`--adm-*` 6 项、`--strato-*` 5 项 `:48-58` |
| §2.2 CSS 只 `--strato-*` + fallback；无 `getComputedStyle` | ✅ | `SchemaRenderer.module.css:20`、`UnknownComponent.module.css:7-10,19`；grep 0 行 |
| §2.2 package.json 字段 | 全部字段 | ✅ | `packages/core/package.json` 逐项：exports 指 src、publishConfig 覆盖、6 peer、无 dependencies、files、sideEffects、build / prepublishOnly。多一个 `typecheck` 脚本（无害） |
| §2.2 构建配置 | external 正则 / cssFileName / chunkFileNames / tsconfig.build 不 exclude vite-env | ✅ | `vite.config.ts:22-32`；`tsconfig.build.json` 含 `rootDir: src`、`types: []`、无 exclude；多 `allowImportingTsExtensions: false`（tsc 发射时必需，INFO） |
| §2.2 解析策略 | 正则 alias 仅 build；predev / prebuild 守护 | ✅ | `apps/chat/vite.config.ts:30-32`（数组 + `/^@strato-ui\/core$/`）；`apps/chat/package.json:8-9` + `scripts/ensure-core-dist.mjs` |
| §2.2 安全边界两列 | README 真源 | ✅ | `packages/core/README.md:63-81`，与 §3 表逐行核对 |
| §2.3 chat | 路由 `/`、tokens.ts、`COMPONENT_TYPES`、entities 组合 core、无 antd、无深路径、`pages/home` 删除、`shared/ui` 只剩 Button | ✅ | `router.tsx:11`；`tokens.ts`；`AgentChatPanel.tsx:39`；`entities/agent-run/model/types.ts:1,116,185,192`；`ls pages` = chat / not-found / schema-playground；`shared/ui/index.ts` 1 行 |
| §2.4 命名 | `[strato-ui]` 前缀、`unknown component type` 文案保留、Provider 改名 | ✅ | `SchemaRenderer.tsx:38,46`；残留一条注释提 `app/providers/DeviceProvider`（L2） |
| §2.4 15 份文档 | 清单全部 | ✅（清单内）/ ❌（清单外 L1 硬约束） | 15 份均已改（含 `dev-workflow.md` 阶段 7、`ui-schema.schema.json` L39）；但 `CLAUDE.md:47` / `AGENTS.md:47` / `platform-owner.md:29` 的 `shared/ui/**` 未改（**M1**） |
| §2.5 change-dir | `STRATO_CHANGE` / 正则 / ∉{DONE} / 退出 2 | ✅ 按 spec 字面 | `lib/change-dir.mjs:15,20,38-43`；但终态集合与 dev-workflow 阶段 8 `DELIVERED` 冲突（S2） |
| §2.5 deploy-verify 12 项 | 名称与顺序 | ✅ | `deploy-verify.sh:28-56` 清点 12 个 `check`，名称与 §2.5 一一对应 |
| §2.5 preview-console | `/?page=…` + `agent-input=1\|0` 独立行 + `/does-not-exist` | ✅ | `preview-console.mjs:12-15,23-26`；`deploy-verify.sh:50-52` `tee` + `grep -o` |
| §2.5 doctor | 必需文件 +6、`fronted/src` 存在即 err、文档路径存在、脚本无 `changes/feat-` | ✅ / 部分 | `harness-doctor.mjs:60-70,85,152-186,188-202`；第 4 项只扫 `scripts/` 一层（S3） |
| §3 非目标 | 未越界 | ✅ | 无 publish、无 changesets、版本 0.1.0、无 license、后端 / Spring AI 零改动、未升级依赖、无新组件、无 locale |
| §5 契约 | 字段零变化，1 处 description | ✅ | `git diff` 仅 L39 文本；summary.md「契约变更」段一致 |
| §6.1 | 5 条 | 4 ✅ / 1 命令不可用 | 第 3 条 `pnpm -C fronted --filter './packages/**' …` 输出 `No projects matched the filters`（S1） |
| §6.2 | 8 条 | ✅ | 见 §1 表 |
| §6.3 | 5 条 | 3 ✅ 实测 / 2 反例未重做 | 干净检出 ci rc=0；两条植入反例（schemaVersion 9.9、git mv Table）本轮未重做，只读推理成立（vite-node 经 exports 走 src；check-registry `existsSync` 逐文件） |
| §6.4 | 2 条 | 未重跑 | 见 §1 表末行 |
| §6.5 | 3 条 | 2 ✅ / 1 未重跑 | doctor 0、全仓 ci 0；e2e-backend 未重跑 |

### 三轮 plan 评审意见落地核对（只列 MUST FIX 与关键 SHOULD）

| 意见 | 落地 | 证据 |
|---|---|---|
| v1-M1 干净检出 typecheck 不依赖 dist | ✅ | exports 指 src + ci 顺序；本机 `rm -rf dist && ci` rc=0 |
| v1-M2 Node import dist 不可行 → lexer + vite-node | ✅ | `verify-pack.mjs:152-159,179-197` |
| v1-M3 路由 `/` 后 deploy-verify 恒真 | ✅ | `#agent-input` 断言有区分度（NotFound 无该元素） |
| v1-M4 文档 / 契约同步 + doctor 路径检查 | ✅（清单内）| doctor `:152-186`；**清单外漏 M1** |
| v1-S3 CSS 变量 / S4 tokens 7 键 / S8 d.ts 消费 / S10 两列 + 反例 | ✅ | 见 §2 表对应行 |
| v2-N1 alias 正则精确 | ✅ | `apps/chat/vite.config.ts:31` |
| v2-N2 不 exclude vite-env | ✅ | `tsconfig.build.json` |
| v2-N3 (g) d.ts 无 antd | ✅（有盲区 S4）| `verify-pack.mjs:162-169` |
| v2-N4 / v3-N8 verify-pack 目录在 `packages/core/.verify-pack` | ✅ | `verify-pack.mjs:32-33` |
| v2-N5 doctor 规则四条 | ✅ | `harness-doctor.mjs:174-178`；/tmp 探针：`fronted/nope/x.ts` → MISSING、`fronted/apps/chat/src/pages/**` → EXISTS、`` `ls fronted/apps/chat/src` `` → 不匹配（跳过）、`fronted/src/entities/*/model/types.ts` → MISSING（正好抓陈旧引用） |
| v2-N6 T02a 纯 rename | ✅ | 26 × R100 |
| v3-N7 根 `workspace:*` | ✅ | `fronted/package.json:26` |
| v3-N9 doctor 不出现字面 `fronted/src` | ✅ | `harness-doctor.mjs:85` 用 `join(root,'fronted','src')`；§6.1 grep 排除 doctor |
| v3-N10 `.verify-pack` 忽略 + 收尾清理 | ✅ | `verify-pack.mjs:261-263` `finally rmSync` |
| v3-N11 dev-workflow 阶段 7 | ✅ | `dev-workflow.md:98` |

---

## 3. agent-safety §4 两列核对（spec §2.2 表 vs 代码）

| 随包走（接入方无法关闭） | 代码位置 | 结论 |
|---|---|---|
| 组件只按注册表白名单查表，`type` 以 string 查找 | `renderer/SchemaRenderer.tsx:35-36` `const type: string = c.type; registry[type]` | ✅；但注册表对象未 `Object.freeze`，宿主 JS 可运行期写入（S7） |
| 每个组件 props 先经 Zod 再渲染，失败渲染占位 | `SchemaRenderer.tsx:43-50` `PROPS_SCHEMAS[type].safeParse` → `UnknownComponent` | ✅ |
| 未知 type → `UnknownComponent` + `console.error('[strato-ui] …')` | `SchemaRenderer.tsx:37-42`；`UnknownComponent.tsx:14 role="alert"` | ✅；`unknown component type` 文案保留（e2e 依赖） |
| 无 eval / new Function / dangerouslySetInnerHTML / 任意路径 import | §6.2 grep 0 行；`componentRegistry.ts` 全部 `import('../components/desktop/Form')` 字面路径 | ✅ |
| UI Schema 中无 URL / HTML 字段可被渲染为链接或富文本 | `ui-schema.schema.json` 无 url/html 字段；7 个封装组件只渲染文本（grep `href\|<a ` 于 components / renderer 仅命中注释） | ✅ |

| 留在宿主（README 明示） | chat 侧代码 | 结论 |
|---|---|---|
| 整体 `parseUiSchema()` 校验 | chat 不直接调 `parseUiSchema`，而是 `SseEventSchema`（组合 core `UiSchemaSchema`）对**每帧整体** `safeParse`：`useAgentRun.ts:91`；`ui.replace.ui` / `ui.patch.components` / `run-summary.currentUi` 均经 core schema：`entities/agent-run/model/types.ts:116,185,192` | ✅ 等价且更严 |
| 不把模型输出直接当 UI Schema 构造 | 仅 `SchemaPlaygroundPage.tsx:25` 在 DEV-only 路由用 `as unknown as UiSchema` 喂未知夹具（`router.tsx:13` 仅 `env.DEV` 注册） | ✅（已知且受控的例外） |
| `confirmationToken` 只回传不解析、不落日志 | `useAgentRun.ts:150-161` 原样放入 `buildActionRequest`；`console.error` 只打印 zod `issues`（实测 zod 4 issues 不含 `input`） | ✅ |
| pageContext 不可信 | `runView.ts:113-129` Zod 校验 URL query；后端鉴权（本 change 未改） | ✅ |
| 不用 `as UiSchema` 绕过校验 | 生产路径 grep `as UiSchema` 仅 playground 一处 | ✅；README 反例 `packages/core/README.md:75-80` 与实现一致（renderer 不校验 `actions`，`ActionBar` 直接渲染 `ui.actions`，所以「actions / token 形态不再被检查」的表述准确） |

其余五条边界（§1 四面、§2 发现、§3 确认、§5 Gateway、§6 流式）后端零改动（`git diff -- backed` 为空），沿用首期 review 结论。

---

## 4. 逐条意见

### MUST FIX

#### M1 · L1 记忆与 Owner 提示词的「硬约束」仍写 antd 只在 `shared/ui/**`，与规则和实现冲突
- **位置**：`CLAUDE.md:47`、`AGENTS.md:47`：「antd / antd-mobile 只在 `shared/ui/**` 内 import。」；`.harness/agents/platform-owner.md:29`：「antd / antd-mobile 只在 `shared/ui/**` 内出现。」
- **问题**：本 change 后 antd / antd-mobile 实际只在 `fronted/packages/core/src/components/**` 与 `theme/**`（`coding-standard.md:34`、`project-structure.md:53`、`.oxlintrc.json:128-143`），而 `apps/chat/src/shared/ui/` 只剩纯 CSS `Button`。CLAUDE.md 是每次会话**隐式加载的 L1 硬约束（"违反即 MUST FIX"）**，它现在允许的位置（`apps/chat/src/shared/ui/**`）恰是红线 2 禁止的位置，禁止的位置（`packages/core`）恰是实现所在。下一个 Agent 按 L1 字面会把实现判为违规，或反过来在 `shared/ui/Button` 引 antd 而自认合规。spec §2.4 对这三个文件只要求改「Generate UI」一句，是 spec 范围漏项，不是编码偷工；但 execution 评审的对象是产出物与规则的一致性。§6.1 的 grep 模式（`shared/ui/generate`）与 doctor 路径检查（只取以 `fronted/` 开头的 token）都无法发现 `shared/ui/**`。
- **建议**：三处改为「antd / antd-mobile 只在 `fronted/packages/core/src/components/**` 与 `theme/**` 内 import；`apps/chat` 不得 import」。**Hashimoto**：`harness-doctor.mjs` 增加一条「L1 / agents 文件中不得出现 `shared/ui/**`」或更通用的「`CLAUDE.md` / `AGENTS.md` / `agents/*.md` 中每个反引号相对路径 token（不限 `fronted/` 前缀）在 `fronted/apps/chat/src` 或 `fronted/packages/core/src` 下必须能定位」；summary.md 经验沉淀加一行。
- **分级**：MUST FIX（L1 硬约束与实现冲突）

### SHOULD

#### S1 · spec §6.1 第 3 条验收命令按字面执行输出「No projects matched」且退出码 0
- **位置**：spec §6.1 第 3 条 `pnpm -C fronted --filter './packages/**' --filter './apps/**' -r exec node -p "require('./package.json').name"`；tasks T01 验收同句。
- **问题**：本机实测（pnpm 10.34.5）从仓库根执行输出 `No projects matched the filters in "/Users/…/fronted"`，rc=0——相对目录 filter 以**当前 cwd** 解析而非 `-C` 目标目录；`{./packages/**}` 变体同样不匹配。`cd fronted && pnpm --filter …` 才输出两行。一个「恰输出两行」的验收在字面形式下什么都不输出还绿，是 spec 文本缺陷。事实本身成立：`pnpm -C fronted -r exec node -p "require('./package.json').name"` 恰输出 `@strato-ui/core` / `strato-chat` 两行（根被 `exec` 排除）。
- **建议**：spec §6.1 / tasks T01 改为 `pnpm -C fronted -r exec node -p "require('./package.json').name"`（或 `cd fronted && …`）；coding_report 若粘贴了该命令输出，应核对是不是在 `fronted/` 下跑的。
- **分级**：SHOULD（验收命令形态；事实已用等价命令复现，故不抬 MUST FIX）

#### S2 · `change-dir` 终态集合只有 `DONE`，dev-workflow 阶段 8 把 summary 置为 `DELIVERED`
- **位置**：`.harness/scripts/lib/change-dir.mjs:15` `const TERMINAL = new Set(['DONE'])`；`.harness/rules/dev-workflow.md:110` 「`summary.md` 状态置 `DELIVERED`」；spec §2.5「∉ {DONE}」。
- **问题**：实现忠实于 spec，但 spec 与规则不一致。本 change 走到阶段 8 时状态会变成 `DELIVERED`（首期 change 是 `DONE`，所以此前没暴露），之后任何新 change 启动，`change-dir` 都会看到 2 个非终态候选并退出 2，所有 e2e / deploy-verify 都要带 `STRATO_CHANGE`——把「并行时的预期用法」变成「永久的必需用法」。
- **建议**：`TERMINAL = new Set(['DONE', 'DELIVERED'])`，并在 spec §2.5 / `deploy-verify/SKILL.md` 同步「终态 = DONE / DELIVERED」；或规则与模板统一只用一个终态词。
- **分级**：SHOULD

#### S3 · doctor「脚本不得含 `changes/feat-`」只扫 `scripts/` 一层，不覆盖 `scripts/lib/`
- **位置**：`.harness/scripts/harness-doctor.mjs:193-194` `for (const e of await rd(scriptsDir…)) if (!e.isFile() …)`（非递归）；tasks T06c 目标写的是 `.harness/scripts/**/*.{sh,mjs}`。
- **问题**：`lib/change-dir.mjs` 当前无硬编码（grep 全 `.harness/scripts` 仅命中 doctor 自身第 196 行）。但 `lib/` 正是将来最可能出现「默认 change」硬编码的地方，检查却对它盲。另：tasks T06a 验收「`grep -rn "changes/feat-" .harness/scripts` 0 行」按字面为 1 行（doctor 自身），该验收不可复现（LOW-11）。
- **建议**：`scan` 递归 `scripts/**`（沿用 pnpm 检查的递归写法）；doctor 内的字面量改为拼接 `'changes/' + 'feat-'`，让 T06a 的 grep 真正为 0。
- **分级**：SHOULD

#### S4 · verify-pack (g) 正则只抓 `from 'antd`，抓不到 tsc 为**推断类型**发射的内联 `import("antd/…")`
- **位置**：`fronted/scripts/verify-pack.mjs:164` `/from ['"](antd|antd-mobile|@ant-design)/`。
- **问题**：tsc 为未显式标注的导出类型发射的是 `import("pkg").X` 内联形式——本 dist 的 d.ts 里就有 22 处 `import("react").JSX.Element`（`dist/theme/StratoThemeProvider.d.ts:22` 等）。若某封装组件导出一个推断出 antd 类型的常量 / 返回值（例如 `export const cols = buildColumns(...)` 推断为 `ColumnsType`），d.ts 会出现 `import("antd/es/table").ColumnsType`，(g) 不红。(e) `skipLibCheck:false` 大概率会因 antd d.ts 不干净而红，但那是「碰巧」，v2-N3 引入 (g) 的目的正是让这条约束被直接断言。tasks T05 反例 ④ 用的是 `import type { ButtonProps } from 'antd'` 显式形式，没覆盖内联形式。当前 dist 实测 `grep 'import("antd'` 为 0，所以不是现行缺陷。
- **建议**：正则改为 `/(from ['"]|import\(['"])(antd|@ant-design)/`（`antd-mobile` 被 `antd` 前缀覆盖）；补一条反例：某组件导出 `export const x = AntButton.defaultProps` 之类推断类型 → (g) 红。
- **分级**：SHOULD

#### S5 · spec §7 / tasks T05 要求的「peer range 与 catalog 主版本一致」断言未实现
- **位置**：`fronted/scripts/verify-pack.mjs:135-139` 只断言 `peerDependencies` 键集合 == 6；spec §7「zod 双实例」行缓解「verify-pack 断言 peer range 与 catalog 一致」；tasks T05 目标「peer range 与 `pnpm-workspace.yaml` catalog 主版本一致」。
- **问题**：spec §6.2 (b) 字面只写「恰 6 键」，实现按 (b) 做了；但 §7 与 T05 明确把「range 主版本 == catalog 主版本」列为 zod 双实例风险的缓解与任务目标。现状：catalog `zod: ^4.5.4` / peer `^4` 一致；若日后 catalog 升到 `zod 5` 而 peer 仍 `^4`，或 antd 升 7 而 peer `^6`，本仓库 install 会因 `strict-peer-dependencies` 红，但 **pack 出去的包**声明会与实际验证用的版本脱节，verify-pack 全绿。
- **建议**：(b) 增加：读 `pnpm-workspace.yaml` catalog，对 6 个 peer 比较 `semver.major(coerce(catalog))` 与 peer range 的主版本（range 形如 `^N`，取 N）；不一致即 ✗。
- **分级**：SHOULD

#### S6 · `COMPONENT_TYPES` 未被任何机械检查与契约 enum 对比；源码注释声称 check-registry 会校验
- **位置**：`fronted/packages/core/src/schema/uiSchema.ts:11` 注释「check-registry 校验注册表键集合等于此列表」；`fronted/scripts/check-registry.mjs` 全文不含 `COMPONENT_TYPES`（grep rc=1），只比对 contract enum ↔ `desktopRegistry` ↔ `mobileRegistry` ↔ `PROPS_SCHEMAS`。
- **问题**：`COMPONENT_TYPES` 现在有三重身份：`ComponentTypeSchema` 的 enum（整体校验的白名单）、`clientCapabilities.components`（`AgentChatPanel.tsx:39`，v1-L1 要求）、公共 API。它与契约 enum 目前一致（7 项逐字核对），但漏掉 `Card` / `Table` / `ConfirmationCard` 任一项时：check-registry 不看它；`verify-examples` 的 16 个示例只用到 Form / OrderCard / RefundConfirmCard / ResultCard 4 种（grep 统计），不会红；e2e-frontend 的 unknown 夹具绕过 Zod。结果是前端会拒绝后端合法下发的 `Card`、并向后端宣告错误的能力集，而全部门禁绿。这是「契约投影字段与 Schema 一致」（contracts.md §1）缺少守护，且注释是假的。
- **建议**：`check-registry.mjs` 用与 `PROPS_SCHEMAS` 相同的文本解析方式读取 `COMPONENT_TYPES = [ … ] as const` 并与 contract enum 比较（第 4 方）；或干脆让 `REGISTRY_KEYS` 从 `COMPONENT_TYPES` 派生、注册表用 `satisfies Record<ComponentType, …>` 约束，把一致性交给 tsc。同时修正注释。
- **分级**：SHOULD

#### S7 · 注册表对象可在运行期被宿主写入，README「随包走（接入方无法关闭）」的白名单表述不成立
- **位置**：`fronted/packages/core/src/registry/componentRegistry.ts:20,56` `export const desktopRegistry: Registry = { … }`（`Readonly<Record>` 仅编译期）；`README.md:65-67` 「组件只按注册表白名单查表」列在「接入方无法关闭」列；`index.ts:11` 把两个注册表导出为公共 API。
- **问题**：`desktopRegistry.Anything = MyComp`（JS 或 `as never`）在运行期成功，`SchemaRenderer` 随后会渲染它——这比 README「不要这样做」列的「替换 UnknownComponent」更隐蔽，且 `PROPS_SCHEMAS` 没有该键时 `schema.safeParse` 会因 `schema` 为 `undefined` 抛 TypeError（`SchemaRenderer.tsx:43-44`），整屏崩而非占位。故意这么做的宿主自担风险，但 README 把它归在「无法关闭」一侧就不准确。
- **建议**：`Object.freeze(desktopRegistry)` / `Object.freeze(mobileRegistry)`（一行，零行为变化），并在 `SchemaRenderer` 对 `schema === undefined` 走占位路径而非抛出；或把 README 该行移到「留在宿主」列并加反例「不要向 `desktopRegistry` 写入」。
- **分级**：SHOULD

### LOW

- **L1 · `peerDependencyRules.allowedVersions react/react-dom: '19'` 作用域是全局**（`pnpm-workspace.yaml:21-24`）。它让**任何**包对 react / react-dom 的 peer 声明在安装 19.x 时都视为满足，包括本仓库 `@strato-ui/core` 自己的 `react: ^19`——今天两者恰好重合，所以「放松了但无实际效果」；但它把 `strict-peer-dependencies` 对 react 这一维的守护整体关掉，且没有记录是为哪几个包开的口子。lock 里真正只声明 `^16 || ^17 || ^18` 的是 `@react-spring/{animated,core,web}@9.6.1` 与 `staged-components@1.1.3`（`pnpm-lock.yaml:621-647,1197-1200`）。建议用 pnpm 的 `parent>peer` 语法精确放行：`'@react-spring/web>react': '19'`、`'@react-spring/web>react-dom': '19'`、`'@react-spring/core>react': '19'`、`'@react-spring/animated>react': '19'`、`'staged-components>react': '19'`。属 spec 未预见的偏差，需在 coding_report 偏差项与 spec §2.1 补记。
- **L2 · 陈旧注释**：`packages/core/src/device/DeviceContext.ts:3` 「由 app/providers/DeviceProvider 一次性判定」→ 应为 `StratoDeviceProvider`（§6.1 grep 模式不含 `DeviceProvider` 裸词所以漏网）。
- **L3 · `ensure-core-dist.mjs` 只检查 `style.css`**（`apps/chat/scripts/ensure-core-dist.mjs:9`），而 `prebuild` 守护的是 alias 到 `dist/index.js`。`vite build` 与 `tsc` 是两步，`dist/index.js` 缺失而 `style.css` 存在的组合（如手工删除、构建被打断后的部分产物）会放行到 Rolldown 的 UNRESOLVED_IMPORT。建议同时检查 `index.js` 与 `style.css`。
- **L4 · verify-pack (f) 基线文件缺失时静默重写并通过**（`verify-pack.mjs:245-250`）。删掉 `scripts/verify-pack.baseline.json` 即可让体积门禁「重置」且 CI 绿。建议在 CI（无 `--write-baseline` / 非 TTY）下基线缺失即 ✗。另 spec §6.2 写「`du -sk` 首测值」，脚本用字节求和（40 KB），`du -sk dist` 实测 180 KB（磁盘块），两者口径不同——脚本自洽即可，但 coding_report / spec 应写明用的是哪一个。
- **L5 · `check-deps.mjs` 正则不抓 `export { x } from '@features/…'` 与 `import('@features/…')`**（`check-deps.mjs:47-48`；/tmp 探针实测：5 种 import 形态全抓，`export … from` 与动态 import 漏）。`shared/index.ts` 一行 `export * from '@features/x'` 即可反向依赖而不红。FSD 分层规则不在 oxlint 里（oxlint 只禁切片内部路径），所以这是唯一守护。首期已存在的盲区，本 change 修了副作用裸导入这一种；建议顺手补 `^\s*export\s+[^;]*?from\s+['"]…` 与 `import\(\s*['"]…`。
- **L6 · Form 字段约束在包内定义了两份**：`schema/uiSchema.ts:24-42 FormFieldSchema`（options `min(1).max(64)`、label/value 限长）与 `registry/types.ts:11-23 FormFieldPropsSchema`（无这些限制），`FormPropsSchema` 与 `FormComponentPropsSchema` 分别对外导出与用于 `PROPS_SCHEMAS`。首期即存在，如今同处一包，「相同逻辑抽取复用」应让 `registry/types.ts` 直接复用 `uiSchema.ts` 的定义。同理 `ActionBarProps` 在 `renderer/ActionBar.tsx:6`、`components/desktop/ActionBar.tsx:9`、`components/mobile/ActionBar.tsx:4` 三处重复（首期已如此）。
- **L7 · `ChatPage.tsx:27-30` `aria-labelledby="chat-title"` 指向一个内容为注释的空 `<h1>`**，屏幕阅读器得到空标签（coding-standard §7）。首期 `AgentPage` 同样；改名时未修。要么放回文字，要么去掉 `aria-labelledby` 与空 h1。
- **L8 · `--strato-color-surface-hover` 的 Provider 值与 CSS fallback 不一致**：Provider 写 `token.colorBgLayout`（#f7f8fa，`StratoThemeProvider.tsx:57`），`UnknownComponent.module.css:9` fallback 是 #f0f2f5（首期 `--color-surface-hover` 的值）。Provider 内永远用 #f7f8fa，Provider 外用 #f0f2f5，两套视觉。UnknownComponent 不在「7 个组件视觉不变」的范围，属 LOW；选一个值统一。
- **L9 · `@ant-design/icons` 被声明为 peer 但 core 源码从未 import**（`packages/core/package.json:38`；`grep -rn "@ant-design" packages/core/src` 0 行）。按 spec 6 peer 落地无误，但让每个宿主多装一个本包不用的包（antd 自己依赖它，宿主其实会传递获得）。建议下一 change 连同 verify-pack 的 6 键断言一起改为 5 peer。
- **L10 · `summary.md:18` 阶段 1 行仍写「tasks.md（8 task：T01 / T02a / T02b / T03 / T04 / T05 / T06 / T07）」**，tasks v3.1 已是 12 个（T02c / T03a / T03b / T06a / T06c / T06b）。
- **L11 · tasks T06a 验收「`grep -rn "changes/feat-" .harness/scripts` 0 行」按字面为 1 行**（`harness-doctor.mjs:196` 自身字面量）。与 S3 一并处理（拼接字符串）即可让验收如实为 0。
- **L12 · `.harness/skills/frontend-doctor/SKILL.md:27` `rm -rf fronted/node_modules/.vite`**：workspace 后 Vite 缓存在 `fronted/apps/chat/node_modules/.vite`。doctor 跳过含 `node_modules` 的 token，所以没报；属 §2.4 清单外的陈旧路径。
- **L13 · verify-pack (e) 的 `consumer.ts` 只「引用」27 个名字**（`verify-pack.mjs:200-211`），不实例化任何 props——EOPT 开 / 关两次实际只验证 d.ts 能被解析且自身（含 zod / react 的 d.ts）在 `skipLibCheck:false` 下干净。spec (e) 字面如此，已满足；但 v1-I4 关心的「`onFormChange?: …` 在 EOPT 下对接入方的影响」并未被这段代码触及。建议加 3 行：`<SchemaRenderer ui={parseUiSchema({})} />`、`tokens={{}}`、`onFormChange={undefined}`（后者在 EOPT=true 下**应**报错，可作为负向断言）。

### INFO

- **I1 · `tsconfig.node.json` 只含两个 `vite.config.ts`**（`fronted/tsconfig.node.json:8`），`scripts/verify-examples.ts` 与所有 `*.mjs` 不在 tsc 范围。首期 `tsconfig.node.json` 亦只含 `vite.config.ts`，`verify-examples.ts` 头注释已声明「不在 typecheck 范围」，vite-node 剥类型直接跑；可接受，但注意它的类型错误永远只在运行期暴露。
- **I2 · core `types: []` 下 DOM 类型来源**：`tsconfig.base.json:4` `lib: ["ES2022","DOM","DOM.Iterable"]`——DOM 来自 `lib` 而非 `@types/*`，`types: []` 只关掉 `@types` 自动包含；`vite-env.d.ts:1` 的 `/// <reference types="vite/client" />` 显式引入 vite/client（core devDependencies 含 vite），且实测未渗入 dist d.ts。成立。
- **I3 · d.ts 对接入方 zod 版本的要求**：`dist/schema/uiSchema.d.ts` 使用 `z.ZodEnum<{…}>`、`z.core.$strict`、`z.ZodObject<…, z.core.$strict>` 等 zod 4 形状；zod 3 宿主必红，peer `^4` 覆盖。(e) 只在 catalog 版本（zod 4.5.4 / @types/react 19.2.18）上验证，未验证 range 下界（zod 4.0.x）；`import("react").JSX.Element` 需要 @types/react ≥ 18.3 的 `JSX` 命名空间，react ^19 宿主天然满足。
- **I4 · dev 期 CSS 双份**：`global.css:1` 引 `dist/style.css`，同时 JS 经 exports 走 src 让 Vite 注入 src 的 CSS Modules；两者类名 hash 以不同 root 计算，不会互相覆盖，dist 那份在 dev 是死重。README「改源码即热更新」对 TSX 与 `.module.css` 都成立（走 src 那份），只是 dist CSS 冗余。生产构建只用 dist，无此问题。
- **I5 · `apps/chat/vite.config.ts:39` `fs.allow: [fronted, .harness/contracts]`**：`fronted` 即 pnpm workspace root（Vite 默认 searchForWorkspaceRoot 也会得到它），`.harness/contracts` 仅 JSON，DEV 服务器暴露范围可接受。
- **I6 · `zustand` 在 `apps/chat/package.json:25` 声明但源码零引用**（grep rc=1）。spec §2.3 列了它，首期亦如此；死依赖，可在后续 change 清理。
- **I7 · `tsconfig.build.json:9` `allowImportingTsExtensions: false`** 是 spec 原文之外的必要项（`noEmit:false` 时 tsc 强制要求），属合理偏差。
- **I8 · deploy-verify.sh:50** `2>&1 | tee` 后取 `${PIPESTATUS[0]}`，无 `pipefail` 亦正确；`preview-console.mjs:34` `process.exit(total)` 在 error > 255 时会回绕，实际不可能触及。
- **I9 · 本轮未重跑的验收**：§6.3 两条植入反例、§6.4 e2e-frontend 21 项与 deploy-verify 12 项、§6.5 e2e-backend。需在下一轮或阶段 7 由脚本重新产出真实退出码；本报告不替它们背书。

---

## 5. 结论

- **verdict：REVISION REQUIRED**
- **MUST FIX：1**
  - M1 `CLAUDE.md:47` / `AGENTS.md:47` / `platform-owner.md:29` 硬约束仍写「antd 只在 `shared/ui/**`」，与 `coding-standard.md` §4、`project-structure.md` 红线 2 及实现冲突；现有 grep / doctor 均抓不到。
- **SHOULD：7**（S1 §6.1 filter 命令形态；S2 `DELIVERED` 不在终态集合；S3 doctor `changes/feat-` 不扫 `lib/`；S4 (g) 漏内联 `import("antd")`；S5 缺 peer range vs catalog 断言；S6 `COMPONENT_TYPES` 无机械校验 + 假注释；S7 注册表可写 vs README「无法关闭」）
- **LOW：13**，**INFO：9**
- 回退：阶段 3（文档级修订 + 可选的 doctor / verify-pack / check-registry 加固），下一轮评审文件 `code_review_v3.md`（编码评审上限 2 轮，本轮为第 1 轮）。
