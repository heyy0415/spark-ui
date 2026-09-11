# Tasks: test-backend-unit-tests-and-fixes-20260911

编码顺序：contracts（副本脚本）→ spark-rooter → spark-ui → harness 文档。每个 task ≤ 0.5 天。

「所属端」除规范的 contracts / spark-rooter / spark-ui 外，本 change 使用 `harness` 指 `.harness/scripts`、`.harness/rules`、`.harness/wiki`、`.harness/skills`、`.harness/agents` 与根 README / CLAUDE / AGENTS 等工程基础设施与文档。

## T01 契约副本同步脚本

- **目标**：`spark-rooter-contracts` 不再依赖仓库外相对路径。
- **所属端**：harness + spark-rooter
- **输入**：`.harness/contracts/*.schema.json`、`examples/*.example.json`；`spark-rooter-contracts/pom.xml`；`check-contracts.mjs`
- **输出**：`.harness/scripts/sync-contracts.mjs`（默认同步，`--check` 只比对；`examples/INDEX` 按文件名排序；不复制 `invalid/`）；`spark-rooter/spark-rooter-contracts/src/main/resources/contracts/{*.schema.json, examples/*.example.json, examples/INDEX}`；模块根 `spark-rooter-contracts/CONTRACTS.md`（说明副本来源与「勿手改」）；pom 删除第二个 `<resource>` 与 antrun 插件；`.harness/package.json` 增 `sync-contracts` script；`check-contracts.mjs` 末尾 `spawnSync` 调用 `sync-contracts.mjs --check` 并合并退出码；`harness-doctor.mjs` required 列表加脚本；`SchemaValidator` 类注释与 `ContractsSelfCheck` 错误文案改为「由 sync-contracts 同步」。
- **验收**：`node .harness/scripts/sync-contracts.mjs --check` 退出 0；改动副本一字节后 `--check` 与 `pnpm -C .harness run check-contracts` 都退出 1；`grep -c "harness/contracts" spark-rooter/spark-rooter-contracts/pom.xml` = 0；`node .harness/scripts/mvn.mjs -q -B -pl spark-rooter-contracts -am package -DskipTests` 退出 0。
- **依赖**：无

## T02 测试依赖与 surefire

- **目标**：各模块可写 JUnit 5 测试并由 `verify` 执行。
- **所属端**：spark-rooter
- **输入**：`spark-rooter/pom.xml`
- **输出**：父 POM `pluginManagement` 固定 `maven-surefire-plugin` 3.5.4；contracts / registry / gateway / runtime / starter 五个子模块 pom 增 `junit-jupiter`、`assertj-core`（test）；starter 另加 `spring-boot-test`（`ApplicationContextRunner`）；runtime 与 gateway 加 `mockito-core`（test，可选使用）。
- **验收**：`node .harness/scripts/mvn.mjs -q -B test-compile` 退出 0；`node .harness/scripts/mvn.mjs -q -B test` 退出 0（此时 0 个测试亦可）。
- **依赖**：无

## T03 contracts 模块单测

- **目标**：契约加载与示例校验有可重复断言。
- **所属端**：spark-rooter
- **输入**：`SchemaValidator`、`ContractsSelfCheck`、T01 的副本
- **输出**：`spark-rooter-contracts/src/test/java/com/sparkrooter/contracts/SchemaValidatorTest.java`、`infra/selfcheck/ContractsSelfCheckTest.java`
- **验收**：surefire 报告 `tests ≥ 6, failures 0`；覆盖 spec §2.1 表 contracts 行全部条目。
- **依赖**：T01、T02

## T04 registry 模块单测

- **目标**：发现策略、仓储、注册 / 搜索用例有断言。
- **所属端**：spark-rooter
- **输入**：`DiscoveryPolicy`、`InMemoryToolRegistryRepository`、`RegisterToolUseCase`、`SearchToolsUseCase`、`tool-manifest.example.json`
- **输出**：`spark-rooter-registry/src/test/java/com/sparkrooter/registry/**` 四个测试类；测试用 Manifest 从契约副本示例读取再改字段。
- **验收**：`tests ≥ 10, failures 0`；覆盖 spec §2.1 表 registry 行。
- **依赖**：T02、T03（复用 SchemaValidator 构造方式）

## T05 gateway 模块单测

- **目标**：执行面管线每个失败码与成功脱敏 / 审计 / 幂等重放都有断言。
- **所属端**：spark-rooter
- **输入**：`InvokeToolUseCase`、`InMemoryIdempotencyStore`、`ArgsDigest`、`RetryPolicy`、`GatewayIdempotencySelfCheck`
- **输出**：`spark-rooter-gateway/src/test/java/com/sparkrooter/gateway/**`：`ArgsDigestTest`、`RetryPolicyTest`、`InMemoryIdempotencyStoreTest`、`application/InvokeToolUseCaseTest`（Fake `ToolResolver / ToolHandler / AuditSink / RunContextPropagator`；`ObjectProvider<ToolAccessPolicy>` 用匿名实现，至少覆写 `getObject()` 与 `getIfAvailable()`，「无策略」用例返回 `null` 触发全放行；`ExecutorService` 用 `Executors.newFixedThreadPool(2)` 并在 `@AfterEach` 关闭）
- **验收**：`tests ≥ 18, failures 0`；覆盖 spec §2.1 表 gateway 三行；超时用例 handler sleep 不超过 300ms。
- **依赖**：T02、T03

## T06a runtime 测试夹具 + PlanValidator 单测

- **目标**：`PlanValidator` 全部拒绝 / 补齐 / `missingEntity` 路径有断言；后续 runtime 测试共用夹具。
- **所属端**：spark-rooter
- **输入**：`PlanValidator`、`PlanSelfCheck`、`ToolMetaRegistry`、`ToolSearch.ToolCandidate`
- **输出**：`spark-rooter-runtime/src/test/java/com/sparkrooter/runtime/support/TestFixtures.java`（测试用 `ObjectMapper`（JavaTimeModule、Jdk8Module、禁 timestamps）、`SchemaValidator`、契约合法的 `ToolCandidate` 构造器、`ToolMetaRegistry` 构造器）；`infra/llm/PlanValidatorTest.java`；`infra/llm/PromptBuilderTest.java`（description 转义与 ≤500 截断）
- **验收**：`tests ≥ 16, failures 0`；覆盖 spec §2.1 表 PlanValidator 与 PromptBuilder 行全部条目。
- **依赖**：T02、T03

## T06b runtime 令牌 / 状态机 / 内存存储单测

- **目标**：令牌服务、Run 状态机、三个内存存储有断言。
- **所属端**：spark-rooter
- **输入**：`ConfirmationTokenService`、`TokenSelfCheck`、`Run`、`RunState`、三个 `InMemory*`
- **输出**：`application/ConfirmationTokenServiceTest.java`、`domain/RunStateTest.java`、`domain/RunTest.java`、`infra/InMemoryStoresTest.java`
- **验收**：`tests ≥ 18, failures 0`；覆盖 spec §2.1 表对应三行全部条目。
- **依赖**：T06a

## T07a RunOrchestrator 脚手架 + 非确认路径

- **目标**：编排器非确认路径可离线重复验证；Fake 与录制 sink 供 T07b 复用。
- **所属端**：spark-rooter
- **输入**：`RunOrchestrator`、`ScreenRegistry`、`RecheckRegistry`、spi `ScreenBuilder / ConfirmationRecheck / UiNodes`、T06a 夹具
- **输出**：`support/` 下 `FakeLlm`（按脚本返回 Decision 或抛 RunFailure）、`FakeRegistry`、`FakeGateway`（按 toolId 返回输出、记录调用）、`FakeScreens`（用 `UiNodes` 构造契约合法的 Card + Form 确认屏与 Card 结果屏）、`FakeRecheck`、`RecordingSink`（收集事件名与 data、记录 close 次数）、`OrchestratorFixture`（装配 12 个协作者，固定 `Clock`）；`application/RunOrchestratorTest.java` 场景 ①②③④⑨⑩⑪
- **验收**：`tests ≥ 7, failures 0`；每个事件序列断言精确到事件名列表。
- **依赖**：T06a

## T07b RunOrchestrator 确认路径

- **目标**：确认链路（令牌、重校验、trustedArgs、并发拒绝、fail-closed）可离线重复验证。
- **所属端**：spark-rooter
- **输入**：T07a 脚手架
- **输出**：`RunOrchestratorTest` 追加场景 ⑤⑥⑦⑧⑫（可拆为 `RunOrchestratorConfirmTest`）
- **验收**：`tests ≥ 5, failures 0`；⑥⑧ 断言 Run 状态仍为 `WAITING_CONFIRMATION`；⑦ 断言 Gateway 收到的目标工具参数含 trustedArgs 覆盖值。
- **依赖**：T07a、T06b

## T08 SessionIdResolver fail-fast

- **目标**：默认不安全 → 默认拒绝启动，演示显式开关。
- **所属端**：spark-rooter
- **输入**：`SparkRooterProperties`、`RuntimeBeans`、`host-demo/application.yml`
- **输出**：`Runtime` record 增 `@DefaultValue("false") boolean demoSessionResolver`；`RuntimeBeans.sparkRooterSessionIdResolver` 按开关抛 `IllegalStateException`（文案见 spec §2.2.4）或返回 demo 实现；`application.yml` **顶层文档** `spark.runtime.demo-session-resolver: true` 并注释三种 profile 的行为；`spark-rooter-spring-boot-starter/src/test/java/com/sparkrooter/starter/SessionIdResolverFailFastTest.java`（`ApplicationContextRunner` 整体加载 `SparkRooterAutoConfiguration`，三用例；第一步先验证裸 runner 能起，起不来则 `withBean` 补最小 Bean 并记入 summary）。
- **验收**：三用例通过；`mvn.mjs -q -B install` 退出 0、`cd spark-rooter/examples/host-demo && rm -rf target && mvn -q -B -o package -DskipTests` 重打 jar 后 `SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh` 无 `BOOT FAILED`，通过数 ≥ 60（基线 `ci_result/e2e_baseline.txt`，无模型），`selfcheck:` 各行与基线一致；`backend.log` 不含 `SessionIdResolver 为 demo 实现`；`backend-ttl.log` 含该 WARN 恰 1 次。
- **依赖**：T02

## T09 降级文案与 SessionIdResolver 文档

- **目标**：文档与实现一致。
- **所属端**：spark-rooter + harness
- **输入**：spec §2.2.1、§2.2.4 列出的文件
- **输出**：`SparkRooterProperties` 类注释；`spark-rooter/README.md`（配置表 + 三步接入第 3 步 + 「参数与多轮」段）；`README.md`（接入第 3 条、Docker 段）；`examples/host-demo/README.md`；`.harness/rules/backend-standard.md` §1、§7；`.harness/rules/agent-safety.md` §2 前两条、§3 令牌条；`.harness/wiki/architecture.md` 端口列表 + 运行链路第 2–4 步整句重写；`.harness/skills/coding-skill/specs/06-backend-module-spec.md` Agent Runtime 专项首条整句重写。
- **验收**：spec §6 第 4 条 grep 无命中；`grep -rn "默认实现 = conversationId 仅演示\|默认实现回落为\|默认实现直接返回\|故意用默认实现\|默认 = conversationId" README.md spark-rooter/README.md spark-rooter/examples/host-demo/README.md .harness/rules .harness/wiki` 无命中（新句子描述 fail-fast + 显式开关，允许含「启动 WARN」）；`pnpm -C .harness run doctor` 退出 0。
- **依赖**：T08

## T10 删除 zustand

- **目标**：去掉零引用依赖与过时技术栈描述。
- **所属端**：spark-ui + harness
- **输入**：`apps/chat/package.json`、`pnpm-lock.yaml`、spec §2.2.2 文件
- **输出**：删依赖并 `pnpm -C spark-ui install` 更新 lockfile；`platform-owner.md`、`coding-standard.md`、`architecture.md`、`01-page-spec.md`、`02-feature-spec.md` 对应行。
- **验收**：spec §6 第 5 条 grep 无命中；`pnpm -C spark-ui run ci` 退出 0。
- **依赖**：无

## T11 CI 接入测试

- **目标**：单测成为门禁。
- **所属端**：harness
- **输入**：`ci.mjs`、`backend-standard.md` §1、`code-review/SKILL.md`
- **输出**：`ci.mjs` spark-rooter 步骤去掉 `-DskipTests`（host-demo 步骤保留）；`backend-standard.md` §1 门禁句改为含单测；`code-review/SKILL.md` 后端段注明 `verify` 含单测。
- **验收**：`pnpm -C .harness run ci` 退出 0 且 surefire 报告 ≥ 17 个、failures / errors 全 0；人为让一个测试失败后 `mvn.mjs -q -B test` 退出码非 0（验证后还原）。
- **依赖**：T03–T08（含 T06a/b、T07a/b）
