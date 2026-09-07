# Spec Review v2 — feat-strato-ui-monorepo-20260904

| 字段 | 值 |
|---|---|
| mode | plan |
| 日期 | 2026-09-04 |
| 轮次 | 2 / 3 |
| 评审对象 | `request_analysis/spec.md`（v2）、`request_analysis/tasks.md`（v2） |
| 依据 | rules/{project-structure, coding-standard, contracts, agent-safety, dev-workflow}；skills/expert-reviewer plan 必查项；上轮 `spec_review_v1.md`；现状 `fronted/**`、`.harness/scripts/**` |
| 评审方式 | 有罪推定。v2 新引入的每个方案都在本机实测（pnpm 10.34.5 / Node 20.20.2 / TS 7.0.2 / Vite 8.2.2 / rolldown 1.2.7 / vite-node 6.0.0 / zod 4.5.4 / antd 6.6.2 / antd-mobile 5.42.3），临时夹具在 `/tmp` 下构造，不改仓库文件。证据附各条 |

---

## 0. verdict

**REVISION REQUIRED** — MUST FIX 2 条（N1、N2），SHOULD 4 条（N3–N6），LOW 6 条，INFO 5 条。

上轮 20 条（M1–M4 / S1–S10 / L1–L6）在 v2 中 **19 条已闭环、1 条部分闭环（S1）**，方向不需要推翻。两条 MUST FIX 都出在 v2 为闭环 M1 / S1 新写进 spec 的具体配置上：按 spec 原文落地，`build:chat` 与 `build:core` 的 `tsc -p tsconfig.build.json` 两步都会失败，§6.2 / §6.3 验收不可达。各自都是一行改动。

---

## 1. 上轮意见闭环核对

| # | 上轮意见 | 状态 | 证据（v2 章节） |
|---|---|---|---|
| M1 | 干净检出 typecheck 依赖未构建的 dist | **已闭环**（新方案引入 N1 / N2） | spec §2.1 ci 顺序 `build:core → typecheck → … → build:chat → verify-pack`；§2.2「package.json 关键字段」开发期 `exports` 指 src + `publishConfig` 覆盖；§2.2「解析策略」；§4.3；§6.3 第 3 条干净检出模拟；tasks T04 验收 ④（vite-node 走 src 的反证） |
| M2 | Node `import()` dist 不可能成立 | **已闭环** | spec §6.2 verify-pack (c) es-module-lexer + (d) vite-node；§7 第 8 行缓解更新；tasks T05 |
| M3 | 路由改 `/` 后 deploy-verify 恒真、漏 preview-console | **已闭环** | spec §2.5 `preview-console.mjs` 段（`/?page=…` + `agent-input=1\|0` + `/does-not-exist`）；`deploy-verify.sh` 12 项逐条列名；§6.4 |
| M4 | 文档 / 契约 / 脚本同步漏 6 处，grep 全盲 | **已闭环** | spec §2.4 清单含 contracts.md §1、coding-standard §2 第 3 条、ui-schema.schema.json L39、project-analysis / deploy-verify SKILL、architecture.md、README；§6.1 第 4 条 grep 扩展模式与目录；§2.5 doctor 新增路径存在检查；§5 契约影响改为「1 处 description 注释」 |
| S1 | TS 7 `rootDir`、core 自带 `vite-env.d.ts`、devDependencies | **部分闭环** | spec §2.2「构建」写入 `tsconfig.build.json` 全文与 devDependencies 清单（✓）；但 `exclude: [src/vite-env.d.ts]` + `types: []` 实测使 d.ts 生成失败 → 见 N2 |
| S2 | zod 单例 | **已闭环** | spec §2.2 `peerDependencies` 含 `zod ^4`、无 `dependencies`；§2.1 catalog；§7「zod 双实例」行；tasks T05 断言 peer range 与 catalog 主版本一致 |
| S3 | core CSS 依赖宿主变量 | **已闭环** | spec §2.2「包内 CSS 约束」（`--strato-*` + fallback）；§6.2 倒数第 1 条 grep；tasks T02b |
| S4 | tokens 键集合小于现实现 | **已闭环** | spec §2.2 `StratoThemeTokens` = 现 7 键、默认值 = 现值；§3 非目标「不新增键 / 不提供 locale」 |
| S5 | task 粒度与 commit 切分 | **已闭环** | tasks T03a / T03b、T06a / T06b 拆分；T02a 单 commit、T02b 两 commit 明示 |
| S6 | 三条断言形态 | **已闭环** | §6.1 第 3 条改 `-r exec node -p`；§6.2 第 6 条过滤器改 `^\S*:\s*\(//\|\*\)`；§6.2 第 3 条基线 ×1.1；§6.2 第 2 条合并说明两断言缺一不可 |
| S7 | `STRATO_CHANGE` 默认判定与「47 passed」 | **已闭环** | spec §2.5 `lib/change-dir`（唯一非 DONE，否则退出 2）；§6.5 第 3 条改为「与主干实测一致」；tasks T07 先记基线 |
| S8 | dist d.ts 从未被 TS 消费 | **已闭环**（可行性见 N3） | spec §6.2 verify-pack (e) `consumer.ts` 双 EOPT 模式；§7 第 2 行 |
| S9 | `sideEffects` glob / oxlint ignore / publish 保护 | **已闭环** | spec §2.2 `sideEffects: ["./dist/style.css"]`、`prepublishOnly`；tasks T01 `ignorePatterns: ["**/dist/**","**/node_modules/**"]` |
| S10 | 安全边界两列清单 + README 反例 | **已闭环** | spec §2.2「安全边界」表；§2.4 core README「不要这样做」三条；`parseUiSchema` 入公共 API |
| L1 | `COMPONENTS` 手写 → `COMPONENT_TYPES` | **已闭环** | spec §2.3；tasks T03b |
| L2 | `.npmrc` 显式 peer 策略 | **已闭环** | spec §2.1 `.npmrc`；tasks T01 |
| L3 | tokens 不在渲染期 `getComputedStyle` | **已闭环** | spec §2.3 `STRATO_TOKENS` 常量对象 |
| L4 | core 内残留 `getComputedStyle` | **已闭环** | spec §2.2 末句；§6.2 grep；tasks T02b 验收 |
| L5 | README `Vite 6` 陈旧 | **已闭环** | spec §2.4 `fronted/README.md`「重写（… Vite 8）」 |
| L6 | `-F` / `--filter` 混用 | **已闭环** | spec §2.1「统一 filter 写法」；全文无 `-F`（T05 目标少写 `-C fronted`，见 INFO-4） |

统计：已闭环 19 / 部分 1 / 未闭环 0。

---

## 2. plan 模式 6 项必查

| # | 必查项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 「非目标」章节存在且非空 | ✅ | spec §3 共 10 条 |
| 2 | 每条验收标准都可被命令或断言校验 | ❌ | 全部为命令形态（✓），但 §6.2 第 1 条 `build` 与 §6.3 第 3 条干净检出 `ci` 按 spec 原文配置**不可能为 0**（N1、N2 实测）；§6.2 (d)(e) 缺执行条件（N3、N4） |
| 3 | 风险章节 ≥1 个失败模式与缓解 | ✅ | spec §7 共 12 行，覆盖上轮要求的 zod / CSS / typecheck 三项 |
| 4 | 每个 task 标注所属端；contracts task 排前 | ✅ | 11 task 全部标 fronted / harness；契约仅注释变更，随 T06b 落地，无依赖者 |
| 5 | 跨端结构 task 列出契约文件 | ✅ | 不新增跨端结构；`ui-schema.schema.json` 变更范围在 §5 明确为 description 注释 |
| 6 | 每个 task ≤ 0.5 天 | ✅（有保留） | T06a = 新 lib ×2 + 5 个脚本改造 + doctor 两项新检查，是 11 个里最重的一个（LOW-6）；其余明显 ≤ 0.5 天；依赖图 T01→…→T07 与 T02c→T05→T06a 无环 |

---

## 3. 新意见

### MUST FIX

#### N1 · chat `vite build` 的 alias 若按 spec 原文写成字符串键，`@strato-ui/core/style.css` 会被前缀匹配到 `dist/index.js/style.css`，构建必红
- **位置**：spec §2.2「解析策略（M1）」：「chat 的 `vite.config.ts` 在 `command === 'build'` 时 alias `@strato-ui/core` → `packages/core/dist/index.js`」；§4.3；tasks T03a 目标「build 时 alias @strato-ui/core → ../../packages/core/dist/index.js」。
- **问题**：Vite `resolve.alias` 的对象写法（`{ '@strato-ui/core': '<abs>/dist/index.js' }`）是**前缀匹配**：`@strato-ui/core/style.css` 也会命中，被改写为 `<abs>/dist/index.js/style.css`。本机用最小包实测：
  ```
  $ vite build -c vite.config.string.mjs
  [UNLOADABLE_DEPENDENCY] Could not load ../../../tmp/aliastest/node_modules/@x/core/dist/index.js/style.css
    import '@x/core/style.css'   ╰── Not a directory (os error 20)
  $ vite build -c vite.config.regex.mjs     # alias: [{ find: /^@x\/core$/, replacement: '<abs>/dist/index.js' }]
  exit 0；产物含 DIST 与 .from-dist{color:red}（JS 走 dist，CSS 子路径经 exports 走 dist）
  ```
  也就是说 spec §2.2 让 `./style.css` 子路径「始终指 dist」的设计成立的前提是 alias **只匹配裸包名**。spec 与 T03a 都没写这一条，编码 Agent 照字面写对象键就会在 §6.2 / §6.3 / §6.4 全部红，然后被迫「顺手」改方案。
- **建议**：spec §2.2 解析策略与 T03a 目标改为：alias 使用数组 + 正则精确匹配 `{ find: /^@strato-ui\/core$/, replacement: <abs>/packages/core/dist/index.js }`，并加一句「不得使用对象键写法（前缀匹配会吞掉 `/style.css` 子路径）」。T03b 验收已含 `pnpm --filter strato-chat build 0`，可作为守护；建议再加 `grep -c "from-dist\|strato" apps/chat/dist/assets/*.css`≥1 之类的 CSS 进入产物断言（可选）。

#### N2 · `tsconfig.build.json` 的 `exclude: [src/vite-env.d.ts]` + `types: []` 使 core 的 `*.module.css` import 失去类型声明，`tsc -p tsconfig.build.json` 必红，`dist/index.d.ts` 不会产出
- **位置**：spec §2.2「构建（S1 / I2）」`tsconfig.build.json = { … types: [] …, include: [src], exclude: [src/vite-env.d.ts] }`；tasks T02c 目标「`tsconfig.build.json`（spec §2.2 原文，含 … `exclude: [src/vite-env.d.ts]`）」；§6.2 第 1 条 `ls dist/index.d.ts`。
- **问题**：`SchemaRenderer.tsx` / `UnknownComponent.tsx` 的 `import styles from './X.module.css'` 靠 `vite-env.d.ts` 里的 `declare module '*.module.css'`（或 `types: ["vite/client"]`）才有类型；spec 同时把两条来源都拿掉了。用现有源码实测（`include: src/shared/ui/generate`，其余选项按 spec 原文）：
  ```
  # exclude vite-env.d.ts + types: []
  src/shared/ui/generate/SchemaRenderer.tsx(9,20): error TS2307: Cannot find module './SchemaRenderer.module.css' …
  src/shared/ui/generate/UnknownComponent.tsx(1,20): error TS2307: …
  exit 2，dist 无产物
  # 不 exclude（其余相同）
  exit 0，产出 30 个 d.ts，其中不含 vite-env（.d.ts 输入文件本来就不会被 emit）
  ```
  上轮 S1 说「应 exclude 以求干净」是评审方的误判——`.d.ts` 作为输入不会被 emit，exclude 没有收益只有破坏；v2 把它照抄进了 spec。
- **建议**：spec §2.2 与 T02c 删除 `exclude: [src/vite-env.d.ts]`（保留 `types: []` 即可；`vite-env.d.ts` 顶部的 `/// <reference types="vite/client" />` 会按需引入 vite/client，无害）。或者保留 exclude 但把 `types: []` 改为 `types: ["vite/client"]`——二选一写死。补一条验收：`find fronted/packages/core/dist -name 'vite-env*' | wc -l` 为 0（证明声明文件没进产物）。

### SHOULD

#### N3 · verify-pack (e) `--skipLibCheck false` 成立的前提是 dist d.ts 不引用 antd / antd-mobile 类型，spec 没把这个前提写成断言；一旦有 d.ts 引到 `ButtonProps` 之类，(e) 恒红且与包无关
- **位置**：spec §6.2 (e)；§7 第 2 行；tasks T05。
- **问题**：实测一个只 `import { Button } from 'antd'; import { Button } from 'antd-mobile'; import zhCN from 'antd/locale/zh_CN'` 的文件，`tsc --noEmit --skipLibCheck false`：EOPT 开 → antd `Button.d.ts` / `Drawer.d.ts` / `qr-code/interface.d.ts` TS2320、rc-field-form TS2503、rc-segmented TS2344 共 10+ 错；EOPT 关 → antd-mobile `*.less` 副作用导入 TS2882 + rc-* 同样红。**两种模式都不干净**。目前 core 源码 emit 出的 d.ts（实测 30 个）只有注释里提到 antd，没有 `from 'antd'`（因为组件封装层的 props 都是自定义 Zod 推导类型），所以 (e) 只加载 zod + @types/react 的 d.ts——这两者在双模式下实测干净（exit 0）。但这是「碰巧」而非「被约束」。同时 (e) 没说 `consumer.ts` 放在哪、`tsconfig` 用什么（`types`、`moduleResolution`、从哪个 `node_modules` 解析 `react` / `zod` 类型）——放在 `/tmp` 下解包目录旁边时 tsc 找不到 `@types/react`。
- **建议**：(1) §6.2 新增断言 (g)：`grep -rln "from 'antd\|from \"antd\|from 'antd-mobile\|from \"antd-mobile\|@ant-design" fronted/packages/core/dist --include='*.d.ts'` 输出 0 行，并写进 §2.2「构建」作为设计约束（「公共类型不得暴露 antd / antd-mobile 类型」）；(2) (e) 明确：`consumer.ts` 与临时 `tsconfig.json` 写到 `fronted/.verify-pack/`（gitignore），`compilerOptions = { strict, moduleResolution: bundler, jsx: react-jsx, types: [], skipLibCheck: false, exactOptionalPropertyTypes: <开/关> }`，`import … from '<解包目录>/package'`；或把解包目录也放到 `fronted/.verify-pack/` 下以复用 `fronted/node_modules`。

#### N4 · verify-pack (d) 用 vite-node `import()` 解包目录，只有 cwd 或 `--root` 为 `fronted` 时才能解析 peer；spec 给的调用形式是从仓库根 `node fronted/scripts/verify-pack.mjs`
- **位置**：spec §6.2 第 4 条 `node fronted/scripts/verify-pack.mjs`；(d)；tasks T05。
- **问题**：实测 `/tmp/vn/t.mjs`（import antd / antd-mobile / antd/locale/zh_CN）：
  ```
  fronted$ vite-node /tmp/vn/t.mjs                    → LOADED（cwd 即 root）
  strato_ui$ fronted/node_modules/.bin/vite-node /tmp/vn/t.mjs
                                                      → Error: Cannot find package 'antd' imported from '/tmp/vn/t.mjs'
  strato_ui$ fronted/node_modules/.bin/vite-node --root fronted /tmp/vn/t.mjs → LOADED
  ```
  解包目录在 workspace 外没问题（vite-node 允许 root 外文件），关键是 Vite root 必须是 `fronted`（peer 从 `fronted/node_modules` 解析）。spec 只写了「用 vite-node import()」。此外 (d) 本身只证明「能加载」，配合 (c) 才完整——spec 已如此，OK。
- **建议**：spec §6.2 (d) 写明「以 `fronted` 为 Vite root：`vite-node --root <fronted 绝对路径> <loader.mjs> <解包 dist/index.js 绝对路径>`（或 spawn 时 `cwd = fronted`）」；T05 目标同步。§6.2 第 4 条调用方式统一为 `pnpm -C fronted run verify-pack`（cwd 自然正确），与 §2.1 「统一命令写法」一致。

#### N5 · doctor 新检查「文档反引号 `fronted/<path>` 必须存在」没有定义匹配规则，现有文档中就有通配 / 命令 / 临时目录三类必然误报
- **位置**：spec §2.5 `harness-doctor.mjs` 段；§6.1 第 5 条；tasks T06a 目标与验收（只给了一个正向植入反例）。
- **问题**：对现状文档做 `` `[^`]*fronted/[^`]*` `` 抽取，命中有：`` `fronted/` ``（×6，目录，OK）、`` `fronted/src/entities/*/model/types.ts` ``（glob）、`` `ls fronted/src/{pages,features,entities,shared}` ``（shell 命令 + brace）、`` `cat fronted/src/main.tsx fronted/src/app/App.tsx` ``（一段里两条路径）、`` `rm -rf fronted/node_modules/.vite` ``（干净检出不存在）。v2 之后 spec §2.4 自己就要写入 `apps/chat/src/entities/*/model/types.ts`（glob）与 `apps/chat/src/pages/**`。机械 `existsSync` 对这些全部误报；若实现者为了通过而「把含 `*` 的都跳过」，那 `fronted/src/entities/*/model/types.ts` 这种正是 M4 要抓的陈旧路径又会漏网。
- **建议**：spec §2.5 写死规则：① 只取反引号内以 `fronted/` 开头、且整段不含空格的 token（排除命令）；② token 含 `*` / `{` 时取第一个通配符之前的**目录前缀**做 `existsSync`（`fronted/apps/chat/src/entities/` 必须存在，`fronted/src/entities/` 会红——正好抓住陈旧引用）；③ 跳过含 `node_modules` / `dist` 的 token；④ 扫描范围 = rules / skills / wiki / agents / CLAUDE.md / AGENTS.md，不含 `changes/`。T06a 验收增加反向证据：植入 `` `fronted/apps/chat/src/pages/**` `` 不得误报。

#### N6 · T02a 验收「`git diff --stat -M HEAD~1` 全部为 `=>`」与目标「复制 `src/vite-env.d.ts`」自相矛盾
- **位置**：tasks T02a 目标末句「复制 `src/vite-env.d.ts`」、输出 `fronted/packages/core/src/vite-env.d.ts`、验收「只含 rename」。
- **问题**：复制产生一个新增文件，`--stat` 显示为普通 `+` 行而非 `=>`，验收必然为假；编码 Agent 要么改验收要么把复制挪走，两种都是无依据的自行决定。另外 T02a 之后 `SchemaRenderer.tsx` 仍 import `@entities/agent-run`、`AgentChatPanel` 仍 import `@shared/ui`，这个中间 commit 的 `typecheck` / `lint` 必红——tasks 没声明「T02a–T03b 之间的中间 commit 不要求 CI 绿」。
- **建议**：把「复制 vite-env.d.ts」移到 T02b 的第一个 commit（`refactor(core): 相对导入 + 抽出 uiSchema.ts` 本来就是新增文件的 commit），T02a 严格只有 rename；或验收改为「除 `packages/core/src/vite-env.d.ts` 一行外全部为 `=>`」。tasks 文件头补一句「Phase B / C 内部的中间 commit 允许 CI 红，T04 完成后必须全绿」。

### LOW

- **LOW-1** `pnpm -C fronted dev` 的「dist/style.css 不存在则先 build:core」守护只挂在根脚本；`pnpm -C fronted --filter strato-chat dev` 或在 `apps/chat` 里直接 `vite` 会绕过。实测 `dist/style.css` 缺失时 `@import '@strato-ui/core/style.css'`（global.css）在 build 报 `[postcss] ENOENT`，serve 期同样白屏报错。建议守护放到 `apps/chat/package.json` 的 `predev` / `prebuild`（或 chat `vite.config.ts` 启动时 `existsSync` 检查并给出明确报错文案），spec §4.3 同步。
- **LOW-2** `lib/change-dir` 的状态字段解析规则未写：现两个 change 的 summary 均为 `| 状态 | DONE |` / `| 状态 | DRAFT |`（模板亦然），形态一致可解析，但 spec 应写死正则（如 `^\| 状态 \| (\S+) \|`）与「终态集合 = {DONE}」；并在 `deploy-verify/SKILL.md` 或 dev-workflow 说明「并行第二个 change 时必须显式 `STRATO_CHANGE=<id>`」——目前 spec 只说退出 2 列候选，没说这是预期用法而非缺陷。
- **LOW-3** `preview-console.mjs` 同时用「退出码 = error 总数」和「stdout `agent-input=1|0`」两条通道，spec 没定义输出行的精确格式与 deploy-verify 的解析方式（`grep -o 'agent-input=[01]'`）。建议 spec §2.5 写死：stdout 独立一行 `agent-input=1`，deploy-verify 用 `tee` 落 `preview-console.log` 再 grep。
- **LOW-4** workspace 根 `package.json` 的 devDependencies（`typescript`、`oxlint`、`prettier`、`vite-node`、`es-module-lexer`、`puppeteer-core` 若前端用）未在 spec §2.1 / T01 列出；T05 说「devDependency 加 `es-module-lexer`」但没说加在根还是 core。`scripts/verify-examples.ts` 从 `fronted/scripts/` 裸 import `@strato-ui/core`，实测依赖 pnpm 默认 `hoist-workspace-packages=true` 才能从 `fronted/node_modules/@strato-ui/core` 解析——建议根 `package.json` 显式 `"@strato-ui/core": "workspace:*"`，不依赖隐式 hoist。
- **LOW-5** chat 的 typecheck 经 `exports` 走到 core src 后，core 源码的 `*.module.css` import 依赖 chat 侧的 `vite/client` 类型才能通过（实测：chat 无 `declare module '*.module.css'` 时 TS2307 报在 `packages/core/src/index.ts`）。现方案下 chat `tsconfig` 有 `types: ["vite/client"]` 所以能过，但这是隐式耦合；spec §2.2 解析策略加一句说明即可。
- **LOW-6** T06a 体量偏大（2 个新 lib + 5 个脚本 + doctor 两项新检查 + 4 条验收反证）。可把 `harness-doctor.mjs` 两项新检查拆为 T06c（依赖 T06a），或接受并在 tasks 标注「T06a 预计 0.5 天，是关键路径瓶颈」。

### INFO

- **INFO-1** Vite 8 / rolldown 选项名核对：`build.lib.fileName`（vite index.d.ts:2328）、`build.lib.cssFileName`（:2334）、`build.rollupOptions`（:873，类型为 `RolldownOptions`）、`output.chunkFileNames`（rolldown define-config d.mts:646）、`external?: ExternalOption`（:3583，接受正则 / 函数）全部存在。`sideEffects` 是 package.json 字段而非 Vite 选项，写法 `["./dist/style.css"]` 与 pnpm pack 后保留一致（实测 tarball 内原样保留）。
- **INFO-2** pnpm 10.34.5 `pnpm pack` 的 `publishConfig` 覆盖**实测成立**：tarball 内 `package.json` 的 `types` / `exports["."]` 变为 dist，`publishConfig` 只剩 `access`，`files` / `sideEffects` / `peerDependencies` 原样保留；workspace 内 `pnpm --filter @strato-ui/core pack` 同样成立，且 `devDependencies` 的 `catalog:` 被替换为具体 range（`^4.5.4`），`peerDependencies: zod ^4` 不变——§6.2 (b) 与 T05「peer range 与 catalog 主版本一致」可断言。`.npmrc strict-peer-dependencies=true` + core `peer zod ^4` + 两包 `catalog:` 在最小 workspace 实测 `pnpm install` 退出 0，`packages/core/node_modules/zod` 与 `apps/chat/node_modules/zod` 指向同一 `.pnpm/zod@4.5.4`——zod 单例成立。
- **INFO-3** `pnpm pack` **不会**触发 `prepublishOnly`（实测：`prepublishOnly: exit 7` 下 pack 仍退出 0，只跑了 `prepack`）。因此 verify-pack.mjs 内部调用 `pnpm pack` 不会递归触发自己；`prepublishOnly` 只在 `pnpm publish` 时生效（按 pnpm 文档，未实测 publish），符合 spec「挡误发」的语义。若日后有人改成 `prepack`，就会无限递归——可在 verify-pack 内用环境变量 `STRATO_VERIFY_PACK=1` 防重入（可选）。
- **INFO-4** `#agent-input` 首屏可用性：`ChatPage` → `AgentChatPanel` 无条件渲染 `<input id="agent-input">`；`useAgentRun` 的 `useQuery` 带 `initialData` 且 `queryFn` 返回本地空视图，挂载不发请求。所以 preview（生产构建）在 `/` 上不依赖后端即可渲染 `#agent-input`，12 项断言中「主页面含 `#agent-input`」有区分度（NotFound 页无该元素）。12 项清点：health / selfcheck / preview `/` 200 / `#agent-input` / 代理 health / 事件序列 / confirm / summary / backend.log / 用户原文 0 / console.error 0 / bundle_size = 12，与 §6.4 数字一致。
- **INFO-5** 命令写法：T05 目标 `pnpm --filter @strato-ui/core pack` 少了 `-C fronted`（在 verify-pack.mjs 内以 cwd 执行时无碍，但与 §2.1「全文用 `pnpm -C fronted --filter`」不一致）。tasks 六要素（目标 / 所属端 / 输入 / 输出 / 验收 / 依赖）11 个 task 全部齐全；「输入」列已改为具体路径（上轮 I5 闭环）；依赖图无环，T05 与 Phase C 并行成立。

---

## 4. 需要 spec 作者在 v3 落实的决策点

1. N1：alias 写法固定为正则精确匹配（无其它选项）。
2. N2：`tsconfig.build.json` 去掉 `exclude` 还是改 `types: ["vite/client"]`——二选一写死。
3. N3：是否把「公共 d.ts 不得引用 antd / antd-mobile 类型」提升为 §2.2 设计约束（推荐是）。
4. N4：verify-pack 调用形式统一为 `pnpm -C fronted run verify-pack`，(d) 指定 `--root`。

---

## 5. 本轮结论

- **verdict：REVISION REQUIRED**
- MUST FIX：2（N1 Vite alias 字符串键前缀匹配吞掉 `/style.css` 子路径，`build:chat` 必红；N2 `tsconfig.build.json` exclude `vite-env.d.ts` + `types: []` 使 `*.module.css` 无类型，d.ts 生成必红）
- SHOULD：4（N3–N6）；LOW：6；INFO：5
- 上轮闭环：19 已闭环 / 1 部分（S1）/ 0 未闭环
- 回退：阶段 1，修订 spec v3 / tasks v3；下一轮评审文件 `spec_review_v3.md`（第 3 轮为上限，超出升级 HITL）。
