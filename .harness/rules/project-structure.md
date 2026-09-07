# Rule: 工程结构（Project Structure）

> 不可商量的硬约束。Agent 创建 / 移动任何文件前**必须**先确认它落在正确的目录与层。

## 0. 仓库布局

```
strato_ui/
├── .harness/                # Harness 本体（根目录只放它和两份记忆文件）
│   ├── agents/ rules/ skills/ wiki/ templates/ mcp/ changes/
│   ├── contracts/           # 跨端契约真源（JSON Schema 2020-12 + examples/）
│   ├── scripts/             # doctor / new-change / check-contracts / check-module-deps / mvn / ci
│   └── package.json         # 仅供上述脚本使用（ajv）
├── fronted/                 # 前端工程，独立 package.json / pnpm
├── backed/                  # 后端工程，独立 pom.xml / Maven
├── docs/                    # 人读文档
└── CLAUDE.md / AGENTS.md    # L1 记忆
```

**根目录禁止**出现 `package.json`、`pnpm-lock.yaml`、`node_modules`、`tsconfig.json`、`pom.xml` 等任何工程文件；`harness-doctor` 会检查。

- `.harness/contracts/` 只放 `*.schema.json` 与 `examples/*.json`，不放任何可执行代码。
- `fronted/` 与 `backed/` 互不 import；只通过 `.harness/contracts/` 定义的 HTTP / SSE 协议通信。

## 1. 前端：pnpm workspace（`fronted/`）

```
fronted/
├── package.json / pnpm-workspace.yaml / .npmrc   # workspace 根：脚本 fan-out、catalog 统一版本；无业务代码
├── tsconfig.base.json / .oxlintrc.json / .prettierrc.json
├── scripts/                 # check-deps / check-registry / verify-examples / verify-pack
├── packages/core/           # @strato-ui/core —— Strato UI 渲染引擎（可 npm 发包）
│   └── src/
│       ├── index.ts         # 唯一公共入口（17 运行时 + 10 类型导出，verify-pack 断言）
│       ├── schema/          # ui-schema 契约的 Zod 投影（前端真源）+ parseUiSchema
│       ├── registry/        # componentRegistry（desktop / mobile）+ 各组件 props Zod
│       ├── renderer/        # SchemaRenderer / UnknownComponent / ActionBar
│       ├── components/      # desktop/{Type}.tsx（antd）、mobile/{Type}.tsx（antd-mobile）
│       ├── device/          # StratoDeviceProvider / useDevice
│       └── theme/           # StratoThemeProvider（tokens → antd token / --adm-* / --strato-*）
└── apps/chat/               # strato-chat —— 唯一应用，只 import @strato-ui/core
    └── src/                 # FSD：app → pages → features → entities → shared
```

**`apps/chat` 内部 FSD**：`app/`（Provider、路由、全局样式）、`pages/`（`chat` 即首页 `/`、DEV-only `schema-playground`、`not-found`）、`features/agent-chat`、`entities/agent-run`（除 ui-schema 外的契约投影）、`shared/`（api / config / lib / ui/Button）。依赖方向 `pages → features → entities → shared` 单向；跨切片只经 `index.ts`；`import type` 允许跨层。`scripts/check-deps.mjs` 与 oxlint 守护。

路径别名（仅 `apps/chat`）：`@app/*`、`@pages/*`、`@features/*`、`@entities/*`、`@shared/*`；只读别名 `@contracts/*` → `.harness/contracts/*`，仅 `pages/` 与 `scripts/` 可用且只 import `*.json`。`packages/core` **不使用别名**（d.ts 无法解析），包内相对导入。

**`@strato-ui/core` 解析策略**：开发期 `exports` 指 `src`（tsc / oxlint / vite serve / vite-node 走源码，热更新）；`publishConfig` 在 `pnpm pack` 时覆盖为 `dist`；chat 的 `vite build` 用正则精确 alias 到 core `dist`（生产吃可发布产物）；`@strato-ui/core/style.css` 始终指 dist，chat 的 `predev` / `prebuild` 守护 dist 存在。

**Strato UI 专项**：
- 白名单组件注册表只有一个入口：`fronted/packages/core/src/registry/componentRegistry.ts`，`desktopRegistry`（antd）与 `mobileRegistry`（antd-mobile）键集合必须完全一致且与 `ui-schema.schema.json` 的 `type` enum 一致；`scripts/check-registry.mjs` 机械校验。
- 每个白名单组件是**封装层**：`fronted/packages/core/src/components/desktop/{Type}.tsx` 与 `fronted/packages/core/src/components/mobile/{Type}.tsx`，对外 props 由契约决定，内部才使用 antd / antd-mobile。antd / antd-mobile 只允许出现在 `components/**` 与 `theme/**`。
- Schema Renderer 只渲染注册表内的 `type`；未知 `type` → `UnknownComponent` 占位 + `console.error('[strato-ui] …')`；每个组件 props 先经 Zod。
- 渲染器**不得**出现 `eval`、`new Function`、`dangerouslySetInnerHTML`、任意路径动态 `import()`。
- 包内 CSS 只允许 `--strato-*` 变量且带 fallback；不读宿主 CSS 变量、不 `getComputedStyle`；公共 d.ts 不得暴露 antd / antd-mobile 类型。
- 端型由宿主挂载 `StratoDeviceProvider` 一次性决定，组件内不各自判断。
- 安全边界分工（随包走 / 留在宿主）见 `fronted/packages/core/README.md`。

## 2. 后端模块分层（Maven 多模块）

```
backed/
├── pom.xml                      # 父 POM：版本、插件、spotless、enforcer
├── platform-spi/                # 极薄接口层：ToolHandler SPI、ToolResolver、PrincipalPermissionResolver；无 Spring 依赖
├── contracts-java/              # 与 .harness/contracts/ 对应的 record DTO + JSON Schema 校验器
├── agent-runtime/               # 意图识别、领域路由、规划、Policy、Run 状态机、SSE 输出
├── tool-registry/               # 控制面：Manifest 注册、查询、版本、状态；实现 ToolResolver
├── tool-gateway/                # 执行面：鉴权、Schema 校验、路由、超时、审计；注入 List<ToolHandler>
├── domains/
│   ├── order-service/           # 模拟领域服务：实现 ToolHandler，携带 tool-manifest 资源
│   └── refund-service/
└── app/                         # 可运行装配（首期单进程装配全部模块）
```

**依赖方向**（Maven `<dependency>` 即红线）：

```
app → 全部模块（唯一允许依赖 domains/* 的非领域模块）
agent-runtime → tool-registry(api) , tool-gateway(api) , contracts-java , platform-spi
tool-gateway  → tool-registry(ToolResolver 接口经 platform-spi) , contracts-java , platform-spi
tool-registry → contracts-java , platform-spi
domains/*     → contracts-java , platform-spi            （实现 ToolHandler；不依赖 gateway / registry / runtime）
agent-runtime ↛ domains/*     tool-registry ↛ domains/*     tool-gateway ↛ domains/*
platform-spi、contracts-java ↛ 任何其他模块
```

模块内包结构：`api/`（controller / DTO）、`application/`（用例）、`domain/`（实体、规则）、`infra/`（持久化、外部调用）。`domain/` 不依赖 Spring。

## 3. 文件命名

| 端 | 类型 | 风格 | 示例 |
|---|---|---|---|
| 前端 | 组件 / 类 | PascalCase | `RefundConfirmCard.tsx` |
| 前端 | Hook | `useCamelCase` | `useAgentRun` |
| 前端 | 模块 | camelCase | `sseClient.ts` |
| 后端 | 类 | PascalCase，后缀表职责 | `ToolRegistryController`、`RefundPreviewUseCase` |
| 后端 | 包 | 全小写 | `com.strato.gateway.infra` |
| 契约 | Schema | kebab-case | `ui-schema.schema.json` |

## 4. 红线（Red Lines）

任一触发即 MUST FIX：

1. `fronted/` 直接引用 `backed/` 文件，或反之。
2. 前端在 `fronted/packages/core/src/registry/componentRegistry.ts` 之外声明可被 UI Schema 引用的组件；`apps/chat` import antd / antd-mobile 或 `@strato-ui/core/src/*` 深路径。
3. 前端在 `fronted/apps/chat/src/app/router/` 与 `pages/` 之外声明路由或使用路由 hook 拼装路由表（页面组件内用 `useParams` / `Link` 允许）。
4. FSD 反向依赖或穿透 `index.ts`。
5. `agent-runtime`、`tool-registry`、`tool-gateway` 的 pom 依赖任何 `domains/*` 模块（只有 `app` 可以）。
6. `tool-registry` 的 pom 依赖任何 `domains/*` 模块，或暴露转发调用的端点。
7. 后端 `domain/` 包 import `org.springframework.*`。
8. 跨端数据结构在 `.harness/contracts/` 中无对应 Schema。
