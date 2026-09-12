# Rule: 后端编码规范（Backend Standard，Java / Spring Boot）

> 每条规则对应一个真实踩过的坑。能被 `mvn verify` 机器校验的优先写进构建，不能的才写文字。

## 1. 工具链

- **Java 21**（LTS），`<release>21</release>`；禁止使用 preview 特性。
- **Spring Boot 3.5.x**，Maven 多模块，仓库内提供 `mvnw`；所有命令用 `./mvnw`，不依赖全局 mvn 版本。
- **LLM 接入只用 Spring AI 1.1.x**（`spring-ai-starter-model-openai`，`base-url` 指向 OpenAI 兼容服务）。**禁止**引入 Spring AI Alibaba、LangChain4j 或其他模型 SDK；模型只能"提议"工具调用，`ChatClient` 必须 `internalToolExecutionEnabled(false)`，执行一律经 Tool Gateway。Spring AI 与 Spring Boot 版本通过 BOM 对齐。
- 编译开启 `-Xlint:all -Werror`；`spotless:check`（google-java-format）作为格式门禁。
- 后端质量门禁 = `./mvnw -q -B verify`（含 JUnit 5 单元测试，surefire 执行，任一失败退出码非 0），退出码 0。测试与 `src/main` 同受 `-Xlint:all -Werror` 与 spotless 约束；测试类一律 `final`。

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
- **线程池必须有界**：禁止 `Executors.newFixedThreadPool` / `newCachedThreadPool` 等工厂方法（队列无界，过载时任务堆到 OOM，用户侧表现为连接挂着既不失败也不返回）。统一用 `NamedThreads.boundedPool(core, queueCapacity, prefix)`——显式 `ThreadPoolExecutor` + `ArrayBlockingQueue` + `AbortPolicy` + 命名守护线程。
- **拒绝必须对用户可见**：`RejectedExecutionException` 不能只落日志。SSE 场景下 emitter 已返回给客户端，无法改 HTTP 状态码，必须发 `run.failed` 终态事件并 close，否则连接会挂到 SSE 超时。
- **例外**：`ScheduledThreadPoolExecutor` 的队列固定为私有 `DelayedWorkQueue`，四个构造器都不接受 `BlockingQueue`，设不了界。此类池允许无界，但**必须在注释里说明任务量为何不会失控**（如「固定周期心跳，数量与活跃连接同阶」）。
- **熔断只统计传输失败**：业务层面的失败（如模型输出不合规）不得计入熔断计数，否则「能用但不够好」会被判成「服务宕机」。

## 7. 配置与安全

- 密钥、LLM 配置只从环境变量读取（`SPARK_LLM_BASE_URL`、`SPARK_LLM_API_KEY`、`SPARK_LLM_MODEL`）；**禁止**写入代码或 `application.yml`。三者任一缺失时 `LlmClient` 为 `UnavailablePlanner`：启动 WARN，所有请求直接失败并返回「未配置模型，无法理解请求」，不做规则兜底。内核不做领域路由与规则规划，模型在全部可发现候选里选，`PlanValidator` 做通用校验。
- Registry 对外返回的工具元数据**不含**内部地址、凭据、Owner 联系方式以外的敏感信息。
- 对外端点**不带身份头**：内核不识别用户；宿主用自己的拦截器 / 登录态建立上下文，并实现 `SessionIdResolver`（会话隔离键）与 `RunContextPropagator`（跨到 spark 工作线程）。
- **测试替身（fake planner）只允许放在宿主工程并以 profile 隔离**，内核不得出现：它必须认识领域动词，而平台模块有 `DOMAIN_WORDS` 红线。替身只负责「原话 → `PlanDraft`」这一段（真模型的职责），产出的草案必须经 `PlanValidator.decide`，与真模型走同一条校验路径——不得自行构造 `Plan` 绕过校验。生产 profile 下替身不得装配。**缺 `SessionIdResolver` Bean 时 starter 拒绝启动**；只有本地演示才设 `spark.runtime.demo-session-resolver=true` 放行「sessionId = conversationId」的演示实现（无会话隔离，启动 WARN）。

## 8. 提交

- Conventional Commits，scope 用模块名：`feat(gateway): validate output schema`。
- footer 含 `Change: {change-id}`。
