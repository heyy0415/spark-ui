# Code Review v3 — feat-strato-ui-monorepo-20260904

| 字段 | 值 |
|---|---|
| mode | execution |
| 日期 | 2026-09-04 |
| 轮次 | 2 / 2（最后一轮；expert-reviewer 独立评审） |
| 评审对象 | 回修 commit `e18e673`（基于 `fa70526`）+ 当前主干全部产出物 |
| 依据 | spec v3.1 §6 / tasks v3.1；上一轮 `code_review_v2.md` 的 M1、S1–S7、L1–L13；rules/{project-structure, coding-standard, contracts, agent-safety, dev-workflow} |
| 独立性 | **未读** `coding/coding_report_v1.md`。所有结论来自本机实际执行；植入反例全部在 `/tmp/strato-probe`（`rsync` 副本，排除 node_modules / dist）上做并逐项 `diff` 确认还原后删除；仓库文件零改动（`git status` 空） |

---

## 0. verdict

**APPROVED** — MUST FIX 0 条，SHOULD 2 条（N1、N2），LOW 7 条，INFO 5 条。

上一轮唯一 MUST FIX（M1，L1 记忆文件硬约束与实现冲突）已在三处文件同步修正并经 grep 证实无残留；S1–S7 七条 SHOULD 中 6 条完整闭环、1 条（S1）spec 已改但 tasks.md 未同步。所有修复经植入反例复核均**真实生效**（详见 §1 证据列），未发现回归：`fronted ci`（清 dist 起跑）、全仓 `ci`、doctor、deploy-verify 12/12、e2e-frontend 21/21、e2e-backend 47/47 本机全部重跑为绿。两条新 SHOULD 分别是 verify-pack 新增 (b) catalog 解析在 YAML 值带引号时**静默失效**、以及上一轮 L5（check-deps 漏 `export … from`）建议升级——两者都不阻断本 change 交付，可在阶段 5 前一并处理或登记为下一 change 的首项。

---

## 1. 上轮意见闭环表

| # | 上轮意见 | 状态 | 证据（本机实测） |
|---|---|---|---|
| **M1** | L1 / agents 硬约束仍写「antd 只在 `shared/ui/**`」 | **已修** | `CLAUDE.md:47`、`AGENTS.md:47`、`.harness/agents/platform-owner.md:29` 三处改为「只在 `fronted/packages/core/src/components/**` 与 `theme/**` 内 import；`apps/chat` 只用 `@strato-ui/core` 包入口」；`grep -rn 'shared/ui/\*\*' CLAUDE.md AGENTS.md .harness/agents .harness/rules` 0 行；doctor「doc paths」对新 token `fronted/packages/core/src/components/**` 探测目录存在 ✓。上轮建议的 Hashimoto 落点（doctor 增加 L1 反引号路径不限 `fronted/` 前缀的检查）**未落地**（→ L-6） |
| S1 | spec §6.1 第 3 条 filter 命令按字面输出「No projects matched」 | **部分** | spec §6.1 已改为 `cd fronted && pnpm -r --filter … exec …` 并注明 cwd 语义；本机执行恰输出 `@strato-ui/core` / `strato-chat` 两行 ✓。但 `request_analysis/tasks.md:14` T01 验收仍是旧命令 `pnpm -C fronted --filter './packages/**' …`（→ L-1） |
| S2 | `TERMINAL` 只有 `DONE`，与 dev-workflow 阶段 8 `DELIVERED` 冲突 | **已修** | `lib/change-dir.mjs:15` `new Set(['DONE', 'DELIVERED'])`；注释、spec §2.5、`deploy-verify/SKILL.md:17` 同步；`node lib/change-dir.mjs` 在当前 2 个 change（DRAFT / DONE）下输出本 change 路径 rc=0 |
| S3 | doctor `changes/feat-` 检查不递归 `lib/`；doctor 自身字面量让 T06a grep 为 1 行 | **已修** | `harness-doctor.mjs:192-209` 递归 `walkScripts` + `needle = 'changes/' + 'feat-'`；`grep -rn "changes/feat-" .harness/scripts` 现为 **0 行**（rc=1）；`find .harness/scripts -type f` = 10 `.mjs` + 3 `.sh`，`/\.(sh\|mjs)$/` 无遗漏 |
| S4 | verify-pack (g) 漏内联 `import("antd/…")` | **已修** | `verify-pack.mjs:182` 正则 `/(from ['"]\|import\(['"])(antd\|@ant-design)/`；/tmp 副本在解包前的 `dist/theme/StratoThemeProvider.d.ts` 末尾植入 `export declare const leaked: import("antd/es/table").ColumnsType<unknown>;` → `✗ (g) antd types leaked: dist/theme/StratoThemeProvider.d.ts`（(e) 两次亦红，符合「(g) 是 (e) 前提」）；真 dist `grep -rl 'import("antd'` 0 |
| S5 | 缺「peer range 主版本 == catalog 主版本」断言 | **已修（有盲区）** | `verify-pack.mjs:140-156` 新增 `(b) peer ranges match catalog major versions`；/tmp 副本把 core `peerDependencies.zod` 改 `^3` → `✗ (b) peer/catalog major mismatch: zod ^3 vs catalog 4`。盲区：catalog 值带引号 / `~` / `>=` 时静默跳过（→ **N1**） |
| S6 | `COMPONENT_TYPES` 无机械校验 + 假注释 | **已修** | `check-registry.mjs:71-79` 解析 `COMPONENT_TYPES = [ … ] as const` 与契约 enum 比对；/tmp 副本删 `'Card'` → `✗ COMPONENT_TYPES [...] != contract enum [...]` rc=1；`uiSchema.ts:11` 注释改为四方一致的准确表述；成功输出改为 `contract / COMPONENT_TYPES / desktop / mobile / props` |
| S7 | 注册表运行期可写；`schema === undefined` 抛 TypeError | **已修** | `componentRegistry.ts:21,57` `Object.freeze({...})`；`SchemaRenderer.tsx:43-49` `schema` 为 `undefined` 走 `UnknownComponent`（reason「组件缺少属性约定」）。运行期探针（vite-node 加载真 dist）：`desktopRegistry.Evil = …` → **TypeError**；`Object.isFrozen` desktop / mobile 均 `true`。`Registry` 类型未变（`Readonly<Record<string, RegisteredComponent>>`，`dist/registry/componentRegistry.d.ts:10-12` 逐字同前）；`check-registry` 正则同时接受 `= {…};` 与 `= Object.freeze({…});`（/tmp 副本把两处改回未冻结写法仍 ✓ 7 types） |
| L1 | `allowedVersions react/react-dom '19'` 全局放行 | **已修** | `pnpm-workspace.yaml:23-25` 改为 `'@react-spring/*>react'`、`'@react-spring/*>react-dom'`、`'staged-components>react'`；见 §2 安装验证 |
| L2 | `DeviceContext.ts:3` 陈旧注释 `app/providers/DeviceProvider` | 未修 | 仍在（→ L-5） |
| L3 | `ensure-core-dist` 只查 `style.css` | **已修** | `ensure-core-dist.mjs:9-10` 同时检查 `style.css` 与 `index.js` |
| L4 | verify-pack (f) 基线缺失静默重写 | 未修 | /tmp 副本 `mv` 掉 baseline → `✓ (f) dist 40 KB — baseline written` 全绿（→ L-3） |
| L5 | check-deps 漏 `export … from` / 动态 import | 未修 | /tmp 副本 `apps/chat/src/shared/index.ts` 写入 `export * from '@features/agent-chat';` → `✓ FSD dependency direction OK` rc=0（→ **N2** 升级） |
| L6 | Form 字段 schema 两份、`ActionBarProps` 三份 | 未修 | `schema/uiSchema.ts:24` / `registry/types.ts:11`；`ActionBarProps` 仍在 renderer / desktop / mobile 三处（→ L-4） |
| L7 | `ChatPage.tsx:28-30` 空 `<h1>` 被 `aria-labelledby` 引用 | 未修 | 仍为 `{/* 智能助手 */}`（→ L-4） |
| L8 | `--strato-color-surface-hover` Provider 与 fallback 不一致 | **已修** | `StratoThemeProvider.tsx:58` `'#f0f2f5'` == `UnknownComponent.module.css:9` fallback == chat `global.css:6` |
| L9 | `@ant-design/icons` peer 未被 core 引用 | 未修（按 spec 6 peer，推迟合理） | `grep -rn "@ant-design" packages/core/src` 0 |
| L10 | `summary.md:18` 仍写「8 task」 | 未修 | 仍为 `8 task：T01 / T02a / …`（→ L-5） |
| L11 | T06a 验收 grep 为 1 行 | **已修** | 随 S3 拼接字符串后为 0 行 |
| L12 | `frontend-doctor/SKILL.md:27` `fronted/node_modules/.vite` 陈旧 | 未修 | 实测 Vite 缓存在 `fronted/apps/chat/node_modules/.vite`（存在），`fronted/node_modules/.vite` 不存在（→ L-5） |
| L13 | (e) consumer.ts 不实例化 props | 未修 | `verify-pack.mjs:218-229` 同前（可推迟） |

**闭环统计**：MUST FIX 1/1 已修；SHOULD 6/7 已修、1/7 部分（S1 tasks.md 未同步）；LOW 5/13 已修（L1 / L3 / L8 / L11 + L2-间接无）、8/13 未修——其中 L5 升级为 SHOULD（N2），其余 7 条可接受推迟（见 §4）。

---

## 2. 机械门禁与 spec §6 复现（本机，本轮全部重跑）

| 命令 | 结果 |
|---|---|
| `rm -rf fronted/packages/core/dist fronted/apps/chat/dist && pnpm -C fronted run ci` | **rc=0**；`16 examples OK`；verify-pack **14 ✓**（(a)(b)×6 含新增 peer/catalog (c)(g)×2(d)(e)×2(f)）；dist 40 KB = 基线；结束后 `dist/index.js` 与 `apps/chat/dist/index.html` 已重建，`.verify-pack/` 不存在 |
| `pnpm -C .harness run ci` | **rc=0**（`ci: all steps passed (exit 0)`） |
| `pnpm -C .harness run doctor` | 0 errors, 0 warnings；「doc paths」「scripts: no hard-coded change directory」✓ |
| `pnpm -C .harness run check-contracts` / `check-module-deps` | 9 schemas OK / ✓ |
| §6.1 第 3 条（新写法 `cd fronted && pnpm -r --filter …`） | 恰两行 `@strato-ui/core` / `strato-chat` ✓ |
| §6.1 第 4 条 stale-name grep | 0 行 ✓ |
| §6.2 三条 grep（eval/http、antd 越界、`--color-`/`getComputedStyle`） | 0 / 0 / 0 ✓ |
| §6.3 两条 grep（chat 内 antd、`@strato-ui/core/src`） | 0 / 0 ✓ |
| §6.3 干净检出 | 同第 1 行 ✓ |
| §6.3 `schemaVersion` → `z.literal('9.9')` 反例（/tmp 副本） | `12 examples OK, 4 failed` rc=1；还原后 `16 examples OK` ✓（证明 vite-node 走 src） |
| §6.3 `mv components/mobile/Table.tsx` 反例（/tmp 副本） | `✗ missing implementation mobile/Table.tsx` rc=1；还原 ✓ |
| §6.4 `node .harness/scripts/e2e-frontend.mjs`（8080 后端 + 5173 dev 均由本轮拉起） | **21 passed, 0 failed** rc=0 |
| §6.4 `bash .harness/scripts/deploy-verify.sh` | **12 passed, 0 failed**；12 项名称与 §2.5 一致（health / selfcheck 4 / preview `/` 200 / proxies health 200 / event sequence / confirm→run.completed / run-summary COMPLETED / backend.log has run / user text 0 / console.error 0 / `agent-input=1` / bundle_size written） |
| §6.5 `bash .harness/scripts/e2e-backend.sh` | **47 passed, 0 failed**（== 上轮基线 47，后端零改动） |
| 收尾 | 上述三个脚本会重写 `deployment/{backend.log,run_events.log,confirm_events.log,run_summary_done.json}`，已 `git checkout -- deployment` 还原；`git status --short` 空；8080 / 4173 / 5173 无监听进程；`/tmp/strato-probe` 已删除 |

### 2.1 `peerDependencyRules.allowedVersions` 精确化后的安装验证（重点 2）

在 `/tmp/strato-probe/fronted`（`rsync` 副本，含 `.npmrc` `strict-peer-dependencies=true`）上，用 `pnpm 10.34.5 --offline`：

| 场景 | 命令 | 结果 |
|---|---|---|
| CI 形态：当前三条精确规则 | `rm -rf node_modules …; pnpm install --frozen-lockfile` | **通过**（140 包，lock 未变） |
| 当前规则，非 frozen | `pnpm install` | 「Lockfile is up to date, resolution step is skipped」通过 |
| 强制重解析，当前规则 | `pnpm install --resolution-only` | **0 unmet peer** ✓（规则确实生效） |
| 强制重解析，删除全部规则 | 同上 | `ERR_PNPM_PEER_DEP_ISSUES`：`@react-spring/{web,animated,shared,core}` + `staged-components` 共 **6 处** unmet |
| 只留 `staged-components>react` | 同上 | 5 处 unmet（证明 `@react-spring/*` 通配确有作用） |
| 只留 `@react-spring/*` 两条 | 同上 | 1 处 unmet（`staged-components`，证明第三条必需） |
| 显式列 4 个 `@react-spring/<pkg>>react` 而漏 `shared` | 同上 | 1 处 unmet → 通配写法比逐包枚举更稳，当前写法是对的 |

结论：三条规则是**最小且充分**的集合；`pnpm-lock.yaml` 不记录 `peerDependencyRules`（`settings:` 段只有 `autoInstallPeers` / `excludeLinksFromLockfile`），故 lock 无需重生成，fix commit 未改 lock 正确。附带发现：`pnpm install` 在 lock 最新时**跳过解析**，因此规则错误只会在依赖变更触发重解析时暴露——上轮 L1 的旧写法和新写法在日常 install 中表现一致，本轮用 `--resolution-only` 才区分出来（→ I-3 建议）。

---

## 3. agent-safety §4 两列复核

| 随包走（接入方无法关闭） | 代码位置 | 结论 |
|---|---|---|
| 组件只按注册表白名单查表，`type` 以 string 查找 | `SchemaRenderer.tsx:35-36`；`componentRegistry.ts:21,57` `Object.freeze` | ✅ 本轮起运行期写入抛 TypeError，「无法关闭」表述成立 |
| 每个组件 props 先经 Zod 再渲染，失败渲染占位 | `SchemaRenderer.tsx:43-56`；`schema` 缺失也走占位 | ✅；但 `PROPS_SCHEMAS` 对象本身未冻结（`Object.isFrozen` = false），宿主可 `PROPS_SCHEMAS.Form = z.any()` 削弱此列（→ L-2） |
| 未知 type → `UnknownComponent` + `console.error('[strato-ui] …')` | `SchemaRenderer.tsx:37-42` | ✅ 文案 `unknown component type` 保留，e2e 第 21 项依赖它通过 |
| 无 eval / new Function / dangerouslySetInnerHTML / 任意路径 import | §6.2 grep 0 行；注册表全部字面路径 `import('../components/…')` | ✅ |
| UI Schema 中无 URL / HTML 字段 | 契约无变化（`git diff cac3d5b..HEAD -- .harness/contracts` 仍仅 1 行 description） | ✅ |

| 留在宿主（README 明示） | chat 侧代码 | 结论 |
|---|---|---|
| 整体 `parseUiSchema()` 校验 | `useAgentRun.ts:91` 每帧 `SseEventSchema.safeParse`（组合 core `UiSchemaSchema`） | ✅ 同上轮 |
| 不把模型输出直接当 UI Schema 构造 | 仅 DEV-only `SchemaPlaygroundPage.tsx:25` | ✅ 受控例外 |
| `confirmationToken` 只回传不解析、不落日志 | `useAgentRun.ts:150-161`；deploy-verify「user text in log: 0」✓ | ✅ |
| pageContext 不可信 | `runView.ts:113-129` Zod 校验 URL query | ✅ |
| 不用 `as UiSchema` 绕过校验 | 生产路径 0 处 | ✅；README「不要这样做」反例与实现一致 |

后端边界（§1 / §2 / §3 / §5 / §6）零改动，e2e-backend 47/47 与基线一致。

---

## 4. 新意见

### MUST FIX

无。

### SHOULD

#### N1 · verify-pack 新增 (b) catalog 解析对 `pnpm-workspace.yaml` 格式假设过强，值带引号 / 非 `^` 前缀时**静默跳过**
- **位置**：`fronted/scripts/verify-pack.mjs:141-151`，正则 `/^ {2}'?([@\w./-]+)'?: \^?(\d+)/gm`，随后 `catalog[name] !== undefined && major !== catalog[name]`。
- **问题**（/tmp 实测）：`zod: '^4.5.4'`（带引号）、`zod: ~4.5.4`、`antd: '>=6.0.0'`、4 空格缩进、`catalogs: default:` 命名 catalog 形式 → 全部匹配为空 → `catalog[name] === undefined` → **判为通过**。把 catalog 改为 `zod: '^4.5.4'` 同时 peer 改 `^3` 验证：`✓ (b) peer ranges match catalog major versions` 全绿。反过来它又会匹配任何两空格缩进的 `key: 数字`（`someSetting:\n  minimumReleaseAge: 1440` → `{minimumReleaseAge:"1440"}`），只是碰巧不与 6 个 peer 名冲突。这是 S5 刚加上的门禁，一次格式化（prettier 对 YAML 不会去引号，但人为编辑常带引号）就会让它悄悄失效，与 Hashimoto「门禁必须会红」相悖。
- **建议**：(1) 6 个 peer 在 catalog 中**必须**都能找到，找不到即 ✗（把「未找到 → 跳过」改成「未找到 → 失败」，静默失效变成显式失败）；(2) 值正则放宽为 `['"]?[\^~>=\s]*(\d+)`；(3) 限制只解析 `catalog:` 段（找到 `^catalog:` 行后到下一个顶层键为止），或用 `pnpm` 已内置的 YAML 解析（`node -e "require('pnpm/…')"` 不稳定，不推荐）。补一条反例到 coding_report：catalog 值加引号 + peer 主版本改错 → (b) 必红。
- **分级**：SHOULD

#### N2 · （L5 升级）check-deps 不识别 `export … from` 与动态 `import()`，FSD 分层的唯一守护对 barrel 再导出盲
- **位置**：`fronted/scripts/check-deps.mjs:47-48` `importRegex` 只匹配 `import … from` 与裸 `import '…'`。
- **问题**（/tmp 实测）：在 `apps/chat/src/shared/index.ts` 追加 `export * from '@features/agent-chat';` → `✓ FSD dependency direction OK` rc=0。FSD 的 public API 惯例正是 barrel 文件用 `export … from` 再导出，所以这是**最可能出现的违规形态**而非边缘写法；oxlint 只禁切片内部深路径，不管分层方向，check-deps 是唯一守护。上轮定 LOW 是因为首期已存在；本轮升级理由：本 change 已经动过这个正则（补裸导入），修一行即可，且 monorepo 化后 `shared/` 与 `features/` 边界比首期更常被 barrel 穿透。
- **建议**：正则改为三段式 `^\s*(?:import|export)\s+(?:(type\s+)?[^;'"]*?from\s+['"]([^'"]+)['"]|['"]([^'"]+)['"])` 并单独抓 `import\(\s*['"]([^'"]+)['"]`；`export type { … } from` 同样按 type-only 放行。附反例到 coding_report。
- **分级**：SHOULD（不阻断；可在阶段 5 前顺手修，或作为下一 change 首项）

### LOW

- **L-1 · tasks.md T01 验收命令未随 spec S1 同步**：`request_analysis/tasks.md:14` 仍是 `pnpm -C fronted --filter './packages/**' …`（按字面输出「No projects matched」rc=0）。改为与 spec §6.1 一致的 `cd fronted && pnpm -r --filter …`。
- **L-2 · `PROPS_SCHEMAS` 未随注册表一起冻结**（`registry/types.ts:90-98`；运行期探针 `Object.isFrozen(PROPS_SCHEMAS) === false`）。注册表冻结后宿主无法加 type，但仍可 `PROPS_SCHEMAS.Form = z.any()` 削弱「props 先经 Zod」这一「随包走」保证。一行 `Object.freeze` 即可；check-registry 对 `PROPS_SCHEMAS = \{…\} as const` 的正则届时需要像 S7 那样兼容 `Object.freeze({…} as const)` 写法。
- **L-3 · （上轮 L4 保留）verify-pack (f) 基线缺失时静默重写并通过**：/tmp 实测删 baseline → `✓ (f) … baseline written`。基线文件受 git 跟踪，删除会出现在 diff 里，所以推迟可接受；建议 `CI=1` 或非 TTY 时基线缺失即 ✗。
- **L-4 · （上轮 L6 / L7 保留）** Form 字段 schema 两份、`ActionBarProps` 三份；`ChatPage.tsx:28-30` 空 `<h1>` 被 `aria-labelledby` 引用（coding-standard §7）。均为首期遗留、不在本 change 目标内，可推迟；L7 是真实的可访问性缺陷，建议下一 change 开头 5 分钟处理。
- **L-5 · 三处陈旧文本未修**：`packages/core/src/device/DeviceContext.ts:3`（`app/providers/DeviceProvider` → `StratoDeviceProvider`）；`summary.md:18`（「8 task」→ 12 task）；`.harness/skills/frontend-doctor/SKILL.md:27`（`fronted/node_modules/.vite` → `fronted/apps/chat/node_modules/.vite`，实测前者不存在、后者存在）。doctor 跳过含 `node_modules` 的 token 所以抓不到第三条。
- **L-6 · M1 的 Hashimoto 落点未实现**：doctor「doc paths」仍只取 `` `fronted/…` `` 前缀 token；新措辞中的 `` `theme/**` ``、`` `apps/chat` `` 裸相对路径不被校验，`shared/ui/**` 一类不带前缀的陈旧路径将来也不会被抓。建议 doctor 增加：L1 / agents / rules 中每个反引号 token 若匹配 `^(apps|packages|shared|features|entities|pages|app|theme|components)/`，则在 `fronted/apps/chat/src`、`fronted/packages/core/src` 任一根下必须能定位。
- **L-7 · doctor 脚本扫描扩展名集合固定为 `.sh|.mjs`**（`harness-doctor.mjs:198`）：当前 13 个脚本全部覆盖；若将来加入 `.ts` / `.js` / `.py` 脚本会漏。可改为「排除 `.md` / `.json` 的所有文件」。

### INFO

- **I-1 · (g) 新正则的前缀误报面**：`/(from ['"]|import\(['"])(antd|@ant-design)/` 会命中假想的 `from 'antdesign-tokens'` 一类以 `antd` 开头的第三方包名；当前 d.ts 外部 import 只有 `zod` / `react`，且宁可误报不可漏报，可接受。`import("react").JSX`、注释 `/** wraps antd */`、`from './antd-like'` 均不命中（实测）。
- **I-2 · check-registry 对 `COMPONENT_TYPES` 用单引号匹配 `'([A-Za-z]+)'`**：若改为双引号会被解析为空列表并**报错**（实测 rc=1，非静默），prettier `singleQuote` 也保证不会出现；可接受。
- **I-3 · `pnpm install` 在 lock 最新时跳过解析**，因此 `peerDependencyRules` 的正确性只在依赖变更时才被检验。建议在 spec / coding_report 中把「`pnpm install --resolution-only` 0 unmet」记为涉及 peer 规则改动时的验收命令（本轮用它区分出 6 种规则写法的差异）。
- **I-4 · fix commit 重跑了 deploy-verify 并提交了刷新后的 `deployment/*`**：`bundle_size.txt` 显示 core `dist/index.js` 10158 → 10388 字节（freeze + schema 守护），dist 总量仍 40 KB = 基线；截图 / 日志刷新属正常。本轮重跑后已 `git checkout` 还原，未留下差异。
- **I-5 · `summary.md` 阶段 4 行仍为「1/2 IN PROGRESS」、经验沉淀未追加 M1 的教训**（「spec §2.4 文档清单漏 L1 硬约束一句」是典型的 request-analysis 阶段漏项）。这是阶段流程文件，应在本轮评审后由 Owner 更新，不算编码缺陷；提醒追加一行到经验沉淀。

---

## 5. 结论

- **verdict：APPROVED**（编码评审 2 轮上限已到；无 MUST FIX，可进入阶段 5 代码推送）
- **MUST FIX：0**
- **SHOULD：2**
  - N1 verify-pack (b) catalog 解析在 YAML 值带引号 / 非 `^` 前缀时静默跳过；peer 在 catalog 中找不到应直接 ✗
  - N2 check-deps 漏 `export … from` 与动态 `import()`（上轮 L5 升级；barrel 再导出是 FSD 最常见形态）
- **LOW：7**，**INFO：5**
- **上轮闭环**：MUST FIX 1/1 已修；SHOULD 6 已修 / 1 部分（S1 的 tasks.md）/ 0 未修；LOW 5 已修 / 8 未修（其中 L5 升级为 SHOULD，其余 7 条可接受推迟）
- 建议阶段 5 前顺手处理 N1 / N2 / L-1 / L-2（合计约 20 行，不需再开评审轮次，由 `pnpm -C .harness run ci` + 两条反例背书即可）；其余 LOW 登记到 summary 经验沉淀 / 下一 change 待办。
