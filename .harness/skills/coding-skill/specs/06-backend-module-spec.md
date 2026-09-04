# Spec: 后端模块（backed/）

## 职责
- 每个 Maven 模块闭合一个平台职责：`platform-spi` / `contracts-java` / `agent-runtime` / `tool-registry` / `tool-gateway` / `domains/*` / `app`。依赖方向以 `project-structure.md` §2 为准。
- 包结构：`api`（controller、DTO）/ `application`（用例、端口接口）/ `domain`（实体、规则、状态机）/ `infra`（适配器、持久化、外部调用）。

## 模块模板

```
{module}/
├── pom.xml
└── src/main/java/com/strato/{module}/
    ├── api/            # @RestController、request/response record
    ├── application/    # UseCase 类 + 出向端口接口（XxxPort）
    ├── domain/         # 纯 Java：实体、值对象、DomainException、状态机
    └── infra/          # 端口实现：内存仓储、HTTP 客户端、JSON Schema 校验器
```

## 端点模板

```java
@RestController
@RequestMapping("/internal/tool-registry")
public class ToolRegistryController {
  private final SearchToolsUseCase searchTools;

  @PostMapping("/search")
  public ToolSearchResponse search(@Valid @RequestBody ToolSearchRequest req) {
    return searchTools.execute(req);
  }
}
```

## 必备
- DTO 为 `record`，字段用 Jakarta Validation 注解；`String` 表示 ID / 金额。
- 出向调用只经 `application` 层定义的端口接口；`infra` 提供实现，Spring 注入。
- `domain` 包 import 白名单：`java.*`、本模块 `domain.*`。**禁止** `org.springframework.*`、`com.fasterxml.*`。
- 每个模块有 `README.md`（≤ 40 行）：职责、对外端点、依赖的端口。
- 日志带 MDC `runId` / `toolCallId`。

## Agent Runtime 专项
- 领域路由先走 `DomainRouter`（规则）；LLM 只在候选集合内选工具，输出经 Schema 校验，`toolId` 不在候选内即拒绝。
- LLM 客户端为 `LlmClient` 端口，`infra` 提供 OpenAI 兼容实现；base URL / key 来自环境变量。
- Run 状态机：`CREATED → PLANNING → EXECUTING → WAITING_CONFIRMATION → EXECUTING → COMPLETED | FAILED`，迁移幂等。
- SSE 用 `SseEmitter`，事件结构按 `sse-events.schema.json`。

## Tool Registry 专项
- 只暴露 `POST /internal/tool-registry/tools`（注册）、`POST /search`、`GET /tools/{toolId}/versions`。
- 已发布版本不可变：同 `toolId@version` 再注册返回 `409`。
- `search` 必须带 `principal` 并按权限 / 状态 / 风险过滤。

## Tool Gateway 专项
- 只暴露 `POST /internal/tool-gateway/invoke`。
- 顺序：输入 Schema 校验 → 鉴权 → 幂等 → 寻址 → 调用（超时 / 重试按 Manifest）→ 输出 Schema 校验 → 脱敏 → 审计。
- 寻址表由 Registry 内部接口提供，**不**从 Runtime 请求体读取地址。

## 反模式
- ❌ Controller 内写 if/else 业务分支。
- ❌ Runtime 模块 pom 依赖 `domains/*`。
- ❌ `catch (Exception e) { log.warn(...) }` 然后继续。
- ❌ 用 `Map<String,Object>` 承载对外 DTO。

## Spring 踩坑清单（来自 change feat-agent-tool-platform）
- `@Order` 控制 `@EventListener` 顺序时必须标在**方法**上，标在类上无效。
- `@ConfigurationProperties` 绑定 `Map<String, …>` 时，键含 `@`、值含 `:` 会被宽松绑定破坏；用显式 `List<record>` 代替，并在启动日志打印绑定条数。
- networknt `SchemaMapper.map` 返回 `AbsoluteIri`；跨文件 `$ref` 用它把 `$id` 前缀映射到 `classpath:`。
- Spring AI：需要"无 key 也能启动"时**不要**用 `spring-ai-starter-model-openai`（自动配置在缺 key 时抛异常），改依赖 `spring-ai-openai` + `spring-ai-client-chat` 并手工装配 `ChatClient`。
- `SseEmitter` 输出格式为 `event:xxx` / `data:{...}`（冒号后无空格），解析器须兼容有无空格。
