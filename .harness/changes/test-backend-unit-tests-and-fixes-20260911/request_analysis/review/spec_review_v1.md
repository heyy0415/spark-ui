---
mode: plan
verdict: REVISION REQUIRED
reviewer: expert-reviewer（plan 模式）
round: 1/3
inputs:
  - request_analysis/spec.md
  - request_analysis/tasks.md
rules:
  - .harness/rules/project-structure.md
  - .harness/rules/contracts.md
  - .harness/rules/backend-standard.md
  - .harness/rules/dev-workflow.md（阶段 1 门禁）
---

# Spec Review v1 — test-backend-unit-tests-and-fixes-20260911

> 独立性原则：未读 coding 目录；只看 spec / tasks 本身，并对其声称的事实核对了源码（`SparkRooterProperties`、`RuntimeBeans`、`host-demo/application.yml`、`DemoSessionIdResolver`、`ci.mjs`、`harness-doctor.mjs`、`e2e-backend.sh`、contracts / parent pom、本机 `~/.m2`、上一轮 e2e 的 `deployment/backend*.log`）。

## 0. 阶段 1 门禁与 plan 模式必查项

| 必查项 | 结果 | 备注 |
|---|---|---|
| spec 含 背景 / 范围 / 非目标 / 验收标准 / 风险 5 章节 | 通过 | §1 / §2 / §3 / §6 / §7 |
| tasks 每个 task 含 目标 / 输入 / 输出 / 验收 / 依赖 | 通过 | T01–T11 齐全 |
| 「非目标」存在且非空 | 通过 | §3 六条 |
| 每条验收可被命令 / 断言校验 | **部分不通过** | §6.7 一条按现状不可能成立（见 M-1）；§6.1 阈值与 tasks 最小值不一致（S-1）；T02 验收命令语义错误（S-2） |
| 风险 ≥1 失败模式 + 缓解 | 通过 | §7 七条 |
| 每个 task 标注所属端 | 通过 | 使用了 `harness` 这一非规范端名，见 I-1 |
| contracts task 先于依赖者 | 通过 | T01（契约副本）无依赖且排首位；契约 Schema 本身无变更 |
| 跨端结构列出契约文件 | 通过 | §5 明确 NONE + 副本目录 |
| 每个 task ≤ 0.5 天 | **不通过** | T07 明显超出（M-2）；T06 边缘（S-3） |

结论：2 条 MUST FIX → `REVISION REQUIRED`。两条都能小改解决，不涉及方案推翻。

---

## 1. 用户点名核对的四个问题

### 1.1 SessionIdResolver fail-fast × host-demo 多 profile yml

核对事实：
- `host-demo/application.yml` 有两个 YAML 文档：顶层文档（无 `on-profile`，始终生效）+ `on-profile: e2e-ttl` 文档（只加 `memory-ttl: 1s`）。
- `DemoSessionIdResolver` 是 `@Component @Profile("e2e")`，**只在 e2e profile 存在**；`e2e-ttl` profile 下没有宿主实现，走 starter 默认 Bean。
- `e2e-backend.sh` 第一次启动 `--spring.profiles.active=e2e`，第二次（㉓）`--spring.profiles.active=e2e-ttl`；`deploy-verify.sh` 与 Dockerfile 都不带 profile。
- 上一轮 e2e 日志：`backend.log`（e2e）中 `SessionIdResolver 为 demo 实现` 出现 **0** 次，`backend-ttl.log`（e2e-ttl）出现 **1** 次。

结论：把 `spark.runtime.demo-session-resolver: true` 写在顶层文档的方案是**正确的**——顶层文档对 default / e2e / e2e-ttl 三种启动都生效，三条启动路径都不会 `BOOT FAILED`。但 spec 有两处表述与事实不符，其中一处是验收标准，会导致阶段 4 门禁按字面执行时失败（M-1）。

### 1.2 契约副本 vs `contracts.md`「真源只有一处」

- 不构成冲突：副本是**机械派生物**（字节级复制 + CI 比对），方向单向（真源 → 副本），与前端 Zod 投影同属"投影"；真源仍唯一。spec 已计划在 `contracts.md` §1 后端行注明，方向正确。
- CI 校验**基本够，但有一个时序缺口**：`sync-contracts --check` 只进 `ci.mjs`（阶段 4 / 6），而 `dev-workflow.md` 阶段 3 门禁与 `contracts.md` §1「先改 Schema 与示例 → 跑 check-contracts → 再改两端」都只提 `check-contracts`。改完真源忘记同步时，阶段 3 编译通过、`SchemaValidator` / `ContractsSelfCheck` / T03–T04 单测却在用旧契约，排障成本高。见 S-4。
- 另外副本目录内的 `README.md`（"勿手改"）会被一并打进 jar 的 `contracts/` 下，无害但多余；见 L-3。

### 1.3 测试类清单 / `-Xlint:all -Werror` 现实性

- 清单里声称的类与方法均存在（`PlanValidator.missingEntity` 包私有 static、`ConfirmationTokenService.TokenUnknown`、`Run.attachPlan/currentStep/advance/idempotencyKeyFor`、`InMemoryRunRepository.evictExpired`、`RetryPolicy.allowedRetries`、`ArgsDigest.of`、`ContractsSelfCheck.contractOf` 包私有 static）；tasks 把测试放在同包下，可访问。
- `-Werror` 作用于 `src/test`：spec §7 第一条缓解（手写 Fake、`serialVersionUID`、局部 `@SuppressWarnings`）是可行的。补充两点现实风险：JDK 21 `-Xlint:all` 含 `this-escape`（测试类若在构造器 / 字段初始化里调用可覆写方法会报警，建议测试类一律 `final` 或包私有）；Mockito 的 `mock(Class)` 与 `ArgumentCaptor` 会触发 `unchecked` / `rawtypes`，spec 已把 Mockito 定位为兜底，合理。见 L-1。
- 依赖可得性：spec 说"本机 `~/.m2` 已缓存"只对 `maven-surefire-plugin 3.5.4`、`junit-jupiter 5.12.2`、`assertj 3.27.7` 成立；**`spring-boot-test:3.5.16`、surefire 的 junit-platform provider、`junit-platform-launcher` 本机均未缓存**，首次执行需要走 `~/.m2/settings.xml` 配置的 Nexus 镜像联网。`ci.mjs` 的 spark-rooter 步骤是在线的，可行；但 spec §7 风险表应写明"首次运行需联网拉取测试依赖"。见 L-2。
- 覆盖遗漏（安全相关）：① 编排器 "需确认工具缺 `ScreenBuilder` / `ConfirmationRecheck` → fail-closed `INTERNAL_ERROR`"（`RunOrchestrator` 类注释明示的不变式，十条场景未包含）；② `PromptBuilder` 对工具 `description` 的转义与 ≤500 字符截断（`contracts.md` §5 与 agent-safety 的"description 不可信"）。二者都是纯逻辑、离线可测。见 S-5。
- `web-mvc`（异常映射 → `ErrorResponse`、`SseRunEventSink`）与 starter 的 `@SparkTool` 扫描 / Manifest 推导没有任何单测，也没有写进非目标。见 S-5。
- spec §2.1 表 contracts 行只列 `SchemaValidatorTest`，tasks T03 却输出两个类（多了 `ContractsSelfCheckTest`）；以 tasks 为准即可，spec 表同步。见 L-4。

### 1.4 工作量 > 0.5 天

- **T07 明显超出**：`RunOrchestrator` 807 行、构造器 12 个协作者（`RunRepository / ToolRegistryClient / ToolGatewayClient / LlmClient / ScreenRegistry / RecheckRegistry / ToolDisplayNames / ConfirmationTokenService / ToolMetaRegistry / ConversationMemory / SchemaValidator / Clock`），且编排器发出的每个事件与每个屏都要**先过 sse-events / ui-schema 契约校验**——意味着 Fake 屏、Fake 候选（含合法 `inputSchema`）、Fake Gateway 输出都必须契约合法；再加 6 个 Fake + 10 条精确事件序列断言。这是全 change 最重的一块，保守估计 1–1.5 天。见 M-2。
- T06 五个测试类、≥30 用例，其中 `PlanValidator`（236 行，十几个拒绝分支）+ 共享 `TestFixtures`（`ToolCandidate` / `ToolMetaRegistry` / `ObjectMapper` / `SchemaValidator`）本身接近 0.5 天。见 S-3。
- 其余 task 在 0.5 天内。T01 项目多（脚本 + pom + `package.json` + `ci.mjs` + doctor + README + 副本提交）但每项都小，可接受。

---

## 2. 意见清单

### MUST FIX

**M-1**
- 位置：spec §6 验收第 7 条「`backend.log` 含 `SessionIdResolver 为 demo 实现` WARN」；spec §2.2.4「顶层文档设 `true`（覆盖默认与 e2e 两个 profile；e2e 另有 `@Component` 实现，默认 Bean 让位）」；§4 核心场景第 3 条「host-demo … 日志 WARN 与此前一致」。
- 问题：`e2e-backend.sh` 第一次启动用 `--spring.profiles.active=e2e`，该 profile 下 `DemoSessionIdResolver`（`@Profile("e2e")`）生效、starter 默认 Bean 让位，**默认 Bean 的 WARN 根本不会打**。上一轮 e2e 实测：`backend.log` 0 次、`backend-ttl.log`（e2e-ttl 第二次启动，无宿主实现）1 次。按字面执行第 7 条必然失败，且「两个 profile」漏掉了真正依赖顶层开关的 `e2e-ttl`。
- 建议：第 7 条改为「`backend.log` 无 `BOOT FAILED` 且**不含**该 WARN（宿主实现生效）；`backend-ttl.log` 含该 WARN 恰 1 次（开关放行的 demo 实现）」；§2.2.4 改为「顶层文档对 default / e2e / e2e-ttl 三种启动都生效；e2e 由 `DemoSessionIdResolver` 顶替，e2e-ttl 与 Docker / deploy-verify 无 profile 启动依赖该开关」。T08 验收同步。
- 分级：MUST FIX

**M-2**
- 位置：tasks T07「RunOrchestrator 编排单测」。
- 问题：单 task 包含 6 个 Fake（含契约合法的 Fake 屏与候选）+ 10 条精确事件序列场景 + 12 协作者装配，被测类 807 行且所有事件 / 屏出站前过契约校验，实际工作量约 1–1.5 天，违反「每个 task ≤ 0.5 天」。
- 建议：拆为 T07a「编排测试脚手架 + 非确认路径」（`support/` 下全部 Fake、`RecordingSink`、`OrchestratorFixture`，场景 ①②③④⑨⑩，验收 `tests ≥ 6`）与 T07b「确认路径」（场景 ⑤⑥⑦⑧，验收 `tests ≥ 4`，依赖 T07a）。T11 依赖改为 T03–T08（含 T07b）。
- 分级：MUST FIX

### SHOULD

**S-1**
- 位置：spec §6 验收第 1 条「`tests="N"` 之和 ≥ 80」 vs tasks 各 task 验收下限。
- 问题：T03 ≥6 + T04 ≥10 + T05 ≥16 + T06 ≥30 + T07 ≥10 + T08 = 3，合计 75 < 80。按 tasks 最低要求交付会让 spec 验收失败。
- 建议：二选一——spec 改为 ≥ 75；或把 T05 / T06 下限各 +3（T05 的 `InvokeToolUseCase` 失败码枚举本就 ≥ 10 个，T06 的 `RunState` 迁移表全枚举天然多）。
- 分级：SHOULD

**S-2**
- 位置：tasks T02 验收「`mvn.mjs -q -B dependency:resolve -Dclassifier=test` 无 ERROR」。
- 问题：`-Dclassifier=test` 让 `dependency:resolve` 去解析每个依赖的 **`-test` classifier 附属产物**（即 `*-test.jar`），并非"解析 test scope 依赖"；绝大多数依赖没有该 classifier，会输出大量 WARN / 失败，与意图相反。
- 建议：改为 `mvn.mjs -q -B dependency:resolve`（默认 `includeScope=test` 已含 test scope），或直接以 `mvn.mjs -q -B test-compile` 退出码 0 作为验收。
- 分级：SHOULD

**S-3**
- 位置：tasks T06。
- 问题：五个测试类 + 共享 `TestFixtures`，其中 `PlanValidatorTest` 要覆盖 §2.1 表列出的 12 类拒绝 / 补齐 / `missingEntity` 三路径，`TestFixtures` 还要产出契约合法的 `ToolCandidate`（含 `inputSchema`）与 `ToolMetaRegistry`；接近或超过 0.5 天。
- 建议：拆为 T06a「`TestFixtures` + `PlanValidatorTest`」与 T06b「令牌 / 状态机 / 内存存储三类测试」；T07（a）依赖 T06a 即可。
- 分级：SHOULD

**S-4**
- 位置：spec §2.2.3「`ci.mjs` 在 `check-contracts` 之后加 `sync-contracts --check`」；`contracts.md` 只改 §1 后端行。
- 问题：阶段 3 门禁（`dev-workflow.md`）与 `contracts.md` §1「变更顺序固定」/ §6「变更流程」只提 `check-contracts`，副本一致性检查要到阶段 4 `ci` 才跑。改真源忘同步时，T03/T04 单测与 `SchemaValidator` 用的是旧副本，失败原因不直观。
- 建议：任选其一并写进 spec：(a) `check-contracts.mjs` 末尾直接调用 `sync-contracts --check`（一条命令覆盖两件事，阶段 3 门禁不用改）；(b) `dev-workflow.md` 阶段 3 门禁与 `contracts.md` §1「变更顺序」、§6 步骤 2 同时加上 `sync-contracts`。并把 `coding-skill` 契约层 Spec 里"改 Schema 后跑 check-contracts"的指令同步。
- 分级：SHOULD

**S-5**
- 位置：spec §2.1 测试类清单；§3 非目标。
- 问题：(1) 编排器「需确认工具缺 `ScreenBuilder` / `ConfirmationRecheck` → fail-closed `INTERNAL_ERROR`」是 `RunOrchestrator` 类注释明示、agent-safety §3 依赖的安全不变式，十条场景未覆盖；(2) `PromptBuilder` 对工具 `description` 的转义 + ≤500 字符截断（`contracts.md` §5）无测试；(3) `web-mvc`（异常 → `ErrorResponse` 映射、SSE sink）与 starter `@SparkTool` 扫描 / Manifest 推导零测试且未写入非目标。
- 建议：(1)(2) 各加一个用例（都是纯逻辑、离线），分别归入 T07b 与 T06a；(3) 在 §3 明确写"web-mvc 与 `@SparkTool` 扫描 / Manifest 推导的单测不在本 change（后者由 `ManifestParitySelfCheck` + e2e 覆盖）"。
- 分级：SHOULD

**S-6**
- 位置：spec §6 验收第 4 条 grep 模式；§2.2.1「只替换失实句子」。
- 问题：grep 只查 `规则规划器|noop 分类器|NoopIntentClassifier|IntentClassifier`。实际文档里还残留其它已删类名：`.harness/wiki/architecture.md:37` 的 `ArgumentExtractor`、`.harness/skills/coding-skill/specs/06-backend-module-spec.md:50` 的 `DomainRouter / ArgumentExtractor / EntityRequirementCheck / IntentVerbs / ToolSelectionValidator`（源码中均已不存在）。按现有 grep 这些会漏网，T09 完成后文档仍失实。
- 建议：grep 模式扩展为 `规则规划器|noop 分类器|IntentClassifier|DomainRouter|ArgumentExtractor|EntityRequirementCheck|IntentVerbs|ToolSelectionValidator`；T09 输出加 `architecture.md` 第 37 行与 06-spec 第 50 行整句重写（这两句已无一处属实，不适用"只替换失实短语"）。另：grep 路径里 `spark-rooter/src` 不存在，删掉以免 grep 报错干扰判读。
- 分级：SHOULD

**S-7**
- 位置：spec §6 验收第 7 条「`e2e-backend.sh`（SPARK_PORT=8091，规则模式）通过数不低于当前基线」；tasks T08 验收。
- 问题：自 03a7838 起已无"规则模式"——未配置 `SPARK_LLM_*` 时 `LlmFactory` 直接 WARN `planner UNAVAILABLE — every request will fail`，此模式下 e2e 除启动 / selfcheck / 契约 400 断言外大面积失败，"基线"只是一个偏低的数字，对 T08 的回归意义很弱。措辞本身也是 spec §2.2.1 要纠正的那类失实表述。
- 建议：改为「以与上一轮相同的 LLM 配置（LIVE 或无模型）跑 e2e，先记录基线；关键断言是 `BOOT FAILED` 不出现、`selfcheck:` 各行与基线一致、通过数 ≥ 基线」，并删掉"规则模式"字样。
- 分级：SHOULD

**S-8**
- 位置：spec §2.1 `SessionIdResolverFailFastTest`、§3 非目标「starter 只用 `ApplicationContextRunner`」。
- 问题：未写明 runner 加载什么：若 `withConfiguration(AutoConfigurations.of(SparkRooterAutoConfiguration.class))` 整体加载，会顺带实例化 Contracts / Registry / Gateway / Runtime / Tool 五组 Bean（线程池、`@EnableAspectJAutoProxy`、Spring AI 类路径、0 个 `@SparkTool` 的扫描）；这些在裸 runner 里能否起来尚未验证。若只 `withUserConfiguration(RuntimeBeans.class)`，RuntimeBeans 又依赖 Contracts / Registry 等 Bean。
- 建议：spec 写明选择——推荐整体加载 `SparkRooterAutoConfiguration`（更接近宿主真实启动），并把「裸 runner + 0 工具能正常启动」列为 T08 的第一步验证；若起不来，允许在测试里 `withBean` 补最小 Bean，并把该事实记入 summary 经验沉淀。
- 分级：SHOULD

### LOW

**L-1**
- 位置：spec §7 风险表第一行。
- 问题：JDK 21 `-Xlint:all` 含 `this-escape`；测试类若非 `final` 且在字段初始化 / 构造器中调用可覆写方法会报警成错。
- 建议：缓解措施补一句"测试类一律 `final`（或包私有）"。
- 分级：LOW

**L-2**
- 位置：spec §2.1「（本机 `~/.m2` 已缓存）」。
- 问题：只对 surefire 3.5.4 / junit-jupiter 5.12.2 / assertj 3.27.7 / mockito 5.23.0 成立；`spring-boot-test:3.5.16`、surefire junit-platform provider、`junit-platform-launcher` 未缓存，首跑需经 Nexus 镜像联网。
- 建议：§7 加一行「首次 `verify` 需联网拉取测试依赖；`ci.mjs` spark-rooter 步骤本就在线，host-demo 的 `-o` 不受影响」。
- 分级：LOW

**L-3**
- 位置：spec §7「副本目录加 README 一行『勿手改』」；T01 输出的 `resources/contracts/README.md`。
- 问题：放在 `src/main/resources/contracts/` 下会被打进 jar 的 `contracts/`；对 `SchemaValidator` 无影响但属无用产物。
- 建议：README 放到 `spark-rooter-contracts/src/main/resources/contracts/../` 之外（例如模块根 `CONTRACTS.md`）或在 pom `<resource>` 里 `<excludes>` 掉；同时顺手把 `SchemaValidator` 类注释「构建时从 .harness/contracts 复制」与 `ContractsSelfCheck` 的错误文案「contracts-java build did not generate it」改为"由 sync-contracts 同步"（这两句改完即失实）。
- 分级：LOW

**L-4**
- 位置：spec §2.1 表 contracts 行 vs tasks T03 输出。
- 问题：spec 只列 `SchemaValidatorTest`，tasks 多一个 `ContractsSelfCheckTest`；§6.1「≥14 个报告」按 tasks 计为 17（拆 T07 后不变），按 spec 计为 16。
- 建议：spec 表补上 `ContractsSelfCheckTest`。
- 分级：LOW

**L-5**
- 位置：spec §6 验收第 6 条 `cd spark-rooter && ./mvnw -q -o -pl spark-rooter-contracts -am package -DskipTests`。
- 问题：绕过了 `mvn.mjs` 的 JDK 21 强制（`backend-standard.md` §1 要求所有命令走统一入口）；本机若默认 JDK 非 21，enforcer 会以无关原因失败。
- 建议：改为 `node .harness/scripts/mvn.mjs -q -o -pl spark-rooter-contracts -am package -DskipTests`。
- 分级：LOW

**L-6**
- 位置：tasks T05 输出「`ObjectProvider` 用 `ObjectProvider` 的静态工厂或匿名实现」。
- 问题：`ObjectProvider` 没有公开静态工厂；`InvokeToolUseCase` 构造器调用的是 `access.getIfAvailable(Supplier)`，匿名实现需覆写 `getIfAvailable()`（默认实现会抛 `UnsupportedOperationException`），而非只覆写 `getObject()`。
- 建议：改为「匿名实现，至少覆写 `getObject()` 与 `getIfAvailable()`；'无策略' 用例返回 `null` 触发全放行」。
- 分级：LOW

**L-7**
- 位置：spec §2.1 RunOrchestrator 行第 ⑩ 条「`argsDigest` 键序无关且确定」。
- 问题：若该方法为 private，测试无法直接调用；spec 未说明可见性。
- 建议：确认为包私有 static 再列为独立用例；否则改为通过"同参数不同键序生成的确认令牌 digest 一致 / 不同参数不一致"间接断言，且不改被测类（符合 §3 非目标）。
- 分级：LOW

### INFO

**I-1**
- 位置：tasks 各 task「所属端」。
- 问题：出现 `harness` 端名，checklist 的三个规范端是 contracts / spark-rooter / spark-ui。
- 建议：保留 `harness` 可以（本 change 大量改脚本与规则），但在 tasks 开头加一句说明该端的定义；后续可考虑把它写进 `expert-reviewer` 的 checklist。
- 分级：INFO

**I-2**
- 位置：spec §2.2.4 fail-fast 实现形态。
- 问题：在 `@Bean` 方法里抛 `IllegalStateException` 可行，但启动日志里会被 `BeanCreationException` 包裹，指引文案在 cause 链第二层；`ApplicationContextRunner` 断言需用 `getStartupFailure()` 的 root cause / `hasRootCauseMessage`。
- 建议：可选地给 starter 加一个 `FailureAnalyzer`（`META-INF/spring.factories`）把两条出路打成独立的 "APPLICATION FAILED TO START" 段落；不强制。
- 分级：INFO

**I-3**
- 位置：spec §2.2.2、tasks T10。
- 问题：核对 `spark-ui/apps/chat/package.json` 与 lockfile、`platform-owner.md:21`、`coding-standard.md:30`、`architecture.md:67`、`01-page-spec.md:34`、`02-feature-spec.md:15` 六处引用，与 spec 列表一致，无遗漏。
- 分级：INFO

**I-4**
- 位置：spec §2.2.3 `examples/INDEX`。
- 问题：当前由 antrun 按文件系统顺序生成；e2e 断言的是计数「27 examples」而非顺序，脚本按文件名排序生成即可。核对当前 `examples/*.example.json` 恰 27 个、`invalid/` 不在复制范围内，与 e2e 断言一致。
- 分级：INFO

---

## 3. 结论

- **verdict: REVISION REQUIRED**
- MUST FIX：M-1（§6.7 / §2.2.4 对 e2e profile 下 WARN 归属与 profile 覆盖面的表述失实，验收不可能成立）、M-2（T07 拆分至 ≤ 0.5 天）。
- 两条均为文档级修订，修订后进入 v2 评审；建议顺手处理 S-1 / S-2 / S-6 / S-7（都是验收命令 / 阈值层面的错误，留到编码阶段会让门禁误判）。
