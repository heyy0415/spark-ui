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
├── packages/core/           # @spark-ui/core —— Spark UI 引擎（三入口；本仓不发包，需要发包者自取源码）
│   └── src/
│       ├── index.ts         # 入口 '.' —— 渲染层（导出清单真源在 scripts/verify-pack.mjs，机械断言）
│       ├── schema/          # ui-schema 契约的 Zod 投影（前端真源，含五组件 props）+ parseUiSchema
│       ├── registry/        # componentRegistry（desktop / mobile）+ 渲染签名类型（props Zod 只 re-export）
│       ├── renderer/        # SchemaRenderer / UnknownComponent / ActionBar
│       ├── components/      # desktop/{Type}.tsx（antd）、mobile/{Type}.tsx（antd-mobile）
│       ├── device/          # SparkDeviceProvider / useDevice
│       ├── theme/           # SparkThemeProvider（tokens → antd token / --adm-* / --spark-*）
│       ├── client/          # 入口 './client' —— headless：契约投影（8 个）/ http / sse / runView / runStore
│       └── react/           # 入口 './react' —— useSparkRun（client 的 React 绑定）
└── apps/chat/               # spark-chat —— 参考宿主，只 import @spark-ui/core 的三个入口
    └── src/                 # FSD：app → pages → features → shared
```

**core 三入口分层**（refactor-headless-client-into-core-20260912）：`'.'` 是渲染层（依赖 react + antd / antd-mobile）；`'./client'` 是 **headless 层**，零框架依赖，不 import react、不读 `import.meta.env`（`check-deps.mjs` 机械守护），任何 TS 工程都能用；`'./react'` 是 client 的 React 绑定。依赖方向单向：`react → client`、`. → schema/registry`；**`client/` 不得 import `../index`**（会拉进 antd，headless 层不该依赖渲染层）。宿主要接 spark 后端但自带渲染，只装 `'./client'`。

**`apps/chat` 内部 FSD**：`app/`（Provider、路由、全局样式）、`pages/`（`chat` 即首页 `/`、DEV-only `schema-playground`、`not-found`）、`features/agent-chat`、`shared/`（config / lib / ui/Button）。依赖方向 `pages → features → entities → shared` 单向；跨切片只经 `index.ts`；`import type` 允许跨层。`scripts/check-deps.mjs` 与 oxlint 守护。

`entities/` 层当前为空：契约投影与运行时状态已下沉 `@spark-ui/core/client`，`shared/api/` 同理删除。分层规则仍保留 entities 位次，将来有 chat 自己的领域实体时按原方向重建。

路径别名（仅 `apps/chat`）：`@app/*`、`@pages/*`、`@features/*`、`@shared/*`（`@entities/*` 随 entities 层清空一并移除，重建时再加回）；只读别名 `@contracts/*` → `.harness/contracts/*`，仅 `pages/` 与 `scripts/` 可用且只 import `*.json`。`packages/core` **不使用别名**（d.ts 无法解析），包内相对导入。

**`@spark-ui/core` 解析策略**：开发期 `exports` 指 `src`（tsc / oxlint / vite serve / vite-node 走源码，热更新）；`publishConfig` 在 `pnpm pack` 时覆盖为 `dist`；chat 的 `vite build` 用正则精确 alias 到 core `dist`（生产吃可发布产物）；`@spark-ui/core/style.css` 始终指 dist，chat 的 `predev` / `prebuild` 守护 dist 存在。`exports` / `publishConfig` 两边必须同时声明全部四项（`.` / `./style.css` / `./client` / `./react`），且 vite `lib.entry` 是对象形式的三入口——少一处就会出现「声明了入口但产物里没有」，`verify-pack` 断言。

**深路径禁令**：宿主只能走这四个声明入口，oxlint 禁止 `@spark-ui/core/src`、`/src/*`、`/dist`、`/dist/*`（绕过公共 API）。

**Spark UI 专项**：
- 白名单组件注册表只有一个入口：`spark-ui/packages/core/src/registry/componentRegistry.ts`，`desktopRegistry`（antd）与 `mobileRegistry`（antd-mobile）键集合必须完全一致且与 `ui-schema.schema.json` 的 `type` enum 一致；`scripts/check-registry.mjs` 机械校验。
- 每个白名单组件是**封装层**：`spark-ui/packages/core/src/components/desktop/{Type}.tsx` 与 `spark-ui/packages/core/src/components/mobile/{Type}.tsx`，对外 props 由契约决定，内部才使用 antd / antd-mobile。antd / antd-mobile 只允许出现在 `components/**` 与 `theme/**`。
- Schema Renderer 只渲染注册表内的 `type`；未知 `type` → `UnknownComponent` 占位 + `console.error('[spark-ui] …')`；每个组件 props 先经 Zod。
- 渲染器**不得**出现 `eval`、`new Function`、`dangerouslySetInnerHTML`、任意路径动态 `import()`。
- 包内 CSS 只允许 `--spark-*` 变量且带 fallback；不读宿主 CSS 变量、不 `getComputedStyle`；公共 d.ts 不得暴露 antd / antd-mobile 类型。
- 端型由宿主挂载 `SparkDeviceProvider` 一次性决定，组件内不各自判断。
- 安全边界分工（随包走 / 留在宿主）见 `spark-ui/packages/core/README.md`。
- 单元测试与被测源码同目录（`*.test.ts`，vitest）；`packages/core/tsconfig.build.json` 排除测试，`scripts/check-deps.mjs` 跳过测试文件。见 `coding-standard.md` §9。

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
├── spark-rooter-redis/                  # 可选：四个状态存储的 Redis 实现（spark.storage.type=redis）；只依赖平台模块，平台模块不得反向依赖它
└── examples/
    ├── demo-support/                    # 示例宿主侧 mock 用户上下文（DemoUserContext），纯 JDK
    ├── domains/{order,product,aftersale,refund}-service/   # @SparkTool 形态的示例领域；只依赖 spi + contracts + demo-support
    └── host-demo/                       # 独立 Maven 工程（parent = spring-boot-starter-parent，不在根 modules）：引入 starter 即可用的验收物
```

**依赖方向**（Maven `<dependency>` 即红线，`check-module-deps` 机械校验）：

```
spi ↛ 任何 com.sparkrooter；contracts → spi
runtime / registry / gateway → contracts, spi（三者之间只经对方 api 包接口 ToolSearchPort / ToolInvokePort / ConfirmationCoveragePolicy）
web-mvc → runtime, registry, gateway；starter → 全部平台模块
redis → runtime, gateway, spi, contracts, starter（可选模块；平台模块与 provider-starter 不得依赖它，也不得依赖 spring-data-redis）
provider-starter → **只有** spi + contracts + spring-boot-autoconfigure + spring-web + spring-aop
examples/domains/* → spi, contracts, demo-support（不依赖任何平台模块；互不 import，跨领域读订单只经 demo-support 的 OrderSnapshotProvider）
host-demo → starter + examples/domains/*（本地仓坐标）
平台模块（spi / contracts / runtime / registry / gateway）pom 禁 spring-boot-starter-web / starter-validation
examples/* 只在 parent pom 的 `examples` profile 里（默认激活），不得回到顶层 `<modules>`——别人 `mvn deploy -P '!examples'` 才能只发平台 artifact

**两种拓扑与 JDK 基线**（feat-provider-http-transport-20260912）：

| 形态 | 领域服务在哪 | Manifest 的 protocol | 引入的坐标 |
|---|---|---|---|
| 单体内嵌 | 与内核同进程 | `in-process` | `spark-rooter-spring-boot-starter` |
| 分布式微服务 | 独立进程（provider） | `http` | provider 侧 `spark-provider-spring-boot-starter`；hub 侧同上 |

JDK 基线不统一，因为 provider 装在别人的业务服务里：

| 模块 | release | 理由 |
|---|---|---|
| `spi` / `contracts` | **17** | 共享契约层取两边下限。provider 加载 21 字节码会 `UnsupportedClassVersionError` |
| `spark-provider-spring-boot-starter` | **17** | 企业存量大量停在 17 |
| runtime / registry / gateway / web-mvc / hub starter | 21 | hub 是本项目自己部署的进程 |

parent pom 的 compiler plugin 用 `<release>${maven.compiler.release}</release>`（属性驱动），子模块才能覆盖。`check-module-deps` 同时守 pom 里的 release 属性与**产物字节码 major ≤ 61**——两者会脱节（改 parent 配置、加未覆盖 release 的新模块），只查 pom 会漏。
平台模块（contracts / runtime / registry / gateway / web-mvc）源码禁 @Component / @Service / @Repository / @Configuration / @ComponentScan —— Bean 由 starter @Bean 装配，不依赖包扫描
```

模块内包结构：`api/`（跨模块公开接口）、`application/`（用例）、`domain/`（实体、规则）、`infra/`（默认实现、适配器、自检）。`domain/` 不依赖 Spring。Controller 只在 `web-mvc`。

**身份边界**：内核不识别用户。`Principal` / `userId` / `tenantId` 不得出现在平台模块源码；身份、权限、业务字段全在宿主工程（`SessionIdResolver` 产出会话键、`RunContextPropagator` 把宿主 ThreadLocal 带到工作线程、方法级切面做权限、`@SparkDefault` 预制业务默认值）。

## 3. 文件命名

| 端 | 类型 | 风格 | 示例 |
|---|---|---|---|
| 前端 | 组件 / 类 | PascalCase | `Timeline.tsx`（core 组件文件名 == 契约 type，官方组件名） |
| 前端 | Hook | `useCamelCase` | `useSparkRun` |
| 前端 | 模块 | camelCase | `sseClient.ts` |
| 后端 | 类 | PascalCase，后缀表职责 | `ToolRegistryController`、`RefundPreviewUseCase` |
| 后端 | 包 | 全小写 | `com.sparkrooter.gateway.infra` |
| 契约 | Schema | kebab-case | `ui-schema.schema.json` |

## 4. 红线（Red Lines）

任一触发即 MUST FIX：

1. `spark-ui/` 直接引用 `spark-rooter/` 文件，或反之。
2. 前端在 `spark-ui/packages/core/src/registry/componentRegistry.ts` 之外声明可被 UI Schema 引用的组件；宿主 import antd / antd-mobile，或走 `@spark-ui/core` 的 `src/*` / `dist/*` 深路径而不是四个声明入口（`.` / `./style.css` / `./client` / `./react`）。
3. 前端在 `spark-ui/apps/chat/src/app/router/` 与 `pages/` 之外声明路由或使用路由 hook 拼装路由表（页面组件内用 `useParams` / `Link` 允许）。
4. FSD 反向依赖或穿透 `index.ts`。
5. `spark-rooter-runtime`、`spark-rooter-registry`、`spark-rooter-gateway` 的 pom 依赖任何 `examples/domains/*` 模块（只有 `examples/host-demo` 可以）；平台模块 pom 出现 `spring-boot-starter-web`；平台模块源码出现 `@Component / @Service / @Repository / @Configuration`；`examples/domains/*` 依赖任何平台模块。
6. `spark-rooter-registry` 暴露转发调用的端点。
7. 后端 `domain/` 包 import `org.springframework.*`。
8. 跨端数据结构在 `.harness/contracts/` 中无对应 Schema。
9. `examples/domains/<a>` 引用 `com.sparkrooter.examples.<b>` 或依赖兄弟领域 artifact；领域屏（`infra/screen/`）直读领域数据而不是用 Gateway 输出。
11. 平台模块源码出现 `userId` / `tenantId` / `Principal` 标识符（身份归宿主）；`@SparkTool` 方法 / 类为 `final`、非 `public`、标在 `@Configuration` 类上（扫描器启动失败）。
10. `spark-ui/packages/core/src/components/**` 出现业务命名组件或 import antd / antd-mobile / react / 本包类型之外的模块（core 组件只能是官方组件映射）。
12. `spark-ui/packages/core/src/client/**` import `react` / `react-dom`、读 `import.meta.env`，或任何文件从 `client/` / `react/` 里 import `../index`（渲染层入口会拉进 antd，headless 层不得依赖渲染层）。`scripts/check-deps.mjs` 机械守护。
13. `spark-provider-spring-boot-starter` pom 依赖 `spring-ai-*` / `spark-rooter-{runtime,registry,gateway,web-mvc}` / `spring-boot-starter-web`（provider 不做规划、不绑宿主 web 栈选型）；或 `spi` / `contracts` / provider-starter 任一未 pin `release 17`（provider 宿主可能是 JDK 17）。`check-module-deps` 机械守护，含产物字节码检查。
14. `protocol=http` 的工具走 Gateway 之外的路径执行，或 `HttpToolTransport` 自行重试 / 自行校验 Schema（治理全留 `InvokeToolUseCase`，transport 只传数据）；远程超时（`REMOTE_TIMEOUT`）被当作可重试（结果未知，重试会造成重复副作用）。
15. hub 未配 `spark.providers.tokens.*` 却接受 `protocol=http` 注册（缺认证的正确含义是「不接受远程工具」，不是「不检查」）；或注册端点放行未出示令牌 / 令牌与 `provider.serviceName` 不匹配的请求。
