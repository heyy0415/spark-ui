# Tasks: feat-provider-http-transport-20260912

> 契约先行（T01）。T02–T03 是**纯结构准备**，必须在任何新功能前完成且证明零回归——它们改的是现有代码的位置与签名，一旦和新功能混在一起，回归失败时无法定位原因。
>
> **阶段 2 评审后修订**：T07 / T08 / T09 / T10 的输出与验收按 spec §3.1a（跨进程语义失效）与 §3.4（trustedArgs 值可信性）扩充。评审 3 项 MUST FIX 全部落到具体 task 的可断言验收，而非只写进 spec 文字。

依赖链：`T01 → T02 → T03 → T04 → {T05, T06} → T07 → T08 → T09 → T10 → T11`

---

## T01 契约：tool-manifest 增 provider 段

- **目标**：`protocol=http` 时能表达「去哪调」，且与 `in-process` 互斥。
- **所属端**：contracts（**契约 task，前置于所有实现**）
- **输入**：`.harness/contracts/tool-manifest.schema.json`；`.harness/contracts/examples/`
- **输出**：
  - `provider` 段（`serviceName` 必填、`baseUrl` / `instanceId` 可选），按 spec §3.2
  - 2 条 `if/then`：`protocol=http ⇒ provider` 必填；`protocol=in-process ⇒ provider` 不得出现
  - 合法示例：新增 1 个 `protocol=http` 形态（现有 in-process 示例不动）
  - invalid 示例 **≥3**：`http` 缺 `provider`、`in-process` 带 `provider`、`serviceName` 不合 pattern
  - `pnpm -C .harness run sync-contracts` 同步后端副本
  - `ToolManifest` record 增 `Provider`（`spark-rooter-contracts`）
- **验收**：
  - `pnpm -C .harness run check-contracts` **0**（含副本一致性）
  - 3 个 invalid 示例**逐个确认被拒**（不是"跑了没报错"，要看到拒绝）
- **依赖**：无

## T02 结构：ToolMeta / ParamMeta 两个 record 下沉 spi

- **目标**：解开 `ManifestDeriver` → `runtime` 的依赖，为 provider 复用铺路。
- **所属端**：spark-rooter（spi + runtime）
- **输入**：`runtime/application/meta/ToolMetaRegistry.java`（record 定义）；其 8 个使用方
- **输出**：
  - `ToolMeta` / `ParamMeta` 移到 `spark-rooter-spi`（纯数据，已核实无 Spring / 无 runtime 类型）
  - `ToolMetaRegistry` 容器类**留在 runtime**，改为 import spi 的 record
  - 8 个使用方改 import
- **验收**：
  - `mvnw install` **0**；`mvnw test` **0**
  - `pnpm -C .harness run check-module-deps` **0**
  - **零行为变化**：`e2e-backend.sh` **161 passed, 0 failed**
- **依赖**：T01（避免两轮 sync-contracts）

## T03 结构：AnnotatedToolHandler + ManifestDeriver 下沉 spi

- **目标**：两侧共用推导逻辑，避免两份实现漂移。
- **所属端**：spark-rooter（spi + starter）
- **输入**：`starter/tool/{AnnotatedToolHandler,ManifestDeriver}.java`
- **输出**：
  - 两个类移入 `spark-rooter-spi`（`ManifestDeriver` 此时已不依赖 runtime，由 T02 保证）
  - `SparkToolScanner` **留在 hub starter**，改 import
  - **不改推导逻辑**：这是纯搬迁，git 应识别为 rename
- **验收**：
  - `mvnw install` 0；`mvnw test` 0
  - `git status` 应显示这两个文件为 **rename**（纯搬迁的机械证据）
  - `e2e-backend.sh` **161 passed, 0 failed**
- **依赖**：T02

## T04 结构：ToolTransport 改签名 + InProcessToolTransport

- **目标**：Gateway 的调用点改为经 transport 分派，进程内行为由 `InProcessToolTransport` 原样承载。
- **所属端**：spark-rooter（spi + gateway + starter）
- **输入**：`spi/ToolTransport.java`；`gateway/application/InvokeToolUseCase.java`（第 265 / 311 行）
- **输出**：
  - `ToolTransport.invoke(ToolManifest, JsonNode, ExecutionContext)`（改签名，零实现零使用故无破坏）
  - `InProcessToolTransport`：`handlers.get(manifest.key())` → 反射调用，**逻辑逐行搬自现状**
  - `InvokeToolUseCase` 改为经 transport 调用；Schema 校验 / 幂等 / 超时 / 重试 / 脱敏 / 审计**全部留在原处不动**
  - starter 装配 `InProcessToolTransport`（`@ConditionalOnMissingBean`）
- **验收**：
  - 单测断言：**治理顺序未变**（寻址 → 输入校验 → 访问策略 → 幂等 → 调用 → 输出校验 → 脱敏 → 审计）
  - `mvnw test` 0
  - `e2e-backend.sh` **161 passed, 0 failed** —— 本 task 的核心验收，证明分派重构零回归
- **依赖**：T03

## T05 provider-starter 骨架（薄依赖 + JDK 17）

- **目标**：provider 能扫 `@SparkTool`、推导 Manifest、推送到 hub，且不被拖进 hub 依赖。
- **所属端**：spark-rooter（新模块）
- **输入**：spec §3.3
- **输出**：
  - 新模块 `spark-provider-spring-boot-starter`，`maven.compiler.release=17`
  - pom 只依赖 `spark-rooter-spi` / `spark-rooter-contracts` / `spring-boot-autoconfigure` / `spring-web`
  - `ProviderToolScanner`（provider 侧扫描器，职责与 hub 侧不同：推送远端 + 注册本地端点，不碰 Registry / Gateway）
  - `ManifestPublisher`：启动后 POST Manifest 到 hub（`RestClient`）
  - 配置：`spark.provider.hub-url`、`spark.provider.service-name`、`spark.provider.token`
  - 无 web 栈时端点不装配，但**启动日志 WARN 明示**（spec §8 决策）
- **验收**：
  - `mvnw install` 0；以 JDK 17 编译通过
  - **新增门禁 + 双向自证**：`check-module-deps` 断言 provider-starter pom 不含 `spring-ai` / `spark-rooter-{runtime,registry,gateway,web-mvc}` / `spring-boot-starter-web`。插入违规必须变红，恢复必须变绿
  - **新增门禁**：provider-starter 源码不出现 `userId` / `tenantId` / `Principal`
- **依赖**：T04

## T06 双向认证

- **目标**：关掉 spec §1.2 的安全漏洞——任意调用方可注册伪造工具。
- **所属端**：spark-rooter（spi + web-mvc + provider-starter）
- **输入**：`webmvc/registry/ToolRegistryController.java`（现无认证）
- **输出**：
  - `ProviderAuth` 端口（spi），默认实现为配置化共享密钥
  - **常量时间比较**（`MessageDigest.isEqual`），不用 `String.equals`
  - hub 侧：注册端点校验 token，且校验 `manifest.provider.serviceName` 与密钥绑定的服务一致（防服务 A 注册服务 B 的工具）
  - provider 侧：执行端点校验 hub 出示的 token
  - **密钥未配置 → fail-fast 拒绝启动**（与 `SessionIdResolver` 既有决策一致）；单体形态不装配这些 Bean，不受影响
- **验收**：
  - 单测：无 token 拒绝、错 token 拒绝、serviceName 不匹配拒绝、常量时间比较被实际使用
  - 单体形态不受影响：`e2e-backend.sh` **161 passed, 0 failed**
- **依赖**：T04

## T07 HttpToolTransport + provider 执行端点

- **目标**：hub 能真正调到远程工具。
- **所属端**：spark-rooter（gateway + provider-starter）
- **输入**：T04 的 transport 抽象；T06 的认证
- **输出**：
  - `HttpToolTransport`：读 `manifest.provider()` 坐标 → POST 到 provider
  - `ProviderEndpointResolver`（spi）：`serviceName` → base URL，默认实现取 Manifest 的 `baseUrl`；接注册中心的宿主自行替换
  - provider 侧 `POST /spark/tools/invoke` 端点
  - Gateway 按 `protocol` 分派；`mcp` **注册时即拒绝**（非目标）
  - 远程失败的错误映射（超时 / 连接失败 / 非 2xx → `GatewayException`，不泄漏 provider 内部错误给用户）
  - **`RetryPolicy` 增协议维度**（spec §3.1a.1）：`protocol=http` 时 `TimeoutException` **不重试**（结果未知，重试不安全）；`ConnectException`（确定未到达）仍可重试
  - **HTTP 客户端超时 ≤ `manifest.execution().timeoutMs()`**，连接超时与读超时分开配置
  - **`idempotencyKey` 随请求传到 provider**；provider-starter 提供默认幂等去重（同 key 返回首次结果）
  - **provider 侧返回前脱敏**（spec §3.1a.2），复用 hub 的 `SENSITIVE_KEYS` 口径
  - `baseUrl` 为 `http://` 时启动 **WARN**
- **验收**：
  - 单测断言：**远程路径同样经过** Schema 校验 + 幂等 + 审计（spec §5 首条风险的缓解，必须证明而非声称）
  - 单测断言（评审 M-1，逐条）：http 形态超时**不重试**；`ConnectException` **重试**；HTTP 读超时 ≤ Manifest 超时；provider 侧同 `idempotencyKey` 的第二次请求返回首次结果而**不重复执行**
  - 单测断言（评审 M-2）：provider 侧响应体中 `password` / `token` / `secret` / `apiKey` 已脱敏
  - `mvnw test` 0
- **依赖**：T05, T06

## T08 跨服务确认重校验

- **目标**：高风险工具在微服务拓扑下仍走完整确认链。
- **所属端**：spark-rooter（runtime + provider-starter）
- **输入**：`spi/ConfirmationRecheck.java`；spec §3.4
- **输出**：
  - provider 侧 `POST /spark/tools/confirm-recheck`：调 `recheckToolId` → 跑 `reject()` / `trustedArgs()` → 返回判定
  - hub 侧：令牌校验保持原处不变，重校验改为远程调用
  - **硬校验一（键）**：provider 返回的 `trustedArgs` 键集合必须 ⊆ `trustedArgKeys()`，超出即**拒绝 + 审计**（防 provider 覆盖任意参数）
  - **硬校验二（值）**（评审 M-3）：对 `trustedArgs` 中「在确认屏展示过的字段」做**纯字面比对**，与 `shownUi` 不一致即拒绝 + 审计告警，对用户提示「金额已变动，请重新确认」。hub 只做字符串比对，不引入领域知识
- **验收**：
  - 单测：越界键被拒、拒绝路径不执行正式工具、令牌仍是一次性
  - 单测（M-3）：provider 返回的金额与确认屏展示值不一致时**拒绝执行**，且审计留痕
  - `mvnw test` 0
- **依赖**：T07

## T09 端到端示例（独立 provider 进程）

- **目标**：证明整条链路真的能跑，而不只是单测通过。
- **所属端**：examples
- **输入**：现有 `examples/domains/*` 与 `examples/host-demo`
- **输出**：
  - 一个独立 provider 示例进程（复用现有某个领域的工具，避免新造业务）
  - 脚本：起 hub + provider 两个进程，跑「搜索 → 规划 → 确认 → 跨服务重校验 → 执行」
  - 日志断言（复用 `check-log-assertions.mjs` 的口径）
- **验收**：
  - 全链路脚本退出码 **0**
  - 日志断言**必须给出明确条数**（评审 S-1）：编码时先实测，把 `N passed, 0 failed` 写进 tasks 与 summary。无数字的验收会退化成「跑通就算过」，某条断言被顺手删掉也不会变红——前一个 change 的评审 S-2 已踩过同类问题
  - 断言至少覆盖：Manifest 推送成功、hub 按 http 分派、provider 收到 `idempotencyKey`、跨服务重校验往返、`trustedArgs` 合并后执行、审计两侧留痕
  - **单体示例不受影响**：`examples/host-demo` 照常 `mvn -o package` 0
- **依赖**：T08

## T10 规则与文档同步

- **目标**：新拓扑的红线与边界写进规则，不靠口头约定。
- **所属端**：harness
- **输出**：
  - `project-structure.md`：模块依赖图增 provider；红线增「provider-starter 不得依赖 hub 模块 / Spring AI」
  - `backend-standard.md`：provider 侧约束（JDK 17、不得自行重试、认证 fail-fast）
  - `agent-safety.md`：跨服务确认的边界（令牌在 hub、判定在 provider、`trustedArgs` 键越界拒绝）
  - `contracts.md`：`provider` 段说明与 `protocol` 语义
  - `wiki/architecture.md`：两种拓扑对照
  - **新增门禁**（评审 S-2）：grep 扫 `examples/domains/*` 与 provider 示例，禁止 `@Retryable` / `RetryTemplate` / 手写重试循环。`ToolHandler` javadoc 早有「不得自行重试」的文字约定但从无门禁；跨进程后 provider 自行重试 + hub 重试会指数放大（M-1 场景更糟）。不求完备，但明显写法必须变红，**双向自证**
  - `RunContextPropagator` 的 javadoc 增「只在 in-process 形态生效」（spec §3.1a.3）
- **验收**：
  - `pnpm -C .harness run doctor` 0；`pnpm -C .harness run ci` 0
  - 新增重试门禁双向自证（插入 `@Retryable` 变红、移除变绿）
- **依赖**：T09

## T11 全链路回归

- **目标**：证明新增微服务能力**没有动摇单体形态**。
- **所属端**：harness
- **验收**（数字必须与本 change 前完全一致）：
  - `pnpm -C .harness run ci` **0**
  - `e2e-backend.sh` **161 passed, 0 failed**
  - `e2e-frontend.sh` **7 passed**
  - `deploy-verify.sh` **12 passed, 0 failed**
  - `pnpm -C spark-ui run test` **106 passed**（前端零改动，数字不应变）
  - T09 的跨服务链路脚本 0
- **依赖**：T10
