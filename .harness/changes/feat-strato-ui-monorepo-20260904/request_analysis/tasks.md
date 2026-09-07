# Tasks: feat-strato-ui-monorepo-20260904

> v3.1 — 响应 `review/spec_review_v3.md`：T01 根 devDependencies 加 `@strato-ui/core: workspace:*` 与 `.verify-pack` 忽略；T05 临时目录改 `packages/core/.verify-pack/` 并收尾清理；T06b 加 `dev-workflow.md`。
> v3 — 响应 `review/spec_review_v2.md`：T02a 严格只 rename，`vite-env.d.ts` 复制移到 T02b 首个 commit（N6）；Phase B / C 内部的中间 commit 允许 CI 红，T04 完成后必须全绿；T02c 按 spec v3 的 tsconfig.build / alias / predev 守护；T05 按 (d)(e)(g) 与 cwd=fronted；T06a 拆出 T06c（doctor 两项新检查，LOW-6）。
> v2 — 响应 `review/spec_review_v1.md`：T03 拆 T03a / T03b，T06 拆 T06a / T06b（S5）；T02a 列出 commit 切分；T05 改为 es-module-lexer + vite-node + consumer.ts（M2 / S8）；T06a 增 `lib/change-dir`、`preview-console.mjs`、doctor 两项新检查（M3 / M4 / S7）；filter 统一 `--filter`（L6）；「输入」列具体路径（I5）。11 task，全部 fronted / harness，每个 ≤ 0.5 天。移动与逻辑分开提交。

## Phase A — 骨架

### T01 pnpm workspace 骨架
- **目标**：`fronted/` 变为 workspace 根：`pnpm-workspace.yaml`（`packages: ['packages/*','apps/*']`；`catalog:` 列 react / react-dom / antd / antd-mobile / @ant-design/icons / zod 的现版本）；`.npmrc`（`auto-install-peers=true`、`strict-peer-dependencies=true`）；根 `package.json`（`private`；脚本：`build:core`、`build:chat`、`dev`（dist/style.css 不存在则先 build:core）、`typecheck`、`lint`、`format`、`format:check`、`verify-examples`、`verify-pack`、`ci` = `build:core && typecheck && lint && format:check && verify-examples && build:chat && verify-pack`）；`tsconfig.base.json`（现 `tsconfig.app.json` compilerOptions 去掉 paths / types）；`.oxlintrc.json` / `.prettierrc.json` / `.prettierignore`（加 `**/dist`、`**/.verify-pack`）移到根，oxlint `ignorePatterns: ["**/dist/**","**/node_modules/**","**/.verify-pack/**"]`；`fronted/.gitignore` 加 `.verify-pack/`；根 devDependencies 含工具链与 `"@strato-ui/core": "workspace:*"`（scripts/ 裸 import 所需）；两个子包目录各放最小 `package.json`（name 正确即可）。
- **所属端**：fronted
- **输入**：`fronted/package.json`、`fronted/tsconfig.app.json`、`fronted/.oxlintrc.json`、`fronted/.prettierrc.json`、spec §2.1
- **输出**：`fronted/{pnpm-workspace.yaml,.npmrc,package.json,tsconfig.base.json,.oxlintrc.json,.prettierrc.json,.prettierignore}`、`fronted/packages/core/package.json`、`fronted/apps/chat/package.json`
- **验收**：`pnpm -C fronted install` 退出码 0；`test -e fronted/node_modules/@strato-ui/core`；`cd fronted && pnpm -r --filter './packages/**' --filter './apps/**' exec node -p "require('./package.json').name"` 恰两行（相对 filter 按 cwd 解析）；`pnpm -C .harness run doctor` 根目录检查仍 ✓
- **依赖**：—

## Phase B — 核心包

### T02a `@strato-ui/core`：纯移动
- **目标**：只做 `git mv`，零逻辑改动：`shared/ui/generate/{SchemaRenderer,UnknownComponent,ActionBar}.tsx + *.module.css` → `packages/core/src/renderer/`；`componentRegistry.ts`、`types.ts` → `registry/`；`desktop/`、`mobile/` → `components/`；`shared/ui/device/DeviceContext.ts` → `device/`；`app/providers/DeviceProvider.tsx` → `device/StratoDeviceProvider.tsx`；`shared/ui/theme/AppThemeProvider.tsx` → `theme/StratoThemeProvider.tsx`。**一个 commit**：`refactor(fronted): 移动 Strato UI 引擎源码到 packages/core`。此 commit 的 typecheck / lint 允许红（别名尚未替换）。
- **所属端**：fronted
- **输入**：T01、`fronted/src/shared/ui/{generate,device,theme}/**`、`fronted/src/app/providers/DeviceProvider.tsx`、`fronted/src/vite-env.d.ts`
- **输出**：`fronted/packages/core/src/{renderer,registry,components,device,theme}/**`
- **验收**：`git diff --stat -M HEAD~1` 每一行均含 `=>`（纯 rename，无新增 / 修改）；`ls fronted/packages/core/src/components/desktop | wc -l` 与 `mobile` 均为 8
- **依赖**：T01

### T02b `@strato-ui/core`：逻辑改造与公共入口
- **目标**：新增 `packages/core/src/vite-env.d.ts`（复制自 chat）；相对导入替换全部别名；从 `entities/agent-run/model/types.ts` 抽 ui-schema 投影到 `schema/uiSchema.ts`（含 `parseUiSchema`），chat 侧暂留原文件（T03b 再切）；日志前缀 `[strato-ui]`（保留 `unknown component type` 文案）；`StratoThemeProvider` 改为 `tokens?: StratoThemeTokens`（7 键可选，默认值 = 现值），删除 `getComputedStyle`，在包裹 div 上写 `--adm-*`（6 项）与 `--strato-*`（5 项）；两份 `.module.css` 改用 `var(--strato-*, fallback)`；`index.ts` 导出 spec §2.2 清单（17 运行时 + 10 类型）。commit：`refactor(core): 相对导入 + 抽出 uiSchema.ts`、`feat(core): StratoThemeProvider tokens / --strato-* 变量 / [strato-ui] 前缀`。
- **所属端**：fronted
- **输入**：T02a、`fronted/src/entities/agent-run/model/types.ts`（ui-schema 段）、spec §2.2 公共 API / tokens / CSS 约束
- **输出**：`fronted/packages/core/src/index.ts`、`schema/uiSchema.ts`，以及改动后的 renderer / theme / css
- **验收**：`grep -rn "@shared\|@entities\|@app\|@pages\|@features" fronted/packages/core/src` 0 行；`grep -rn "\[schema-renderer\]\|AppThemeProvider\|getComputedStyle\|var(--color-\|var(--radius-" fronted/packages/core/src` 0 行；`grep -c "^export" fronted/packages/core/src/index.ts` ≥ 2（运行时与类型各一组）
- **依赖**：T02a

### T02c `@strato-ui/core`：构建与包元数据
- **目标**：`package.json`（spec §2.2 全部字段：开发期 `exports`/`types` 指 src、`publishConfig` 覆盖为 dist、6 peer、无 dependencies、devDependencies `catalog:`、`sideEffects: ["./dist/style.css"]`、`files`、`scripts.build`、`prepublishOnly`）；`vite.config.ts` lib mode（`lib.fileName: 'index'`、`cssFileName: 'style'`、external 正则覆盖 6 peer 与 `antd/*`、`antd-mobile/*`、`react/jsx-runtime`、`chunkFileNames: 'chunks/[name]-[hash].js'`）；`tsconfig.json`（extends base，`include: src`）与 `tsconfig.build.json`（spec v3 §2.2 原文，含 `rootDir: src`、`types: []`，**不** exclude vite-env.d.ts）；`README.md`（安装 / peer 表 / §4.1 示例 / 公共 API 表 / 安全边界两列 / 「不要这样做」三条）。
- **所属端**：fronted
- **输入**：T02b、spec §2.2、§4.1、§2.4 README 要求
- **输出**：`fronted/packages/core/{package.json,vite.config.ts,tsconfig.json,tsconfig.build.json,README.md}`
- **验收**：`pnpm -C fronted --filter @strato-ui/core build` 退出码 0；`ls dist/index.js dist/index.d.ts dist/style.css` 存在；`grep -c 'from "antd"' dist/index.js` ≥ 1；`grep -rc "ant-design/cssinjs" dist | grep -v ":0$" | wc -l` 为 0；`du -sk dist` 首测值记入 coding_report；`cd fronted/packages/core && pnpm pack --pack-destination /tmp/sp && tar -xzf /tmp/sp/*.tgz -C /tmp/sp && node -p "require('/tmp/sp/package/package.json').exports['.'].import"` 输出 `./dist/index.js`（证明 publishConfig 覆盖生效）
- **依赖**：T02b

## Phase C — chat 应用

### T03a `apps/chat`：纯移动与配置骨架
- **目标**：`git mv fronted/src fronted/apps/chat/src`（core 已移走的部分除外）、`git mv fronted/index.html fronted/apps/chat/`；`git rm -r fronted/src/pages/home`；`git mv pages/agent pages/chat`、`AgentPage.tsx → ChatPage.tsx`（仅文件名，内容下一 task 改）；新建 `apps/chat/{vite.config.ts（aliases、proxy、fs.allow、build 时 alias 用正则 `/^@strato-ui\/core$/` → ../../packages/core/dist/index.js）,tsconfig.json（extends ../../tsconfig.base.json + paths + types vite/client）,package.json（spec §2.3 依赖，catalog:；`predev` / `prebuild` 守护 core dist 存在）}`；删除 `fronted/{vite.config.ts,tsconfig.app.json,tsconfig.node.json,tsconfig.json}`。commit：`refactor(fronted): 移动 chat 应用到 apps/chat`。
- **所属端**：fronted
- **输入**：T02c、`fronted/src/**`（剩余部分）、`fronted/index.html`、`fronted/vite.config.ts`
- **输出**：`fronted/apps/chat/{index.html,vite.config.ts,tsconfig.json,package.json,src/**}`；`fronted/src` 不存在
- **验收**：`test ! -d fronted/src`；`ls fronted/apps/chat/src/pages` 恰为 `chat not-found schema-playground`；`test ! -e fronted/vite.config.ts`
- **依赖**：T02c

### T03b `apps/chat`：接入 core 与路由
- **目标**：`ChatPage` 改名（组件名、路由 `/`、`index.ts`）；`app/providers` 用 `StratoDeviceProvider` / `StratoThemeProvider tokens={STRATO_TOKENS}`（`app/styles/tokens.ts` 常量，与 `global.css` 同值）；`global.css` import `@strato-ui/core/style.css`；`features` / `entities` / `pages/schema-playground` 改为从 `@strato-ui/core` 导入；`entities/agent-run/model/types.ts` 删除 ui-schema 段、改 import core 的 `UiSchemaSchema`；`AgentChatPanel` 的 `COMPONENTS` 改 `COMPONENT_TYPES`；`shared/ui/index.ts` 只剩 `Button`；`HomePage` 链接迁移到 `RootLayout` 品牌位或删除。commit：`feat(chat): 接入 @strato-ui/core，路由收敛为 /`。
- **所属端**：fronted
- **输入**：T03a、spec §2.3
- **输出**：`fronted/apps/chat/src/**` 改动
- **验收**：`pnpm -C fronted --filter strato-chat exec tsc -p tsconfig.json --noEmit` 0；`pnpm -C fronted --filter strato-chat build` 0；`grep -rln "from 'antd\|from \"antd\|@ant-design" fronted/apps/chat/src` 0 行；`grep -rn "@strato-ui/core/src" fronted/apps/chat/src` 0 行；`grep -rn "UiSchemaSchema = z" fronted/apps/chat/src` 0 行（真源已迁 core）
- **依赖**：T03a

## Phase D — 治理脚本

### T04 workspace 级 lint / 结构脚本
- **目标**：`.oxlintrc.json`：antd / antd-mobile / @ant-design 全局禁止，override 仅放开 `packages/core/src/components/**` 与 `packages/core/src/theme/**`；`@contracts/*` 仅 `apps/chat/src/pages/**` 与 `scripts/**`；新增禁止 `@strato-ui/core/src/*`；`packages/core/src/**` 放开 `../../*`。`scripts/check-deps.mjs` 扫 `apps/chat/src`；`scripts/check-registry.mjs` 路径改 `packages/core/src/registry/{componentRegistry,types}.ts` 与 `components/{desktop,mobile}`；`scripts/verify-examples.ts` 从 `@strato-ui/core` 取 `UiSchemaSchema`、其余从 `@entities/agent-run`，以 `vite-node -c apps/chat/vite.config.ts` 运行。
- **所属端**：fronted
- **输入**：T03b、`fronted/.oxlintrc.json`、`fronted/scripts/{check-deps.mjs,check-registry.mjs,verify-examples.ts}`、spec §2.5
- **输出**：同上四个文件
- **验收**：`pnpm -C fronted run lint` 0；`pnpm -C fronted run verify-examples` 输出 `16 examples OK`；四条植入违规各证明会红并恢复：① `AgentChatPanel.tsx` 加 `import { Button } from 'antd'` → oxlint 红；② `git mv` 临时移走 `components/mobile/Table.tsx` → check-registry 红；③ `shared/api/httpClient.ts` 加 `import '@features/agent-chat'` → check-deps 红；④ core `uiSchema.ts` 把 `schemaVersion` 改 `z.literal('9.9')` → verify-examples 红（证明 vite-node 走 src）。命令与输出记入 coding_report
- **依赖**：T03b

### T05 发包就绪校验 `verify-pack.mjs`
- **目标**：`pnpm --filter @strato-ui/core pack --pack-destination <tmp>` → 解包 → 断言 spec §6.2 (a)–(f)：文件清单；`package.json`（exports 指 dist、types 指 dist、6 peer、无 dependencies、files、peer range 与 `pnpm-workspace.yaml` catalog 主版本一致）；`es-module-lexer` 解析 `dist/index.js` 导出名 == 17 项清单；解包到 `fronted/packages/core/.verify-pack/pkg/`，`vite-node --root <fronted/packages/core>` 加载 `pkg/dist/index.js` 成功；在同目录生成 `consumer.ts` + 临时 tsconfig（spec §6.2 (e) 原文，`paths` 指向解包 d.ts）分别以 EOPT 开 / 关跑 `tsc --noEmit` 两次 0；解包 dist 内 `.d.ts` 不含 antd / antd-mobile / @ant-design import（(g)）；脚本所有子进程 `cwd = fronted/packages/core`；`finally` 删除 `.verify-pack/`；dist 体积 ≤ `scripts/verify-pack.baseline.json` 记录值 × 1.1（首次运行写入基线）。`es-module-lexer` 加到 workspace 根 devDependencies。
- **所属端**：fronted
- **输入**：T02c、spec §2.2 公共 API 清单、§6.2 (a)–(f)
- **输出**：`fronted/scripts/verify-pack.mjs`、`fronted/scripts/verify-pack.baseline.json`；根 `package.json` 的 `verify-pack` 脚本纳入 `ci`
- **验收**：`pnpm -C fronted run verify-pack` 0 且打印 17 个导出名；三条植入违规各证明会红并恢复：① core `package.json` 的 `files` 加 `src` → (a) 红；② `index.ts` 删掉 `export { parseUiSchema }` → (c) 红；③ `index.ts` 删掉 `export type { StratoThemeTokens }` → (e) 红；④ 在 core 某公共类型上 `import type { ButtonProps } from 'antd'` 并导出 → (g) 红
- **依赖**：T02c

## Phase E — Harness 与文档

### T06a Harness 脚本
- **目标**：新增 `.harness/scripts/lib/change-dir.mjs` 与 `change-dir.sh`（`STRATO_CHANGE` 或唯一非 DONE change，否则退出码 2 列候选）；`e2e-frontend.mjs` / `e2e-backend.sh` / `deploy-verify.sh` 改经 lib 取 `deployment/`；`e2e-frontend.mjs` 步骤 5 URL 改 `/?page=…`；`deploy-verify.sh` preview cwd → `fronted/apps/chat`、断言改为 spec §2.5 的 12 项、bundle 统计两处 dist；`preview-console.mjs` 页面改 `/?page=…`（输出 `agent-input=1|0`，截图 `preview-chat.png`）与 `/does-not-exist`（`preview-notfound.png`）；`harness-doctor.mjs` 必需文件 +6、`fronted/src` 存在即 err；`.harness/package.json` 无新脚本。（doctor 两项新检查见 T06c）
- **所属端**：harness
- **输入**：`.harness/scripts/{e2e-frontend.mjs,e2e-backend.sh,deploy-verify.sh,preview-console.mjs,harness-doctor.mjs}`、spec §2.5
- **输出**：上述 5 个脚本改动 + `.harness/scripts/lib/{change-dir.mjs,change-dir.sh}`
- **验收**：`grep -rn "changes/feat-" .harness/scripts` 0 行；`STRATO_CHANGE=nonexistent node .harness/scripts/lib/change-dir.mjs` 退出码 2；`pnpm -C .harness run doctor` 0 errors
- **依赖**：T03b、T04、T05

### T06c doctor 新增机械检查
- **目标**：`harness-doctor.mjs` 增加两项：①「文档路径存在」（spec §2.5 规则原文：反引号内以 `fronted/` 开头且无空格的 token；通配符前目录前缀 `existsSync`；跳过 node_modules / dist；范围 rules / skills / wiki / agents / CLAUDE.md / AGENTS.md）；②「`.harness/scripts/**/*.{sh,mjs}` 不得含 `changes/feat-`」。
- **所属端**：harness
- **输入**：T06a、`.harness/scripts/harness-doctor.mjs`、spec §2.5
- **输出**：`.harness/scripts/harness-doctor.mjs`
- **验收**：doctor 0 errors；正向：植入 `.harness/rules/project-structure.md` 一条 `fronted/nope/x.ts` → 红；反向：植入 `fronted/apps/chat/src/pages/**` 与 `ls fronted/apps/chat/src` → 不报；恢复后绿
- **依赖**：T06a

### T06b 文档同步
- **目标**：按 spec §2.4 清单逐文件改：`CLAUDE.md`、`AGENTS.md`、`.harness/agents/platform-owner.md`、`.harness/rules/{project-structure,coding-standard,contracts}.md`、`.harness/contracts/ui-schema.schema.json`（仅第 39 行 description）、`.harness/skills/coding-skill/specs/05-styling-spec.md`、`.harness/skills/{code-review,project-analysis,deploy-verify}/SKILL.md`、`.harness/rules/dev-workflow.md`（阶段 7 一句）、`.harness/wiki/architecture.md`、`fronted/README.md`；本 change `summary.md` 契约变更段写「1 处 description 注释路径，无字段变更」。
- **所属端**：harness
- **输入**：spec §2.4 清单、T02c / T03b / T06a / T06c 实际产出路径
- **输出**：上述 15 个文件
- **验收**：spec §6.1 第 4 条 grep 0 行；`pnpm -C .harness run check-contracts` 仍 9 schema / 20 example ✓；`pnpm -C .harness run doctor` 0 errors（含新的路径存在检查）
- **依赖**：T06c

### T07 全链路验收
- **目标**：T01 开始前先记录主干 `e2e-backend.sh` 通过数作为基线；完成后在后端 jar + `pnpm -C fronted dev` 下跑 `e2e-frontend.mjs`；`deploy-verify.sh`；`e2e-backend.sh`；干净检出模拟 `rm -rf fronted/packages/core/dist && pnpm -C fronted run ci`；全仓 `run ci`。产物写入本 change `deployment/`（经 `lib/change-dir`）。
- **所属端**：harness
- **输入**：T06b
- **输出**：`.harness/changes/feat-strato-ui-monorepo-20260904/deployment/**`、`coding/coding_report_v1.md`
- **验收**：spec §6.3 第 3 条、§6.4、§6.5 全部为真，真实退出码与输出记入 coding_report
- **依赖**：T06b

## 依赖图

```
T01 → T02a → T02b → T02c → T03a → T03b → T04 → T06a → T06c → T06b → T07
                          └──────→ T05 ─────────┘
```
