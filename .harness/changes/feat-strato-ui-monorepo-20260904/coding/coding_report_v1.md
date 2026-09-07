# Coding Report v1 — feat-strato-ui-monorepo-20260904

日期：2026-09-04 · 基线：T01 前 `bash .harness/scripts/e2e-backend.sh` = **47 passed / 0 failed**（记录于本文件顶部，供 T07 对比）

## 提交（移动与逻辑分开）

| commit | task | 内容 |
|---|---|---|
| `f146764` build(fronted) | T01 | pnpm workspace 骨架：`pnpm-workspace.yaml`（catalog）、`.npmrc`、根 `package.json`、`tsconfig.base.json`、oxlint / prettier 配置移到根 |
| `33da291` refactor(fronted) | T02a | 纯 `git mv`：引擎源码 → `packages/core/src/{renderer,registry,components,device,theme}`；`git diff --stat -M HEAD~1` 全部 `=>` |
| `0f5b426` feat(core) | T02b + T02c | 相对导入、`schema/uiSchema.ts` + `parseUiSchema`、`StratoThemeProvider tokens` + `--strato-*`、`[strato-ui]` 前缀、`index.ts`（17 + 10）、`package.json`（开发期 exports 指 src / publishConfig 覆盖 dist / 6 peer / 无 deps）、vite lib、`tsconfig.build.json`、README |
| `7bad08b` refactor(fronted) | T03a | `git mv src → apps/chat/src`、删 `pages/home`、`pages/agent → pages/chat`（仅文件名）、chat 的 vite / tsconfig / package + `ensure-core-dist.mjs` 守护 |
| `a779661` feat(chat) | T03b + T04 | 接入 core、路由 `/`、`ChatPage`、`tokens.ts`、entities 删 ui-schema 段；oxlint 规则、check-deps（识别副作用裸导入）、check-registry、verify-examples |
| `767c5af` feat(fronted) | T05 | `verify-pack.mjs` + `verify-pack.baseline.json` |
| `a208431` chore(harness) | T06a + T06b + T06c | `lib/change-dir`、脚本去硬编码、preview-console 改页、doctor 四项新检查、14 份文档同步、契约 description 注释 |

## 关键决策与偏差

1. **`peerDependencyRules.allowedVersions`**（spec 未预见）：`strict-peer-dependencies=true` 下 `pnpm install` 因 antd-mobile 5.42 的传递依赖（`@react-spring/*`、`staged-components`）只声明 react ≤18 而失败。放行 `react: '19'`、`react-dom: '19'` 两条（首期已在 React 19 上验证可用）。本仓库自己包的 peer 仍严格。写进 `pnpm-workspace.yaml` 注释。
2. **`tsconfig.node.json` 只含两个 `vite.config.ts`**：`scripts/verify-examples.ts` 依赖 chat 别名与 `toSorted`（ES2023），用 tsc 单独 typecheck 需要一套与运行环境不同的配置；spec 已规定它由 vite-node 运行并以 4 条植入反例证明有效，故不再重复 typecheck。
3. **check-deps 原正则漏掉副作用裸导入**（`import '@features/x'`），T04 植入反例 ③ 首次未红。修为同时匹配 `import ... from '...'` 与 `import '...'`，并限制 `[^;'"]+?` 防止懒匹配跨语句。这是 Hashimoto：门禁脚本用负例证明会红时发现的真 bug。
4. **`@contracts` 别名深度**：`apps/chat/vite.config.ts` 初写 `../../.harness` 少一级，`vite build` 报 UNLOADABLE_DEPENDENCY；改 `../../../.harness`（tsconfig 同）。
5. **verify-pack 用 `check(cond, ok, fail)`** 而非三元语句（oxlint `no-unused-expressions`）；模板字串在 `check()` 参数里会被立即求值，`pkg.dependencies` 为 undefined 时 `Object.keys` 抛错 → 加 `?? {}`。

## 验收（真实输出）

```
pnpm -C fronted --filter @strato-ui/core build         exit 0；dist/index.js 10.16 kB，17 chunks，style.css 0.69 kB；dist 目录 180 KB（含 d.ts）
grep -c 'from "antd"' dist/index.js                     1      ；cssinjs 特征 0；.d.ts 无 antd import
pnpm pack → exports["."].import                          ./dist/index.js（publishConfig 覆盖生效）；tarball 42 dist + package.json + README.md
pnpm -C fronted run verify-examples                      16 examples OK
pnpm -C fronted run verify-pack                          13 checks passed；packed dist 40 KB → baseline
rm -rf */dist && pnpm -C fronted run ci                  exit 0（build:core → typecheck → lint → format → verify-examples → build:chat → verify-pack）
pnpm -C .harness run doctor                              0 errors（含新增：workspace 文件、无 fronted/src、文档路径存在、脚本无硬编码 change）
bash .harness/scripts/e2e-backend.sh                     47 passed / 0 failed（== 基线，后端零改动）
node .harness/scripts/e2e-frontend.mjs                   21 passed / 0 failed（步骤 5 URL 为 /?page=…）
bash .harness/scripts/deploy-verify.sh                   12 passed / 0 failed（含 agent-input=1）
rm -rf */dist && pnpm -C .harness run ci                 check-contracts 0 / check-module-deps 0 / fronted 0 / backed 0
```

### 植入反例（全部先红后绿并还原，`git status` 无残留）
| 门禁 | 植入 | 结果 |
|---|---|---|
| oxlint | `AgentChatPanel.tsx` 加 `import { Button as AntButton } from 'antd'` | 1 条 no-restricted-imports |
| check-registry | 移走 `components/mobile/Table.tsx` | 1 errors |
| check-deps | `shared/api/httpClient.ts` 加 `import '@features/agent-chat'` | 首次未红（正则漏裸导入）→ 修后 1 violation |
| verify-examples | core `schemaVersion` 改 `'9.9'` | exit 1（证明 vite-node 走 core src） |
| verify-pack (a) | core `files` 加 `src` | ✗ 29 个 src 条目 |
| verify-pack (c) | 删 `parseUiSchema` 导出 | ✗ missing=parseUiSchema |
| verify-pack (e) | 删 `StratoThemeTokens` 类型导出 | ✗ 两种 EOPT 都 TS2305 |
| verify-pack (g) | 公共类型 re-export antd `ButtonProps` | ✗ leaked: dist/registry/types.d.ts（且 (e) 因 antd d.ts 不干净连带红，印证 spec 的设计约束） |
| doctor 文档路径 | 植入 `fronted/nope/x.ts` + glob + shell 命令 | 只有前者报错 |
| change-dir | `STRATO_CHANGE=nonexistent` | exit 2 |

### 体积（`deployment/bundle_size.txt`）
- chat 最大 chunk `index-*.js` 251,987 B（首期 254,794 B，-1.1%）；Table / useSize / Input 等 antd chunk 与首期一致。
- core dist：`index.js` 10,158 B + 14 个组件 chunk ≤ 1,027 B。
- `app.jar` 36,026,813 B（后端未改，字节差异来自构建时间戳）。

## agent-safety §4 自查（可发布包语境）
- 随包走：注册表查表（`type` 按 string）、props Zod、`UnknownComponent` + `console.error('[strato-ui] …')`、无 eval / innerHTML / 动态路径（grep 0）、CSS 只 `--strato-*` + fallback、d.ts 不泄露 antd 类型（verify-pack (g)）。
- 留在宿主：chat 的 `useAgentRun.apply` 仍对每帧做 `SseEventSchema.safeParse`（内含 core 的 `UiSchemaSchema`）；`confirmationToken` 只回传；README 两列清单 + 三条反例。
- e2e 步骤 4（unknown 组件占位）与步骤 2/3（端型切换、antd / adm 互斥）在 dev 模式全部通过，说明引擎搬进包后行为不变。

## 与 spec 的偏差
- `peerDependencyRules`（决策 1）：spec §2.1 未提；建议评审后回写。
- `tsconfig.node.json` 不含 `scripts/*.ts`（决策 2）：spec T01 未细化 typecheck 范围；verify-examples 由运行 + 反例证明，无静态 typecheck。
