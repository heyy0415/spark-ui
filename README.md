# Spark

用自然语言操作业务系统的一套前后端。用户在聊天框里说「最近 5 单已发货的订单」，前端把这句话原样发给后端，后端找到对应的工具、填好参数、必要时弹确认，最后把结果渲染成表格或卡片。

后端是一个 Spring Boot Starter。任何 Java 服务加一个依赖，在方法上标 `@SparkTool`，这个方法就能被前端用自然语言调到。

```
「最近 5 单已发货的订单」   order.list.search{status: SHIPPED, limit: 5}   → 表格
「第二个的物流」             会话记忆解析出第二行订单号 → order.logistics.get → 卡片 + 时间线
「申请售后」（没说哪一单）   先给一张订单列表让用户点选
「删除订单 10010」           查详情 → 确认卡片（后端签发一次性令牌）→ 重校验 → 宿主权限切面 → 执行
```

## 目录

| 目录 | 内容 |
|---|---|
| `spark-rooter/` | 后端。平台模块 + `spark-rooter-spring-boot-starter` + `examples/`（四个示例领域、一个独立的示例宿主 `host-demo`）。Java 21 / Spring Boot 3.5 / Spring AI 1.1 |
| `spark-ui/` | 前端。`@spark-ui/core` 是 UI Schema 渲染引擎（可发 npm 包），`spark-chat` 是聊天应用。React 19 / TypeScript / Vite / antd 6 / antd-mobile 5 |
| `.harness/contracts/` | 前后端共用的 9 个 JSON Schema 和示例，两端代码都是它的投影 |
| `.harness/` | 工程规则、契约校验、模块依赖检查、e2e、部署验证、每次改动的记录 |
| `docs/` | Harness 的使用说明 |

## 跑起来

需要 JDK 21、Node 20 + pnpm、Maven（或用仓内 `mvnw`）。前端 e2e 另需本机 Chrome。

```bash
# 后端：先把平台和示例领域装进本地 Maven 仓，再打包示例宿主
node .harness/scripts/mvn.mjs -q -B install -DskipTests
cd spark-rooter/examples/host-demo && mvn -q package -DskipTests && cd -
JAVA_HOME=~/.jenv/versions/21 java -jar spark-rooter/examples/host-demo/target/host-demo.jar
# 启动日志里应看到 "spark-rooter: 14 tools registered from 6 beans" 和 9 项自检 OK

# 前端
pnpm -C spark-ui install
pnpm -C spark-ui run dev        # http://localhost:5173，代理到 8080
```

打开页面试几句：「看看我的订单」→ 点某一行的「查看物流」→「有什么商品」→ 点「查看商品」→ 点卡片底部「返回列表」。直接说「申请售后」会得到一张订单列表让你选。

要接真实模型，只用环境变量，不要写进任何文件：

```bash
export SPARK_LLM_BASE_URL=... SPARK_LLM_API_KEY=... SPARK_LLM_MODEL=...
```

不配的话走确定性的规则规划器，整条链路照样能跑。

## 接到自己的 Java 服务

1. `pom.xml` 加依赖 `com.sparkrooter:spark-rooter-spring-boot-starter`。
2. 在任意 `@Service` 的 public 方法上标注解，参数和返回值用 record：

   ```java
   public record ListIn(
       @SparkParam(description = "按状态筛选", aliases = {"SHIPPED=已发货"}) Optional<Status> status,
       @SparkParam(description = "条数", unit = {"单", "条"}, min = 1, max = 50) @SparkDefault("20") Integer limit) {}

   @SparkTool(id = "order.list.search", version = "1.1.0", domain = "order", name = "搜索订单",
       description = "按状态筛选订单", clarifiesEntity = EntityType.ORDER)
   public ListOut list(ListIn in) { ... }
   ```

   有副作用的方法再加 `@SparkRisk(level = HIGH, confirmation = REQUIRED, idempotency = REQUIRED, ...)` 和 `@SparkPrerequisite({"order.detail.get"})`。运行时会先执行只读前置步骤，弹确认卡片，用户确认后重校验再执行。Manifest（inputSchema、outputSchema、风险、执行策略）从注解和 record 推导，启动时按契约校验。

3. 接身份。内核不认识用户，这部分全在宿主：
   - 实现 `RunContextPropagator`，把登录态的 ThreadLocal 带到 spark 的工作线程。
   - 实现 `SessionIdResolver`，把请求映射成会话隔离键。默认实现直接返回前端传的 conversationId，没有隔离，只能本地演示用，启动时会 WARN。
   - 权限用方法级切面（`@Aspect`、`@PreAuthorize`）。Controller 拦截器拦不住 spark 的调用，因为 spark 不经过 Controller。示例宿主里有正反两条路径。

完整示例在 `spark-rooter/examples/host-demo`。前端要么直接用 `spark-chat`，要么在自己的页面里用 `@spark-ui/core` 的 `SchemaRenderer` 渲染后端下发的 UI Schema；要带登录态就给 `AgentChatPanel` 传自己的 `fetch`。

## 边界

- 前端只发自然语言。输入框、示例按钮、表格行里的按钮、卡片底部的按钮，点了都变成一条新消息发出去。不发页面上下文，不发用户信息，不发业务字段。
- 只有 `@SparkParam` 标过的参数会进 inputSchema。参数值先由确定性规则抽取（订单号、枚举别名、数量、相对时间），再用 `@SparkDefault` 补默认；模型只能在候选工具里选，只能填 schema 里有的字段，值要过 JSON Schema 校验，订单号必须来自用户原话。
- 高风险工具必须经后端签发的一次性令牌确认。令牌绑定 runId、actionId、参数摘要、conversationId、sessionId。确认后由领域自己的重校验逻辑再看一遍才执行。
- Gateway 通过 Spring 代理对象调宿主方法，宿主的方法级切面正常生效。每次调用写一条审计，不含参数原文。
- 会话记忆只存 ID，只在成功结束时写。日志里没有用户原文，也没有模型网关地址、密钥、模型名。

细节在 `.harness/rules/agent-safety.md`。

## 门禁

```bash
pnpm -C .harness run ci          # 改名检查、契约检查、模块依赖检查、种子检查、前端 ci、后端 install、host-demo 离线打包
pnpm -C .harness run doctor      # Harness 自检
SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh     # 159 条端到端断言
SPARK_PORT=8091 bash .harness/scripts/deploy-verify.sh   # 部署验证 12 条
SPARK_FRONT_BASE=http://localhost:5199 node .harness/scripts/e2e-frontend.mjs   # 50 条 headless Chrome 断言
```

脚本会检查的硬性规则：平台模块不能依赖 `spring-boot-starter-web`，不能用 Spring 组件注解（Bean 全部由 starter 装配），代码里不能出现 `userId` / `tenantId` / `Principal`；示例领域不能依赖平台模块，领域之间不能互相 import；前端 `apps/chat` 不能直接 import antd；`@spark-ui/core` 的组件文件只能是官方组件的映射；跨端数据结构必须先有契约。

## 改动是怎么落地的

每个需求走 8 个阶段：需求分析、需求评审、编码、编码评审、推送、CI、部署验证、用户确认。每个阶段的产物放在 `.harness/changes/<change-id>/` 下，包括 spec、评审意见、编码报告和冻结的验收产物。最近一次是 `refactor-spark-embedded-starter-20260909`：后端改成 Starter、方法级 `@SparkTool`、内核去掉身份概念、前端只发自然语言、加了会话记忆和澄清屏。

## 已知限制

- 只有进程内模式。远程工具和服务发现只留了 `ToolTransport` / `ToolProviderDiscovery` 两个接口，没有实现。
- 存储全是内存实现（带 TTL）。多实例部署要自己替换成 Redis 之类，端口都是 `@ConditionalOnMissingBean`。
- 领域路由的关键词表写死了 4 个领域（order / product / aftersale / refund），宿主新增领域目前只能靠 LLM 分类到达。
- 动词表、枚举别名、澄清文案都是中文，没有 i18n。
- 聊天记录只在内存里，刷新页面就没了。
