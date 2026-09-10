# Spark — 自然语言驱动的企业工具平台（全栈）

> 用户只说一句话，前端只发这句话，后端把它变成一次**可发现、可校验、可确认、可审计**的工具调用，再把结果变成一屏 UI。
> 后端是一个 **Spring Boot Starter**：任何 Java 服务加一个依赖、在方法上标一个注解，就能被前端用自然语言驱动。

```
「最近 5 单已发货的订单」 → order.list.search{status: SHIPPED, limit: 5} → Table
「第二个的物流」           → 会话记忆解析行号 → order.logistics.get → Card + Timeline
「申请售后」（没说哪单）   → 澄清屏：订单列表，每行一个「申请售后」按钮
「删除订单 10010」         → 详情前置 → 确认屏（后端签发令牌）→ 重校验 → 宿主权限切面 → 执行 → Result
```

## 仓库布局

| 目录 | 内容 | 技术栈 |
|---|---|---|
| [`spark-rooter/`](spark-rooter/) | 后端：平台模块 + `spark-rooter-spring-boot-starter` + `examples/`（四个示例领域、独立示例宿主 `host-demo`） | Java 21 / Spring Boot 3.5 / Spring AI 1.1 / Maven |
| [`spark-ui/`](spark-ui/) | 前端：`@spark-ui/core`（UI Schema 渲染引擎，可发包）+ `spark-chat`（对话应用） | React 19 / TypeScript strict / Vite / antd 6 / antd-mobile 5 / Zod / TanStack Query |
| [`.harness/contracts/`](.harness/contracts/) | 前后端唯一真源：9 个 JSON Schema 2020-12 + 27 个示例 | — |
| [`.harness/`](.harness/) | 工程 Harness：规则、Skill、契约校验、模块依赖红线、e2e、部署验证、change 记录 | Node 20 / pnpm |
| [`docs/`](docs/) | Harness 使用与编写指南 | — |

## 架构一句话

Spark UI 负责交互（只发自然语言，只渲染白名单组件）；Agent Runtime 负责理解与规划（决策面）；Tool Registry 负责能力发现与治理（控制面）；Tool Gateway 负责安全执行（执行面）；宿主服务的 `@SparkTool` 方法负责确定性业务执行。**内核不识别用户、权限、页面上下文**——这些全部留在宿主工程。

```
 spark-ui (chat / 任意页面嵌 @spark-ui/core)
   │ 自然语言 + SSE
 宿主 Spring Boot 服务 ──── 引入 spark-rooter-spring-boot-starter ────┐
   ├ 宿主拦截器 / 登录态 → SessionIdResolver / RunContextPropagator   │
   ├ runtime：路由 → 抽取 → 记忆补位 → 规划 → 编排 → 令牌 → 屏 / 澄清屏 │
   ├ registry：@SparkTool 推导 Manifest；按状态过滤（+ 宿主策略可选）   │
   ├ gateway：校验 → 幂等 → 经 Spring 代理调用 → 校验 → 脱敏 → 审计     │
   └ 宿主 @Service：@SparkTool 方法（宿主 @Aspect / @PreAuthorize 生效）│
```

详细图与运行链路见 [`.harness/wiki/architecture.md`](.harness/wiki/architecture.md)。

## 快速开始

前置：JDK 21、Node 20 + pnpm、Maven（或用仓内 `mvnw`）、本机 Google Chrome（仅前端 e2e 需要）。

```bash
# 1. 后端：平台 + 示例领域进本地仓，打包独立示例宿主
node .harness/scripts/mvn.mjs -q -B install -DskipTests
cd spark-rooter/examples/host-demo && mvn -q package -DskipTests && cd -
JAVA_HOME=~/.jenv/versions/21 java -jar spark-rooter/examples/host-demo/target/host-demo.jar
#   日志：spark-rooter: 14 tools registered from 6 beans；selfcheck 9 项 OK；SessionIdResolver demo WARN

# 2. 前端
pnpm -C spark-ui install && pnpm -C spark-ui run dev      # http://localhost:5173（vite 代理到 8080）

# 3. 试几句
#   看看我的订单 → 有什么商品 → 点「查看商品」→ 点卡片底部「返回列表」
#   查看订单 10002 的物流 → 申请售后（省略订单号，记忆补位）→ 确认
#   申请售后（新会话）→ 澄清屏点选
```

接真实模型（可选，不接则用确定性规则规划器）：只用环境变量，不写进任何文件。

```bash
export SPARK_LLM_BASE_URL=...  SPARK_LLM_API_KEY=...  SPARK_LLM_MODEL=...
```

## 把 spark 接进你自己的 Java 服务（三步）

1. `pom.xml` 引入 `com.sparkrooter:spark-rooter-spring-boot-starter`。
2. 任意 `@Service` 的 public 方法加 `@SparkTool`，参数 / 返回值用 record：

   ```java
   public record ListIn(
       @SparkParam(description = "按状态筛选", aliases = {"SHIPPED=已发货"}) Optional<Status> status,
       @SparkParam(description = "条数", unit = {"单", "条"}, min = 1, max = 50) @SparkDefault("20") Integer limit) {}

   @SparkTool(id = "order.list.search", version = "1.1.0", domain = "order", name = "搜索订单",
       description = "按状态筛选订单…", clarifiesEntity = EntityType.ORDER)
   public ListOut list(ListIn in) { … }
   ```

   有副作用的方法加 `@SparkRisk(level = HIGH, confirmation = REQUIRED, idempotency = REQUIRED, …)` 与 `@SparkPrerequisite({"order.detail.get"})`，运行时会先走只读前置、出确认屏、用户确认后重校验再执行。Manifest（inputSchema / outputSchema / risk / execution）由注解与 record 自动推导并经契约校验。
3. 接自己的身份：实现 `RunContextPropagator` 把登录态 ThreadLocal 带到 spark 工作线程；**生产必须**实现 `SessionIdResolver`（会话隔离键绑定登录态；默认实现等于 conversationId，只适合演示，启动会 WARN）。权限用**方法级**切面——Controller 拦截器对 spark 的代理调用无效（示例宿主用正反两条路径演示）。

完整示例：[`spark-rooter/examples/host-demo`](spark-rooter/examples/host-demo/README.md)。前端接入：直接复用 `spark-chat` 应用，或在自己的页面 `import { SchemaRenderer } from '@spark-ui/core'` 渲染后端下发的 UI Schema；要带登录态就给 `AgentChatPanel` 注入自己的 `fetch`。

## 安全边界（摘要）

- 前端只发自然语言；按钮、输入框、行内指令都原样成为一条新消息。不发页面上下文、身份、业务字段。
- 只有 `@SparkParam` 显式开放的参数进 inputSchema；值由确定性抽取（实体 ID / 枚举别名 / 数量 / 相对时间）与 `@SparkDefault` 填，模型只能在候选工具内选、只能填 schema 内字段、值必须过 JSON Schema、实体 ID 必须来自用户原话。
- 高风险工具必须经后端签发的一次性令牌确认；令牌绑定 `runId + actionId + 参数摘要 + conversationId + sessionId`；确认后由领域重校验策略再执行。
- Gateway 经 Spring 代理调用宿主方法，宿主的方法级切面照常生效；每次调用一条审计（不含参数原文）。
- 会话记忆只存 ID、只在成功终态写入；日志不记用户原文、不记模型网关地址 / 密钥 / 模型名（Harness 有形态扫描门禁）。

全文见 [`.harness/rules/agent-safety.md`](.harness/rules/agent-safety.md)。

## 质量门禁

```bash
pnpm -C .harness run ci          # check-rename → check-contracts → check-module-deps → check-seed → spark-ui ci → spark-rooter install → host-demo 离线打包
pnpm -C .harness run doctor      # Harness 自检（含 change 目录密钥形态扫描）
SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh     # 145 条端到端断言（含记忆 TTL 二次启动）
SPARK_PORT=8091 bash .harness/scripts/deploy-verify.sh   # 部署验证 12 条 + 产物冻结
SPARK_FRONT_BASE=http://localhost:5199 node .harness/scripts/e2e-frontend.mjs   # 37 条 headless Chrome 断言
```

机械红线：平台模块不得依赖 `spring-boot-starter-web`、不得用 Spring 组件注解（Bean 全部由 starter 装配）、不得出现 `userId / tenantId / Principal`；示例领域不得依赖平台模块、互不 import；前端 `apps/chat` 不得直接 import antd，`@spark-ui/core` 组件只能是官方组件映射；跨端数据结构必须先有契约。

## 开发流程

本仓库按 Harness 的 8 阶段流程演进（需求分析 → 需求评审 → 编码 → 编码评审 → 推送 → CI → 部署验证 → 用户确认），每个 change 在 [`.harness/changes/`](.harness/changes/) 留下 spec、评审、编码报告与冻结产物。最近一次 change：[`refactor-spark-embedded-starter-20260909`](.harness/changes/refactor-spark-embedded-starter-20260909/summary.md)（Starter 化、方法级 `@SparkTool`、内核去身份、前端只发自然语言、会话记忆与澄清屏）。

## 当前限制（已知）

- 只有进程内（embedded）模式；远程工具 / 服务发现只预留了 `ToolTransport` / `ToolProviderDiscovery` 端口。
- 存储全部内存实现（带 TTL），多实例部署需自行替换为 Redis 等（端口都是 `@ConditionalOnMissingBean`）。
- 领域路由关键词表硬编码 4 个领域（order / product / aftersale / refund）；宿主新增领域目前只能经 LLM 分类到达。
- 动词表、枚举别名、澄清文案全中文，无 i18n。
