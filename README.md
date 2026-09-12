<div align="center">

# Spark

**给任意 Java 服务装上自然语言入口。**

给方法标一个注解，用户就能用自然语言调用它——选工具、填参数、弹确认、渲染界面，全自动。

[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
![Java 17+](https://img.shields.io/badge/java-17%2B-orange)
![Spring Boot 3.5](https://img.shields.io/badge/spring%20boot-3.5-brightgreen)
![React 19](https://img.shields.io/badge/react-19-61dafb)

[快速开始](#快速开始) · [接入你的服务](#接入你的服务) · [架构](#架构) · [安全模型](#安全模型) · [示例](spark-rooter/examples/)

</div>

---

```
「最近 5 单已发货的订单」   → order.list.search{status: SHIPPED, limit: 5}     → 表格
「第二个的物流」             → 从上一轮列表取第二行订单号 → order.logistics.get → 卡片 + 时间线
「申请售后」（没说哪一单）   → 先给一张订单列表让用户点选
「删除订单 10010」           → 查详情 → 确认卡片（一次性令牌）→ 重校验 → 权限切面 → 执行
```

用户在聊天框里说人话，你的 `@SparkTool` 方法被调用，结果以表格或卡片渲染出来。你不写 prompt、不写意图分类、不写参数解析。

## 目录

- [为什么是这样设计](#为什么是这样设计)
- [功能](#功能)
- [快速开始](#快速开始)
- [接入你的服务](#接入你的服务)
- [两种部署拓扑](#两种部署拓扑)
- [架构](#架构)
- [Docker 部署](#docker-部署)
- [安全模型](#安全模型)
- [开发与质量门禁](#开发与质量门禁)
- [已知限制](#已知限制)
- [许可](#许可)

## 为什么是这样设计

三条约束贯穿全部代码，理解它们就理解了这个项目：

**内核不含业务词汇。** 工具的用途、同义说法、参数含义、实体类型全写在注解上，模型据此决策。新增一个领域不改内核一行代码——所以 `order` / `refund` 这些词只出现在 `examples/`，平台模块里出现即门禁失败。

**模型负责理解，代码负责核实。** 模型只能在候选工具里选、只能填 schema 声明的字段、值必须过 JSON Schema；标了 `entity` 的 ID 必须原样出自用户原话或会话上下文，否则一律走澄清。模型编不出一个能执行的调用。

**前端只发自然语言。** 输入框、示例按钮、表格行按钮、卡片按钮，点了都是一条新消息。不发页面上下文、不发用户信息、不发业务字段——所以前端不可能成为越权的路径。

## 功能

| | |
|---|---|
| **注解声明工具** | `@SparkTool` + record 参数，inputSchema / outputSchema / 风险等级 / 幂等策略自动推导并按 JSON Schema 契约校验 |
| **多轮会话** | 会话记忆记住上一轮的实体与列表，支持「第二个」「最后一个」这类指代；缺实体时给澄清列表让用户点选 |
| **高风险确认** | 需确认的工具先走只读前置步骤 → 确认卡片 → 后端签发一次性令牌 → 领域重校验 → 执行 |
| **生成式 UI** | 后端下发 UI Schema，前端只渲染 5 个白名单组件（Form / Card / Table / Result / Timeline），antd 与 antd-mobile 双端 |
| **单体与微服务** | 领域服务可与内核同进程，也可作为独立进程经 HTTP 接入；对前端完全透明 |
| **身份留在宿主** | 内核不认识用户。登录态、权限、租户全由宿主通过两个接口和方法级切面接入 |
| **前端可嵌任意页面** | `@spark-ui/core` 分三个入口：headless 层零框架依赖，渲染层可选，自带 UI 的宿主只装 headless |

## 快速开始

需要 **JDK 21**（跑 hub）、**Node 20 + pnpm**、Maven（或用仓内 `mvnw`）。前端 e2e 用 Playwright 自带 chromium，不依赖本机浏览器。

```bash
# 后端：装进本地 Maven 仓，打包示例宿主
node .harness/scripts/mvn.mjs -q -B install -DskipTests
cd spark-rooter/examples/host-demo && mvn -q package -DskipTests && cd -
java -jar spark-rooter/examples/host-demo/target/host-demo.jar
# 日志应出现 "spark-rooter: 14 tools registered from 6 beans" 与 9 项自检 OK

# 前端
pnpm -C spark-ui install
pnpm -C spark-ui run dev        # http://localhost:5173，代理到 8080
```

试几句：「看看我的订单」→ 点某行「查看物流」→「有什么商品」→ 点「查看商品」→ 点卡片底部「返回列表」。直接说「申请售后」会得到一张订单列表让你选。

### 配置模型

**模型是必需的**：意图理解、工具选择、参数填写全靠它。没配置时所有请求返回「未配置模型，无法理解请求」——不做规则兜底。任何 OpenAI 兼容接口都行。

只走环境变量，不要写进任何文件（`application.yml` 已用 `${SPARK_LLM_*:}` 预留占位）：

```bash
export SPARK_LLM_BASE_URL=https://your-openai-compatible-endpoint/v1   # 以 /v{n} 结尾时不再拼一层 /v1
export SPARK_LLM_API_KEY=...
export SPARK_LLM_MODEL=...
```

建议用响应快的小模型：每轮至少一次规划调用，慢模型会明显拖长等待。

> **没有模型也能跑完整验收**：示例宿主在 `e2e` profile 下装配确定性假规划器（`FakeLlmPlanner`，只在测试 profile 存在），产出的计划仍要过 `PlanValidator` 全部校验——验的依然是校验边界、编排、网关与领域实现。

## 接入你的服务

### 1. 加依赖

```xml
<dependency>
  <groupId>com.sparkrooter</groupId>
  <artifactId>spark-rooter-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 2. 标注方法

```java
@Service
public class OrderTools {

  public record ListIn(
      @SparkParam(description = "按状态筛选", aliases = {"SHIPPED=已发货"}) Optional<Status> status,
      @SparkParam(description = "条数", unit = {"单", "条"}, min = 1, max = 50) @SparkDefault("20") Integer limit) {}

  public record ListOut(List<Item> items, int total) {}

  @SparkTool(id = "order.list.search", version = "1.1.0", domain = "order", name = "搜索订单",
      description = "按状态筛选订单", clarifiesEntity = EntityType.ORDER)
  public ListOut list(ListIn in) { ... }

  @SparkTool(id = "order.delete", version = "1.0.0", domain = "order", name = "删除订单", description = "软删除订单")
  @SparkRisk(level = HIGH, confirmation = REQUIRED, idempotency = REQUIRED, sideEffect = true, reversible = false)
  @SparkPrerequisite({"order.detail.get"})
  public DeleteOut delete(OrderIdIn in) { ... }
}
```

只有标了 `@SparkParam` 的字段会进 inputSchema。有副作用的方法加 `@SparkRisk` 与 `@SparkPrerequisite`，运行时会先执行前置只读步骤、弹确认、确认后重校验再执行。

### 3. 接身份

内核不认识用户，这部分在宿主：

- 实现 **`RunContextPropagator`**，把登录态 ThreadLocal 带到 spark 的工作线程。
- 实现 **`SessionIdResolver`**，把请求映射成会话隔离键。**没有这个 Bean 时 starter 拒绝启动**；只有本地演示才设 `spark.runtime.demo-session-resolver=true` 放行演示实现（无隔离，启动 WARN）。
- 权限用**方法级切面**（`@Aspect`、`@PreAuthorize`）。Controller 拦截器拦不住 spark 的调用——spark 不经过 Controller。示例宿主里有正反两条路径可对照。

完整示例见 [`examples/host-demo`](spark-rooter/examples/host-demo/README.md)。

### 4. 前端

直接用 `spark-chat`，或在自己页面里嵌 `@spark-ui/core`。它有三个入口，按需取用：

| 入口 | 内容 | 依赖 |
|---|---|---|
| `@spark-ui/core/client` | 契约投影、SSE、事件归约、状态容器 | **仅 zod**（零框架） |
| `@spark-ui/core/react` | `useSparkRun`（订阅 SSE、提交确认） | + react |
| `@spark-ui/core` | `SchemaRenderer` / 五个白名单组件 / 主题与端型 Provider | + react、antd、antd-mobile |

自带渲染或非 React 工程只装 `./client`，不会被拖进 React 或 antd。详见 [`packages/core/README.md`](spark-ui/packages/core/README.md)。

## 两种部署拓扑

同一个 hub 可同时承载两种形态，Gateway 按 Manifest 的 `protocol` 分派。**对前端完全透明**——前端不知道工具在哪个进程。

| | 单体内嵌 | 分布式微服务 |
|---|---|---|
| 领域服务 | 与内核同进程 | 独立进程（provider） |
| `protocol` | `in-process` | `http` + provider 坐标 |
| provider 侧坐标 | — | `spark-provider-spring-boot-starter` |
| JDK 下限 | 21 | **17** |
| 示例 | [`host-demo`](spark-rooter/examples/host-demo/) | [`provider-demo`](spark-rooter/examples/provider-demo/README.md) |

微服务形态下 provider 只做「声明工具 + 执行工具」：**不含 Agent Runtime、Registry、Gateway、LLM 客户端**。规划与治理全在 hub。

```yaml
# provider 侧：一个业务服务变成 spark 的能力提供方
spark:
  provider:
    hub-url: http://spark-hub.internal:8080
    service-name: inventory-service     # 与 hub 的密钥绑定
    base-url: http://inventory.internal:8080
    token: ${SPARK_PROVIDER_TOKEN}      # 缺失则拒绝启动
```

```yaml
# hub 侧：不配 = 不接受远程工具（不是"不检查"）
spark:
  providers:
    tokens:
      inventory-service: ${INVENTORY_TOKEN}
```

provider 启动后自动把 Manifest 推给 hub，新增工具无需改 hub 配置。跨进程的安全边界（双向认证、两层幂等、发送端脱敏、重试判据）见 [`agent-safety.md` §8](.harness/rules/agent-safety.md)。

## 架构

```
 浏览器（spark-chat，或任何嵌了 @spark-ui/core 的页面）
   │  只发自然语言；SSE 收事件（10 种）；渲染 UI Schema
   ▼
 你的 Spring Boot 服务  ←── spark-rooter-spring-boot-starter
   ├─ 宿主拦截器 / 登录态 ──► SessionIdResolver、RunContextPropagator
   ├─ runtime   路由 → 抽取 → 记忆补位 → 规划 → 编排 → 令牌 → 屏 / 澄清屏
   ├─ registry  从 @SparkTool 推导 Manifest；按状态过滤（可接宿主策略）
   ├─ gateway   校验 → 策略 → 幂等 → 传输 → 校验 → 脱敏 → 审计
   │              ├─ InProcessToolTransport → 你的 @SparkTool 方法（切面照常生效）
   │              └─ HttpToolTransport ──────► 远程 provider 进程
   └─ 你的 @Service
```

四个平面职责固定：**Agent Runtime** 决策、**Tool Registry** 能力发现（控制面）、**Tool Gateway** 安全执行（执行面）、**领域服务** 确定性业务执行。平台模块互相只经接口依赖，都不含 Controller 与 Spring 组件注解，由 starter 统一装配。

| 目录 | 内容 | 技术栈 |
|---|---|---|
| [`spark-rooter/`](spark-rooter/) | 后端：6 个平台模块、2 个 starter、`examples/` | Java 17/21 · Spring Boot 3.5 · Spring AI 1.1 |
| [`spark-ui/`](spark-ui/) | 前端：`@spark-ui/core`（三入口）、`spark-chat` | React 19 · TypeScript · Vite · antd 6 / antd-mobile 5 |
| [`.harness/contracts/`](.harness/contracts/) | 前后端共用的 **9 个** JSON Schema 及示例；两端代码都是它的投影 | JSON Schema 2020-12 |
| [`.harness/`](.harness/) | 工程规则、契约校验、依赖红线、e2e、部署验证、变更记录 | Node 20 |

详见 [`architecture.md`](.harness/wiki/architecture.md)。

## Docker 部署

仓库根的 `Dockerfile` 把前端静态资源与示例宿主打进一个镜像：单进程、8080 端口、无外部依赖（存储全在内存），适合本机演示。

```bash
docker build -t spark-demo .
docker run -d --name spark-demo -p 8080:8080 \
  -e SPARK_LLM_BASE_URL=... -e SPARK_LLM_API_KEY=... -e SPARK_LLM_MODEL=... \
  spark-demo
# http://localhost:8080
```

首次构建约 6–10 分钟（前端 pnpm install + 后端 Maven 拉依赖），之后有缓存。构建期不需要本机装 JDK / Node / Maven。

| 变量 | 说明 |
|---|---|
| `SPARK_LLM_BASE_URL` / `SPARK_LLM_API_KEY` / `SPARK_LLM_MODEL` | **必填**。用 `-e` 传入，不要写进镜像 |
| `JAVA_TOOL_OPTIONS` | 默认 `-Xmx300m -XX:+UseSerialGC` |
| `SPARK_SELFCHECK_ENABLED=false` | 关闭启动自检，启动更快 |

> 镜像里的示例宿主显式打开 `spark.runtime.demo-session-resolver=true`（会话即 conversationId，**无用户隔离**），只适合演示，不要对公网开放。

## 安全模型

- **前端只发自然语言**。不发页面上下文、用户信息、业务字段。
- **模型输出必须过校验**。只能选候选里的工具，只能填 schema 里的字段，值要过 JSON Schema；标了 `entity` 的 ID 必须原样出自用户原话或会话上下文，否则走澄清——不会拿编造的 ID 去执行。
- **高风险操作必须确认**。后端签发一次性令牌，绑定 runId / actionId / 参数摘要 / conversationId / sessionId。确认后由领域自己的重校验逻辑再看一遍才执行。
- **执行面单一入口**。Gateway 顺序固定：寻址 → 输入校验 → 访问策略 → 幂等 → 调用 → 输出校验 → 脱敏 → 审计。远程工具走同一条管线，transport 只负责传数据。
- **跨进程超时不重试**。进程内超时可真正中断，跨进程只能中断本地等待——远端可能已执行成功。重试的判据是「有没有碰到工具」，不是「错误严不严重」。
- **审计不含参数原文**，只记摘要。日志不含用户原文、模型网关地址、密钥、模型名。

全文见 [`agent-safety.md`](.harness/rules/agent-safety.md)。

## 开发与质量门禁

```bash
pnpm -C .harness run ci        # 改名 / 契约 / 模块依赖 / 种子 / 日志断言 / shell / 前端 ci / 后端 install / host-demo 离线打包
pnpm -C .harness run doctor    # Harness 自检

SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh    # 后端端到端 161 条
SPARK_PORT=8091 bash .harness/scripts/e2e-frontend.sh   # 前端端到端 7 个用例
SPARK_PORT=8091 bash .harness/scripts/deploy-verify.sh  # 部署验证 12 条
bash .harness/scripts/e2e-provider.sh                   # 跨服务（hub + provider 双进程）15 条
```

前端 e2e 首次需 `pnpm -C spark-ui run e2e:install` 下载 chromium（约 150 MB，一次性）。失败时 Playwright 的 HTML 报告与 trace 落在当前 change 的 `deployment/e2e-frontend/`。

脚本强制的红线（违反即构建失败）：

- 平台模块不依赖 `spring-boot-starter-web`、不用 Spring 组件注解、源码不出现 `userId` / `tenantId` / `Principal`
- provider-starter 不依赖 Spring AI 与任何 hub 模块；`spi` / `contracts` / provider 的**产物字节码** major ≤ 61（JDK 17）
- 工具实现不得自行重试（重试策略由 Gateway 按 Manifest 统一决定）
- 示例领域不依赖平台模块、互不 import
- 前端 `apps/chat` 不直接 import antd；`@spark-ui/core/client` 不 import react、不读 `import.meta.env`
- 跨端数据结构必须先有契约

每个需求走 8 个阶段（需求分析 → 需求评审 → 编码 → 编码评审 → 推送 → CI → 部署验证 → 用户确认），产物在 [`.harness/changes/`](.harness/changes/)。

## 已知限制

- **全内存状态**。Run、令牌、幂等记录、会话记忆、工具注册表默认都在进程内存里，重启即丢，不支持水平扩展。生产要替换 `RunRepository` / `ConfirmationTokenStore` / `IdempotencyStore` / `ToolRegistryRepository` / `ConversationMemory`（定义同类型 Bean 即覆盖）。
- **provider 幂等默认进程内**。多实例部署时 hub 重试可能落到另一实例而绕过缓存；要强一致需替换 `ProviderIdempotencyStore` 为共享存储。
- **provider 重启漏推需人工介入**。hub 侧的对账补偿未实现。
- **不做服务发现**。provider 坐标用配置化 base URL；接注册中心需自行实现 `ProviderEndpointResolver`。
- **不做 MCP**。契约保留 `protocol: mcp` 枚举值但注册时拒绝。
- **只支持 Java + 注解声明工具**。其他技术栈的服务需自行按 HTTP 协议实现 provider 端点。
- **本仓不发包**。需要 npm / Maven 产物者自取源码构建。

## 许可

[MIT](LICENSE)
