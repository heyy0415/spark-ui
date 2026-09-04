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

## 1. 前端分层（Feature-Sliced Design）

```
fronted/src/
├── app/        # 启动入口、Provider、路由声明、全局样式
├── pages/      # 路由级页面（一个路由 = 一个目录）
├── features/   # 业务能力（UI + hook + 编排）
├── entities/   # 业务实体（Zod schema + 纯 API）
├── shared/     # 与业务无关的通用资产
│   ├── ui/     # 通用 UI + Generate UI 的 Schema Renderer / ComponentRegistry
│   ├── lib/    # 工具函数
│   ├── hooks/  # 通用 Hook
│   ├── api/    # http 客户端、SSE 客户端、错误约定
│   └── config/ # 环境变量、常量
```

依赖方向 `pages → features → entities → shared`，单向；同层不互相 import；跨切片只经 `index.ts`。
`import type` 允许跨层。`oxlint` 的 `no-restricted-imports` 与 `scripts/check-deps.mjs` 守护。

切片内部：`api/`、`model/`、`ui/`、`index.ts`。页面目录：`{Name}Page.tsx`、`index.ts`、`parts/`。

路径别名：`@/*`、`@app/*`、`@pages/*`、`@features/*`、`@entities/*`、`@shared/*`；另有只读别名 `@contracts/*` → `../.harness/contracts/*`，**仅允许 import `*.json`**（示例夹具），且仅在 `pages/` 与 `scripts/` 使用。禁止 `../../` 跨层。

**Generate UI 专项**：
- 白名单组件注册表只有一个入口：`shared/ui/generate/componentRegistry.ts`，按端型导出 `desktopRegistry`（antd 封装）与 `mobileRegistry`（antd-mobile 封装），键集合必须完全一致且与 `ui-schema.schema.json` 的 `type` enum 一致。
- 每个白名单组件是**封装层**：`shared/ui/generate/desktop/{Type}.tsx` 与 `shared/ui/generate/mobile/{Type}.tsx`，对外 props 由契约 `props` 决定，内部才使用 antd / antd-mobile。业务代码与 Schema Renderer **不得**直接 import `antd` / `antd-mobile`。
- Schema Renderer 只能渲染注册表内的 `type`；未知 `type` 渲染 `UnknownComponent` 占位并 `console.error`。
- 渲染器**不得**出现 `eval`、`new Function`、`dangerouslySetInnerHTML`、动态 `import()` 任意路径。
- 端型由 `app/` 层根据视口 / UA 一次性决定并通过 Provider 下发，不在组件内各自判断。

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
2. 前端在 `shared/ui/generate/` 之外声明可被 UI Schema 引用的组件。
3. 前端在 `app/router/` 与 `pages/` 之外声明路由或使用路由 hook 拼装路由表（页面组件内用 `useParams` / `Link` 允许）。
4. FSD 反向依赖或穿透 `index.ts`。
5. `agent-runtime`、`tool-registry`、`tool-gateway` 的 pom 依赖任何 `domains/*` 模块（只有 `app` 可以）。
6. `tool-registry` 的 pom 依赖任何 `domains/*` 模块，或暴露转发调用的端点。
7. 后端 `domain/` 包 import `org.springframework.*`。
8. 跨端数据结构在 `.harness/contracts/` 中无对应 Schema。
