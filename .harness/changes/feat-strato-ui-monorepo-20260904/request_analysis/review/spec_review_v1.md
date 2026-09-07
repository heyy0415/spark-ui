# Spec Review v1 — feat-strato-ui-monorepo-20260904

| 字段 | 值 |
|---|---|
| mode | plan |
| 日期 | 2026-09-04 |
| 轮次 | 1 / 3 |
| 评审对象 | `request_analysis/spec.md`（v1）、`request_analysis/tasks.md`（v1） |
| 依据 | rules/{project-structure, coding-standard, contracts, agent-safety §4, dev-workflow}；skills/expert-reviewer（plan 必查项）；skills/coding-skill/specs/05-styling-spec；现状 `fronted/**`、`.harness/scripts/**`；上一 change「经验沉淀」 |
| 评审方式 | 有罪推定。所有"可行性"结论均在本仓库当前 node_modules（TS 7.0.2 / Vite 8.2.2 / pnpm 10.34.5 / Node 20.20.2 / zod 4.5.4 / antd 6.6.2 / antd-mobile 5.42.3）上实际执行命令验证，证据附在各条 |

---

## 0. verdict

**REVISION REQUIRED** — MUST FIX 4 条，SHOULD 10 条，LOW 6 条，INFO 5 条。

四条 MUST FIX 的共同特征与上一 change 经验沉淀第 9 条相同：**验收段落写了命令，但命令在当前工具链下要么根本跑不通（M2），要么在改动之后恒为绿（M3），要么门禁顺序让干净检出必红（M1），要么校验范围对遗漏盲区（M4）**。都不是设计方向问题，修 spec 即可，不需要推翻方案。

---

## 1. plan 模式 6 项必查

| # | 必查项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 「非目标」章节存在且非空 | ✅ | spec §3 共 8 条 |
| 2 | 每条验收标准都可被命令或断言校验 | ❌ | §6.2 第 3 条「`dist/index.js` 可在 Node 中 `import()`」在当前依赖下**不可能为真**（见 M2）；§6.2 第 4 条过滤器 `grep -v "//"` 会把真正的 `http://` 违规也过滤掉（S6）；§6.1 第 3 条 `pnpm -r ls` 会多列出 workspace 根（S6）；§6.4 deploy-verify「12 passed」在路由改 `/` 后其中 2 项变为恒真（M3） |
| 3 | 风险章节列出 ≥1 个失败模式与缓解措施 | ✅ | spec §7 共 7 行；但缺 zod 双实例（S2）、core CSS 依赖宿主变量（S3）、typecheck 对 dist 的依赖（M1） |
| 4 | 每个 task 标注所属端；contracts task 排在依赖者之前 | ✅ | 8 个 task 全部标 fronted / harness；契约影响 NONE，无 contracts task |
| 5 | 涉及跨端结构的 task 列出对应契约文件 | ✅（有保留） | 本 change 不新增跨端结构。但 `ui-schema.schema.json` 第 39 行 description 与 `contracts.md` §1 表引用了将被删除的路径 `fronted/src/shared/ui/generate/types.ts`、`fronted/src/entities/*/model/types.ts`，spec §5「NONE」与 §2.4 都没提（见 M4） |
| 6 | 每个 task 工作量 ≤ 0.5 天 | ❌ | T03 把「移动全部 src + 删页 + 改路由 + 改 Provider + tokens 读取 + 5 个配置文件 + 删 5 个旧文件」放在一个 task，且与文件头「移动与逻辑分开提交」不可同时成立；T06 = 3 个脚本改造 + 1 个新守护 + 9 份文档重写（见 S5） |

---

## 2. 逐条意见

### MUST FIX

#### M1 · 干净检出下 `pnpm -C fronted run ci` 必红：chat 的 typecheck 解析 `@strato-ui/core` 到未构建的 dist
- **位置**：spec §2.2「开发期 `main`/`types` 不指向 src；chat 通过 `workspace:*` + vite alias（仅 serve）」；spec §7 第 1 行；tasks T01 根脚本 `ci` = `typecheck && lint && format:check && build`；T03 验收 `tsc -p tsconfig.json --noEmit`。
- **问题**：vite alias 只影响 Vite；`tsc` 与 oxlint 走 Node 解析，经 `node_modules/@strato-ui/core` 符号链接读 `package.json` 的 `exports["."].types = ./dist/index.d.ts`。按 T01 的 ci 顺序 typecheck 在 build 之前执行，干净检出（CI、`git clean`、新同事）时 dist 不存在 → `TS2307 Cannot find module '@strato-ui/core'`。spec §7 的缓解「ci 强制先 build core 再 build chat」只覆盖了 build 步，没覆盖 typecheck / lint。同样问题：`vite-node scripts/verify-examples.ts` 走哪条路径 spec 没写——实测 vite-node 内部用 `createServer`，`command === 'serve'`，所以会命中 alias 走 src，但这是隐含行为，必须显式写出来并加断言。
- **建议**（三选一，spec 必须明确选哪一个并同步 §6.3 / T01 / T03 验收）：
  1. **推荐**：core `package.json` 开发期字段指向 src（`"types": "./src/index.ts"`、`exports["."]` 指 src），用 pnpm 的 `publishConfig.exports / publishConfig.types` 在 `pnpm pack` 时覆盖为 dist（pnpm 原生支持，verify-pack 解包后断言的正是覆盖后的值）。这样 tsc / oxlint / vite serve / vite-node 全部无需 alias 天然走 src；chat 的 `vite build` 在 workspace 内也会走 src，需要在 chat `vite.config.ts` 用 `command === 'build'` 时 alias 到 `packages/core/dist/index.js` 保证生产吃 dist（与现 spec 方向相反但逻辑等价）。
  2. 保持 spec 现方案，但 chat `tsconfig.json` 增加 `paths: { "@strato-ui/core": ["../../packages/core/src/index.ts"] }`，并把根 `ci` 顺序改为 `build:core → typecheck → lint → format:check → build:chat`。代价：typecheck 从不消费 dist d.ts（配合 S8 补上）。
  3. 根 `ci` 第一步 `pnpm -F @strato-ui/core build`，其余不变。最简单，但 `pnpm -C fronted typecheck`（dev-workflow 阶段 3 门禁单独跑这个命令）在干净检出仍红。
  无论哪种，spec §4.3 要补一句「`verify-examples` 经 vite-node 以 serve 模式解析，走 core src」，T04 验收加一条能证明的断言（如在 core src 临时植入让 `UiSchemaSchema` 拒绝 `schemaVersion` → verify-examples 必红）。

#### M2 · §6.2「`dist/index.js` 可在 Node 中 `import()`」在当前依赖下不可能成立，verify-pack 的核心断言需换实现
- **位置**：spec §2.5 verify-pack、§6.2 第 3 条、§7 第 2 行；tasks T05。
- **问题**：core 入口 `index.ts` 静态导出 `ActionBar`（→ 静态 import `./mobile/ActionBar` → `antd-mobile` `Button`）与 `StratoThemeProvider`（→ `antd` + `antd/locale/zh_CN`）。dist 把这些全部 external 化，Node `import()` 时会真的去加载 peer。本仓库实测：
  ```
  $ node -e "import('antd')"               → ok
  $ node -e "import('antd-mobile')"        → SyntaxError: Unexpected token ':'   （es/components/button/index.js 第 1 行 import "./button.css"）
  $ node -e "import('antd/locale/zh_CN')"  → ERR_MODULE_NOT_FOUND（antd 无 exports map，Node ESM 不补 .js 后缀）
  ```
  即 T05 设想的「通过 NODE_PATH / symlink 让 peer 从 fronted/node_modules 解析」解决的是找不到包，解决不了 antd-mobile ESM 顶层 import CSS 与 antd 深路径无扩展名这两个 Node 原生 loader 必炸的问题。按这条验收编码，T05 只能以失败或以"改 core 源码绕过"收场——后者会顺手改包结构（把 ActionBar/Theme 拆出入口），超出 spec。
- **建议**：把「Node 可 import」改为两条可成立的断言：
  1. **导出清单**：用 `es-module-lexer`（或 `rolldown` 已内置的 parse）静态解析 `dist/index.js` 的 `export` 名，与 §2.2 运行时清单比对；类型导出用 §S8 的 tsc 消费夹具比对。
  2. **可加载性**：在 `vite-node`（已是 devDependency）中 `import()` 解包后的 `dist/index.js`——vite-node 会处理 `.css` 与深路径解析，等价于接入方 bundler 的行为；或用 Node `--import` 注册一个把 `.css` 解析为空模块、把 `antd/locale/zh_CN` 映射到 `.js` 的 loader hook。二者选一写死在 T05，并在 spec §7 第 2 行更新缓解措施。
  同时 §6.2「`grep -c "ant-design/cssinjs"` 为 0」保留，它是唯一真正证明 antd 没被打进 dist 的断言。

#### M3 · 路由 `/agent → /` 后，deploy-verify 的 2 项断言与 `preview-console.mjs` 变为「对着 NotFound 页验收仍绿」；spec §2.5 漏了 `preview-console.mjs`
- **位置**：spec §2.5「`.harness/scripts/deploy-verify.sh`：preview 与 dist 路径改到 `fronted/apps/chat`」、§6.4「deploy-verify 12 passed / 0 failed」；tasks T06。
- **问题**：
  - `deploy-verify.sh:36` `check "preview /agent (SPA)" 200 …/agent?page=order-detail`——SPA 任何路径都 200，改路由后它验的是 NotFound，恒真。
  - `deploy-verify.sh:52` 调 `preview-console.mjs`，该脚本硬编码打开 `http://localhost:4173/`（原 HomePage，现变 ChatPage）与 `…/4173/agent?page=…`（现 NotFound），统计 console.error==0。NotFound 页当然 0 error → 「preview pages console.error == 0」这一项对主链路页面**不再有覆盖**，仍绿。
  - `preview-console.mjs` 不在 spec §2.5 与 T06 列表；上一 change 的 spec §6.3 之所以要求它，是为了阶段 7 门禁「预览页面 console.error == 0」真的看到主页面。
  - 同理 `deploy-verify.sh:56` bundle 统计 `fronted/dist/assets/*.js`（spec 有提），`deploy-verify/SKILL.md:12,42` 也写死 `fronted/dist`（spec 没提，见 M4）。
- **建议**：spec §2.5 增加 `preview-console.mjs`：URL 改为 `/?page=order-detail&entityType=order&entityId=10001`（截图名 `preview-chat`）与 `/dev/schema?example=confirm` 不可用（preview 是生产构建，无 `/dev/schema`），所以第二张改为 `/does-not-exist`（验证 NotFound 无 error）或直接只留一张；`deploy-verify.sh` 第 36 行改为有区分度的断言，例如 `curl localhost:4173/ | grep -c 'id="root"'` 之外，再由 preview-console 断言页面上存在 `#agent-input`（写进退出码或输出解析）。§6.4 的「12 passed」相应调整为新的断言数，并在 spec 中列出 12 项名字，避免数字恒等而内容空心。

#### M4 · 文档 / 脚本同步范围漏项，且 §6.1 第 4 条 grep 模式对漏项全盲
- **位置**：spec §2.4 文档清单、§2.5、§5「契约影响 NONE」、§6.1 第 4 条；tasks T06。
- **问题**：全仓 grep（排除 `changes/`、`node_modules`、`dist`）发现以下引用将失效，但都不在 spec §2.4 / §2.5：
  | 文件 | 行 | 内容 | §6.1 grep 能否发现 |
  |---|---|---|---|
  | `.harness/rules/contracts.md` | 11 | `fronted/src/entities/*/model/types.ts`（Zod 投影位置；ui-schema 投影将迁到 core） | 否（不含四个关键字） |
  | `.harness/rules/coding-standard.md` | 17 | 「`entities/{x}/model/types.ts` 是该实体类型的唯一真源」——与 spec §2.3「ui-schema 真源迁到 core」直接冲突；spec 只列了 §4 | 否 |
  | `.harness/contracts/ui-schema.schema.json` | 39 | description 引用 `fronted/src/shared/ui/generate/types.ts` | 否（`.harness/contracts` 不在 grep 目录列表） |
  | `.harness/skills/project-analysis/SKILL.md` | 20, 38 | `cat fronted/src/main.tsx …`、`ls fronted/src/{pages,…}`、「fronted/src 切片清单」 | 否 |
  | `.harness/skills/deploy-verify/SKILL.md` | 12, 42 | `fronted/dist/`、`ls -la fronted/dist/assets/*.js` | 否 |
  | `.harness/scripts/preview-console.mjs` | 2, 13 | `/agent` URL（见 M3） | 否 |
  | `fronted/README.md` | 多处 | `Vite 6`（现为 8）、`/agent`、`src/shared/ui/generate` | 部分 |
  其中 `ui-schema.schema.json` 是**契约文件**：改 description 不破坏示例校验，但一旦改动，summary.md「契约变更 NONE」就不成立，需要在 spec §5 明说「仅改 description 路径注释，无字段变更」或明确不改并接受陈旧引用（不推荐——契约是真源）。
- **建议**：(1) §2.4 补上述 6 个文件；`coding-standard.md` 补 §2 行的改法（「契约投影真源：ui-schema 在 `@strato-ui/core`，其余在 `apps/chat/entities`」）；(2) §6.1 第 4 条 grep 模式扩展为 `Generate UI\|shared/ui/generate\|AppThemeProvider\|\[schema-renderer\]\|fronted/src\b\|fronted/dist\|fronted/src/` 且目录列表加 `.harness/contracts .harness/scripts`；(3) 建议在 `harness-doctor.mjs` 加「rules / skills 中出现的 `fronted/` 路径必须存在」的机械检查（doctor 头注释就写着"rules/ 引用的路径是否真存在"，但实际没实现），这是 Hashimoto 法则的直接落点。

### SHOULD

#### S1 · `tsc --emitDeclarationOnly` 在 TS 7 下需显式 `rootDir`；core 需要自己的 `vite-env.d.ts` 与 devDependencies
- **位置**：spec §2.2「构建」；tasks T02b `tsconfig.build.json（declaration、emitDeclarationOnly、outDir dist）`。
- **问题**：在现有源码上实测 `tsc -p tsconfig.app.json --noEmit false --emitDeclarationOnly --declaration --outDir /tmp/x` → `error TS5011: The common source directory … 'rootDir' setting must be explicitly set`（TS 7 新行为）；加 `--rootDir src` 后退出码 0、产出 55 个 d.ts。其次 `import styles from './SchemaRenderer.module.css'` 依赖 `src/vite-env.d.ts` 的 `declare module '*.module.css'`，core 的 `include: src` 里必须也放一份，否则 typecheck 与 d.ts 生成都失败；且这份 `declare module` **不能进 dist d.ts 的公共入口**（`index.d.ts` 不会引用它，ok，但要确认 `tsconfig.build.json` 不把它 emit 成会污染接入方全局的 `.d.ts`——它是 `declare module` 形式，emit 出去无害，但应 `exclude` 以求干净）。第三，T01 说 core 先放"最小 package.json"，T02a 验收要 `tsc --noEmit` 过——core 只声明 peerDependencies 时，其本地 `node_modules` 不一定有 react / antd 类型（pnpm `autoInstallPeers: true` 对 workspace 项目自身 peer 的处理不应作为设计依赖），需要 `devDependencies` 同时列出 react / react-dom / antd / antd-mobile / @ant-design/icons / @types/react / @types/react-dom / vite / typescript。
- **建议**：T02b 明确 `tsconfig.build.json` = `{ extends: ./tsconfig.json, compilerOptions: { noEmit: false, declaration: true, emitDeclarationOnly: true, rootDir: src, outDir: dist, types: [] }, include: [src], exclude: [src/vite-env.d.ts] }`；T02a 输出加 `packages/core/src/vite-env.d.ts`；T02b package.json 加 devDependencies 清单，并把「devDependencies 版本 == chat 依赖版本」写成 verify-pack 或 doctor 断言（防两处漂移）。

#### S2 · zod 单例：core `dependencies: zod` + chat 直接依赖 zod，spec 未处理版本对齐；对外发包时 `dependencies` 比 `peerDependencies` 更容易双实例
- **位置**：spec §2.2 package.json 字段、§6.2 第 3 条「`dependencies` 只有 zod」、§7 风险表（无此行）。
- **问题**：chat 的 `RunSummarySchema` / `SseEventSchema` 把 core 的 `UiSchemaSchema` 作为子 schema 组合。zod 4 组合子 schema 时按内部 `_zod` 结构与 `instanceof`-类 判定，两份 zod 会导致类型不兼容（`z.ZodObject<…>` 来自不同模块实例）与运行期 `superRefine` 路径不可预期。本仓库内：两个包都写 `^4.5.4` 时 pnpm 会去重为一份（lock 已是 `zod@4.5.4` 单条），风险低；但 spec 没把「两处 range 必须一致」写成约束，将来任一处 bump 就分叉。对接入方：`dependencies` 意味着接入方用 zod ^4 其它小版本时可能出现两份（npm/pnpm 只在 range 相交且 hoist 成功时去重），而接入方要 `UiSchemaSchema.parse` 再喂给 renderer，本来就必须拿到 core 的那份 zod 实例——这正是 peer 的语义。
- **建议**：二选一并写进 spec：(a) `peerDependencies: zod ^4`（devDependencies 同时列）— 同步改 §6.2 断言为「`dependencies` 为空对象或不存在」；(b) 维持 `dependencies`，但用 pnpm `catalog:`（pnpm-workspace.yaml `catalog`）统一 zod / react / antd 版本，两个子包写 `"zod": "catalog:"`，verify-pack 断言 pack 后 package.json 中 zod range 与 chat 一致。§7 加一行「zod 双实例」风险。

#### S3 · core 的 CSS Modules 依赖宿主 `global.css` 变量，与 spec「色值来自 props.tokens，不再读宿主」不一致
- **位置**：spec §2.2 `theme/StratoThemeProvider.tsx` 说明；`shared/ui/generate/SchemaRenderer.module.css:20`、`UnknownComponent.module.css:6-9,18`。
- **问题**：两份 CSS 用了 `var(--color-border)`、`var(--radius-md)`、`var(--color-surface-hover)`、`var(--color-text-muted)`、`var(--color-text)`，均无 fallback。它们会原样进入 `dist/style.css`。第三方宿主没有这些变量 → UnknownComponent 边框 / 背景 / 文字色全部落到 `initial`（占位块几乎不可见，违背 05-styling-spec「不允许仅靠颜色传达状态」的反向：状态提示消失）。Theme 只改 antd token 与 `--adm-*` 变量，管不到这两份 CSS。
- **建议**：spec §2.2 增加约束：core 内 CSS 只允许引用 `--strato-*` 变量且必须带 fallback（`var(--strato-color-border, #dee0e3)`），`StratoThemeProvider` 在包裹 `div` 上按 `tokens` 同时写 `--strato-*`；chat 的 `global.css` 保持 `--color-*` 给自己的 Button 用。验收加：`grep -rn "var(--color-\|var(--radius-" fronted/packages/core/src` 为 0。

#### S4 · §8 假设的 `tokens` 键集合小于现实现使用的键 → 会悄悄改视觉，与非目标「不改 7 个组件视觉」冲突
- **位置**：spec §8 第 3 条「只暴露 colorPrimary / colorSuccess / colorError / borderRadius / fontSize」；现 `AppThemeProvider.tsx` 使用 colorPrimary / colorText / colorTextSecondary / colorBorder / colorBgLayout / colorBgContainer / borderRadius 并映射 6 个 `--adm-*`。
- **问题**：按 §8 落地，文字色 / 边框色 / 容器背景将回退到 antd 默认，antd-mobile 的 `--adm-color-text` 等也不再设置——桌面与移动的视觉都会变，而 e2e-frontend 21 项不看颜色，不会红。`colorSuccess` / `colorError` / `fontSize` 目前根本没用到，属"顺手扩展"。
- **建议**：`StratoThemeTokens` 键 = 现实现 7 个键（全部可选，默认值 = 现 `global.css` 的值），不新增；`--adm-*` 映射表原样迁移；spec §8 改写。可加 `locale?: Locale` 可选 prop 供接入方覆盖 `zhCN`（LOW，若不做写进非目标）。

#### S5 · task 粒度：T03、T06 超 0.5 天且与「移动 / 逻辑分开提交」不可同时成立；T02a 需列 commit 切分
- **位置**：tasks 文件头、T02a、T03、T06。
- **问题**：T03 同时包含 `git mv` 全部 src、删 `pages/home`、改路由、改 Provider 组合、tokens 读取、新建 5 个配置文件、删 5 个旧文件、改 features / entities / pages 的 import 源。这既超 0.5 天，又天然是「移动 + 逻辑」混合。T06 = 3 脚本参数化 + doctor 新守护 + 9 份文档。
- **建议**：拆 T03a（纯 `git mv` + 新配置文件 + 删旧文件，验收只要 `test ! -d fronted/src` 与 `ls apps/chat/src` 结构）/ T03b（路由 `/`、`ChatPage` 改名、Provider 用 core、import 改 `@strato-ui/core`、tokens；验收 = 现 T03 的 tsc / build / grep）；T06a（脚本：e2e-frontend / e2e-backend / deploy-verify / preview-console / doctor）/ T06b（文档）。T02a 在「目标」末尾列出预期 commit：① `refactor(fronted): git mv shared/ui/generate → packages/core/src`（零逻辑）② `refactor(core): 相对导入 + 抽 uiSchema.ts` ③ `feat(core): StratoThemeProvider tokens + 日志前缀`。依赖图无环，T05 与 T03/T04 并行成立。

#### S6 · 三条验收断言的形态问题
- **位置**：spec §6.1 第 3 条、§6.2 第 2 / 4 条。
- **问题与建议**：
  1. `pnpm -C fronted -r ls --depth -1` 恰列出两个包——`-r` 会把 workspace 根 `fronted`（private）也列出（实测当前就列出根），恒为 3 项。改为 `pnpm -C fronted -r --filter './packages/**' --filter './apps/**' ls --depth -1` 或 `pnpm -C fronted -r exec node -p "require('./package.json').name"`。
  2. `grep -rn "eval(\|new Function\|dangerouslySetInnerHTML\|href=\|http" … | grep -v "^\s*\*\|//"`——`grep -v "//"` 会把 `fetch('http://…')` 这类**真实违规**也过滤掉（`http://` 自带 `//`），过滤器自我否定。改为只过滤以 `//` 或 `*` 开头的行：`grep -v "^\s*\(//\|\*\)"`，或干脆不过滤（当前 core 源码 grep `http\|href=\|@ant-design` 为 0 行，实测）。
  3. `du -sk dist < 400`——无基线依据。现应用 dist 1480 KB 含 antd；core 去掉 antd / zod 后估算 < 150 KB，400 是拍的。改为 T02b 完成后记录首个实测值到 coding_report，并把阈值写为「≤ 首测 × 1.1」写入 verify-pack（与 deploy-verify「bundle 未恶化 < +10%」口径一致）。
  4. `grep -c "from \"antd\"\|from 'antd'" dist/index.js ≥ 1`——Rolldown ESM 输出用双引号，可成立；但它只证明「有 antd import」不证明「没有 antd 源码」，后者靠 T05 的 `cssinjs` 断言。建议在 §6.2 合并说明，避免评审者误以为前者已足够。

#### S7 · `STRATO_CHANGE` 默认「取最新目录」的判定方式与 e2e-backend「47 passed」的数字来源
- **位置**：tasks T06「默认取 `.harness/changes/` 下最新目录」；spec §6.5「`e2e-backend.sh` 47 passed」。
- **问题**：按目录名排序，`fix-*` 永远排在 `feat-*` 之后，与"最新"无关；按 mtime 排序，评审写 review 文件就会改 mtime。三处脚本各写一遍判定逻辑还会漂移。「47 passed」：脚本里 `check` 调用 38 处（部分在循环内），上一 change 的 deployment 没有记录 e2e-backend 的通过数，评审无法核对 47 从何而来。
- **建议**：新增 `.harness/scripts/lib/change-dir.sh` / `.mjs` 统一实现：`STRATO_CHANGE` 未设置时，按目录名末尾 8 位日期 + 目录内 `summary.md` 状态非 `DELIVERED` 选取；候选 > 1 时退出码 2 并列出候选，要求显式指定；`harness-doctor` 检查三处脚本都通过该 lib 读取（grep 不得再出现 `changes/feat-`）。§6.5 把 47 改为「与 T07 前在当前主干实测的通过数一致（记录于 coding_report）」。

#### S8 · dist 的 `index.d.ts` 在整条 CI 中从未被 TypeScript 消费；「exactOptionalPropertyTypes 对接入方兼容性」无断言
- **位置**：spec §6.2、§7 第 2 行；tasks T05。
- **问题**：chat 走 alias（或 paths）时 typecheck 吃的是 core src；`vite build` 不做类型检查；verify-pack 只 `ls` d.ts 存在。所以 d.ts 内容错误（如漏导出类型、引用了未随包发布的路径）在 CI 全绿下无法发现。关于 EOPT：实测 emit 出的 d.ts 是普通 `onFormChange?: …` 形态，未开 EOPT 的接入方读取无问题（INFO-4），但这是"我看了一眼"，不是断言。
- **建议**：T05 在解包后的 tarball 旁生成 `consumer.ts`（`import { SchemaRenderer, UiSchemaSchema, type UiSchema, … } from './package'`，逐个引用 §2.2 全部类型导出）并分别以 `--exactOptionalPropertyTypes` 开 / 关跑 `tsc --noEmit --skipLibCheck false`；两次都 0 才算过。这一条同时把 §2.2「类型清单」变成可断言，而不只是运行时导出清单。

#### S9 · package.json / lint 配置细节：`sideEffects` glob、oxlint `ignorePatterns`、`publishConfig.access` 只在文本层面正确
- **位置**：spec §2.2 `sideEffects: ["*.css"]`；T01/T04 `.oxlintrc.json` 移到根。
- **问题**：`"*.css"` 只匹配包根一层，dist/style.css 在子目录（antd 自己用 `"*.css"` 是因为它的 css 在 es/ 里且配套 `**` 语义不一定生效——不要照抄）。根 `.oxlintrc.json` 现 `ignorePatterns: ["dist/**"]` 移到 workspace 根后不再匹配 `packages/core/dist/**`。`publishConfig.access: public` 对一个内部 scope 若有人误跑 `npm publish` 会直接发到公网。
- **建议**：`sideEffects: ["./dist/style.css"]`；`ignorePatterns: ["**/dist/**", "**/node_modules/**"]`；`publishConfig` 在本 change 只写 `access`，并在 core `package.json` 加 `"private": false` 的同时加 `prepublishOnly: "node ../../scripts/verify-pack.mjs"` 挡误发（或把这点写进非目标：「本 change 不设置任何 publish 保护，registry 与保护为后续 change」）。

#### S10 · agent-safety §4 在"可发布包"语境下的分界需在 spec 显式列表化，README 示例需补反例
- **位置**：spec §2.2「安全边界不变」、§7 第 5 行、§4.1 示例。
- **问题**：spec 用一句话"边界不变"带过，未区分「随包走的保证」与「留在宿主的保证」。梳理现实现：随包走 = 注册表白名单、每组件 props Zod、未知 type 占位 + console.error、无 eval / innerHTML / 动态路径 import、`type` 按 string 查表（绕过 Zod 也只会得到占位）、React 转义 attribute（`data-screen-id` 等注入无效）。留在宿主 = 整体 `UiSchemaSchema.parse`（`screenId` / `title` 长度、`actions[]` 形态、`confirmationToken` 长度）、`actions[].confirmationToken` 只回传不解析、不把模型输出直接当 UI Schema 构造、pageContext 不可信。README 若只给 §4.1 正例，接入方最常见的绕过是 `SchemaRenderer ui={payload as UiSchema}`——TS 允许，运行期 renderer 不再整体校验 actions。
- **建议**：spec §2.2 增加上述两列清单（作为 README「安全边界」章节的真源），README 加一段「不要这样做」：`as UiSchema` 强转、把 `UnknownComponent` 换成自定义渲染任意 HTML、在宿主实现 `ActionBar` 时解析 token。可选：core 额外导出 `parseUiSchema(input: unknown): UiSchema`（就是 `UiSchemaSchema.parse` 的别名，便于 README 一行接入）——不做也行，但要在非目标写明。

### LOW

- **L1** `AgentChatPanel.tsx` 的 `COMPONENTS` 常量手写 7 个名字；迁移后应改为 `COMPONENT_TYPES`（core 已导出），否则新增组件时 `clientCapabilities` 会漏。写进 T03b。
- **L2** `fronted/.npmrc`：pnpm 10 默认 `auto-install-peers=true`（lock `settings.autoInstallPeers: true` 已证实），不加 `.npmrc` 也成立；但 spec 依赖这一行为（chat 不列 antd 时也能装上），建议显式 `fronted/.npmrc` 写 `auto-install-peers=true` + `strict-peer-dependencies=true`（后者让 peer 范围不满足时直接红，替代 §7 第 3 行的"README 写明"）。
- **L3** T03「tokens 由 `global.css` 变量值在 `app/` 层读取一次」：`getComputedStyle` 在渲染期调用违反 coding-standard §4「禁止渲染期副作用」的精神（虽是读）；要求用 `useState(() => readTokens())` 一次性初始化，与 `DeviceProvider` 现写法一致。
- **L4** 现 `AppThemeProvider` 用 `useMemo(…, [])` 在首次渲染读 CSS 变量；迁到 core 改 props 后这段逻辑要删干净，避免 core 内残留 `getComputedStyle(document.documentElement)`。加验收 `grep -rn "getComputedStyle" fronted/packages/core/src` 为 0。
- **L5** `fronted/README.md` 现写 `Vite 6`，实际 8.2.2；T06 重写时顺带修正（属文档同步，不算顺手改代码）。
- **L6** `pnpm -C fronted -r ls`、`pnpm -F` 等命令在 spec / tasks 里混用 `-F` 与 `--filter`；统一一种以便 grep。

### INFO

- **I1** e2e-frontend 21 项断言在路由改 `/` 后只需改 step 5 的 URL；step 4 断言的是子串 `'unknown component type'`，spec §2.2 保留了该文案（前缀改 `[strato-ui]`），不受影响。spec 已正确判断「其余不变」。
- **I2** Vite 8 lib mode 用 Rolldown；`cssCodeSplit` 在 lib 模式默认 `false`，CSS Modules 全部合并进单文件，文件名由 `build.lib.cssFileName`（不填时默认取 package name，含 `@` / `/` 会被拆成目录）决定——T02b 须写 `cssFileName: 'style'`。CSS Modules 生产类名默认 `_[local]_[hash:5]` 形态，与宿主同名 `.title` 不冲突。`React.lazy` + `formats: ['es']` 在 Rolldown 下正常分 chunk，`rollupOptions.output.chunkFileNames` 建议固定为 `chunks/[name]-[hash].js` 便于 verify-pack 断言文件清单。
- **I3** `erasableSyntaxOnly` / `verbatimModuleSyntax` 与 `emitDeclarationOnly` 无冲突（实测 emit 成功）；现源码在 core 范围内没有 enum / namespace / 参数属性。唯一需要注意的是 `vite-env.d.ts`（S1）。
- **I4** EOPT：emit 出的 d.ts 里可选属性是普通 `?:` 语法，关闭 EOPT 的接入方读取无差异；开启 EOPT 的接入方若显式传 `onFormChange={undefined}` 会报错——这是接入方自己的编译选项问题，与包无关。用 S8 的双模式夹具把这个结论变成断言即可。
- **I5** 上一 change 经验沉淀第 1 条建议「harness-doctor 加 tasks.md 六要素机械校验」尚未落地；本 tasks.md 六要素齐全（目标 / 所属端 / 输入 / 输出 / 验收 / 依赖），但 T06 的「输入」写的是「T03–T05 实际产出」而非文件路径，建议列出脚本与文档路径。

---

## 3. 需要 spec 作者回答的决策点（进入 v2 前）

1. M1 三选一：core 开发期字段指 src + `publishConfig` 覆盖（推荐）/ chat tsconfig paths + ci 重排 / ci 首步 build core。
2. S2 二选一：zod 改 peer / 保持 dependency 但用 pnpm catalog 锁版本。
3. M4：`ui-schema.schema.json` description 改还是不改；改则 §5 与 summary「契约变更」措辞更新。
4. S4：确认 tokens 键集合 = 现 7 个键，`locale` prop 做或写进非目标。

---

## 4. 本轮结论

- **verdict：REVISION REQUIRED**
- MUST FIX：4（M1 干净检出 typecheck 对 dist 的隐性依赖与 ci 顺序；M2 Node `import()` dist 在 antd-mobile / antd locale 下不可能成立；M3 路由改 `/` 后 deploy-verify + preview-console 恒真且后者漏列；M4 文档 / 契约 / 脚本同步漏 6 处且验收 grep 全盲）
- SHOULD：10（S1–S10）
- LOW：6，INFO：5
- 回退：阶段 1，修订 spec v2 / tasks v2；下一轮评审文件 `spec_review_v2.md`。
