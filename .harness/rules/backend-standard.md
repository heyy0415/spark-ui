# Rule: 后端编码规范（Backend Standard，Java / Spring Boot）

> 每条规则对应一个真实踩过的坑。能被 `mvn verify` 机器校验的优先写进构建，不能的才写文字。

## 1. 工具链

- **Java 21**（LTS），`<release>21</release>`；禁止使用 preview 特性。
- **Spring Boot 3.5.x**，Maven 多模块，仓库内提供 `mvnw`；所有命令用 `./mvnw`，不依赖全局 mvn 版本。
- **LLM 接入只用 Spring AI 1.1.x**（`spring-ai-starter-model-openai`，`base-url` 指向 OpenAI 兼容服务）。**禁止**引入 Spring AI Alibaba、LangChain4j 或其他模型 SDK；模型只能"提议"工具调用，`ChatClient` 必须 `internalToolExecutionEnabled(false)`，执行一律经 Tool Gateway。Spring AI 与 Spring Boot 版本通过 BOM 对齐。
- 编译开启 `-Xlint:all -Werror`；`spotless:check`（google-java-format）作为格式门禁。
- 后端质量门禁 = `./mvnw -q -B verify`，退出码 0。**首期不把测试作为门禁**，与全仓决策一致；后续如引入以 change 形式追加。

## 2. 类型与数据

- **金额、ID 一律 `String`**。金额运算用 `BigDecimal`，序列化回 `String`；**禁止** `long` / `double` 表示金额或 ID。
- 时间用 `java.time.Instant` / `OffsetDateTime`，序列化为 ISO-8601 字符串。
- DTO 用 `record`；字段用 `@NotNull` 等 Jakarta Validation 注解，Controller 入参加 `@Valid`。
- 枚举可用，但对外序列化为字符串；未知值反序列化必须失败而非默认。
- **禁止** `Object` / `Map<String,Object>` 作为对外 DTO 字段类型，除非该字段在契约中就是自由 JSON（如 `arguments`），此时用 `JsonNode`。

## 3. 契约与校验

- 所有对外 HTTP / SSE 结构必须在 `.harness/contracts/` 有 Schema；后端用 JSON Schema 校验器（networknt）在边界校验：
  - 入站请求体：校验失败返回 `400` + `ErrorResponse`。
  - 工具调用参数：Gateway 按工具 Manifest 的 `inputSchema` 校验。
  - 工具返回值：Gateway 按 `outputSchema` 校验，失败视为工具错误。
  - LLM 输出：按预期 Schema 校验，失败重试一次后转人工。
- **禁止**在业务代码里手写 JSON 字符串拼接。

## 4. 分层与依赖

- 包结构 `api / application / domain / infra`。`domain` 不依赖 Spring、不依赖 Jackson。
- Controller 只做：参数绑定、校验、调用 application、映射响应。**禁止**在 Controller 写业务逻辑。
- 跨模块只依赖对方的 `api` 包公开接口（`ToolSearchPort` / `ToolInvokePort`）；**禁止**依赖对方 `infra`、`domain`、`application`。`check-module-deps` 机械校验。
- Agent Runtime 只能通过 `ToolGatewayClient` 接口执行工具，通过 `ToolRegistryClient` 接口发现工具。

## 5. 错误处理

- 统一 `ErrorResponse { code, message, traceId }`，由 `@RestControllerAdvice` 输出。
- 业务错误抛领域异常（继承 `DomainException`），基础设施错误抛 `InfrastructureException`；**禁止** `catch (Exception e) {}` 吞掉。
- 日志用 SLF4J，禁止 `System.out`；每条日志带 `runId` / `toolCallId` MDC。
- 不在日志中输出用户输入原文、Token、密钥。

## 6. 并发与幂等

- 有副作用的工具调用必须携带 `idempotencyKey`，Gateway 用 `(tenantId, idempotencyKey)` 去重。
- 超时、重试、熔断由 Gateway 依据 Manifest `execution` 配置执行；**禁止**在 Runtime 或领域服务里私自重试。
- Run 状态机迁移必须是幂等的，同一事件重放不产生第二次副作用。

## 7. 配置与安全

- 密钥、LLM 配置只从环境变量读取（`SPARK_LLM_BASE_URL`、`SPARK_LLM_API_KEY`、`SPARK_LLM_MODEL`）；**禁止**写入代码或 `application.yml`。三者任一缺失时 `LlmClient` 回退为确定性规则实现并在启动日志警告；`IntentClassifier`（意图分类器）与规划器共用同一组变量与 `ChatClient` 装配，缺失时回退为 `NoopIntentClassifier`（恒 none）。
- Registry 对外返回的工具元数据**不含**内部地址、凭据、Owner 联系方式以外的敏感信息。
- 所有对外端点默认需要 `X-Tenant-Id` 与用户身份头（首期为简化头；替换为真实 IdP 是后续 change）。

## 8. 提交

- Conventional Commits，scope 用模块名：`feat(gateway): validate output schema`。
- footer 含 `Change: {change-id}`。
