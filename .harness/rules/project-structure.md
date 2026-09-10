# Rule: 工程结构（Project Structure）

> 不可商量的硬约束。Agent 创建 / 移动任何文件前**必须**先确认它落在正确的目录与层。

## 0. 仓库布局

```
spark_ui/
├── .harness/                # Harness 本体（根目录只放它和两份记忆文件）
│   ├── agents/ rules/ skills/ wiki/ templates/ mcp/ changes/
│   ├── contracts/           # 跨端契约真源（JSON Schema 2020-12 + examples/）
│   ├── scripts/             # doctor / new-change / check-contracts / check-module-deps / mvn / ci
│   └── package.json         # 仅供上述脚本使用（ajv）
├── spark-ui/                # 前端工程，独立 package.json / pnpm（@spark-ui/core + spark-chat）
├── spark-rooter/            # 后端：平台模块 + spring-boot-starter + examples/（领域示例、独立示例宿主）
├── docs/                    # 人读文档
└── CLAUDE.md / AGENTS.md    # L1 记忆
```

**根目录禁止**出现 `package.json`、`pnpm-lock.yaml`、`node_modules`、`tsconfig.json`、`pom.xml` 等任何工程文件；`harness-doctor` 会检查。

- `.harness/contracts/` 只放 `*.schema.json` 与 `examples/*.json`，不放任何可执行代码。
- `spark-ui/` 与 `spark-rooter/` 互不 import；只通过 `.harness/contracts/` 定义的 HTTP / SSE 协议通信。

## 1. 前端：pnpm workspace（`spark-ui/`）

```
spark-ui/
├── package.json / pnpm-workspace.yaml / .npmrc   # workspace 根：脚本 fan-out、catalog 统一版本；无业务代码
├── tsconfig.base.json / .oxlintrc.json / .prettierrc.json
├── scripts/                 # check-deps / check-registry / verify-examples / verify-pack
├── packages/core/           # @spark-ui/core —— Spark UI 渲染引擎（可 npm 发包）
│   └── src/
│       ├── index.ts         # 唯一公共入口（17 运行时 + 19 类型导出，verify-pack 断言）
│       ├── schema/          # ui-schema 契约的 Zod 投影（前端真源，含五组件 props）+ parseUiSchema
│       ├── registry/        # componentRegistry（desktop / mobile）+ 渲染签名类型（props Zod 只 re-export）
│       ├── renderer/        # SchemaRenderer / UnknownComponent / ActionBar
│       ├── components/      # desktop/{Type}.tsx（antd）、mobile/{Type}.tsx（antd-mobile）
│       ├── device/          # SparkDeviceProvider / useDevice
│       └── theme/           # SparkThemeProvider（tokens → antd token / --adm-* / --spark-*）
└── apps/chat/               # spark-chat —— 唯一应用，只 import @spark-ui/core
    └── src/                 # FSD：app → pages → features → entities → shared
```

**`apps/chat` 内部 FSD**：`app/`（Provider、路由、全局样式）、`pages/`（`chat` 即首页 `/`、DEV-only `schema-playground`、`not-found`）、`features/agent-chat`、`entities/agent-run`（除 ui-schema 外的契约投影）、`shared/`（api / config / lib / ui/Button）。依赖方向 `pages → features → entities → shared` 单向；跨切片只经 `index.ts`；`import type` 允许跨层。`scripts/check-deps.mjs` 与 oxlint 守护。

路径别名（仅 `apps/chat`）：`@app/*`、`@pages/*`、`@features/*`、`@entities/*`、`@shared/*`；只读别名 `@contracts/*` → `.harness/contracts/*`，仅 `pages/` 与 `scripts/` 可用且只 import `*.json`。`packages/core` **不使用别名**（d.ts 无法解析），包内相对导入。

**`@spark-ui/core` 解析策略**：开发期 `exports` 指 `src`（tsc / oxlint / vite serve / vite-node 走源码，热更新）；`publishConfig` 在 `pnpm pack` 时覆盖为 `dist`；chat 的 `vite build` 用正则精确 alias 到 core `dist`（生产吃可发布产物）；`@spark-ui/core/style.css` 始终指 dist，chat 的 `predev` / `prebuild` 守护 dist 存在。

**Spark UI 专项**：
- 白名单组件注册表只有一个入口：`spark-ui/packages/core/src/registry/componentRegistry.ts`，`desktopRegistry`（antd）与 `mobileRegistry`（antd-mobile）键集合必须完全一致且与 `ui-schema.schema.json` 的 `type` enum 一致；`scripts/check-registry.mjs` 机械校验。
- 每个白名单组件是**封装层**：`spark-ui/packages/core/src/components/desktop/{Type}.tsx` 与 `spark-ui/packages/core/src/components/mobile/{Type}.tsx`，对外 props 由契约决定，内部才使用 antd / antd-mobile。antd / antd-mobile 只允许出现在 `components/**` 与 `theme/**`。
- Schema Renderer 只渲染注册表内的 `type`；未知 `type` → `UnknownComponent` 占位 + `console.error('[spark-ui] …')`；每个组件 props 先经 Zod。
- 渲染器**不得**出现 `eval`、`new Function`、`dangerouslySetInnerHTML`、任意路径动态 `import()`。
- 包内 CSS 只允许 `--spark-*` 变量且带 fallback；不读宿主 CSS 变量、不 `getComputedStyle`；公共 d.ts 不得暴露 antd / antd-mobile 类型。
- 端型由宿主挂载 `SparkDeviceProvider` 一次性决定，组件内不各自判断。
- 安全边界分工（随包走 / 留在宿主）见 `spark-ui/packages/core/README.md`。

## 2. 后端模块分层（Maven 多模块 + Starter）

```
spark-rooter/
├── pom.xml                              # 父 POM：版本、插件、spotless
├── spark-rooter-spi/                    # 注解（@SparkTool / @SparkRisk / @SparkPrerequisite / @SparkParam / @SparkDefault）+ 端口接口；零 Spring
├── spark-rooter-contracts/              # 契约 schema 打进 jar + record DTO + SchemaValidator
├── spark-rooter-runtime/                # 决策面：路由 → 抽取 → 规划 → 编排 → 令牌 → 记忆 → 澄清屏；无 Controller、无 Spring 组件注解
├── spark-rooter-registry/               # 控制面：Manifest 注册 / 发现 / 版本；不转发调用
├── spark-rooter-gateway/                # 执行面：校验 → 幂等 → 代理调用 → 输出校验 → 脱敏 → 审计；不做用户鉴权
├── spark-rooter-web-mvc/                # /agent/runs SSE 端点 + /internal/** 端点 + 异常映射；唯一依赖 spring-boot-starter-web 的平台模块
├── spark-rooter-spring-boot-starter/    # AutoConfiguration.imports + spark.* 属性 + 全部平台 Bean 的 @ConditionalOnMissingBean 装配 + @SparkTool 扫描 / Manifest 推导；宿主唯一引入坐标
└── examples/
    ├── demo-support/                    # 示例宿主侧 mock 用户上下文（DemoUserContext），纯 JDK
    ├── domains/{order,product,aftersale,refund}-service/   # @SparkTool 形态的示例领域；只依赖 spi + contracts + demo-support
    └── host-demo/                       # 独立 Maven 工程（parent = spring-boot-starter-parent，不在根 modules）：引入 starter 即可用的验收物
```

**依赖方向**（Maven `<dependency>` 即红线，`check-module-deps` 机械校验）：

```
spi ↛ 任何 com.sparkrooter；contracts → spi
runtime / registry / gateway → contracts, spi（三者之间只经对方 api 包接口 ToolSearchPort / ToolInvokePort）
web-mvc → runtime, registry, gateway；starter → 全部平台模块
examples/domains/* → spi, contracts, demo-support（不依赖任何平台模块；互不 import，跨领域读订单只经 demo-support 的 OrderSnapshotProvider）
host-demo → starter + examples/domains/*（本地仓坐标）
平台模块（spi / contracts / runtime / registry / gateway）pom 禁 spring-boot-starter-web / starter-validation
平台模块（contracts / runtime / registry / gateway / web-mvc）源码禁 @Component / @Service / @Repository / @Configuration / @ComponentScan —— Bean 由 starter @Bean 装配，不依赖包扫描
```

模块内包结构：`api/`（跨模块公开接口）、`application/`（用例）、`domain/`（实体、规则）、`infra/`（默认实现、适配器、自检）。`domain/` 不依赖 Spring。Controller 只在 `web-mvc`。

**身份边界**：内核不识别用户。`Principal` / `userId` / `tenantId` 不得出现在平台模块源码；身份、权限、业务字段全在宿主工程（`SessionIdResolver` 产出会话键、`RunContextPropagator` 把宿主 ThreadLocal 带到工作线程、方法级切面做权限、`@SparkDefault` 预制业务默认值）。

## 3. 文件命名

| 端 | 类型 | 风格 | 示例 |
|---|---|---|---|
| 前端 | 组件 / 类 | PascalCase | `Timeline.tsx`（core 组件文件名 == 契约 type，官方组件名） |
| 前端 | Hook | `useCamelCase` | `useAgentRun` |
| 前端 | 模块 | camelCase | `sseClient.ts` |
| 后端 | 类 | PascalCase，后缀表职责 | `ToolRegistryController`、`RefundPreviewUseCase` |
| 后端 | 包 | 全小写 | `com.sparkrooter.gateway.infra` |
| 契约 | Schema | kebab-case | `ui-schema.schema.json` |

## 4. 红线（Red Lines）

任一触发即 MUST FIX：

1. `spark-ui/` 直接引用 `spark-rooter/` 文件，或反之。
2. 前端在 `spark-ui/packages/core/src/registry/componentRegistry.ts` 之外声明可被 UI Schema 引用的组件；`apps/chat` import antd / antd-mobile 或 `@spark-ui/core/src/*` 深路径。
3. 前端在 `spark-ui/apps/chat/src/app/router/` 与 `pages/` 之外声明路由或使用路由 hook 拼装路由表（页面组件内用 `useParams` / `Link` 允许）。
4. FSD 反向依赖或穿透 `index.ts`。
5. `spark-rooter-runtime`、`spark-rooter-registry`、`spark-rooter-gateway` 的 pom 依赖任何 `examples/domains/*` 模块（只有 `examples/host-demo` 可以）；平台模块 pom 出现 `spring-boot-starter-web`；平台模块源码出现 `@Component / @Service / @Repository / @Configuration`；`examples/domains/*` 依赖任何平台模块。
6. `spark-rooter-registry` 暴露转发调用的端点。
7. 后端 `domain/` 包 import `org.springframework.*`。
8. 跨端数据结构在 `.harness/contracts/` 中无对应 Schema。
9. `examples/domains/<a>` 引用 `com.sparkrooter.examples.<b>` 或依赖兄弟领域 artifact；领域屏（`infra/screen/`）直读领域数据而不是用 Gateway 输出。
11. 平台模块源码出现 `userId` / `tenantId` / `Principal` 标识符（身份归宿主）；`@SparkTool` 方法 / 类为 `final`、非 `public`、标在 `@Configuration` 类上（扫描器启动失败）。
10. `spark-ui/packages/core/src/components/**` 出现业务命名组件或 import antd / antd-mobile / react / 本包类型之外的模块（core 组件只能是官方组件映射）。
