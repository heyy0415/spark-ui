# Spark

[![CI](https://img.shields.io/badge/ci-pnpm%20-C%20.harness%20run%20ci-blue)](.harness/scripts/ci.mjs)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
![Java 21](https://img.shields.io/badge/java-21-orange) ![Spring Boot 3.5](https://img.shields.io/badge/spring%20boot-3.5-brightgreen) ![React 19](https://img.shields.io/badge/react-19-61dafb)

Spark 让业务系统能听懂自然语言。用户在聊天框里输入「最近 5 单已发货的订单」，后端找到对应的工具、填好参数、需要时弹确认，结果以表格或卡片渲染出来。

后端是一个 Spring Boot Starter。在任意 Java 服务里加一个依赖，给方法标上 `@SparkTool`，这个方法就能被前端用自然语言调用。

```
「最近 5 单已发货的订单」   → order.list.search{status: SHIPPED, limit: 5}     → 表格
「第二个的物流」             → 从上一轮列表取第二行订单号 → order.logistics.get → 卡片 + 时间线
「申请售后」（没说哪一单）   → 先给一张订单列表让用户点选
「删除订单 10010」           → 查详情 → 确认卡片（后端签发一次性令牌）→ 重校验 → 权限切面 → 执行
```

## 目录

- [功能](#功能)
- [架构](#架构)
- [快速开始](#快速开始)
- [接入你的服务](#接入你的服务)
- [Docker 部署](#docker-部署)
- [安全模型](#安全模型)
- [开发与质量门禁](#开发与质量门禁)
- [已知限制](#已知限制)
- [许可](#许可)

## 功能

- **方法级注解声明工具**：`@SparkTool` + record 参数，inputSchema / outputSchema / 风险等级 / 幂等策略自动推导并按 JSON Schema 契约校验。
- **模型读注解理解意图**：内核不含任何业务词汇。工具的用途、同义说法、参数含义、实体类型全部写在 `@SparkTool` / `@SparkParam` 上，模型据此选工具、填参数。宿主新增一个领域不用改内核一行代码。
- **多轮会话**：会话记忆记住上一轮的实体和列表，支持「第二个」「最后一个」这类指代；缺实体时给一张澄清列表让用户点选。
- **高风险操作确认**：需确认的工具先走只读前置步骤，出确认卡片，后端签发一次性令牌，用户确认后重校验再执行。
- **生成式 UI**：后端下发 UI Schema，前端只渲染 5 个白名单组件（Form / Card / Table / Result / Timeline，antd 与 antd-mobile 双端），表格行和卡片上的按钮都是预写好的自然语言。
- **聊天式界面**：每条消息一个回合，加载期骨架屏 + 状态文案，历史回合保留可回看。
- **身份留在宿主**：内核不认识用户。登录态、权限、租户全由宿主服务通过两个接口和方法级切面接入。
- **模型输出必须过校验**：只能选候选里的工具，只能填 schema 里的字段，值要过 JSON Schema，实体 ID 必须原样出自用户原话或会话上下文——模型负责理解，代码负责核实。

## 架构

```
 浏览器（spark-chat，或任何嵌了 @spark-ui/core 的页面）
   │  只发自然语言；SSE 收事件；渲染 UI Schema
   ▼
 你的 Spring Boot 服务  ←── 引入 spark-rooter-spring-boot-starter
   ├─ 宿主拦截器 / 登录态 ──► SessionIdResolver（会话隔离键）、RunContextPropagator（跨线程带上下文）
   ├─ runtime   路由 → 抽取 → 记忆补位 → 规划 → 编排 → 令牌 → 屏 / 澄清屏
   ├─ registry  从 @SparkTool 推导 Manifest；按状态过滤（可接宿主策略）
   ├─ gateway   校验 → 幂等 → 通过 Spring 代理调用方法 → 校验 → 脱敏 → 审计
   └─ 你的 @Service：@SparkTool 方法（你的 @Aspect / @PreAuthorize 照常生效）
```

三个平台模块互相只通过接口依赖，都不含 Controller 和 Spring 组件注解，由 starter 统一装配。详见 [`.harness/wiki/architecture.md`](.harness/wiki/architecture.md)。

| 目录 | 内容 | 技术栈 |
|---|---|---|
| [`spark-rooter/`](spark-rooter/) | 后端：平台模块、starter、`examples/`（四个示例领域 + 独立示例宿主 `host-demo`） | Java 21 / Spring Boot 3.5 / Spring AI 1.1 / Maven |
| [`spark-ui/`](spark-ui/) | 前端：`@spark-ui/core` 渲染引擎（可发 npm 包）、`spark-chat` 聊天应用 | React 19 / TypeScript / Vite / antd 6 / antd-mobile 5 |
| [`.harness/contracts/`](.harness/contracts/) | 前后端共用的 9 个 JSON Schema 及示例；两端代码都是它的投影 | JSON Schema 2020-12 |
| [`.harness/`](.harness/) | 工程规则、契约校验、依赖红线、e2e、部署验证、每次变更的记录 | Node 20 |

## 快速开始

需要 JDK 21、Node 20 + pnpm、Maven（或用仓内 `mvnw`）。前端 e2e 另需本机 Chrome。

```bash
# 后端：把平台和示例领域装进本地 Maven 仓，再打包示例宿主
node .harness/scripts/mvn.mjs -q -B install -DskipTests
cd spark-rooter/examples/host-demo && mvn -q package -DskipTests && cd -
java -jar spark-rooter/examples/host-demo/target/host-demo.jar
# 日志里应看到 "spark-rooter: 14 tools registered from 6 beans" 和 9 项自检 OK

# 前端
pnpm -C spark-ui install
pnpm -C spark-ui run dev        # http://localhost:5173，代理到 8080
```

试几句：「看看我的订单」→ 点某一行的「查看物流」→「有什么商品」→ 点「查看商品」→ 点卡片底部「返回列表」。直接说「申请售后」会得到一张订单列表让你选。

**模型是必需的**：意图理解、工具选择、参数填写全靠它，没配置时所有请求都会返回「未配置模型，无法理解请求」。任何 OpenAI 兼容接口都行。

配置只走环境变量，不要写进任何文件（`application.yml` 里已用 `${SPARK_LLM_*:}` 预留了占位）：

```bash
export SPARK_LLM_BASE_URL=https://your-openai-compatible-endpoint/v1   # baseUrl 以 /v{n} 结尾时不会再拼一层 /v1
export SPARK_LLM_API_KEY=...
export SPARK_LLM_MODEL=...
```

建议用响应快的小模型：每轮对话至少一次规划调用，慢模型会明显拖长等待。

## 接入你的服务

**1. 加依赖**

```xml
<dependency>
  <groupId>com.sparkrooter</groupId>
  <artifactId>spark-rooter-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

**2. 标注方法**

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

只有标了 `@SparkParam` 的字段会进 inputSchema。有副作用的方法加 `@SparkRisk` 和 `@SparkPrerequisite`，运行时会先执行前置只读步骤、弹确认、确认后重校验再执行。

**3. 接身份**

内核不认识用户，这部分在宿主：

- 实现 `RunContextPropagator`，把登录态 ThreadLocal 带到 spark 的工作线程。
- 实现 `SessionIdResolver`，把请求映射成会话隔离键。默认实现直接返回前端传的 conversationId，没有隔离，只能本地演示，启动时会 WARN。
- 权限用方法级切面（`@Aspect`、`@PreAuthorize`）。Controller 拦截器拦不住 spark 的调用，因为 spark 不经过 Controller。示例宿主里有正反两条路径可对照。

完整示例见 [`spark-rooter/examples/host-demo`](spark-rooter/examples/host-demo/README.md)。前端可以直接用 `spark-chat`，或在自己页面里用 `@spark-ui/core` 的 `SchemaRenderer` / `RunStatus` / `SchemaSkeleton`；要带登录态就给 `AgentChatPanel` 传自己的 `fetch`。

## Docker 部署

仓库根的 `Dockerfile` 把前端静态资源和示例宿主打进一个镜像：单进程、8080 端口、无外部依赖（存储全在内存），适合本机演示或放到任意一台有 Docker 的机器上。

```bash
docker build -t spark-demo .
docker run -d --name spark-demo -p 8080:8080 spark-demo
# 打开 http://localhost:8080
```

首次构建约 6～10 分钟（前端 pnpm install + 后端 Maven 拉依赖），之后有缓存会快很多。构建期间不需要本机装 JDK / Node / Maven，都在镜像里完成。

常用操作：

```bash
docker logs -f spark-demo                      # 看启动日志，应出现 "14 tools registered from 6 beans"
curl http://localhost:8080/actuator/health     # {"status":"UP"}
docker stop spark-demo && docker rm spark-demo # 停止并删除
```

可选环境变量：

| 变量 | 说明 |
|---|---|
| `JAVA_TOOL_OPTIONS` | 镜像默认 `-Xmx300m -XX:+UseSerialGC`，内存紧就改小 |
| `SPARK_LLM_BASE_URL` / `SPARK_LLM_API_KEY` / `SPARK_LLM_MODEL` | **必填**，OpenAI 兼容接口。用 `docker run -e` 传入，不要写进镜像；不传则所有请求返回「未配置模型」 |
| `SPARK_SELFCHECK_ENABLED=false` | 关闭启动自检，启动更快 |

镜像里的示例宿主用的是 demo 版 `SessionIdResolver`（会话即 conversationId，没有用户隔离），只适合演示，不要直接对公网开放。

## 安全模型

- 前端只发自然语言。输入框、示例按钮、表格行和卡片上的按钮，点了都是一条新消息。不发页面上下文，不发用户信息，不发业务字段。
- 模型只能在候选工具里选，只能填 schema 里声明的字段，值必须过 JSON Schema；标了 `entity` 的 ID 参数，值必须原样出现在用户原话或会话上下文里，否则一律当作「缺实体」走澄清，不会拿一个编造的 ID 去执行。
- 高风险工具必须经后端签发的一次性令牌确认。令牌绑定 runId、actionId、参数摘要、conversationId、sessionId。确认后由领域自己的重校验逻辑再看一遍才执行。
- Gateway 通过 Spring 代理调宿主方法，宿主的方法级切面正常触发。每次调用一条审计，不含参数原文。
- 会话记忆只存 ID，只在成功结束时写。日志不含用户原文、模型网关地址、密钥、模型名。

全文见 [`.harness/rules/agent-safety.md`](.harness/rules/agent-safety.md)。

## 开发与质量门禁

```bash
pnpm -C .harness run ci          # 改名检查、契约检查、模块依赖检查、种子检查、前端 ci、后端 install、host-demo 离线打包
pnpm -C .harness run doctor      # Harness 自检
SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh     # 161 条端到端断言
SPARK_PORT=8091 bash .harness/scripts/deploy-verify.sh   # 部署验证 12 条
SPARK_FRONT_BASE=http://localhost:5199 node .harness/scripts/e2e-frontend.mjs   # 54 条 headless Chrome 断言
```

脚本强制的规则：平台模块不依赖 `spring-boot-starter-web`、不用 Spring 组件注解、源码里不出现 `userId` / `tenantId` / `Principal`；示例领域不依赖平台模块、互不 import；前端 `apps/chat` 不直接 import antd；`@spark-ui/core` 的组件文件只能是官方组件的映射；跨端数据结构必须先有契约。

每个需求走 8 个阶段（需求分析、需求评审、编码、编码评审、推送、CI、部署验证、用户确认），产物放在 [`.harness/changes/`](.harness/changes/) 下。

## 许可

[MIT](LICENSE)
