# Spec Review v3 — feat-strato-ui-monorepo-20260904

| 字段 | 值 |
|---|---|
| mode | plan |
| 日期 | 2026-09-04 |
| 轮次 | 3 / 3（最后一轮；本轮若 REVISION REQUIRED 则按 dev-workflow 升级 HITL） |
| 评审对象 | `request_analysis/spec.md`（v3）、`request_analysis/tasks.md`（v3） |
| 依据 | rules/{project-structure, coding-standard, contracts, agent-safety}；skills/expert-reviewer plan 必查 6 项；上轮 `spec_review_v2.md`（N1–N6、LOW-1~6）；现状 `fronted/**`、`.harness/scripts/**` |
| 评审方式 | 只评 v3 相对 v2 的增量与闭环质量，不重开已 APPROVED 的方向性决策。有罪推定：v3 新写进 spec 的每个机制都按**目标布局（pnpm workspace，peer 在子包）**在本机构造最小夹具实测（pnpm 10.34.5 / Node 20 / TS 7.0.2 / vite-node 6.0.0），夹具全部在 `/tmp` 下，仓库文件零改动。证据附各条 |

---

## 0. verdict

**REVISION REQUIRED** — MUST FIX 2 条（N7、N8），SHOULD 3 条（N9–N11），LOW 5 条，INFO 4 条。

上轮 12 条（N1–N6 / LOW-1~6）在 v3 中 **10 条已闭环、2 条部分闭环（N4、LOW-4）**，方向无需推翻。两条 MUST FIX 同一个根因：v2 轮的可行性证据是在**当前单包布局**（`fronted/node_modules` 里有 antd / zod / react）上取得的，而 v3 spec 把 peer 全部放进 `packages/core` / `apps/chat` 的 devDependencies、根只留工具链——于是 v3 写进 spec 的两句「解析前提」在目标布局下都不成立：

1. §2.1「依赖 pnpm 默认 hoist-workspace-packages 让 scripts/ 能裸 import `@strato-ui/core`」为假 → `verify-examples` 必红（§6.3 第 4 条、根 `ci` 不可达）。
2. §6.2 (d)(e)「以 fronted 为 Vite root / cwd = fronted 即可解析 peer」为假 → `verify-pack` (d)(e) 必红（§6.2 第 4 条、根 `ci` 不可达）。

两条各自是一行配置 / 一个路径的改动，且不改任何方向性决策。考虑到已到第 3 轮上限，建议 HITL 时**只**授权这两处最小修订（见 §5「建议的 v3.1 最小补丁」），不重开其它讨论。

---

## 1. 上轮意见闭环核对（N1–N6、LOW-1~6）

| # | 上轮意见 | 状态 | 证据（v3 章节原文） |
|---|---|---|---|
| N1 | chat `vite build` alias 字符串键前缀匹配吞掉 `/style.css` | **已闭环** | spec §2.2「解析策略」：「alias **必须用正则精确匹配**：`resolve.alias: [{ find: /^@strato-ui\/core$/, replacement: <abs>/packages/core/dist/index.js }]`（对象字符串键会前缀匹配……→ UNLOADABLE_DEPENDENCY）」；tasks T03a 目标：「build 时 alias 用正则 `/^@strato-ui\/core$/` → ../../packages/core/dist/index.js」。两处一致，编码 Agent 按字面写即成立 |
| N2 | `tsconfig.build.json` exclude `vite-env.d.ts` + `types: []` 使 d.ts 生成必红 | **已闭环** | spec §2.2「构建」：`tsconfig.build.json = { …, types: [] }, include: [src]`，并明写「**不要** exclude `vite-env.d.ts`……`.d.ts` 输入本来不会被 emit」；tasks T02c：「（spec v3 §2.2 原文，含 `rootDir: src`、`types: []`，**不** exclude vite-env.d.ts）」。二选一已写死为「去 exclude」。上轮建议的 `find dist -name 'vite-env*' \| wc -l == 0` 验收未采纳（LOW-4，可选） |
| N3 | (e) `skipLibCheck:false` 的前提是 d.ts 不引用 antd 类型，未写成断言 | **已闭环** | spec §2.2「构建」新增设计约束：「**公共类型不得暴露 antd / antd-mobile 类型**：dist 下任何 `.d.ts` 不得 import `antd` / `antd-mobile` / `@ant-design`」；§6.2 (g) 断言 + 「(e) 成立的前提」；(e) 写明 `consumer.ts` 位置、tsconfig 全部 compilerOptions；tasks T05 目标含 (g)、验收 ④「在 core 某公共类型上 `import type { ButtonProps } from 'antd'` 并导出 → (g) 红」。实测现有封装层无 `import type … from 'antd'`，(g) 当前为真 |
| N4 | (d) vite-node 需以 `fronted` 为 root 才能解析 peer；调用形式统一 | **部分闭环** | 形式已采纳：spec §6.2 第 4 条改为 `pnpm -C fronted run verify-pack`、「脚本内一切子进程 `cwd = fronted`，vite-node 以 `fronted` 为 Vite root」、(d) `vite-node --root <fronted> <loader.mjs> <解包 dist/index.js 绝对路径>`；tasks T05「`vite-node --root <fronted>`」「脚本所有子进程 `cwd = fronted`」。**但**「否则 peer 无法从 `fronted/node_modules` 解析」这一前提在 v3 的 monorepo 布局下不成立（`fronted/node_modules` 没有 peer）→ 见 **N8** |
| N5 | doctor「文档路径存在」检查无匹配规则，必然误报 | **已闭环** | spec §2.5 `harness-doctor.mjs` 段「规则写死」四条与上轮建议 ①–④ 逐字对应（只取反引号内以 `fronted/` 开头且不含空格的 token；含 `*` / `{` 取第一个通配符前的目录前缀 `existsSync`；跳过 `node_modules` / `dist`；范围 rules / skills / wiki / agents / CLAUDE.md / AGENTS.md，不含 `changes/`）；tasks T06c 验收含正向（`fronted/nope/x.ts` → 红）与反向（`fronted/apps/chat/src/pages/**` 与 `ls fronted/apps/chat/src` → 不报）。用规则对现状文档人工过一遍：`fronted/`（×6）、`fronted/README.md` 通过；`fronted/src/entities/*/model/types.ts` 取前缀 `fronted/src/entities/` 会红（正是要抓的）；`ls fronted/src/{…}` 含空格跳过 ✓ |
| N6 | T02a「复制 vite-env.d.ts」与「全部 `=>`」自相矛盾；中间 commit CI 红未声明 | **已闭环** | tasks 头部：「T02a 严格只 rename，`vite-env.d.ts` 复制移到 T02b 首个 commit（N6）；Phase B / C 内部的中间 commit 允许 CI 红，T04 完成后必须全绿」；T02a 目标「只做 `git mv`，零逻辑改动……此 commit 的 typecheck / lint 允许红」；T02b 目标首句「新增 `packages/core/src/vite-env.d.ts`（复制自 chat）」。实测 `git diff --stat -M HEAD~1` 纯 rename 形态为 `a.txt => b.txt \| 0`，验收可执行。残留：T02a「输入」列仍含 `fronted/src/vite-env.d.ts`（LOW-1） |
| LOW-1 | dist 缺失守护只在根脚本，`--filter strato-chat dev` 绕过 | **已闭环** | spec §2.2「dist 缺失守护放在 `apps/chat/package.json` 的 `predev` / `prebuild`（检查 `../../packages/core/dist/style.css` 存在，否则退出并提示先 `pnpm -C fronted build:core`），而不只在根脚本」；tasks T03a「package.json（……`predev` / `prebuild` 守护 core dist 存在）」。实测 pnpm 10.34.5 在无任何 rc 配置下 `pnpm dev` / `pnpm run build` 均执行 `predev` / `prebuild`（INFO-1），机制成立 |
| LOW-2 | `lib/change-dir` 状态解析规则未写死；并行 change 预期用法未说明 | **已闭环** | spec §2.5「按正则 `^\| 状态 \| (\S+) \|` 读 `summary.md` 状态，选状态 ∉ {`DONE`} 的目录……**并行第二个 change 时必须显式 `STRATO_CHANGE=<id>`，这是预期用法**，写进 `dev-workflow.md` 阶段 7 与 `deploy-verify/SKILL.md`」。正则对两个现存 summary（`\| 状态 \| DRAFT \|` / `\| 状态 \| DONE \|`）与模板逐一核对匹配，且不会误匹配阶段表头 `\| # \| 阶段 \| 状态 \|` |
| LOW-3 | `preview-console.mjs` 双通道输出格式与解析未定义 | **已闭环** | spec §2.5「stdout 独立一行 `agent-input=1` 或 `agent-input=0`；退出码仍 = console.error 总数；deploy-verify 用 `tee deployment/preview-console.log` 落盘后 `grep -o 'agent-input=[01]'` 解析」；tasks T06a 同步 |
| LOW-4 | 根 devDependencies 未列；`es-module-lexer` 加在哪；裸 import `@strato-ui/core` 建议显式 `workspace:*` | **部分闭环** | 根 devDependencies 已列（spec §2.1：typescript / oxlint / prettier / vite-node / es-module-lexer）；T05「`es-module-lexer` 加到 workspace 根 devDependencies」✓。**但**「显式 `@strato-ui/core: workspace:*`」未采纳，改写为「依赖 pnpm 默认 hoist-workspace-packages 让 scripts/ 能裸 import」——实测该前提为假 → 见 **N7** |
| LOW-5 | chat typecheck 经 exports 走 core src 时 `*.module.css` 依赖 chat 的 `vite/client` | **已闭环** | spec §2.2「core 源码的 `*.module.css` 类型由 chat `tsconfig` 的 `types: ["vite/client"]` 提供（……隐式耦合，特此说明）」 |
| LOW-6 | T06a 体量偏大 | **已闭环** | tasks 新增 T06c（doctor 两项新检查），T06a 目标末句「（doctor 两项新检查见 T06c）」；依赖 T06a → T06c → T06b |

统计：已闭环 10 / 部分 2（N4、LOW-4）/ 未闭环 0。两条「部分」都升级为本轮 MUST FIX，因为 v3 用一句**错误的机制断言**替代了原建议，而这句断言是验收命令能否为 0 的前提。

---

## 2. plan 模式 6 项必查

| # | 必查项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 「非目标」章节存在且非空 | ✅ | spec §3 共 10 条，v3 未删减 |
| 2 | 每条验收标准都可被命令或断言校验 | ❌ | 全部为命令形态（✓），但 §6.3 第 4 条 `verify-examples` 与 §6.2 第 4 条 `verify-pack` 按 spec v3 原文配置**不可能为 0**（N7、N8 实测），进而 §6.3 第 3 条干净检出 `ci`、§6.5 `run ci` 不可达 |
| 3 | 风险章节 ≥1 个失败模式与缓解 | ✅ | spec §7 共 12 行；但「dev / typecheck 走 src、生产走 dist」一行的缓解未覆盖「根 scripts 与 verify-pack 的解析基准目录」这个新失败模式（N7 / N8 修完后建议补一行） |
| 4 | 每个 task 标注所属端；contracts task 排前 | ✅ | 12 task（T01 / T02a / T02b / T02c / T03a / T03b / T04 / T05 / T06a / T06c / T06b / T07）全部标 fronted / harness；契约仅 description 注释，随 T06b 落地，无依赖者 |
| 5 | 跨端结构 task 列出契约文件 | ✅ | 不新增跨端结构；`ui-schema.schema.json` L39 变更范围在 §5 / T06b 明确为 description 注释 |
| 6 | 每个 task ≤ 0.5 天 | ✅ | T06c 拆出后 T06a 体量回到可控；六要素（目标 / 所属端 / 输入 / 输出 / 验收 / 依赖）12 个 task 全部齐全；依赖图 `T01→T02a→T02b→T02c→T03a→T03b→T04→T06a→T06c→T06b→T07` 与 `T02c→T05→T06a` 无环，T05 与 Phase C 并行成立 |

---

## 3. 新意见

### MUST FIX

#### N7 · §2.1「依赖 pnpm 默认 hoist-workspace-packages 让 scripts/ 能裸 import `@strato-ui/core`」为假；`verify-examples.ts` 从 `fronted/scripts/` 直接 import `@strato-ui/core` 必报 `Cannot find package`
- **位置**：spec §2.1 目录树第 2 行注释「依赖 pnpm 默认 hoist-workspace-packages 让 scripts/ 能裸 import @strato-ui/core」；§2.1 根 `package.json`「devDependencies 只放工具链」；§6.3 第 4 条 `verify-examples` 输出 `16 examples OK`；tasks T01 根 `package.json` 字段清单（无 `@strato-ui/core`）；T04 目标「`scripts/verify-examples.ts` 从 `@strato-ui/core` 取 `UiSchemaSchema`……以 `vite-node -c apps/chat/vite.config.ts` 运行」。
- **问题**：pnpm 的 `hoist-workspace-packages=true`（默认）把 workspace 包 hoist 到**隐藏目录** `node_modules/.pnpm/node_modules/`，不是根 `node_modules/`。Node / Vite 的 bare specifier 解析从 importer 所在目录向上找 `node_modules/@strato-ui/core`，`fronted/scripts/verify-examples.ts` 的向上路径只有 `fronted/node_modules`，那里没有它。按 spec 布局构造最小 workspace（根 `private`、`packages/core` 有 `exports` 指 src、`apps/chat` 依赖 `workspace:*`、`.npmrc` 两键）实测：
  ```
  root node_modules 可见条目: (none)            .pnpm/node_modules/@x/core: yes（隐藏 hoist 确实发生）
  $ node scripts/t.mjs                            → ERR_MODULE_NOT_FOUND '@x/core'
  $ vite-node --root <ws> scripts/t.mjs           → Cannot find package '@x/core' imported from scripts/t.mjs
  # 根 package.json 加 "devDependencies": { "@x/core": "workspace:*" } 后
  $ node scripts/t.mjs                            → LOADED FROM_CORE_SRC
  $ vite-node --root <ws> scripts/t.mjs           → LOADED FROM_CORE_SRC
  ```
  `-c apps/chat/vite.config.ts` 不改变这一点：chat 的 alias 只覆盖 `@shared/*` 等六个前缀与 `@contracts`，`@strato-ui/core` 仍走 node 解析；Vite root（cwd = `fronted`）的 `node_modules` 同样没有它。结果：T04 验收「`16 examples OK`」与反证 ④、§6.3 第 3–4 条、根 `ci`（含 `verify-examples`）、§6.5 全部红。编码 Agent 只能在「偷偷加依赖」「改 `.npmrc` 开 `shamefully-hoist`」「改成经 `@entities/agent-run` 间接拿 `UiSchemaSchema`」之间自行决定——三种都与 spec 原文冲突。
- **建议**（一行）：spec §2.1 根 `package.json` devDependencies 清单加入 `"@strato-ui/core": "workspace:*"`，并把该行注释改为「根显式依赖 `@strato-ui/core`（`workspace:*`），供 `scripts/` 裸 import；不依赖隐式 hoist」；tasks T01 目标同步。§6.1 第 3 条的 `-r exec` 只 filter 子包，不受影响。不要用 `shamefully-hoist` / `public-hoist-pattern`（会把 peer 也提到根，破坏 §7「antd 双实例」的缓解前提）。

#### N8 · verify-pack (d)(e) 的解包目录 / `consumer.ts` 位置按 spec 原文（`fronted/.verify-pack/` 或 `<tmp>`）落地时，dist 的 `zod` / `react` 类型与运行时 import 从该位置向上都找不到，两断言必红；「以 `fronted` 为 Vite root / cwd = fronted 即可解析 peer」的前提在 monorepo 布局下不成立
- **位置**：spec §6.2 第 4 条「脚本内一切子进程 `cwd = fronted`，vite-node 以 `fronted` 为 Vite root，否则 peer 无法从 `fronted/node_modules` 解析」；(d) `vite-node --root <fronted> <loader.mjs> <解包 dist/index.js 绝对路径>`；(e)「在 `fronted/.verify-pack/`（gitignore）生成 `consumer.ts` 与临时 `tsconfig.json`（…… `skipLibCheck: false`, `paths: { '@strato-ui/core': [<解包 dist/index.d.ts>] }`）」；tasks T05 目标「`pack --pack-destination <tmp>` → 解包」「`vite-node --root <fronted>` 加载解包后的 `dist/index.js`」。
- **问题**：v3 布局下 `fronted/node_modules` 只有根 devDeps（typescript / oxlint / prettier / vite-node / es-module-lexer）；`zod`、`react`、`@types/react`、antd 等全部只在 `packages/core/node_modules` 与 `apps/chat/node_modules`（实测：根 `node_modules` 可见条目仅 `typescript`，`zod` / `@types/react` 只出现在 `packages/a/node_modules`）。而：
  - (d) vite-node 对解包 `dist/index.js` 里 `import { z } from "zod"` 的解析基准是 **importer 文件所在目录**，`--root` 只决定 Vite 项目根，不是解析基准。实测（`--root <ws>` 固定）：解包在 `<ws>/.verify-pack/pkg/dist/` → `Cannot find package 'zod'`；解包在 `<ws>/packages/core/.verify-pack/pkg/dist/` → `LOADED_D`。
  - (e) `skipLibCheck: false` 会类型检查解包 d.ts，d.ts 里 `import type { ComponentType } from 'react'` / `import { z } from 'zod'` 同样从 d.ts 所在目录解析。实测（tsc 7.0.2，spec (e) 原文 compilerOptions）：d.ts 在 `<ws>/.verify-pack/pkg/dist/` → `TS2307: Cannot find module 'react'` / `'zod'`，exit 1；移到 `<ws>/packages/core/.verify-pack/pkg/dist/` → exit 0。
  
  也就是说 v2-N4 的实测（在当前单包 `fronted` 上，peer 就在 `fronted/node_modules`）被 v3 直接搬进了目标布局，结论反了。按 spec 原文实现，T05 验收「`verify-pack` 0 且打印 17 个导出名」、§6.2 第 4 条、根 `ci`（含 `verify-pack`）、§6.5 全部红；(c)(g) 等纯静态断言不受影响。
- **建议**（一处路径）：spec §6.2 第 4 条与 T05 把「解包目录、`consumer.ts`、临时 `tsconfig.json`」统一放到 **`fronted/packages/core/.verify-pack/`**（gitignore），并把理由改写为「peer 只安装在 `packages/core/node_modules`，vite-node 与 tsc 都从被加载文件所在目录向上解析，所以解包产物必须位于 `packages/core` 之下；`cwd = fronted` 只为 `pnpm --filter` 与相对路径稳定」。(d) 保留 `--root <fronted>`（无害）。(e) 的 `paths` 改为相对 `packages/core/.verify-pack/`。`verify-pack.mjs` 末尾删除该目录（配合 N10）。§7 风险表补一行「根 `node_modules` 无 peer：任何放在 `packages/*` 之外的临时消费代码都解析不到 peer」。

### SHOULD

#### N9 · §6.1 第 4 条 grep 模式 `fronted/src\b` 覆盖 `.harness/scripts`，而 §2.5 要求 `harness-doctor.mjs` 新增「`fronted/src` 存在即 err」——按字面实现两者互斥
- **位置**：spec §6.1 第 4 条 `grep -rn "…\|fronted/src\b\|fronted/src/\|fronted/dist" … .harness/scripts …` 输出 0 行；§2.5 `harness-doctor.mjs`「`fronted/src` 存在即 err」；tasks T06a 目标同句；T06b 验收「spec §6.1 第 4 条 grep 0 行」。
- **问题**：doctor 最自然的写法 `existsSync(join(root, 'fronted/src'))` 或错误文案 `'fronted/src must not exist'` 都会让 §6.1 grep 命中 1 行；T06b 验收随即为假。编码 Agent 要么改验收、要么把字符串拆开躲 grep，两种都是无依据的自行决定。
- **建议**：spec §2.5 明写「doctor 用 `join(root, 'fronted', 'src')` 拼路径、错误文案不出现字面 `fronted/src`」；或 §6.1 第 4 条对 `.harness/scripts/harness-doctor.mjs` 加 `--exclude`。二选一写死，T06a / T06b 同步。

#### N10 · `fronted/.verify-pack/`（或 N8 后的 `packages/core/.verify-pack/`）残留产物会被下一次 `lint` / `format:check` 扫到，第二次跑 `ci` 可能红
- **位置**：spec §6.2 (e)「在 `fronted/.verify-pack/`（gitignore）生成 `consumer.ts` 与临时 `tsconfig.json`」；§2.1 根 `ci` 顺序（`lint`、`format:check` 在 `verify-pack` 之前）；tasks T01 `.prettierignore`（加 `**/dist`）与 oxlint `ignorePatterns: ["**/dist/**","**/node_modules/**"]`；T07 多次运行 `ci`。
- **问题**：`verify-pack` 是 `ci` 最后一步，运行后 `.verify-pack/consumer.ts` / `tsconfig.json` 留在工作区；T07 先跑干净检出模拟 `ci`，再跑全仓 `run ci`，第二次的 `oxlint`（ignorePatterns 不含 `.verify-pack`）会 lint 生成的 `consumer.ts`（`no-unused-vars` 等大概率命中），`prettier --check` 依赖 `.gitignore` 是否被尊重。spec 只说了 gitignore，没说 lint / prettier 忽略，也没说清理。
- **建议**：spec §6.2 (e) / T05 加「`verify-pack.mjs` 结束（含失败路径）时删除 `.verify-pack/`」；T01 的 oxlint `ignorePatterns` 与 `.prettierignore` 各加 `**/.verify-pack/**`。

#### N11 · spec §2.5 要求把「并行 change 必须显式 `STRATO_CHANGE`」写进 `dev-workflow.md` 阶段 7，但 §2.4 文档同步清单与 T06b 文件清单都没有 `dev-workflow.md`
- **位置**：spec §2.5「写进 `dev-workflow.md` 阶段 7 与 `deploy-verify/SKILL.md`」；§2.4 清单（无 `dev-workflow.md`）；tasks T06b 目标文件列表（`.harness/rules/{project-structure,coding-standard,contracts}.md`，无 dev-workflow）与输出「上述 14 个文件」。
- **问题**：T06b 按字面执行不会改 `dev-workflow.md`，§2.5 的承诺落空；LOW-2 的闭环只剩一半。另 T06b 输出「14 个文件」按目标列表清点为 14 + `summary.md` = 15。
- **建议**：§2.4 清单与 T06b 目标加 `.harness/rules/dev-workflow.md`（阶段 7 一句话），输出改「15 个文件」或删掉计数。

### LOW

- **LOW-1** tasks T02a「输入」列仍含 `fronted/src/vite-env.d.ts`，但 v3 已把复制移到 T02b；T02a 输入应删掉该项（N6 残留，纯清单一致性）。
- **LOW-2** tasks T05「输入」写 `§6.2 (a)–(f)`，目标与验收已覆盖 (g)；验收写「三条植入违规」实列 ①–④ 四条。改为 `(a)–(g)`、「四条」。
- **LOW-3** tasks T03a 目标先 `git mv fronted/src fronted/apps/chat/src` 再 `git rm -r fronted/src/pages/home`，第二步路径在 mv 后已不存在；应为 `git rm -r fronted/apps/chat/src/pages/home`（或先 rm 再 mv）。
- **LOW-4** N2 建议的产物守护 `find fronted/packages/core/dist -name 'vite-env*' | wc -l` 为 0 未采纳；可选，但成本为零，建议进 §6.2 或 verify-pack (a)。
- **LOW-5** spec (d) 的 `<loader.mjs>` 未指定位置，tasks T05 输出只列 `verify-pack.mjs` 与 `verify-pack.baseline.json`；建议写死 `fronted/scripts/verify-pack-loader.mjs`（内容一行 `await import(process.argv[2])`）或由 `verify-pack.mjs` 写到 `.verify-pack/` 内。

### INFO

- **INFO-1** pnpm 10.34.5 在无 `~/.npmrc` / 项目 `.npmrc` 相关配置下，`pnpm dev`、`pnpm run build` 均执行 `predev` / `prebuild`（实测各命中 1 次）。`enable-pre-post-scripts` 默认已为 true，LOW-1 闭环机制成立，无需额外 rc。
- **INFO-2** TypeScript 7.0.2 接受 §6.2 (e) 原文 compilerOptions（`strict` / `moduleResolution: bundler` / `jsx: react-jsx` / `types: []` / `skipLibCheck: false`，未显式 `module`）：最小文件 `tsc --noEmit` exit 0；(e) 只差 N8 的解析位置。
- **INFO-3** `^\| 状态 \| (\S+) \|` 对 `.harness/changes/*/summary.md`（`DRAFT` / `DONE`）与 `templates/change-template/summary.md` 逐一匹配；阶段表头 `| # | 阶段 | 状态 | …` 不以 `| 状态` 开头，不会误命中。当前状态下 `lib/change-dir` 会唯一选中本 change（另一个为 DONE）。
- **INFO-4** 现有 `.harness/scripts/` 中硬编码 `changes/feat-` 的只有 `deploy-verify.sh:6` 与 `e2e-backend.sh:6`（`e2e-frontend.mjs:19` 用 `join(...)` 拼接同样指向旧 change，T06a 需一并改）；e2e-frontend 只依赖 `unknown component type` 文案不依赖 `[schema-renderer]` 前缀，§2.4 改前缀不会破坏 21 项。

---

## 4. 与规则的对照（增量部分）

- `project-structure.md` §1 / 红线 2–3：v3 未改规则文件本身（属 T06b），spec §2.4 给出的新红线措辞与 §2.2 / §2.3 一致，无冲突。
- `coding-standard.md` §4「antd 只在 `shared/ui/**`」：v3 改为「只在 `packages/core/src/components/**` 与 `theme/**`」（T04 override）+ chat 全局禁止 + §6.3 grep 守护，语义等价且更严，OK。
- `contracts.md` §1 前端投影真源：v3 拆为 core（ui-schema）+ chat（其余 8 个），§2.3 与 §2.4 一致；字段零变化，§5 与 T06b 的 `check-contracts` 验收一致。
- `agent-safety.md` §4 前端边界：随包走 / 留宿主两列表（§2.2）未变；`parseUiSchema` 为公共 API，README 反例保留。无新增风险。

---

## 5. 建议的 v3.1 最小补丁（供 HITL 决策）

只改以下 4 处即可让 N7 / N8 闭环，不触碰任何已 APPROVED 的方向：

1. spec §2.1 根 `package.json` devDependencies 加 `"@strato-ui/core": "workspace:*"`，删除「依赖默认 hoist」一句；tasks T01 同步。
2. spec §6.2 第 4 条 / (d) / (e) 与 tasks T05：解包目录、`consumer.ts`、临时 tsconfig 统一放 `fronted/packages/core/.verify-pack/`（gitignore），理由改为「peer 只在 `packages/core/node_modules`，解析基准是被加载文件所在目录」；脚本结束删除该目录。
3. （SHOULD，顺手）spec §2.5 doctor 用 `join(root, 'fronted', 'src')` 且文案不含字面 `fronted/src`；T01 oxlint / prettierignore 加 `**/.verify-pack/**`；§2.4 与 T06b 加 `dev-workflow.md`。
4. （LOW，顺手）T02a 输入去掉 `vite-env.d.ts`；T05 `(a)–(g)` / 「四条」；T03a rm 路径。

---

## 6. 本轮结论

- **verdict：REVISION REQUIRED**
- MUST FIX：2（N7 根 `scripts/` 裸 import `@strato-ui/core` 依赖「默认 hoist」为假，`verify-examples` 必红；N8 verify-pack (d)(e) 解包 / consumer 放在 `packages/core` 之外时 `zod` / `react` 解析不到，两断言必红）
- SHOULD：3（N9–N11）；LOW：5；INFO：4
- 上轮闭环：10 已闭环 / 2 部分（N4、LOW-4，均因同一个错误前提）/ 0 未闭环
- 流程：第 3 轮为上限，本轮未 APPROVED → 按 dev-workflow 升级 **HITL**。建议 HITL 只授权 §5 第 1–2 项的最小修订，然后以 `spec_review_v3.1.md`（或直接进入阶段 3 并把两处修订记入 coding_report 偏差项）收口。
