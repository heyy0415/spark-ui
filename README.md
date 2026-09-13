<div align="center">

# Spark

**给任意 Java 服务装上自然语言入口。**

方法上标一个注解，用户就能用自然语言调用它。选工具、填参数、弹确认、渲染界面，都不用你写。

[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
![Java 17+](https://img.shields.io/badge/java-17%2B-orange)
![Spring Boot 3.5](https://img.shields.io/badge/spring%20boot-3.5-brightgreen)
![React 19](https://img.shields.io/badge/react-19-61dafb)

[快速开始](#快速开始) · [接入你的服务](#接入你的服务) · [部署到生产](#部署到生产) · [架构](#架构) · [安全模型](#安全模型)

</div>

---

```
「最近 5 单已发货的订单」   → order.list.search{status: SHIPPED, limit: 5}     → 表格
「第二个的物流」             → 从上一轮列表取第二行订单号 → order.logistics.get → 卡片 + 时间线
「申请售后」（没说哪一单）   → 先给一张订单列表让用户点选
「删除订单 10010」           → 查详情 → 确认卡片（一次性令牌）→ 重校验 → 权限切面 → 执行
```

用户在聊天框里说人话，你的 `@SparkTool` 方法被调用，结果以表格或卡片渲染出来。不写 prompt，不写意图分类，不写参数解析。

## 目录

- [它是怎么想的](#它是怎么想的)
- [能做什么](#能做什么)
- [快速开始](#快速开始)
- [接入你的服务](#接入你的服务)
- [两种拓扑](#两种拓扑)
- [部署到生产](#部署到生产)
- [架构](#架构)
- [可观测性](#可观测性)
- [安全模型](#安全模型)
- [自己发包](#自己发包)
- [开发与质量门禁](#开发与质量门禁)
- [已知限制](#已知限制)
- [许可](#许可)

## 它是怎么想的

三条约束贯穿全部代码，理解它们就理解了这个项目。

**内核不含业务词汇。** 工具的用途、同义说法、参数含义、实体类型全写在注解上，模型据此决策。新增一个领域不改内核一行代码——所以 `order`、`refund` 这些词只出现在 `examples/`，平台模块里出现即门禁失败。

**模型负责理解，代码负责核实。** 模型只能在候选工具里选、只能填 schema 声明的字段、值必须过 JSON Schema；标了 `entity` 的 ID 必须原样出自用户原话或会话上下文，否则一律走澄清。模型编不出一个能执行的调用。

**前端只发自然语言。** 输入框、示例按钮、表格行按钮、卡片按钮，点了都是一条新消息。不发页面上下文、不发用户信息、不发业务字段，所以前端不可能成为越权的路径。

## 能做什么

| | |
|---|---|
| **注解声明工具** | `@SparkTool` + record 参数，inputSchema / outputSchema / 风险等级 / 幂等策略自动推导并按 JSON Schema 契约校验 |
| **多轮会话** | 记住上一轮的实体与列表，支持「第二个」「最后一个」这类指代；缺实体时给澄清列表让用户点选 |
| **高风险确认** | 需确认的工具先走只读前置步骤 → 确认卡片 → 后端签发一次性令牌 → 领域重校验 → 执行 |
| **生成式 UI** | 后端下发 UI Schema，前端只渲染 5 个白名单组件（Form / Card / Table / Result / Timeline），antd 与 antd-mobile 双端 |
| **单体与微服务** | 领域服务可与内核同进程，也可作为独立进程经 HTTP 接入；对前端透明 |
| **多副本** | 四个状态存储可换成 Redis，hub 任意扩副本；在 A 发起、在 B 确认 |
| **身份留在宿主** | 内核不认识用户。登录态、权限、租户由宿主通过两个接口和方法级切面接入 |
| **可观测与自保** | 7 个 Micrometer 指标（标签低基数）、readiness 探针、计划步数与单会话并发上限、审计与埋点失败不拖垮业务 |
| **前端可嵌任意页面** | `@spark-ui/core` 分三个入口：headless 层只依赖 zod，渲染层可选 |

## 快速开始

需要 **JDK 21**（跑 hub）、**Node 20 + pnpm 10**、Maven（或用仓内 `mvnw`）。Redis 可选。

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

**模型是必需的**：意图理解、工具选择、参数填写全靠它。没配置时所有请求返回「未配置模型，无法理解请求」，不做规则兜底。任何 OpenAI 兼容接口都行。

最省事的方式是环境变量：

```bash
export SPARK_LLM_BASE_URL=https://your-openai-compatible-endpoint/v1   # 以 /v{n} 结尾时不再拼一层 /v1
export SPARK_LLM_API_KEY=...
export SPARK_LLM_MODEL=...
```

建议用响应快的小模型。每轮至少一次规划调用，慢模型会明显拖长等待。

三项的性质不一样，配置方式也该不一样。`api-key` 是密钥，另外两项不是：

| | 是密钥？ | 放哪里 |
|---|---|---|
| `spark.llm.base-url` | 否 | `application.yml` / 配置中心都行 |
| `spark.llm.model` | 否 | 同上，换模型不必重新部署 |
| `spark.llm.api-key` | **是** | 只走环境变量或密钥管理，不要提交 |

三项都是属性优先、环境变量回落。常见写法是非密钥项进 yml，密钥项留占位：

```yaml
spark:
  llm:
    base-url: https://your-endpoint/v1
    model: your-fast-model
    api-key: ${SPARK_LLM_API_KEY:}
```

> 为什么显式兼容 `SPARK_LLM_*`：Spring 的宽松绑定把 `spark.llm.base-url` 映射为 `SPARK_LLM_BASEURL`，不认 `BASE_URL` 里的下划线。不做这层兼容，按直觉 `export SPARK_LLM_BASE_URL=...` 会静默失效——只有一行启动 WARN，请求全部失败，而人以为自己配对了。

> **没有模型也能跑完整验收**：示例宿主在 `e2e` profile 下装配一个确定性的假规划器（`FakeLlmPlanner`，只在测试 profile 存在），产出的计划仍要过 `PlanValidator` 全部校验。验的依然是校验边界、编排、网关与领域实现，只是不验模型理解得对不对。

## 接入你的服务

### 1. 加依赖

```xml
<dependency>
  <groupId>com.sparkrooter</groupId>
  <artifactId>spark-rooter-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

本仓库不发包，先 `mvn install` 到本地仓或发到你的私服（见[自己发包](#自己发包)）。

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

只有标了 `@SparkParam` 的字段会进 inputSchema。有副作用的方法加 `@SparkRisk` 与 `@SparkPrerequisite`，运行时会先执行前置只读步骤、弹确认、确认后重校验再执行。需确认的工具还要提供 `ScreenBuilder`（确认屏长什么样）和 `ConfirmationRecheck`（确认时再查一次什么、什么情况拒绝），缺一个启动就报错——高风险操作不给兜底屏。

### 3. 接身份

内核不认识用户，这部分在宿主：

- 实现 **`SessionIdResolver`**，把请求映射成会话隔离键。**没有这个 Bean 时 starter 拒绝启动**；只有本地演示才设 `spark.runtime.demo-session-resolver=true` 放行演示实现。
- 实现 **`RunContextPropagator`**，把登录态 ThreadLocal 带到 spark 的工作线程。
- 权限用**方法级切面**（`@Aspect`、`@PreAuthorize`）。Controller 拦截器拦不住 spark 的调用——spark 不经过 Controller。示例宿主里有正反两条路径可对照。

完整示例见 [`examples/host-demo`](spark-rooter/examples/host-demo/README.md)。

### 4. 前端

直接用 `spark-chat`，或在自己页面里嵌 `@spark-ui/core`。三个入口按需取：

| 入口 | 内容 | 依赖 |
|---|---|---|
| `@spark-ui/core/client` | 契约投影、SSE、事件归约、状态容器 | 仅 zod |
| `@spark-ui/core/react` | `useSparkRun`（订阅 SSE、提交确认） | + react |
| `@spark-ui/core` | `SchemaRenderer` / 五个白名单组件 / 主题与端型 Provider | + react、antd、antd-mobile |

自带渲染或非 React 工程只装 `./client`，不会被拖进 React 或 antd。详见 [`packages/core/README.md`](spark-ui/packages/core/README.md)。

## 两种拓扑

同一个 hub 可同时承载两种形态，Gateway 按 Manifest 的 `protocol` 分派。前端不知道工具在哪个进程。

| | 单体内嵌 | 分布式微服务 |
|---|---|---|
| 领域服务 | 与内核同进程 | 独立进程（provider） |
| `protocol` | `in-process` | `http` + provider 坐标 |
| provider 侧坐标 | — | `spark-provider-spring-boot-starter` |
| JDK 下限 | 21 | **17** |
| 示例 | [`host-demo`](spark-rooter/examples/host-demo/) | [`provider-demo`](spark-rooter/examples/provider-demo/README.md) |

微服务形态下 provider 只做「声明工具 + 执行工具」，不含 Agent Runtime、Registry、Gateway、LLM 客户端。规划与治理全在 hub。

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

## 部署到生产

这一节讲的是 hub。provider 是你自己的业务服务，按你原来的方式部署就行。

### 单副本还是多副本

hub 有四份运行时状态：进行中的 Run、确认令牌、幂等记录、会话记忆。默认它们都在进程内存里——单副本够用，重启即丢，写操作最多重复一次（客户端重试时）。

要起多个副本，四份状态必须共享。否则写操作在 A 副本 claim、重试落到 B → 重复执行；令牌在 A 签发、确认请求到 B → 找不到。解法是引 Redis 模块：

```xml
<dependency>
  <groupId>com.sparkrooter</groupId>
  <artifactId>spark-rooter-redis</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
spark:
  storage:
    type: redis
spring:
  data:
    redis:
      host: redis.internal
      port: 6379
```

之后 hub 想起几个起几个，不需要粘性路由。`e2e-multi-instance.sh` 用两个 hub 共享一个 Redis 验证这条链路：在 A 发起退款、在 B 确认、回 A 重放被拒、跨副本幂等不重复执行、A 写的会话记忆 B 能读到。

几个要知道的点：

- 配了 `spark.storage.type=redis` 但 Redis 模块没引或连不上，启动会 WARN 并回落内存。**多副本下这是数据错误不是降级**，看到这行 WARN 就别接流量。
- `spark.storage.redis.claim-ttl`（默认 5 分钟）必须大于任何工具的 `timeoutMs`，否则幂等占位在 owner 执行完之前过期，等待方会重新执行。
- Redis 挂了 hub 不降级到内存，请求直接 500，`/actuator/health` 里 Redis 组件 DOWN。宁可拒绝服务，不要两个副本各持一份状态却以为在共享。
- 用户原话不进 Redis。Run 快照刻意不带 `message` 字段，与日志红线同一口径。

### 健康探针

宿主装了 actuator 时，starter 注册一个 `sparkRooter` 健康指示器：模型没配 → DOWN，否则 UP。把它加进 readiness 组，模型没配的 Pod 就不会接流量：

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
      group:
        readiness:
          include: readinessState,sparkRooter
```

两条注意：

- **liveness 别打根 `/actuator/health`**。根端点聚合全部指示器，模型没配时它也 DOWN——那是「不该接流量」，不是「进程坏了」，用根端点会让容器被反复重启。Dockerfile 里的 HEALTHCHECK 已经改打 `/actuator/health/liveness`。
- **熔断不影响 readiness**。LLM 熔断器 OPEN 时指示器仍报 UP，只在 detail 里标 `circuit=open`。熔断器靠真实请求恢复（OPEN → HALF_OPEN 只在有请求探测时发生），摘掉流量它就永远恢复不了。

### 优雅停机与请求上限

示例宿主的 `application.yml` 已配好，生产照抄：

```yaml
server:
  shutdown: graceful          # SIGTERM 后等在飞的 Run / SSE 收尾
  tomcat:
    max-http-form-post-size: 64KB
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

`intent-request.message` 契约已限 2000 字符，请求体上限主要防非契约路径。CORS 由宿主或网关决定，starter 不替你配。

### 客户端断开

用户刷新页面或关掉标签，SSE 断了，但 Run 还在跑。编排器在每个**只读步骤**前检查连接状态，已断则终止（没人看的结果不值得再调一次工具）；**写步骤照跑**，不留半截的写链；确认后的执行不检查（用户已明确确认）。

### 容量

默认线程池 8 核心 + 32 队列（编排）、8 + 64（工具）、单会话在飞上限 4。这几个数压测过，不是拍的：

- 独立会话 32 并发 0 拒绝、p99 55ms；64 并发从第 8 个请求开始拒绝，拒绝毫秒级返回，没有一条挂到超时
- 同会话 4 并发 0 拒绝，8 并发开始出现 `session busy`
- 128 并发下拒绝全部来自编排池，工具池从未成为瓶颈

那是 fake planner 下 hub 自身的容量。接真模型后一次规划 5–70s，一个编排线程会被占几十秒，**约 40 个并发对话是单副本的实际上限**。要更多就水平扩，别调大队列——那只是把拒绝延后成超时。压测脚本是 `.harness/scripts/load-test.mjs`，报告在最近一个 change 的 `deployment/load_test.md`。

### 密钥

生产不要用 `.env` 文件：明文落盘、容易误提交、轮换要重新部署。按基础设施选：

| 部署方式 | 做法 |
|---|---|
| 本地开发 | shell profile（`~/.zshrc`），不在仓库目录里 |
| Docker | `-e` 传入或 `--env-file`（文件权限 600、不进镜像层） |
| K8s | Secret 挂成环境变量；生产开 etcd 静态加密 |
| 有密钥管理系统 | Vault / 云 KMS 签发短期凭据，自动轮换 |
| Spring 配置中心 | 非密钥项放配置中心，密钥项仍走上面任一种 |

`.gitignore` 已经忽略 `.env` 和 `.env.*`（放行 `.env.example`）。

### Docker

仓库根的 `Dockerfile` 把前端静态资源与示例宿主打进一个镜像。单进程、8080 端口、存储全在内存，适合本机演示。

```bash
docker build -t spark-demo .
docker run -d --name spark-demo -p 8080:8080 \
  -e SPARK_LLM_BASE_URL=... -e SPARK_LLM_API_KEY=... -e SPARK_LLM_MODEL=... \
  spark-demo
```

首次构建约 6–10 分钟。镜像里的示例宿主开了 `demo-session-resolver`（无用户隔离），只适合演示，不要对公网开放。

## 架构

```
 浏览器（spark-chat，或任何嵌了 @spark-ui/core 的页面）
   │  只发自然语言；SSE 收事件（10 种）；渲染 UI Schema
   ▼
 你的 Spring Boot 服务  ←── spark-rooter-spring-boot-starter
   ├─ 宿主拦截器 / 登录态 ──► SessionIdResolver、RunContextPropagator
   ├─ runtime   规划 → 编排 → 令牌 → 屏 / 澄清屏
   ├─ registry  从 @SparkTool 推导 Manifest；按状态过滤（可接宿主策略）
   ├─ gateway   校验 → 策略 → 幂等 → 传输 → 校验 → 脱敏 → 审计
   │              ├─ InProcessToolTransport → 你的 @SparkTool 方法（切面照常生效）
   │              └─ HttpToolTransport ──────► 远程 provider 进程
   ├─ 状态存储  Run / 令牌 / 幂等 / 记忆（内存，或 spark-rooter-redis）
   └─ 你的 @Service
```

四个平面职责固定：**Agent Runtime** 决策、**Tool Registry** 能力发现、**Tool Gateway** 安全执行、**领域服务** 确定性业务执行。平台模块互相只经接口依赖，都不含 Controller 与 Spring 组件注解，由 starter 统一装配。

| 目录 | 内容 | 技术栈 |
|---|---|---|
| [`spark-rooter/`](spark-rooter/) | 后端：7 个平台模块、Redis 模块、2 个 starter、`examples/` | Java 17/21 · Spring Boot 3.5 · Spring AI 1.1 |
| [`spark-ui/`](spark-ui/) | 前端：`@spark-ui/core`（三入口）、`spark-chat` | React 19 · TypeScript · Vite · antd 6 / antd-mobile 5 |
| [`.harness/contracts/`](.harness/contracts/) | 前后端共用的 **9 个** JSON Schema 及 28 个示例；两端代码都是它的投影 | JSON Schema 2020-12 |
| [`.harness/`](.harness/) | 工程规则、契约校验、依赖红线、e2e、压测、变更记录 | Node 20 |

详见 [`architecture.md`](.harness/wiki/architecture.md)。

## 可观测性

装了 Micrometer（如 `spring-boot-starter-actuator`）时自动导出 **7 个**指标；没装则落日志，不强加依赖。

| 指标 | 类型 | 标签 |
|---|---|---|
| `spark.llm.requests` / `spark.llm.duration` | Counter / Timer | `outcome`（6 个规划出口） |
| `spark.llm.tokens` | Counter | `kind`（prompt / completion） |
| `spark.tool.invocations` | Counter | `toolId`, `status` |
| `spark.tool.duration` | Timer | `toolId` |
| `spark.run.outcomes` / `spark.run.duration` | Counter / Timer | `outcome`（completed / 各失败码 / confirmation_rejected） |

要暴露 `/actuator/prometheus` 自行加 `micrometer-registry-prometheus`。本项目不替宿主选监控栈。

**标签一律低基数**：不含 `sessionId` / `runId` / `conversationId` / 业务 ID。它们基数无上界，会打爆时序库，而把监控系统打挂比没有监控更糟。追溯单次调用请查审计（`AuditSink` 逐次留痕，含参数摘要）。

**监控故障不拖垮业务**：埋点与审计的实现抛异常时，Gateway / Runtime 捕获并记日志，工具结果照常返回。审计丢失可据 ERROR 日志补账，而让已执行的操作对调用方表现为失败会导致用户重试 → 重复副作用。

### 自保阈值

| 项 | 默认 | 说明 |
|---|---|---|
| 计划步数上限 | 6（常量） | 挡住「模型规划几十步」；实测最长链 3 步 |
| `spark.gateway.max-concurrent-per-session` | 4 | 单会话在飞工具调用上限，≤ 0 关闭 |
| `spark.runtime.run-queue` / `spark.gateway.tool-queue` | 32 / 64 | 线程池队列容量，满则拒绝而非排队 |
| `spark.gateway.idempotency-ttl` | 24h | 幂等结果保留窗口；内存实现按它淘汰，Redis 实现作 key TTL |
| `spark.llm.circuit.failure-threshold` | 2 | LLM 连续传输失败即熔断 |

全部配置项见 [`spark-rooter/README.md`](spark-rooter/README.md)。

## 安全模型

- **前端只发自然语言**。不发页面上下文、用户信息、业务字段。
- **模型输出必须过校验**。只能选候选里的工具，只能填 schema 里的字段，值要过 JSON Schema；标了 `entity` 的 ID 必须原样出自用户原话或会话上下文，否则走澄清。
- **高风险操作必须确认**。后端签发一次性令牌，绑定 runId / actionId / 参数摘要 / conversationId / sessionId。确认后由领域自己的重校验逻辑再看一遍才执行。
- **确认互斥靠令牌原子消费**。内存用 `ConcurrentHashMap.remove`，Redis 用 `GETDEL`，并发的确认请求里只有一个能拿到令牌。不依赖实例内的锁，所以多副本下也成立。
- **执行面单一入口**。Gateway 顺序固定：寻址 → 输入校验 → 访问策略 → 幂等 → 调用 → 输出校验 → 脱敏 → 审计。远程工具走同一条管线。
- **跨进程超时不重试**。进程内超时可真正中断，跨进程只能中断本地等待——远端可能已执行成功。重试的判据是「有没有碰到工具」，不是「错误严不严重」。
- **审计不含参数原文**，只记摘要。日志和共享存储都不含用户原文、模型网关地址、密钥、模型名。

全文见 [`agent-safety.md`](.harness/rules/agent-safety.md)。安全问题请按 [`SECURITY.md`](SECURITY.md) 报告。

## 自己发包

本仓库不发布到 Maven Central 或 npm。clone 之后按下面的方式发到你自己的仓库。

### 后端

```bash
cd spark-rooter

# 1. 去掉 SNAPSHOT（多数私服的 release 仓拒收 SNAPSHOT）
mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false

# 2. 只发平台模块，不发示例领域；带 sources / javadoc jar
mvn -q deploy -P 'release,!examples' -DskipTests \
  -DaltDeploymentRepository=my-nexus::https://nexus.example.com/repository/maven-releases/
```

`-P '!examples'` 把 `order-service` 这类示例领域排除在外——它们是演示，不该出现在你的私服里。`release` profile 挂了 source 和 javadoc 插件，日常构建不激活。发出去的是 9 个 artifact：7 个平台模块、`spark-rooter-redis`、`spark-provider-spring-boot-starter`（加 parent pom）。

### 前端

```bash
cd spark-ui/packages/core
pnpm build
# 改 package.json 的 name 到你的 scope（如 @your-org/spark-core），然后
pnpm publish --registry https://npm.example.com/ --no-git-checks
```

包已经是 `private: false`、三个入口的 `exports` 和 `publishConfig` 都写好了，`pnpm pack` 出来只有 `dist/` 和 README，约 30 KB。

## 开发与质量门禁

```bash
pnpm -C .harness run ci        # 全部门禁，退出码 0 才算过
pnpm -C .harness run doctor    # Harness 自检

SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh          # 后端全链路 162 条
SPARK_PORT=8091 bash .harness/scripts/e2e-frontend.sh         # Playwright 7 个用例
SPARK_PORT=8091 bash .harness/scripts/deploy-verify.sh        # 部署验证 14 条
bash .harness/scripts/e2e-provider.sh                          # hub + provider 双进程 15 条
bash .harness/scripts/e2e-multi-instance.sh                    # 两个 hub 共享 Redis 21 条
node .harness/scripts/load-test.mjs --port 8080 --concurrency 32 --duration 20   # 压测
```

`ci` 一条命令跑 9 步：改名一致性、契约校验、模块依赖方向、种子数据、日志断言、shellcheck、前端 ci（typecheck / 107 单测 / lint / format / 构建 / 打包校验）、后端 install（275 单测 / spotless）、host-demo 离线打包。GitHub Actions 在 push 和 PR 上跑同一条命令。e2e 不在 CI 里，改了编排 / 网关 / 前端交互请本地跑对应的脚本。

脚本强制的红线（违反即构建失败）：

- 平台模块不依赖 `spring-boot-starter-web`、不用 Spring 组件注解、源码不出现 `userId` / `tenantId` / `Principal`、不出现业务词汇
- provider-starter 不依赖 Spring AI 与任何 hub 模块；`spi` / `contracts` / provider 的产物字节码 major ≤ 61（JDK 17）
- Redis 模块只能被宿主引，平台模块与 provider-starter 不得依赖它
- `examples/*` 只在 `examples` profile 里，不得回到顶层 `<modules>`
- 工具实现不得自行重试
- 前端 `apps/chat` 不直接 import antd；`@spark-ui/core/client` 不 import react
- 跨端数据结构必须先有契约

每个需求走 8 个阶段（需求分析 → 需求评审 → 编码 → 编码评审 → 推送 → CI → 部署验证 → 用户确认），产物在 [`.harness/changes/`](.harness/changes/)。想参与见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

## 已知限制

- **provider 幂等默认进程内**。多实例部署的 provider，hub 重试可能落到另一实例而绕过缓存；要强一致需替换 `ProviderIdempotencyStore` 为共享存储。
- **客户端断开的提前终止对 provider 工具不生效**。判定要读工具的 `sideEffect`，而 http 注册的工具目前没有这份元数据，全按写操作处理（照跑）。fail-safe 方向是对的，只是少了一个优化。
- **provider 重启漏推需人工介入**。hub 侧的对账补偿未实现。
- **不做服务发现**。provider 坐标用配置化 base URL；接注册中心需自行实现 `ProviderEndpointResolver`。
- **不做 MCP**。契约保留 `protocol: mcp` 枚举值但注册时拒绝。
- **只支持 Java + 注解声明工具**。其他技术栈的服务需自行按 HTTP 协议实现 provider 端点。
- **工具注册表在进程内**。多副本下每个 hub 各自扫描 `@SparkTool`（单体形态）或各自接收 provider 推送（微服务形态）。这是设计如此——注册表是配置的派生，不是运行时状态。
- **本仓不发包**。见[自己发包](#自己发包)。

## 许可

[MIT](LICENSE)
