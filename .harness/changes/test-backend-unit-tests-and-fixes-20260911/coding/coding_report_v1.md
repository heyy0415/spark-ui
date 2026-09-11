# Coding Report v1 — test-backend-unit-tests-and-fixes-20260911

## 1. 改动文件

### spark-rooter（后端）

| 文件 | 变更 |
|---|---|
| `pom.xml` | pluginManagement 固定 `maven-surefire-plugin` 3.5.4 |
| `spark-rooter-contracts/pom.xml` | 删相对路径 `<resource>` 与 antrun 插件；加 junit-jupiter / assertj（test） |
| `spark-rooter-contracts/CONTRACTS.md` | 新增：副本来源与「勿手改」 |
| `spark-rooter-contracts/src/main/resources/contracts/**` | 新增 37 个文件：9 schema + 27 example + INDEX（`sync-contracts` 生成） |
| `spark-rooter-contracts/src/main/java/.../SchemaValidator.java` | 类注释：副本由 sync-contracts 同步 |
| `spark-rooter-contracts/src/main/java/.../ContractsSelfCheck.java` | INDEX 注释与缺失文案改为 sync-contracts |
| `spark-rooter-contracts/src/test/java/.../SchemaValidatorTest.java` | 新增 8 用例 |
| `spark-rooter-contracts/src/test/java/.../ContractsSelfCheckTest.java` | 新增 3 用例 |
| `spark-rooter-registry/pom.xml` | 测试依赖 |
| `spark-rooter-registry/src/test/java/.../{DiscoveryPolicyTest, InMemoryToolRegistryRepositoryTest, RegisterToolUseCaseTest, SearchToolsUseCaseTest, support/Manifests, support/Providers}` | 新增 13 用例 + 2 夹具 |
| `spark-rooter-gateway/pom.xml` | 测试依赖 |
| `spark-rooter-gateway/src/test/java/.../{ArgsDigestTest, RetryPolicyTest, InMemoryIdempotencyStoreTest, InvokeToolUseCaseTest, support/Manifests, support/Providers}` | 新增 27 用例 + 2 夹具 |
| `spark-rooter-runtime/pom.xml` | 测试依赖 |
| `spark-rooter-runtime/src/test/java/.../{PlanValidatorTest 19, PromptBuilderTest 5, ConfirmationTokenServiceTest 11, RunStateTest 3, RunTest 10, InMemoryStoresTest 7, RunOrchestratorTest 13, RunOrchestratorConfirmTest 9, support/{TestFixtures, Fakes, OrchestratorFixture}}` | 新增 77 用例 + 3 夹具 |
| `spark-rooter-spring-boot-starter/pom.xml` | 测试依赖 + spring-boot-test |
| `spark-rooter-spring-boot-starter/src/main/java/.../SparkRooterProperties.java` | 类注释纠正；`Runtime.demoSessionResolver`（默认 false） |
| `spark-rooter-spring-boot-starter/src/main/java/.../RuntimeBeans.java` | 默认 `SessionIdResolver` 按开关 fail-fast；文案常量 `SESSION_RESOLVER_REQUIRED` |
| `spark-rooter-spring-boot-starter/src/test/java/.../SessionIdResolverFailFastTest.java` | 新增 3 用例（整体加载 AutoConfiguration） |
| `examples/host-demo/src/main/resources/application.yml` | 顶层文档 `spark.runtime.demo-session-resolver: true` + 三 profile 注释 |
| `examples/host-demo/README.md`、`spark-rooter/README.md` | fail-fast 说明；配置表 UnavailablePlanner + 新属性；「参数与多轮」段按模型主导重写 |

### spark-ui（前端）

| 文件 | 变更 |
|---|---|
| `apps/chat/package.json`、`pnpm-lock.yaml` | 删 `zustand`（零引用） |

### harness / 文档

| 文件 | 变更 |
|---|---|
| `.harness/scripts/sync-contracts.mjs` | 新增：同步 / `--check` |
| `.harness/scripts/check-contracts.mjs` | 末尾调用 `sync-contracts --check` |
| `.harness/scripts/ci.mjs` | spark-rooter 步骤去 `-DskipTests`（host-demo 保留） |
| `.harness/scripts/harness-doctor.mjs` | required 加 sync-contracts |
| `.harness/package.json` | `sync-contracts` script |
| `.harness/rules/backend-standard.md` | §1 门禁含单测；§7 UnavailablePlanner + fail-fast |
| `.harness/rules/agent-safety.md` | §2 前三条改为模型主导 + 代码核实；§3 令牌条 fail-fast |
| `.harness/rules/coding-standard.md`、`.harness/agents/platform-owner.md`、`.harness/skills/coding-skill/specs/{01,02}` | 去 Zustand |
| `.harness/rules/contracts.md` | §1 后端行副本说明；§6 步骤 2 加 sync-contracts |
| `.harness/skills/coding-skill/specs/06-backend-module-spec.md` | Agent Runtime 专项首条整句重写 |
| `.harness/skills/code-review/SKILL.md` | 后端段注明 verify 含单测 |
| `.harness/wiki/architecture.md` | 图、运行链路 2–4、端口列表、状态表、改动边界 |
| `.harness/wiki/api-contracts.md`、`.harness/wiki/domain-model.md` | SessionIdResolver 描述 |
| `README.md` | 接入第 3 条、Docker 段 |

## 2. 新增 / 删除的公共出口

- 新增配置属性 `spark.runtime.demo-session-resolver`（boolean，默认 false）。**行为收紧**：宿主未定义 `SessionIdResolver` Bean 且未开此开关 → 启动失败（`IllegalStateException`，文案含两条出路）。
- 新增脚本 `pnpm -C .harness run sync-contracts`。
- 契约 jar 内容不变（仍 `contracts/*.schema.json` + `examples/*` + `INDEX`），来源由构建期相对路径改为模块内副本。
- 无端点、契约 Schema 变更。

## 3. 关键决策

- **副本一致性检查挂在 `check-contracts` 末尾**而非 CI 单列步骤（评审 v1 S-4）：阶段 3 门禁与 `contracts.md` 变更顺序天然覆盖。
- **SelfCheck 全部保留**：e2e / deploy-verify / README 断言其日志行；单测只复制不变式。
- **Fake 优先于 Mockito**：`-Xlint:all -Werror` 下 Mockito 泛型 mock 易触发 unchecked；全部协作者手写 Fake，测试类一律 `final`。最终未引入 mockito 依赖。
- **runtime 测试工具全部中性命名**（`demo.item.*`、实体类型 `item`）；gateway / registry / contracts 测试的 Manifest 派生自契约副本示例 `tool-manifest.example.json`（`refund.eligibility.check`、`orderId`），属契约真源内容而非内核领域词，手写部分已改中性键。`DOMAIN_WORDS` 红线只扫 `src/main`。
- **`ApplicationContextRunner` 整体加载 `SparkRooterAutoConfiguration`**：裸 runner（0 工具、无 Web）能正常起来，无需 `withBean` 兜底。
- **Jdk8Module 不在 runtime 类路径**（只有 starter 直接依赖），runtime 夹具 ObjectMapper 不注册它；测试不经 Jackson 序列化 Optional。
- 契约副本目录里不放 README（会进 jar），说明放模块根 `CONTRACTS.md`。

## 4. agent-safety 六条边界自查

| § | 结论 |
|---|---|
| §1 四面职责 | 未改任何面的职责；测试只验证既有边界 |
| §2 发现 | 文档改为与实现一致（模型在全部可发现候选里选，description 不可信）；`PromptBuilderTest` 覆盖转义截断 |
| §3 确认 | `ConfirmationTokenServiceTest` 7 类拒绝 + `RunOrchestratorConfirmTest` 9 场景；SessionIdResolver 默认不安全 → 拒绝启动，是收紧 |
| §4 前端边界 | 未改前端逻辑（仅删未用依赖） |
| §5 Gateway | `InvokeToolUseCaseTest` 覆盖全部失败码、脱敏、审计、幂等 |
| §6 流式 | `RecordingSink` 断言事件序列；未改事件内容 |

## 5. 门禁结果

| 命令 | 退出码 |
|---|---|
| `node .harness/scripts/mvn.mjs -q -B verify` | 0（19 个报告，tests=131，failures=0，errors=0） |
| `node .harness/scripts/sync-contracts.mjs --check` | 0；篡改副本一字节后 1（已还原） |
| 真源目录改名后 `mvn.mjs -q -B -o -pl spark-rooter-contracts -am package` | 0（已还原） |
| `pnpm -C .harness run doctor` | 0 |
| spec §6 第 4 / 5 条 grep | 无命中 |
| `pnpm -C .harness run ci`、`e2e-backend.sh` | 见 `coding/review/code_review_v1.md`（阶段 4） |

## 6. 已知限制 / 后续

- e2e-backend.sh 注释里仍有「规则规划器」「rule mode」字样（脚本非本 change 范围，spec §3 明确不改 e2e 脚本）；第 3 项 Playwright 迁移时一并清理。
- `docs/AUTHORING-GUIDE.md:539` 提到 Zustand 属通用写作指南的举例，不是技术栈声明，保留。
- 单测未发现被测类 bug；无需另开 fix change。
- 与 spec 偏差：spec §2.1 / T02 允许 runtime、gateway 可选加 mockito，实际全部手写 Fake，未引入 mockito 依赖（评审 v1 INFO 已确认合理）。
