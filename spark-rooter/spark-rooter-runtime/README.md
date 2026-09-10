# spark-rooter-runtime

**决策面 + 状态面**。理解意图、路由领域、抽取参数、发现工具、规划、编排执行、管理 Run 生命周期、确认令牌、会话记忆与澄清屏。**不直连领域服务**：所有工具调用经 `ToolGatewayClient`（agent-safety §1）。无 Controller、无 Spring 组件注解：Bean 由 starter `RuntimeBeans` 装配，SSE 端点在 `spark-rooter-web-mvc`。

## 流程

`DomainResolver`（`DomainRouter` 关键词规则优先；未命中且配置了 LLM 时 `IntentClassifier` 在可发现领域内分类，越界视为 none）→ `ToolRegistryClient.search(domain)`（不带身份）→ `ArgumentExtractor`（实体 ID 正则；序数指代查记忆最近列表）→ `EntityRequirementCheck`（候选全需实体而缺失 → 记忆懒补位 → 仍缺则 `ClarificationScreen`）→ `LlmClient.plan`（Spring AI 或 `RuleBasedLlmClient`；参数 = 实体 + 抽取值 + `@SparkDefault`）→ `ToolSelectionValidator`（候选内、键 ⊆ inputSchema、值过 JSON Schema、实体值一致、前置齐全、不填可信参数）→ 逐步执行：低风险自动经 Gateway；需确认步骤由领域 `ScreenBuilder` 出确认屏 + 令牌（绑 `runId / actionId / argsDigest / conversationId / sessionId`）→ `WAITING_CONFIRMATION`。确认：令牌一次性 / TTL / 双绑定校验 → 领域 `ConfirmationRecheck` 重校验 → 执行 → 结果屏 → `COMPLETED` → `ConversationMemory` 写入。

事件发出前经 `sse-events` 契约校验；屏经 `ScreenRegistry.toUi` 校验（唯一 ui-schema 校验点）。

## 元数据

`ToolMetaRegistry`：`@SparkTool` 扫描出的前置步骤、实体参数、别名、单位、默认值、`clarifiesEntity`；规划器 / 校验器 / 抽取器读它。`IntentVerbs.PREREQUISITES` 与 `EntityRequirementCheck.ENTITY_ARGS` 只作手写 Manifest 的回落表。

## 包结构

`api`（`RuntimeExecutorConfiguration` 已迁 starter，仅余端口）/ `application`（编排器、令牌服务、抽取器、`meta/ToolMetaRegistry`、`screen/`、端口）/ `domain`（Run 状态机、Plan / Step、路由、令牌；无框架依赖）/ `infra`（内存仓储 / 记忆、LLM 工厂与客户端、进程内 Registry / Gateway 适配、自检）。
