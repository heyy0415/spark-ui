# Spec: test-backend-unit-tests-and-fixes-20260911

> 改造清单第 1 项（后端单元测试基建）+ 第 5 项（四处小修）。不改运行时行为（第 5 项 SessionIdResolver fail-fast 除外，属显式收紧）。

## 1. 背景

- 后端内核 7 个模块共 7709 行，`src/test` 为空。现有验证手段：11 个启动期 `SelfCheck`（生产代码里的测试，不进 CI 报告）与需要真起进程的 `e2e-backend.sh`。`RunOrchestrator`（807 行）、`PlanValidator`（236 行）、`ConfirmationTokenService`、`InvokeToolUseCase` 等分支密集逻辑没有可重复运行的细粒度断言，后续任何重构都缺安全网。
- `backend-standard.md` §1 明文：「首期不把测试作为门禁；后续如引入以 change 形式追加」——本 change 即该追加。
- 四处已确认的脱节 / 隐患：
  1. `SparkRooterProperties` 类注释与 `spark-rooter/README.md`、`backend-standard.md` §7、`architecture.md`、`agent-safety.md` §2、`06-backend-module-spec.md` 仍写「LLM 未配置 → 规则规划器 + noop 分类器 / 三层路由」；实际自 commit 03a7838 起为 `UnavailablePlanner` 直接失败，规则路由 / 分类 / 抽取 16 个类已删。
  2. `apps/chat/package.json` 声明 `zustand@5.0.15`，全仓零引用；L1 / 规则 / wiki 仍把 Zustand 列为技术栈。
  3. `spark-rooter-contracts/pom.xml` 用 `${project.basedir}/../../.harness/contracts` 相对路径把契约打进 jar，模块无法脱离仓库目录独立构建与发布。
  4. starter 默认 `SessionIdResolver` 返回前端可伪造的 `conversationId`（无会话隔离），仅 WARN 不阻断；对 starter 而言「默认不安全」是隐患。

## 2. 范围（In Scope）

### 2.1 后端单元测试基建（spark-rooter）

- 父 POM：`dependencyManagement` 已 import `spring-boot-dependencies`，直接引用其管理的 `junit-jupiter`、`assertj-core`、`mockito-core`（test scope，各子模块按需声明）；`pluginManagement` 固定 `maven-surefire-plugin` 3.5.4。本机 `~/.m2` 只缓存了 surefire 插件本体、junit-jupiter 5.12.2、assertj 3.27.7；`spring-boot-test`、surefire junit-platform provider、`junit-platform-launcher` 首次 `verify` 需经 Nexus 镜像联网拉取（`ci.mjs` 的 spark-rooter 步骤本就在线，host-demo `-o` 步骤不受影响）。`-Xlint:all -Werror` 与 spotless 同样作用于 `src/test`；测试类一律 `final`（避开 JDK 21 `this-escape`）。
- 测试类（全部 JUnit 5 + AssertJ；协作者优先手写 Fake，Mockito 仅在 Fake 不划算时使用）：

| 模块 | 测试类 | 覆盖的不变式 |
|---|---|---|
| runtime | `PlanValidatorTest` | 候选外 toolId、args 键 ∉ inputSchema、值类型不符、enum 违反 → `TOOL_SELECTION_INVALID`；实体值不在原话 ∪ 记忆 ∪ 最近行 / 不匹配 pattern / 必填实体缺失 → `EntityMissing`；需确认步骤缺前置、模型填 trusted-only 参数 → 拒绝；`@SparkDefault` 补齐进 args；`missingEntity()` 的 declared / label 规范化 / 反推三条路径 |
| runtime | `ConfirmationTokenServiceTest` | 正常签发消费；重放 → `TokenUnknown`；过期 / run-action 不符 / digest 不符 / conversationId 不符 / sessionId 不符 / formData 白名单外键 → `CONFIRMATION_REJECTED`；`TokenUnknown` 不是普通 reject（类型可区分） |
| runtime | `RunStateTest`、`RunTest` | 迁移表全枚举（合法 / 非法 / 自迁移幂等）；`attachPlan` 二次 → 异常；`fail` 写 failureCode；`currentStep` / `advance` / `idempotencyKeyFor` |
| runtime | `InMemoryStoresTest` | `InMemoryConversationMemory` TTL 惰性淘汰；`InMemoryRunRepository.evictExpired` 按 updatedAt；`InMemoryConfirmationTokenStore.consume` 一次性 |
| runtime | `PromptBuilderTest` | 工具 `description` 注入前转义花括号 / 反引号 / 换行且截断到 ≤ 500 字符（agent-safety §2「description 不可信」） |
| runtime | `RunOrchestratorTest`（T07a 非确认路径 + T07b 确认路径） | 用 Fake `LlmClient / ToolRegistryClient / ToolGatewayClient / ScreenBuilder / ConfirmationRecheck / RunEventSink` 驱动：① 无候选 → `message.delta` + `run.completed`；② `NoCapability`；③ `Clarify` 且无 clarifier → reply 作 `message.delta`；④ 单只读步骤 `Planned` → `tool.selected/started/completed` + `ui.replace` + `run.completed`，记忆写入实体；⑤ 需确认步骤 → `ui.replace` + `confirmation.required`，状态 `WAITING_CONFIRMATION`，`sink.close()`；⑥ `confirm` sessionId 不符 → `run.failed{CONFIRMATION_REJECTED}` 且 Run 状态不变；⑦ `confirm` 正确令牌 → recheck 调用 → 目标工具执行（trustedArgs 覆盖）→ `run.completed`；⑧ 同一令牌二次 confirm → 拒绝且状态不变；⑨ `LlmClient` 抛 `RunFailure` → `run.failed` 对应 code；⑩ `argsDigest`（包私有 static）键序无关且确定；⑪ 需确认工具缺 `ScreenBuilder` 确认屏 → fail-closed `INTERNAL_ERROR`；⑫ 确认后缺 `ConfirmationRecheck` → `INTERNAL_ERROR` |
| gateway | `ArgsDigestTest`、`RetryPolicyTest` | 摘要长度 32 hex、相同输入相同输出；仅 idempotency=required 或 sideEffect=false 才允许重试 |
| gateway | `InMemoryIdempotencyStoreTest` | 迁移 `GatewayIdempotencySelfCheck` 三条断言（Owner→Awaiting→同结果 / release 后可重 claim / complete 后 release 为 no-op），用 latch 而非调度巧合 |
| gateway | `InvokeToolUseCaseTest` | 寻址失败 `TOOL_NOT_FOUND`；入参不合 schema `INPUT_INVALID`；宿主策略拒绝 `FORBIDDEN`；无 handler `TOOL_NOT_FOUND`；handler 抛异常 `HANDLER_ERROR` 且非幂等工具不重试、幂等工具按 maxRetries 重试；超时 `TIMEOUT`；输出不合 schema `OUTPUT_INVALID`；成功路径脱敏 `password/token/secret/apiKey` → `***`；每次调用恰一条审计且失败审计 status 为 `failed:<code>`；幂等重放第二次审计 `replayed` 且 handler 只执行一次 |
| registry | `DiscoveryPolicyTest`、`InMemoryToolRegistryRepositoryTest`、`RegisterToolUseCaseTest`、`SearchToolsUseCaseTest` | 仅 active / canary 可发现；`putIfAbsent` 同 key 返回 false；注册契约校验失败抛 `ContractViolationException`、同 `toolId@version` 二次 → `ToolVersionConflictException`；search domain 为空返回全部、`ToolAccessPolicy` 过滤生效、候选恰 6 字段 |
| contracts | `SchemaValidatorTest`、`ContractsSelfCheckTest` | 9 个契约可加载；副本内全部示例通过对应契约；`bind` 对 null body 抛 `ContractViolationException`；`validateWithInlineSchema` 对不合规数据返回非空；`ContractsSelfCheck.contractOf` 最长匹配、`run()` 全部示例通过 |
| starter | `SessionIdResolverFailFastTest` | `ApplicationContextRunner`：未提供 `SessionIdResolver` Bean 且未开演示开关 → 启动失败，失败原因含指引文案；开关打开 → demo 实现装配并 WARN；宿主自定义 Bean → 默认实现让位 |

- **SelfCheck 处理**：11 个 `SelfCheck` **保留不删**（`e2e-backend.sh` / `deploy-verify.sh` / README 都断言其日志行，删除是行为变更）。本 change 只把同等不变式复制为单测；SelfCheck 的去留另开 change。
- CI：`ci.mjs` 的 `spark-rooter` 步骤由 `install -DskipTests` 改为 `install`（surefire 执行测试，任一失败退出码非 0；host-demo 步骤保留 `-DskipTests`，示例宿主无测试）；`backend-standard.md` §1 改为「后端质量门禁 = `./mvnw -q -B verify`（含单元测试）」。

### 2.2 小修（第 5 项）

1. **降级文案纠正**：`SparkRooterProperties` 类注释、`spark-rooter/README.md` 配置表与「参数与多轮」段、`backend-standard.md` §7、`architecture.md` 端口列表与运行链路第 2–4 步、`agent-safety.md` §2 前两条、`06-backend-module-spec.md`「Agent Runtime 专项」首条，统一改为「三项任一缺失 → `UnavailablePlanner`，所有请求返回『未配置模型，无法理解请求』；内核不做领域路由与规则规划，模型在全部可发现候选里选，`PlanValidator` 做通用校验」。一般只替换失实句子；`architecture.md` 运行链路第 2–3 步与 06-spec「Agent Runtime 专项」首条整句已无一处属实（引用已删类 `DomainRouter / ArgumentExtractor / EntityRequirementCheck / IntentVerbs / ToolSelectionValidator`），整句重写。
2. **删除 zustand**：`apps/chat/package.json` 去掉依赖并重新 `pnpm install` 更新 lockfile；`platform-owner.md` 技术栈行、`coding-standard.md` 状态管理条、`architecture.md` 状态表、`01-page-spec.md` / `02-feature-spec.md` 相关行改为「跨页面客户端状态：当前无；需要时以 change 引入」。
3. **契约副本进模块**：新增 `.harness/scripts/sync-contracts.mjs`：把 `.harness/contracts/*.schema.json`、`examples/*.example.json` 复制到 `spark-rooter/spark-rooter-contracts/src/main/resources/contracts/`（含按文件名排序生成的 `examples/INDEX`；`invalid/` 不复制），`--check` 模式比对内容不一致即非 0。副本提交进 git。`contracts pom` 删除相对路径 resource 与 antrun 插件，只用模块内资源。**`check-contracts.mjs` 末尾直接调用 `sync-contracts --check`**（阶段 3 门禁与 `contracts.md` 变更顺序无需改动即覆盖）；`ci.mjs` 不单列步骤；`harness-doctor` 必需文件加该脚本；`contracts.md` §1 后端行与 §6 步骤 2 注明「模块内副本由 `sync-contracts` 同步，`check-contracts` 校验一致」。「勿手改」说明放模块根 `spark-rooter-contracts/CONTRACTS.md`，不进 resources。`SchemaValidator` 类注释「构建时从 .harness/contracts 复制」与 `ContractsSelfCheck` 错误文案同步改为「由 sync-contracts 同步」。真源仍是 `.harness/contracts/`。
4. **SessionIdResolver fail-fast**：`SparkRooterProperties.Runtime` 增 `demoSessionResolver`（默认 `false`）。`RuntimeBeans` 默认 Bean：开关关闭 → 抛 `IllegalStateException`，文案指明「实现 `com.sparkrooter.spi.SessionIdResolver` 绑定登录态，或本地演示时设 `spark.runtime.demo-session-resolver=true`（无会话隔离）」；开关打开 → 现有 demo 实现 + WARN。`host-demo/application.yml` **顶层文档**设 `true`，对 default / e2e / e2e-ttl 三种启动都生效：e2e profile 下宿主 `DemoSessionIdResolver`（`@Profile("e2e")`）顶替默认 Bean，WARN 不出现；e2e-ttl 与 deploy-verify / Docker 的无 profile 启动没有宿主实现，依赖该开关放行 demo 实现并打 WARN。README 三处、host-demo README、`spark-rooter/README.md`、`backend-standard.md` §7、`agent-safety.md` §3 的「默认 = conversationId 仅演示，启动 WARN」改为「默认拒绝启动；演示需显式开关」。`SessionIdResolverFailFastTest` 用 `ApplicationContextRunner` 整体加载 `SparkRooterAutoConfiguration`（贴近宿主真实启动）；若裸 runner 起不来，允许 `withBean` 补最小 Bean 并记入 summary 经验沉淀。启动失败断言用 `getStartupFailure()` 的 root cause 消息。

## 3. 非目标（Out of Scope）

- 不删除任何 `SelfCheck`，不改 `e2e-backend.sh` / `deploy-verify.sh` 断言。
- 不引入 Spring Boot 集成测试（`@SpringBootTest` 起整个上下文）；starter 只用 `ApplicationContextRunner`。
- `web-mvc`（异常 → `ErrorResponse` 映射、`SseRunEventSink`）与 starter 的 `@SparkTool` 扫描 / `ManifestDeriver` 单测不在本 change（后者由 `ManifestParitySelfCheck` + e2e 覆盖）；`LlmPlanner` 的真模型调用不做单测。
- 不改 `RunOrchestrator` 等被测类的实现（发现 bug 记入 summary 另开 change）。
- 不做前端单测（第 2 项）、不迁 Playwright（第 3 项）、不配置 GitHub Actions（第 4 项）。
- 不改契约 Schema 内容；不改 `@contracts` 前端别名（前端仍直读 `.harness/contracts`）。
- 不改线程池、LLM 超时等韧性项（第二批）。

## 4. 核心场景

- 开发者改动 `PlanValidator` 后执行 `node .harness/scripts/mvn.mjs -q -B test -pl spark-rooter-runtime`，秒级得到断言结果，无需起进程、无需模型。
- `pnpm -C .harness run ci` 现在会：契约校验（含副本一致性）→ 模块依赖 → 种子 → 前端 → **后端编译 + 单测 + spotless** → host-demo 离线打包。任一失败退出码非 0。
- 宿主未实现 `SessionIdResolver` 直接启动 → 启动失败并打印指引；host-demo 因 yml 顶层开关在三种 profile 下都正常启动：e2e 用宿主实现（无 WARN），e2e-ttl / 无 profile 用 demo 实现（WARN 一次）。
- 运行链路（前端 → Runtime → Registry → Gateway → 领域 → 前端）**不变**。

## 5. 契约影响

- `.harness/contracts/*.schema.json`：**NONE**（无 Schema 变更）。
- 新增契约副本目录 `spark-rooter/spark-rooter-contracts/src/main/resources/contracts/`（由脚本生成、提交、CI 校验与真源一致）。

## 6. 验收标准

全部可程序化：

1. `node .harness/scripts/mvn.mjs -q -B verify` 退出码 0；`find spark-rooter -path '*/target/surefire-reports/*.xml' | wc -l` ≥ 17；汇总 `tests="N"` 之和 ≥ 83、`failures="0"`、`errors="0"`。
2. `pnpm -C .harness run ci` 退出码 0；随后 `spark-rooter/*/target/surefire-reports/TEST-*.xml` ≥ 17 个且 failures / errors 全 0（`-q` 模式下 surefire 不打印 `Tests run:`，以报告为证）。
3. `node .harness/scripts/sync-contracts.mjs --check` 退出码 0；`pnpm -C .harness run check-contracts` 退出码 0；手工改动副本任一字节后两者退出码均为 1（验证后还原）。
4. `grep -rnE "规则规划器|noop 分类器|IntentClassifier|DomainRouter|ArgumentExtractor|EntityRequirementCheck|IntentVerbs|ToolSelectionValidator" spark-rooter/*/src spark-rooter/README.md .harness/rules .harness/wiki .harness/skills .harness/agents CLAUDE.md AGENTS.md README.md` 无命中（`.harness/changes/` 历史记录除外）。
5. `grep -rn "zustand" spark-ui/apps/chat/package.json spark-ui/pnpm-lock.yaml .harness/rules .harness/agents .harness/skills .harness/wiki CLAUDE.md AGENTS.md` 无命中；`pnpm -C spark-ui run ci` 退出码 0。
6. `grep -c "harness/contracts" spark-rooter/spark-rooter-contracts/pom.xml` 为 0；`node .harness/scripts/mvn.mjs -q -o -pl spark-rooter-contracts -am package` 在把 `.harness/contracts` 临时改名后仍退出码 0（验证后还原）。
7. `SessionIdResolverFailFastTest` 三个用例通过；以与基线相同的模型配置（本机当前无 `SPARK_LLM_*`，基线 60 通过 / 101 失败，失败全因 `UnavailablePlanner`，完整输出见 `ci_result/e2e_baseline.txt`）跑 `SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh`：`BOOT FAILED` 不出现，`selfcheck:` 各行与基线一致，通过数 ≥ 60；`backend.log`（e2e profile，宿主实现）**不含** `SessionIdResolver 为 demo 实现`；`backend-ttl.log`（e2e-ttl，开关放行）含该 WARN 恰 1 次。
8. `pnpm -C .harness run doctor` 退出码 0。

## 7. 风险与权衡

| 风险 | 缓解 |
|---|---|
| `-Xlint:all -Werror` 让测试代码里的泛型 / 序列化警告变成编译错误 | 优先手写 Fake 而非 Mockito 泛型 mock；测试类一律 `final`（JDK 21 `this-escape`）；测试类里的匿名异常类加 `serialVersionUID`；必要时对单个测试类 `@SuppressWarnings` 并注释原因 |
| 首次 `verify` 需联网拉取 `spring-boot-test` / surefire provider / `junit-platform-launcher` | `ci.mjs` spark-rooter 步骤本就在线；host-demo `-o` 步骤不依赖这些产物 |
| `ApplicationContextRunner` 整体加载 `SparkRooterAutoConfiguration` 在裸环境（0 个 `@SparkTool`、无 Web）可能起不来 | T08 第一步先验证；起不来则 `withBean` 补最小 Bean 并记入经验沉淀 |
| 在 `@Bean` 方法抛异常时指引文案被 `BeanCreationException` 包裹 | 测试断言用 root cause 消息；文案本身两条出路完整，可选后续加 `FailureAnalyzer` |
| CI 时长增加 | 单测全部进程内、无网络、无模型，预计 < 30s；`RunOrchestratorTest` 用固定 `Clock`，不 sleep |
| SessionIdResolver fail-fast 是行为收紧，可能让未读文档的接入方启动失败 | 失败文案自带两条出路；host-demo / Docker 镜像经 yml 开关不受影响；README 与规则同步更新（agent-safety §3：默认不安全是 MUST FIX 级隐患，收紧符合红线精神） |
| 契约副本与真源漂移 | `check-contracts.mjs` 末尾调用 `sync-contracts --check`，阶段 3 / 4 / 6 门禁与 `contracts.md` 变更顺序全部覆盖；模块根 `CONTRACTS.md` 说明「勿手改」 |
| 删 zustand 后规则仍指向它 | 同 change 改 L1 / 规则 / wiki，`harness-doctor` 通过；`platform-owner.md` 技术栈行同步 |
| 单测暴露被测类 bug | 记入 summary「经验沉淀」，另开 fix change；本 change 不改实现（若 bug 阻塞测试则该用例标 `@Disabled("见 change xxx")` 并说明） |
| `agent-safety.md` §2 修改涉及安全边界表述 | 只替换「三层路由 / 确定性抽取」失实句为现状（模型主导 + 代码核实），边界条款（候选内选择、字段 ⊆ schema、实体出自原话或上下文、description 不可信）逐字保留 |
