# Spec: 后端模块（spark-rooter/）

## 职责
- 每个 Maven 模块闭合一个平台职责：`spark-rooter-spi` / `spark-rooter-contracts` / `spark-rooter-runtime` / `spark-rooter-registry` / `spark-rooter-gateway` / `spark-rooter-web-mvc` / `spark-rooter-spring-boot-starter` / `examples/*`。依赖方向以 `project-structure.md` §2 为准。
- 包结构：`api`（跨模块公开接口）/ `application`（用例、端口接口）/ `domain`（实体、规则、状态机）/ `infra`（适配器、默认实现、自检）。Controller 只在 `web-mvc`；平台模块**不用** `@Component/@Service/@Repository/@Configuration`，Bean 由 starter `@Bean` 装配。

## 模块模板

```
{module}/
├── pom.xml
└── src/main/java/com/sparkrooter/{module}/
    ├── api/            # 跨模块公开接口（如 ToolSearchPort）；Controller 在 web-mvc
    ├── application/    # UseCase 类 + 出向端口接口（XxxPort）
    ├── domain/         # 纯 Java：实体、值对象、DomainException、状态机
    └── infra/          # 端口实现：内存仓储、HTTP 客户端、JSON Schema 校验器
```

## 工具声明模板（宿主 / 示例领域）

```java
@Service
public class OrderTools {
  public record ListIn(
      @SparkParam(description = "按状态筛选", aliases = {"SHIPPED=已发货"}) Optional<Status> status,
      @SparkParam(description = "条数", unit = {"单", "条"}, min = 1, max = 50) @SparkDefault("20") Integer limit) {}
  public record ListOut(List<Item> items, int total) {}

  @SparkTool(id = "order.list.search", version = "1.1.0", domain = "order", name = "搜索订单",
      description = "…", clarifiesEntity = EntityType.ORDER)
  public ListOut list(ListIn in) { … }          // public、非 final；宿主切面在 Gateway 代理调用时触发

  @SparkTool(id = "order.delete", version = "1.0.0", domain = "order", name = "删除订单", description = "…")
  @SparkRisk(level = HIGH, confirmation = REQUIRED, idempotency = REQUIRED, sideEffect = true, reversible = false)
  @SparkPrerequisite({"order.detail.get"})
  public DeleteOut delete(OrderIdIn in, ToolContext ctx) { … }
}
```

启动失败并指明原因：方法非 public / final 类或方法 / 标在 `@Configuration` 类 / In-Out 非 record / 同 id / 缺 description。推导结果经 `tool-manifest` 契约校验。

## 必备
- DTO 为 `record`，字段用 Jakarta Validation 注解；`String` 表示 ID / 金额。
- 出向调用只经 `application` 层定义的端口接口；`infra` 提供实现，Spring 注入。
- `domain` 包 import 白名单：`java.*`、本模块 `domain.*`。**禁止** `org.springframework.*`、`com.fasterxml.*`。
- 每个模块有 `README.md`（≤ 40 行）：职责、对外端点、依赖的端口。
- 日志带 MDC `runId` / `toolCallId`。

## Agent Runtime 专项
- 领域路由三层：`DomainRouter`（关键词规则，顺序 refund → aftersale → order → product）→ `IntentClassifier`（规则未命中且配置了 LLM 时，只输出可发现领域的枚举或 none）→ 无能力路径。路由后 `ArgumentExtractor`（实体 ID 正则；按 `ToolMetaRegistry` 抽枚举别名 / 数量 / 相对时间；序数指代查会话记忆的最近列表）→ `EntityRequirementCheck` 在规划前拦截「候选全需实体而缺失」→ 记忆懒补位 → 仍缺则 `ClarificationScreen`（调 `clarifiesEntity` 工具投影通用 Table）。规则规划器 `IntentVerbs`：动词 → 目标工具；前置步骤 / 实体参数 / 默认值读 `ToolMetaRegistry`（注解声明，内核表只作手写 Manifest 的回落）。LLM 只在候选集合内选工具，输出经 `ToolSelectionValidator`（候选内、args 在 inputSchema 且值过 JSON Schema、实体参数值 == 已识别实体、需确认步骤前置齐全、不填可信参数）。
- 屏与重校验不在 runtime：`ScreenRegistry` / `RecheckRegistry` 按 toolId 查领域模块提供的 spi `ScreenBuilder` / `ConfirmationRecheck` Bean；runtime 是 ui-schema 契约校验的唯一点。`displayName` 由 Registry 注册时经 spi `ToolNameSink` 回填。
- LLM 客户端为 `LlmClient` 端口，`infra` 提供 OpenAI 兼容实现；base URL / key 来自环境变量。
- Run 状态机：`CREATED → PLANNING → EXECUTING → WAITING_CONFIRMATION → EXECUTING → COMPLETED | FAILED`，迁移幂等。
- SSE 用 `SseEmitter`，事件结构按 `sse-events.schema.json`。

## Tool Registry 专项
- 只暴露 `POST /internal/tool-registry/tools`（注册）、`POST /search`、`GET /tools/{toolId}/versions`。
- 已发布版本不可变：同 `toolId@version` 再注册返回 `409`。
- `search` 不带身份，按状态 / 风险过滤；宿主 `ToolAccessPolicy` Bean 存在时再按它过滤。

## Tool Gateway 专项
- 只暴露 `POST /internal/tool-gateway/invoke`。
- 顺序：寻址 → 输入 Schema 校验 → 宿主 `ToolAccessPolicy`（可选）→ 幂等（按 sessionId）→ 经 Spring 代理调用 `@SparkTool` 方法（`RunContextPropagator` 先恢复宿主上下文；超时 / 重试按 Manifest）→ 输出 Schema 校验 → 脱敏 → 审计（`AuditSink`）。
- 寻址表由 Registry 内部接口提供，**不**从 Runtime 请求体读取地址。

## 反模式
- ❌ Controller 内写 if/else 业务分支。
- ❌ 平台模块 pom 依赖 `examples/domains/*` 或 `spring-boot-starter-web`；平台模块源码用 Spring 组件注解；平台模块出现 `userId` / `tenantId` / `Principal`。
- ❌ runtime 内出现领域词汇的屏 / 策略（`refundConfirmation`、`DeletionPolicy` 之类）；领域模块 `infra/screen/` 调 `OrderSnapshotProvider`（屏只用 Gateway 输出）。
- ❌ `examples/domains/<a>` import `com.sparkrooter.examples.<b>`。
- ❌ 宿主把权限做在 Controller 拦截器里指望保护 `@SparkTool`（spark 不经 Controller）。
- ❌ `catch (Exception e) { log.warn(...) }` 然后继续。
- ❌ 用 `Map<String,Object>` 承载对外 DTO。

## Spring 踩坑清单（来自 change feat-agent-tool-platform / refactor-spark-embedded-starter）
- `ObjectMapper.setSerializationInclusion` 已过时（-Werror 红）→ `setDefaultPropertyInclusion(JsonInclude.Value.construct(...))`。
- record 组件 `Optional<T>` 要 `Jdk8Module`，否则缺键反序列化为 null 而不是 `Optional.empty()`。
- `BigDecimal` 默认序列化为数字；契约要两位小数字符串 → 平台 ObjectMapper 注册自定义 `JsonSerializer<BigDecimal>`。
- 推导 Manifest 与手写 JSON 比对要经字符串往返归一化（`IntNode` vs `LongNode` 不相等）。
- `mvn -o package` 在 target 已存在时 boot repackage 可能沿用旧 jar，且离线可能没缓存 clean 插件 → 直接删 `target/`。
- `@ConditionalOnMissingBean` 校验用 `--debug` 条件报告：宿主定义 `AuditSink` 后应看到 `sparkRooterAuditSink … Did not match`。
- `@Order` 控制 `@EventListener` 顺序时必须标在**方法**上，标在类上无效。
- `@ConfigurationProperties` 绑定 `Map<String, …>` 时，键含 `@`、值含 `:` 会被宽松绑定破坏；用显式 `List<record>` 代替，并在启动日志打印绑定条数。
- networknt `SchemaMapper.map` 返回 `AbsoluteIri`；跨文件 `$ref` 用它把 `$id` 前缀映射到 `classpath:`。
- Spring AI：需要"无 key 也能启动"时**不要**用 `spring-ai-starter-model-openai`（自动配置在缺 key 时抛异常），改依赖 `spring-ai-openai` + `spring-ai-client-chat` 并手工装配 `ChatClient`。
- `SseEmitter` 输出格式为 `event:xxx` / `data:{...}`（冒号后无空格），解析器须兼容有无空格。
