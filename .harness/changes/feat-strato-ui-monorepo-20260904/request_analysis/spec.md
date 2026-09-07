# Spec: feat-strato-ui-monorepo-20260904

> v1 — 阶段 1 产出。用户已确认：包名 `@strato-ui/core`；核心包只含渲染引擎；antd / antd-mobile 作 peerDependencies；`/dev/schema` 渲染宿主保留在 chat 应用（仅 DEV）。
> v3.1 — 响应 `review/spec_review_v3.md`（N7 根 package.json 显式 `@strato-ui/core: workspace:*`；N8 verify-pack 临时目录改 `packages/core/.verify-pack/`；N9 §6.1 grep 排除 `.harness/scripts/harness-doctor.mjs`；N10 `.verify-pack` 加 ignore 且脚本收尾清理；N11 `dev-workflow.md` 进 §2.4 清单）。第 3 轮后进入 HITL。
> v3 — 响应 `review/spec_review_v2.md`（N1 alias 正则精确匹配；N2 tsconfig.build 去掉 exclude；N3 d.ts 不得引用 antd 类型 + (e) 断言细化；N4 verify-pack 以 fronted 为 Vite root；N5 doctor 路径检查规则写死；N6 见 tasks；LOW-1~6）。
> v2 — 响应 `review/spec_review_v1.md`（REVISION REQUIRED，M1–M4 / S1–S10）。决策：M1 采用「core 开发期 `exports` 指 src + pnpm `publishConfig` 覆盖为 dist」；zod 改 peer；`ui-schema.schema.json` 仅改 description 注释；tokens 键 = 现 7 键，`locale` 进非目标。所有验收命令均已在本仓库工具链上核对可执行。

## 1. 背景

首期（`feat-agent-tool-platform-20260903`）把 Generate UI 渲染引擎实现在 `fronted/src/shared/ui/generate/`，与 chat 业务代码同一个 Vite 应用。下一步业务方希望：

- 渲染引擎作为**独立 npm 包**发布，供其它前端接入（只要把 UI Schema 交给它就能渲染白名单组件）。
- 本仓库的前端收敛为 **一个 chat 页面**，只 import 这个核心包。
- "Generate UI" 这一命名在文件、代码、文档中统一改为 **Strato UI**。

目标用户：接入方前端工程师（npm 包消费者）与本仓库维护者。业务价值：引擎与业务解耦、可独立版本化、接入成本从"复制目录"降到"安装依赖"。

## 2. 范围（In Scope）

### 2.1 前端目录：pnpm workspace monorepo（仍在 `fronted/` 内）

```
fronted/
├── package.json              # workspace 根（private），脚本对子包 fan-out；devDependencies 只放工具链：typescript / oxlint / prettier / vite-node / es-module-lexer，**以及 `"@strato-ui/core": "workspace:*"`**（pnpm 只把 workspace 包 hoist 到隐藏的 `.pnpm/node_modules`，scripts/ 要裸 import 必须显式声明）；无业务代码
├── pnpm-workspace.yaml       # packages: ['packages/*', 'apps/*']；catalog 统一 react / antd / antd-mobile / @ant-design/icons / zod 版本
├── pnpm-lock.yaml
├── .npmrc                    # auto-install-peers=true、strict-peer-dependencies=true
├── tsconfig.base.json        # 共享编译选项（strict / exactOptionalPropertyTypes / erasableSyntaxOnly …）
├── .oxlintrc.json / .prettierrc.json / .prettierignore   # 三者均忽略 **/dist/** 与 **/.verify-pack/**；.gitignore 同
├── scripts/                  # 治理脚本：check-deps / check-registry / verify-examples / verify-pack
├── packages/
│   └── core/                 # @strato-ui/core —— 渲染引擎（可发包）
└── apps/
    └── chat/                 # strato-chat —— 唯一应用，只 import @strato-ui/core
```

仓库根仍**不得**出现 package.json / pnpm-lock / node_modules / tsconfig（doctor 现有守护不变）。`pnpm -C fronted run ci` 仍是 Harness `ci.mjs` 的前端入口。

**根 `ci` 顺序（M1）**：`build:core → typecheck → lint → format:check → verify-examples → build:chat → verify-pack`。typecheck / lint / vite-node 解析 `@strato-ui/core` 到 core **src**（见 §2.2 解析策略），不依赖 dist；`build:chat` 与 `verify-pack` 消费 dist，所以 `build:core` 排最前。

**统一 filter 写法**：全文用 `pnpm -C fronted --filter <name> <cmd>`（不用 `-F`）。

### 2.2 `packages/core`（`@strato-ui/core`）

**包内容**（全部来自现有 `shared/ui/generate/**`、`shared/ui/device/**`、`shared/ui/theme/**` 以及 `entities/agent-run/model/types.ts` 中 ui-schema 的 Zod 投影）：

```
packages/core/src/
├── index.ts                  # 唯一公共入口（见下"公共 API"）
├── vite-env.d.ts             # declare module '*.module.css'（不进 dist）
├── schema/uiSchema.ts        # ui-schema.schema.json 的 Zod 投影：UiSchemaSchema / UiComponentSchema / UiActionSchema / FormPropsSchema / COMPONENT_TYPES / parseUiSchema
├── registry/componentRegistry.ts   # desktopRegistry / mobileRegistry / REGISTRY_KEYS
├── registry/types.ts         # PROPS_SCHEMAS（7 组件 props Zod）、RenderedComponentProps、FormValues、FormComponentHandlers
├── renderer/SchemaRenderer.tsx
├── renderer/UnknownComponent.tsx
├── renderer/ActionBar.tsx
├── components/desktop/{ActionBar,Card,Table,ResultCard,ConfirmationCard,OrderCard,RefundConfirmCard,Form}.tsx   # antd
├── components/mobile/{同上 8 个}.tsx                                                                          # antd-mobile
├── device/DeviceContext.ts   # DeviceKind / MOBILE_MAX_WIDTH / useDevice
├── device/StratoDeviceProvider.tsx   # 由宿主挂载一次，按视口决定端型（原 app/providers/DeviceProvider）
└── theme/StratoThemeProvider.tsx     # 原 AppThemeProvider：antd ConfigProvider token + antd-mobile CSS 变量 + --strato-* 变量；色值只来自 props.tokens
```

**公共 API（`index.ts` 导出，固定清单，作为 verify-pack 断言）**

运行时导出（17）：`SchemaRenderer`、`ActionBar`、`UnknownComponent`、`desktopRegistry`、`mobileRegistry`、`REGISTRY_KEYS`、`PROPS_SCHEMAS`、`UiSchemaSchema`、`UiComponentSchema`、`UiActionSchema`、`FormPropsSchema`、`COMPONENT_TYPES`、`parseUiSchema`、`StratoThemeProvider`、`StratoDeviceProvider`、`useDevice`、`MOBILE_MAX_WIDTH`。
类型导出（10）：`UiSchema`、`UiComponent`、`UiAction`、`ComponentType`、`FormValues`、`DeviceKind`、`SchemaRendererProps`、`ActionBarProps`、`StratoThemeTokens`、`StratoThemeProviderProps`。

`parseUiSchema(input: unknown): UiSchema` = `UiSchemaSchema.parse` 的命名别名，供 README 一行接入（S10）。

**`StratoThemeTokens`（S4）**：键 = 现 `AppThemeProvider` 实际使用的 7 个：`colorPrimary`、`colorText`、`colorTextSecondary`、`colorBorder`、`colorBgLayout`、`colorBgContainer`、`borderRadius`，全部可选，默认值 = 现 `global.css` 的值。`--adm-*` 映射表（6 项）原样迁移。Provider 同时在包裹 `div` 上写 `--strato-color-text`、`--strato-color-text-muted`、`--strato-color-border`、`--strato-color-surface-hover`、`--strato-radius-md`，供包内 CSS Modules 使用。

**包内 CSS 约束（S3）**：`packages/core/src/**/*.module.css` 只允许引用 `--strato-*` 变量且必须带 fallback（`var(--strato-color-border, #dee0e3)`）；禁止 `--color-*` / `--radius-*`（那是 chat 的 global.css）。core 内**不得**出现 `getComputedStyle`（L4）。

**package.json 关键字段（M1 / S2 / S9）**：
- `name: "@strato-ui/core"`，`version: "0.1.0"`，`type: "module"`，`sideEffects: ["./dist/style.css"]`
- **开发期**（workspace 内 tsc / oxlint / vite serve / vite-node 都读这里）：`"types": "./src/index.ts"`，`"exports": { ".": { "types": "./src/index.ts", "import": "./src/index.ts" }, "./style.css": "./dist/style.css" }`
- **发布期**（pnpm pack / publish 时覆盖）：`"publishConfig": { "access": "public", "types": "./dist/index.d.ts", "exports": { ".": { "types": "./dist/index.d.ts", "import": "./dist/index.js" }, "./style.css": "./dist/style.css" } }`
- `files: ["dist", "README.md"]`
- `peerDependencies`（6）：`react ^19`、`react-dom ^19`、`antd ^6`、`antd-mobile ^5`、`@ant-design/icons ^6`、`zod ^4`；**无 `dependencies`**。
- `devDependencies`：上述 6 个 peer 的具体版本（`catalog:`）+ `@types/react`、`@types/react-dom`、`vite`、`@vitejs/plugin-react`、`typescript`。
- `scripts.build = "vite build && tsc -p tsconfig.build.json"`；`scripts.prepublishOnly = "node ../../scripts/verify-pack.mjs"`（挡误发）。

**解析策略（M1）**：workspace 内一切对 `@strato-ui/core` 的解析（tsc、oxlint、vite serve、vite-node）经 `exports` 走 **src**；chat 的 `vite.config.ts` 在 `command === 'build'` 时 alias **必须用正则精确匹配**：`resolve.alias: [{ find: /^@strato-ui\/core$/, replacement: <abs>/packages/core/dist/index.js }]`（对象字符串键会前缀匹配，把 `@strato-ui/core/style.css` 改写成 `dist/index.js/style.css` → UNLOADABLE_DEPENDENCY），保证生产构建吃可发布产物；`@strato-ui/core/style.css` 子路径始终经 `exports` 指 dist。dist 缺失守护放在 `apps/chat/package.json` 的 `predev` / `prebuild`（检查 `../../packages/core/dist/style.css` 存在，否则退出并提示先 `pnpm -C fronted build:core`），而不只在根脚本，避免 `--filter strato-chat dev` 绕过。core 源码的 `*.module.css` 类型由 chat `tsconfig` 的 `types: ["vite/client"]` 提供（typecheck 经 exports 走 core src 时的隐式耦合，特此说明）。

**构建（S1 / I2）**：Vite 8 lib mode：`build.lib = { entry: src/index.ts, formats: ['es'], fileName: 'index', cssFileName: 'style' }`；`rollupOptions.external` = 6 个 peer + `react/jsx-runtime` + `antd/*` + `antd-mobile/*` 深路径（正则）；`output.chunkFileNames: 'chunks/[name]-[hash].js'`；`React.lazy` 保留分 chunk。声明文件：`tsconfig.build.json = { extends: ./tsconfig.json, compilerOptions: { noEmit: false, declaration: true, emitDeclarationOnly: true, rootDir: src, outDir: dist, types: [] }, include: [src] }`（**不要** exclude `vite-env.d.ts`：它提供 `*.module.css` 的 `declare module`，排除后 tsc 报 TS2307 且 d.ts 不产出；`.d.ts` 输入本来不会被 emit）。**公共类型不得暴露 antd / antd-mobile 类型**：dist 下任何 `.d.ts` 不得 import `antd` / `antd-mobile` / `@ant-design`（这两个库自身 d.ts 在 `skipLibCheck:false` 下不干净，泄露即让接入方 tsc 恒红）。核心包**不使用路径别名**，包内相对导入；oxlint 对 `packages/core/src/**` 放开 `../../*`。

**安全边界（S10）**，作为 README「安全边界」章节真源：

| 随包走（接入方无法关闭） | 留在宿主（README 明示） |
|---|---|
| 组件只按注册表白名单查表，`type` 以 string 查找 | 整体 `parseUiSchema()` 校验（screenId / title / actions 形态 / token 长度） |
| 每个组件 props 先经 Zod 再渲染，失败渲染占位 | 不把模型输出直接当 UI Schema 构造 |
| 未知 type → `UnknownComponent` + `console.error('[strato-ui] …')` | `confirmationToken` 只回传不解析、不落日志 |
| 无 eval / new Function / dangerouslySetInnerHTML / 任意路径 import | pageContext 视为不可信，鉴权在后端 |
| UI Schema 中无 URL / HTML 字段可被渲染为链接或富文本 | 不用 `as UiSchema` 绕过校验 |

### 2.3 `apps/chat`（`strato-chat`，private）

FSD 不变（`app → pages → features → entities → shared`），内容：

- `app/`：Providers（QueryClient → `StratoDeviceProvider` → `StratoThemeProvider tokens={STRATO_TOKENS}`，`STRATO_TOKENS` 为 `app/styles/tokens.ts` 中与 `global.css` 同值的常量对象，不做 `getComputedStyle`（L3））、路由、`RouteErrorBoundary`、`global.css`（import `@strato-ui/core/style.css`）。
- `pages/chat/`：原 `AgentPage` 改名 `ChatPage`，路由 **`/`**；`pages/schema-playground/`（仅 `env.DEV` 注册 `/dev/schema`）；`pages/not-found/`。**删除** `pages/home`。
- `features/agent-chat/`：`useAgentRun`、`runView`、`AgentChatPanel`（从 `@strato-ui/core` 拿 `SchemaRenderer` / `ActionBar` / `FormPropsSchema` / `COMPONENT_TYPES`；`clientCapabilities.components` 改用 `COMPONENT_TYPES`（L1））。
- `entities/agent-run/`：intent / action / run-summary / sse-events / error 的 Zod 投影；`currentUi` / `ui.replace.ui` 复用 `@strato-ui/core` 的 `UiSchemaSchema`。**契约投影真源分工**：ui-schema 在 `@strato-ui/core/src/schema/uiSchema.ts`，其余 8 个契约在 `apps/chat/src/entities/*/model/types.ts`（`contracts.md` §1 与 `coding-standard.md` §2 同步）。
- `shared/`：`api/`（http、SSE）、`config/env`、`lib/`、`ui/Button`（纯 CSS）。
- `package.json`：`@strato-ui/core: workspace:*`、react、react-dom、react-router、@tanstack/react-query、zustand、antd、antd-mobile、@ant-design/icons、zod（后四者为 core 的 peer，**由宿主安装，但应用源码不得 import antd / antd-mobile / @ant-design**；安装 ≠ import）。
- **应用内只能 import `@strato-ui/core` 包入口与 `@strato-ui/core/style.css`**（禁止 `@strato-ui/core/src/*` 深路径）。

### 2.4 命名与文档：Generate UI → Strato UI（M4）

- 目录 `shared/ui/generate/` 消失（内容进 `packages/core`）。`AppThemeProvider` → `StratoThemeProvider`；`DeviceProvider` → `StratoDeviceProvider`；日志前缀 `[schema-renderer]` → `[strato-ui]`（文案 `unknown component type` 保留，e2e 依赖）。
- 文档同步清单（**全部**，历史 change 目录 `feat-agent-tool-platform-20260903/**` 不改）：
  - `CLAUDE.md`、`AGENTS.md`：一句话 "Generate UI" → "Strato UI"。
  - `.harness/agents/platform-owner.md`：职责表 `fronted/` 行。
  - `.harness/rules/project-structure.md` §1：重写为 monorepo 布局 + Strato UI 专项；红线 2 改为「在 `packages/core/src/registry/componentRegistry.ts` 之外声明可被 UI Schema 引用的组件」；红线 3 路径改 `apps/chat/src/app/router`。
  - `.harness/rules/coding-standard.md` §2 第 3 条（投影真源分工）、§4 组件库条目（路径）。
  - `.harness/rules/contracts.md` §1 表「前端」行：`@strato-ui/core/src/schema/uiSchema.ts`（ui-schema）与 `apps/chat/src/entities/*/model/types.ts`（其余）。
  - `.harness/contracts/ui-schema.schema.json` 第 39 行 description：`fronted/src/shared/ui/generate/types.ts` → `@strato-ui/core（fronted/packages/core/src/registry/types.ts）`。**仅注释文本，字段与约束零变化**，示例校验不受影响。
  - `.harness/skills/coding-skill/specs/05-styling-spec.md`：Strato UI 封装层路径、`--strato-*` 约束。
  - `.harness/skills/code-review/SKILL.md`：红线路径。
  - `.harness/skills/project-analysis/SKILL.md`：`fronted/src/main.tsx` / `ls fronted/src/{…}` → `fronted/apps/chat/src/…`。
  - `.harness/skills/deploy-verify/SKILL.md`：`fronted/dist` → `fronted/apps/chat/dist` 与 `fronted/packages/core/dist`；并写明并行 change 时 `STRATO_CHANGE=<id>`。
  - `.harness/rules/dev-workflow.md` 阶段 7：增加「`deployment/` 由 `lib/change-dir` 定位；并行 change 需显式 `STRATO_CHANGE`」一句。
  - `.harness/wiki/architecture.md`：前端框图与分层段。
  - `fronted/README.md`：重写（workspace 命令、包与应用、发包流程占位、Vite 8）。
  - `fronted/packages/core/README.md`：新写（安装 / peer / 示例 / 公共 API / 安全边界两列 / 「不要这样做」反例：`as UiSchema` 强转、替换 UnknownComponent 渲染任意 HTML、宿主解析 token）。

### 2.5 Harness（M3 / S7）

- 新增 `.harness/scripts/lib/change-dir.mjs` 与 `change-dir.sh`：读 `STRATO_CHANGE`；未设置时在 `.harness/changes/` 中按正则 `^\| 状态 \| (\S+) \|` 读 `summary.md` 状态，选状态 ∉ {`DONE`} 的目录，恰 1 个则用之，0 或 >1 个则退出码 2 并列出候选。**并行第二个 change 时必须显式 `STRATO_CHANGE=<id>`，这是预期用法**，写进 `dev-workflow.md` 阶段 7 与 `deploy-verify/SKILL.md`。`e2e-frontend.mjs` / `e2e-backend.sh` / `deploy-verify.sh` 全部经此 lib 取 `deployment/`，脚本中不得再出现 `changes/feat-`（doctor 机械检查）。
- `e2e-frontend.mjs`：步骤 5 URL `/agent?…` → `/?…`；其余 21 项不变（I1）。
- `deploy-verify.sh`：preview cwd → `fronted/apps/chat`；「preview /agent (SPA) 200」改为「preview 主页面含 `#agent-input`」（由 `preview-console.mjs` 输出解析）；bundle 统计改 `fronted/apps/chat/dist/assets/*.js` + `fronted/packages/core/dist/**/*.js`。断言清单固定为 12 项：health、selfcheck 4/4、preview `/` 200、preview 主页面含 `#agent-input`、preview 代理 `/actuator/health` 200、事件序列、confirm 到 `run.completed`、summary COMPLETED、backend.log 含本 run、用户原文 0、preview console.error 0、bundle_size 写出。
- `preview-console.mjs`：页面改为 `/?page=order-detail&entityType=order&entityId=10001`（截图 `preview-chat.png`；stdout 独立一行 `agent-input=1` 或 `agent-input=0`；退出码仍 = console.error 总数；deploy-verify 用 `tee deployment/preview-console.log` 落盘后 `grep -o 'agent-input=[01]'` 解析）与 `/does-not-exist`（截图 `preview-notfound.png`，验证 NotFound 页无 error）。
- `harness-doctor.mjs`：必需文件加 `fronted/pnpm-workspace.yaml`、`fronted/.npmrc`、`fronted/packages/core/package.json`、`fronted/apps/chat/package.json`、`scripts/lib/change-dir.mjs`、`scripts/lib/change-dir.sh`；`fronted/src` 存在即 err；新增「文档路径存在」检查（M4 Hashimoto），规则写死：扫描 rules / skills / wiki / agents / CLAUDE.md / AGENTS.md（不含 `changes/`）；只取反引号内**以 `fronted/` 开头且不含空格**的 token（排除 shell 命令）；token 含 `*` / `{` 时取第一个通配符之前的目录前缀做 `existsSync`；跳过含 `node_modules` / `dist` 的 token；新增「scripts/*.{sh,mjs} 不得含 `changes/feat-`」检查。
- `.harness/scripts/ci.mjs` 不变（仍 `pnpm -C fronted run ci`）。

## 3. 非目标（Out of Scope）

- **不**真正 `npm publish`。本 change 只保证 `pnpm pack` 产物可用；registry 地址、token、CI 发布流水线为后续 change。`prepublishOnly` 只是误发保护，不是发布流程。
- **不**引入 changesets / 自动版本号；版本固定 `0.1.0`。`license` 字段不设置，发布前由用户决定。
- **不**拆 `@strato-ui/mobile`、`@strato-ui/agent` 等更多包；SSE / 其余契约投影 / `useAgentRun` / `AgentChatPanel` 留在 chat 应用。
- **不**改 7 个白名单组件的视觉与 props；**不**新增组件；**不**新增 `StratoThemeTokens` 键；**不**提供 `locale` prop（固定 zhCN，与现状一致）。
- **不**改契约字段（仅 `ui-schema.schema.json` 一处 description 注释路径）、**不**改后端、**不**改 Spring AI 任何代码。
- **不**做 Storybook / 文档站 / 单元测试框架。
- **不**支持 React 18 及以下、antd 5；peer 范围只声明当前大版本。
- **不**升级任何现有依赖版本（catalog 只是把现版本集中声明）。
- **不**做真实 IdP、领域路由改造、外置存储（已排在后续 change）。

## 4. 核心场景

### 4.1 接入方安装使用（发包目标）

```ts
import { SchemaRenderer, StratoDeviceProvider, StratoThemeProvider, parseUiSchema } from '@strato-ui/core';
import '@strato-ui/core/style.css';

const ui = parseUiSchema(payloadFromBackend); // 不要写 payload as UiSchema
<StratoDeviceProvider>
  <StratoThemeProvider tokens={{ colorPrimary: '#3370ff' }}>
    <SchemaRenderer ui={ui} onFormChange={setForm} />
  </StratoThemeProvider>
</StratoDeviceProvider>
```

接入方自行安装 6 个 peer。核心包不发请求、不持有会话状态。

### 4.2 本仓库 chat 主链路（与首期一致）

`/?page=order-detail&entityType=order&entityId=10001` → 输入「帮我把这个订单退款」→ `POST /agent/runs` SSE → 两条工具进度 → `ui.replace` 经 core 的 `UiSchemaSchema` 校验 → `SchemaRenderer` 渲染 OrderCard + RefundConfirmCard + Form → `ActionBar` 确认 → 结果屏。后端、契约字段、事件序列零变化。

### 4.3 开发期链路

`pnpm -C fronted dev` = 若 `packages/core/dist/style.css` 不存在先 `build:core`，再启动 `apps/chat` dev server；`@strato-ui/core` 经 `exports` 解析到 core src，改 core 源码即热更新。`pnpm -C fronted run verify-examples` 经 vite-node（serve 模式）同样解析到 src。`pnpm -C fronted build` = `build:core` → `build:chat`（chat 的 vite build 通过 alias 吃 core dist）。

## 5. 契约影响

**字段零变化**。9 个 schema 与 20 个示例的结构、约束、示例内容均不变；`check-contracts` 结果不变。

唯一改动：`ui-schema.schema.json` 第 39 行 `props.description` 中的实现路径注释 `fronted/src/shared/ui/generate/types.ts` → `@strato-ui/core（fronted/packages/core/src/registry/types.ts）`。summary.md「契约变更」记为「1 处 description 注释路径，无字段变更」。

ui-schema 的前端 Zod 投影从 `entities` 迁到 `packages/core/src/schema/uiSchema.ts`，仍由 `verify-examples` 用契约示例逐一校验。

## 6. 验收标准

### 6.1 结构

- [ ] `ls fronted/pnpm-workspace.yaml fronted/.npmrc fronted/packages/core/package.json fronted/apps/chat/package.json` 全部存在；`test ! -d fronted/src`。
- [ ] 仓库根无 `package.json` / `pnpm-lock.yaml` / `node_modules` / `tsconfig.json`（doctor 现有检查）。
- [ ] `pnpm -C fronted --filter './packages/**' --filter './apps/**' -r exec node -p "require('./package.json').name"` 恰输出 `@strato-ui/core` 与 `strato-chat` 两行。
- [ ] `grep -rn "Generate UI\|shared/ui/generate\|AppThemeProvider\|\[schema-renderer\]\|fronted/src\b\|fronted/src/\|fronted/dist" CLAUDE.md AGENTS.md .harness/rules .harness/skills .harness/wiki .harness/agents .harness/contracts .harness/scripts fronted --exclude-dir=node_modules --exclude-dir=dist --exclude-dir=.verify-pack --exclude=harness-doctor.mjs` 输出 0 行（doctor 自身含 `fronted/src` 字面量用于「存在即 err」检查，故排除）。
- [ ] `pnpm -C .harness run doctor` 的「文档引用路径存在」与「脚本无硬编码 change」两项检查为 ✓。

### 6.2 核心包

- [ ] `pnpm -C fronted --filter @strato-ui/core build` 退出码 0；`ls fronted/packages/core/dist/index.js fronted/packages/core/dist/index.d.ts fronted/packages/core/dist/style.css` 全部存在。
- [ ] `grep -c 'from "antd"' fronted/packages/core/dist/index.js` ≥ 1 且 `grep -rc "ant-design/cssinjs" fronted/packages/core/dist | grep -v ":0$" | wc -l` 为 0（前者证明 antd 是外部引用，后者证明 antd 源码未被打包；两者缺一不可）。
- [ ] `du -sk fronted/packages/core/dist` 首测值记入 coding_report；`verify-pack` 断言 ≤ 首测 × 1.1。
- [ ] `pnpm -C fronted run verify-pack` 退出码 0（脚本内一切子进程 `cwd = fronted`，vite-node 以 `fronted` 为 Vite root，否则 peer 无法从 `fronted/node_modules` 解析），断言：(a) tarball 文件清单只含 `package/package.json`、`package/README.md`、`package/dist/**`；(b) 解包后 `package.json` 的 `exports["."].import == ./dist/index.js`、`types == ./dist/index.d.ts`（publishConfig 已覆盖）、`peerDependencies` 恰 6 键、无 `dependencies`、`files == ["dist","README.md"]`；(c) 用 `es-module-lexer` 静态解析 `dist/index.js` 的导出名集合 == §2.2 运行时清单（17）；(d) 解包到 `fronted/packages/core/.verify-pack/pkg/`，`vite-node --root <fronted/packages/core> <loader.mjs>` 加载 `pkg/dist/index.js` 成功；脚本结束（含失败）时删除 `.verify-pack/`；(e) 在 `fronted/packages/core/.verify-pack/`（gitignore；必须在 core 包目录下，因为 zod / react / @types/react 只安装在 `packages/core/node_modules`，vite-node 与 tsc 都从被加载文件所在目录向上解析）生成 `consumer.ts`（引用 10 个类型导出与 17 个运行时导出）与临时 `tsconfig.json`（`strict`、`moduleResolution: bundler`、`jsx: react-jsx`、`types: []`、`skipLibCheck: false`、`paths: { '@strato-ui/core': [<解包 dist/index.d.ts>] }`），分别以 EOPT 开 / 关跑 `tsc --noEmit`，两次退出码 0；(f) dist 体积 ≤ 基线 × 1.1；(g) 解包 dist 内所有 `.d.ts` 不含 `from 'antd` / `from "antd` / `@ant-design`（(e) 成立的前提）。
- [ ] `grep -rn "eval(\|new Function\|dangerouslySetInnerHTML\|href=\|http" fronted/packages/core/src | grep -v "^\S*:\s*\(//\|\*\)"` 输出 0 行。
- [ ] `grep -rln "from 'antd\|from \"antd\|@ant-design" fronted/packages/core/src | grep -v "/components/desktop/\|/components/mobile/\|/theme/"` 输出 0 行。
- [ ] `grep -rn "var(--color-\|var(--radius-\|getComputedStyle" fronted/packages/core/src` 输出 0 行。

### 6.3 chat 应用

- [ ] `grep -rln "from 'antd\|from \"antd\|@ant-design" fronted/apps/chat/src` 输出 0 行。
- [ ] `grep -rn "@strato-ui/core/src" fronted/apps/chat/src` 输出 0 行。
- [ ] 干净检出模拟：`rm -rf fronted/packages/core/dist && pnpm -C fronted run ci` 退出码 0（M1：ci 自含 build:core 且 typecheck 不依赖 dist）。
- [ ] `pnpm -C fronted run verify-examples` 输出 `16 examples OK`；在 core src 临时把 `UiSchemaSchema.schemaVersion` 改为 `z.literal('9.9')` → 输出含 `failed` 且退出码 1（证明 vite-node 走 src），恢复后绿。
- [ ] `node fronted/scripts/check-registry.mjs` 用 `git mv` 临时移走 `components/mobile/Table.tsx` 证明会红，恢复后绿。

### 6.4 端到端（后端 8080 + `pnpm -C fronted dev` 5173）

- [ ] `node .harness/scripts/e2e-frontend.mjs` 21 passed / 0 failed（步骤 5 URL 为 `/?page=order-detail&entityType=order&entityId=10001`）。
- [ ] `pnpm -C .harness run deploy-verify` 12 passed / 0 failed，12 项名称与 §2.5 清单一致（coding_report 粘贴完整输出）。

### 6.5 全仓

- [ ] `pnpm -C .harness run doctor` 0 errors。
- [ ] `pnpm -C .harness run ci` 退出码 0。
- [ ] `bash .harness/scripts/e2e-backend.sh` 通过数与 T01 开始前在主干实测值一致（记入 coding_report；证明后端零改动）。

## 7. 风险与权衡

| 风险 | 影响 | 缓解 |
|---|---|---|
| dev / typecheck 走 core src、生产 build 走 dist，两条路径可能不一致 | 开发期正常、生产构建炸或行为差异 | 根 `ci` 顺序 `build:core` 最前；e2e-frontend 跑 dev（src），deploy-verify 跑 preview（dist），两条都在门禁；verify-pack (e) 用 tsc 消费 dist d.ts |
| dist d.ts 从未被 TypeScript 消费，错误在 CI 全绿下逃逸 | 接入方 TS 报错 | verify-pack (e) `consumer.ts` 双模式（EOPT 开 / 关）tsc |
| antd 双实例 / peer 版本不符 | 主题 token 不生效、体积翻倍 | 6 个 peer + `external` + `.npmrc strict-peer-dependencies`；verify-pack (b)(c) |
| zod 双实例 | `UiSchemaSchema` 组合进 chat 的 schema 时类型 / `instanceof` 失败 | zod 改 peer；workspace `catalog:` 统一版本；verify-pack 断言 peer range 与 catalog 一致 |
| core CSS 依赖宿主变量 | 第三方宿主里 UnknownComponent 不可见 | 只允许 `--strato-*` + fallback；Provider 写变量；§6.2 grep 守护 |
| tokens 键集合变化悄悄改视觉 | e2e 不看颜色，不会红 | 键 = 现 7 键、默认值 = 现值；不新增键写进非目标 |
| 渲染器成为第三方包后被绕过 Zod | 未校验 UI 进渲染器 | §2.2 两列清单；`parseUiSchema` 作为公共 API；README 反例；props 级 Zod 随包走 |
| Node 原生无法 `import()` dist（antd-mobile ESM 顶层 import css、antd 深路径无扩展名） | verify-pack 不可行 | 改用 es-module-lexer 静态导出比对 + vite-node 加载（M2） |
| 大规模 `git mv` 与逻辑改动混在一起 | 历史断裂、评审困难 | T02a / T03a 纯移动，T02b / T03b 才改逻辑，各自独立 commit |
| Harness 脚本硬编码 change 目录 | 产物写到上一个 change | `lib/change-dir` 统一 + doctor 检查 `changes/feat-` 不再出现 |
| 文档路径引用漂移 | 规则指向不存在的文件 | doctor 新增「文档反引号 `fronted/` 路径必须存在」机械检查 |

## 8. 假设（非阻塞）

- `publishConfig.access = public`；若发内部 registry，发布时以 `--registry` 覆盖，不改包文件。
- pnpm 10 `publishConfig` 覆盖 `exports` / `types` 的行为以本机 `pnpm pack` 实测为准（T02b 验收即包含）。
