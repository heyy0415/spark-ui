# Change Summary: feat-provider-http-transport-20260912

| 字段 | 值 |
|---|---|
| Change ID | feat-provider-http-transport-20260912 |
| 类型 | feat |
| 状态 | **DONE**（8 阶段全部完成；提交在分支 `feat/provider-http-transport`，未 push） |
| 负责人 | Platform Owner Agent |
| 涉及端 | contracts + spark-rooter（spi / contracts / runtime / gateway / registry / web-mvc / 新 provider-starter / examples）+ harness 规则；**spark-ui 零改动** |
| 起止时间 | 2026-09-12 ~ — |
| Spec | [request_analysis/spec.md](request_analysis/spec.md) |
| Tasks | [request_analysis/tasks.md](request_analysis/tasks.md) |

## 阶段进度表

| # | 阶段 | 状态 | 评审轮次 | 产出 | 时间 |
|---|---|---|---|---|---|
| 1 | 需求分析 | DONE | — | spec.md（含 7 项现状核实 + 3 项开放项当场验完）、tasks.md（T01–T11） | 2026-09-12 |
| 2 | 需求评审 | DONE | 1/3 | [review/spec_review_v1.md](request_analysis/review/spec_review_v1.md)（**CHANGES REQUESTED** → 3 MUST FIX + 3 SHOULD **全部已落实**到 spec §3.1a / §3.4 与 T07–T10 的可断言验收） | 2026-09-12 |
| 3 | 编码实现 | DONE | — | T01–T11 全部完成 + README / provider-demo README 重写；五套回归全绿（161 / 15 / 7 / 12 / 106），`pnpm -C .harness run ci` 0 | 2026-09-12 |
| 4 | 编码评审 | DONE | 1/2 | [coding/code_review_v1.md](coding/code_review_v1.md)（**APPROVED WITH FIXES**：3 MUST FIX 已修并回归，2 SHOULD 记录未改；自评审，**不满足独立性**） | 2026-09-12 |
| 5 | 代码推送 | DONE（本地提交） | — | 提交 `ac2d2b3` 于分支 `feat/provider-http-transport`；**未 push**（禁止直接 push 主分支，远端推送待用户在目标分支策略下执行） | 2026-09-12 |
| 6 | CI 验证 | SKIP | — | 未配置 GitHub Actions（用户决策：单人仓库）；本地 `pnpm -C .harness run ci` 退出 **0**（九步全过），见 [ci_result/ci_summary.md](ci_result/ci_summary.md) | 2026-09-13 |
| 7 | 部署验证 | DONE | — | [deployment/preview_report.md](deployment/preview_report.md)：deploy-verify **12 passed**、e2e-provider **15 passed**（双进程，provider 以 JDK 17 编译）；console.error 0、health UP、9 项自检 OK | 2026-09-13 |
| 8 | 用户确认 | DONE | — | 用户确认交付。提交 `ac2d2b3`（未 push）。HITL ④：本 change 不涉及生产部署参数，投产需人工确认的三项已在 preview_report 与 README 写明 | 2026-09-13 |

## 契约变更

- `tool-manifest.schema.json`：增 `provider` 段（`serviceName` 必填，`baseUrl` / `instanceId` 可选）+ 2 条 `if/then`（`protocol=http ⇒ provider` 必填；`protocol=in-process ⇒ provider` 不得出现）。
- **非破坏性**：`provider` 是新增字段且在 `in-process` 形态下禁止出现，单体形态的现有 Manifest 一字不改即仍合法。`$id` 保持 `/v1/`、`schemaVersion` 保持 `1.0`。
- `protocol` 的 `mcp` 枚举值**保留但不实现**（用户决策：不做 MCP），注册时拒绝。

## 本 change 的覆盖范围

改造清单第 11–14 项合并为一个 change（四项是同一能力的连续切面，拆开会产生无法独立验收的中间态）：

| 清单项 | 对应 task |
|---|---|
| 11 契约加 provider 段 | T01 |
| 12 拆 spark-provider-starter | T02, T03, T05 |
| 13 HTTP transport + manifest 上传 + 调用方认证 | T04, T06, T07 |
| 14 跨服务确认 | T08 |
| （新增）端到端证明 + 规则同步 + 回归 | T09, T10, T11 |

## 阶段 1 的关键决策（用户确认）

| 决策点 | 选择 | 理由 |
|---|---|---|
| `ToolTransport` 预留签名对 HTTP 不成立 | **改签名为按 Manifest 寻址** | 原签名 `invoke(ToolHandler, ...)` 要求先有本地 handler，远程工具没有。该接口零实现零使用，改签名无破坏成本 |
| Manifest 如何让 hub 知道 | **provider 启动时推送** | provider 只配 hub 地址，新增工具自动上报，无需改 hub 配置 |
| 跨服务确认的领域判定在哪执行 | **全部在 provider 侧** | 领域知识零泄漏到 hub，符合「内核不懂业务」 |

## 阶段 1 自我纠偏记录

写 spec 时的假设与代码实际不符，已逐项 grep 核实并修正设计（详见 spec §3.3 / §8）：

- 假设「三个共用类都能下沉 spi」→ 实际 `SparkToolScanner` 深度绑定 gateway + registry + runtime + Spring 容器，**不下沉**，provider 侧另写（职责本就不同）；
- 假设「`ManifestDeriver` 不碰 hub 模块」→ 实际依赖 `runtime.ToolMetaRegistry`，需先把两个纯数据 record 下沉 spi、容器类留 runtime；
- 发现 spec 初稿未提及的安全问题：`/internal/tool-registry/tools` **当前无任何认证**，跨服务后成为「任意调用方可注册伪造工具」的漏洞。已升级为本 change 的前置必要条件（T06），而非可选增强。

## 阶段 3 进度：T01–T04 完成（结构准备阶段）

按用户决定的节奏，先做「纯结构准备」四步并停下。这四步的共同特征是**验收即现有的 161 passed**——零行为变化可机械证明。

| task | 内容 | 验收实测 |
|---|---|---|
| T01 | 契约加 `provider` 段 + 2 条 if/then；`ToolManifest.Provider` record | `check-contracts` 0；**3 个 invalid 逐个确认被拒**；e2e **161 passed** |
| T02 | `ToolMeta` / `ParamMeta` 两个 record 下沉 spi，容器留 runtime | `mvnw test` 0；`check-module-deps` 0；e2e **161 passed** |
| T03 | `AnnotatedToolHandler` → spi、`ManifestDeriver` → contracts | git 识别为 **rename**；e2e **161 passed** |
| T04 | `ToolTransport` 改签名 + `InProcessToolTransport` + 按协议分派 | 新增 **11** 条 dispatch 单测；e2e **161 passed**；`pnpm -C .harness run ci` **0** |
| T05 | provider-starter 骨架（薄依赖 + JDK 17 + 双向认证基础） | 新增 **13** 条认证单测；**7** 条新门禁全部双向自证；e2e **161 passed**；`pnpm -C .harness run ci` **0** |

### T05 发现的真实阻塞：「provider 下限 17」原本没生效

spec 只说 provider-starter 设 `release=17`。实测发现它依赖的 `spi` / `contracts` 编译为 **major 65（JDK 21）**——JDK 17 进程加载即 `UnsupportedClassVersionError`。**只改 provider 自己的 pom 等于没改**。

连带发现 parent pom 在 compiler plugin 里硬编码 `<release>21</release>`，子模块的属性覆盖不生效。已改为属性驱动。

最终字节码实测：

| 模块 | major | 含义 |
|---|---|---|
| spi / contracts / provider-starter | **61** | JDK 17，provider 闭包可加载 |
| runtime / registry / gateway / web-mvc / hub starter | **65** | JDK 21 |

### T05 的 7 条新门禁（全部双向自证）

| 门禁 | 注入的违规 | 结果 |
|---|---|---|
| provider 禁依赖 hub 模块 | 加 `spark-rooter-runtime` | ✗ 变红 |
| provider 禁依赖 Spring AI | 加 `spring-ai-openai` | ✗ 变红 |
| provider 禁绑 web 栈 | 加 `spring-boot-starter-web` | ✗ 变红 |
| provider 必须 pin release 17 | 改回 21 | ✗ 变红 |
| spi / contracts 必须 pin 17 | spi 改回 21 | ✗ 变红 |
| **产物字节码 ≤ major 61** | 注入一个真的 JDK 21 class | ✗ 变红（诊断准确） |
| 身份红线覆盖 provider | provider 源码加 `userId()` | ✗ 变红 |

字节码那条尤其必要：pom 的意图与实际产物**可能脱节**（改 parent 配置、加未覆盖 release 的新模块），只查 pom 会漏。第一次自证时我改 pom 插件配置没能让字节码变成 65（Maven 未重编），于是改用「直接注入一个 javac --release 21 编出的 class」，才真正看到红。

### T05 的两处共享抽取（避免两侧漂移）

| 抽取 | 位置 | 若不抽会怎样 |
|---|---|---|
| `SparkToolSignature` | spi | 签名规则（public / 非 final / 非配置类 / 参数形状）两侧各写一份，一侧放宽就出现同一工具在单体合法、微服务非法 |
| `PlatformMapper` | contracts | 序列化口径两侧各配一份，同一 `@SparkTool` 在两种形态下推导出不同的金额表示 / 时间格式，跨形态迁移静默破坏契约 |

`PlatformMapper` 抽取时踩了一个坑：我本想用 `findAndRegisterModules()` 让模块「缺失时优雅退化」，**实测直接崩**——它按 ServiceLoader 扫全 classpath，任一被发现的模块实例化失败就整体抛 `ServiceConfigurationError`（被 classpath 上一个无 joda-time 依赖的 JodaModule 打挂）。宿主 classpath 不可控，平台 mapper 必须只显式注册自己要的模块。已实测三项行为：金额 → `"128.50"`、时间 → ISO-8601、缺键 `Optional` → `empty`。

### T06–T11：认证接端点 → HTTP 传输 → 注册期覆盖校验 → 双进程示例 → 规则同步 → 回归

| task | 内容 | 验收实测 |
|---|---|---|
| T06 | 注册端点接认证（`RegistrationGuard`）+ provider 执行端点（认证 / 幂等 / 脱敏） | 9 + 10 条单测；e2e **161 passed** |
| T07 | `HttpToolTransport` + `ProviderEndpointResolver` | 13 条单测；单体日志 `transports=[IN_PROCESS]`（不多一个 HTTP 客户端）；**161 passed** |
| T08 | 注册期确认覆盖校验（M-3 的实际缺口） | 7 + 2 条单测；**161 passed** |
| T09 | `examples/provider-demo`（JDK 17 独立进程）+ `e2e-provider.sh` | **15 passed, 0 failed** |
| T10 | 规则与文档同步 + 「不得自行重试」门禁 | doctor 0；门禁双形态自证 |
| T11 | 五套全量回归 | 161 / **15** / 7 / 12 / 106，`.harness ci` **0** |

### 评审 M-3 的结论修正：我原来的判断是错的

评审时我写「跨进程后 `reject(recheckOutput, shownUi)` 的比对跑在 provider 侧，provider 自己比对自己返回的值，等于没比对」，并据此写了一个通用的 `TrustedArgsConsistency` 字面比对器。

编码期读代码发现**前提不成立**，于是删掉了那个类：

- `RecheckRegistry` 由本地 Spring `ConfirmationRecheck` Bean 构建。微服务形态下这些 Bean 在 **provider**，hub 根本没有远程工具的 recheck；
- `reject()` 与 `trustedArgs()` **都在 hub 执行**，只有 recheck 工具的调用走远程。所以「provider 自己比对自己」这个场景当前不存在；
- 领域实现（`RefundRecheck`）已经在做 shown-vs-trusted 比对，且按自己拥有的组件 ID + label 取值。我那个通用 label 匹配既重复又会误判（屏上是 `"459.00 CNY"`，trustedArgs 是 `"459.00"`，字面相等会把每次正常确认都拒掉）。

**真实缺口比我描述的窄，但确实存在**：hub 的 `ConfirmationCoverageSelfCheck` 跑在自己的 `ApplicationReadyEvent`，provider 在**它自己的** `ApplicationReadyEvent` 才推 Manifest——两个进程无顺序保证，高风险远程工具通常在 hub 自检通过之后注册，**完全绕过那道检查**。虽仍 fail-closed（确认时 `INTERNAL_ERROR`），但故障点从「启动即失败」退化为「用户点确认那一刻才失败」。

处置：`RegisterToolUseCase` 在写入前调 `ConfirmationCoveragePolicy`（接口在 `registry/api/`，实现在 runtime——两模块只经 api 包通信）。需确认工具缺确认屏或 recheck → **注册即拒绝**。

### T06–T11 的三处自纠

1. **`ProviderAuth.outboundToken()` 无参是设计错误**。hub 要调多个 provider、各自密钥不同，一个无参方法表达不了。首版让 hub 侧只能传一个"默认出站令牌"，调第二个 provider 必然 401。改为 `outboundToken(serviceName)`。
2. **`@ConditionalOnProperty(name = "tokens[0]")` 对 Map 永不成立**。`tokens[0]` 是 List 语法。导致 hub 明明配了密钥却不装配认证，provider 全部推送 401——**跨进程 e2e 第一次跑就是 8 项红，正是它暴露的**。改为运行期判空返回 null。
3. **provider-demo 起初复用 order-service，撞 toolId 冲突**。host-demo 自己已在进程内注册 `order.*`，provider 再推同名 `toolId@version` 被 Registry 按 409 拒绝。改为 provider 自带 `inventory.stock.get`——真实微服务本就是每个服务暴露自己的领域。

### 跨进程 e2e 的价值（单测测不到的三个问题）

`e2e-provider.sh` 第一次运行 **8 项红**，全部是单测无法覆盖的装配与集成问题：`ProviderAuth` 条件装配失效、toolId 冲突、host-demo jar 未重新打包（SNAPSHOT 依赖不刷新——与 T01 踩的是同一个坑）。这验证了 spec 把「端到端示例」列为独立 task 是对的。

### T05 的一处门禁误判（改代码而非放宽门禁）

`SparkToolSignature` 的 javadoc 提到 `@Configuration` 时被组件注解门禁判为违规——正则分辨不了注释文本与真注解。**选择改写措辞**（「Spring 的配置类注解」）而不是给门禁开例外：门禁宁可严格，一旦为注释开口子，真注解也可能溜过去。


### 编码期修正的两处 spec 判断偏差（均因当场核实依赖方向而发现）

1. **`ManifestDeriver` 该放 contracts 而非 spi**。spec §3.3 写「下沉 spi」，但它需要 `contracts.SchemaValidator`，而 `contracts → spi` 已是既有方向，放进 spi 会造成 `spi → contracts → spi` 循环。`check-module-deps` 明确规定 spi 不得依赖任何 `com.sparkrooter` artifact。改放 contracts（已依赖 spi，是能同时看到两者的最低层级）。
2. **`ToolTransport` 同理该放 contracts**。它的签名需要 `ToolManifest`。spec 原以为它留在 spi 只改签名即可——实际改签名就越界了。

### T04 的设计自纠：不该用 `instanceof` 锁定传输实现

初版让 Gateway 的构造器断言「IN_PROCESS 的传输必须是 `InProcessToolTransport` 实例」，以便 `registerHandler` 转交给它。**6 个新测试当场报错暴露了这个错误**：这使接口不可替换，与我自己写的 `@ConditionalOnMissingBean`（本意就是允许宿主用装饰器替换）直接矛盾。

修法是看清 `registerHandler` 的真实需求——`SparkToolScanner` 要的是「把 handler 注册进进程内传输」，本来就不必经过 Gateway。于是 handler 注册表完全归 `InProcessToolTransport`，Gateway 不再认识任何具体实现，只按协议查表。

**教训**：测试报错时先问「是测试写错了，还是测试发现了真问题」。这次是后者——我差点为了迁就自己的实现去改测试。

### 三条关键断言的双向自证

门禁不自证等于没有。逐条注入违规实现，确认变红后恢复：

| 注入的违规实现 | 结果 |
|---|---|
| 移除「结果未知不重试」守卫 | ✗ `remoteTimeoutIsNotRetried` **[结果未知时不得重试]** 变红 |
| 让未装配协议静默回落 in-process | ✗ `manifestWithUnassembledProtocolIsToolNotFound` 变红 |
| 移除输入 Schema 校验 | ✗ `inputValidationStillRunsBeforeTransport` **[输入校验必须在传输之前]** 变红 |
| 全部恢复 | ✓ 11 passed |

第二条尤其重要：它守的是「远程工具被就近执行成同名本地工具」这一最危险的误实现。

## 新增门禁清单（全部双向自证）

| 门禁 | 守什么 |
|---|---|
| provider pom 禁 hub 模块 / Spring AI / web 栈 | 薄依赖承诺 |
| spi / contracts / provider 必须 pin release 17 | provider 宿主可能是 JDK 17 |
| **产物字节码 major ≤ 61** | pom 意图与实际产物会脱节 |
| 身份红线覆盖 provider 源码 | 身份归宿主 |
| 工具实现不得自行重试（`@Retryable` 简名 + FQN / `RetryTemplate` / 手写循环） | provider 重试 × hub 重试 = 指数放大 |

**两次 regex 门禁的教训**：`@Retryable\b` 只抓简名，用 FQN 注入不变红；与 T05 的组件注解门禁同一手法（`@(?:org\.springframework\.…\.)?X`）才完整。**自证要覆盖"绕过写法"，不只是最直白的那一种。**

## 阶段 2 评审发现（3 MUST FIX 全部已落实）

评审只提「能用代码证伪」的问题，每条先 grep 到行号再判断 spec 是否覆盖。三条 MUST FIX 有同一个模式——**进程内成立的假设在跨进程后失效**，而 spec 初稿只声明了「治理位置不变」，没检查语义是否仍成立：

| 编号 | 问题 | 后果 | 落实位置 |
|---|---|---|---|
| M-1 | hub 超时后 `cancel(true)` 只能中断自己的等待线程，provider 侧业务继续跑；`RetryPolicy` 照常重试 | **真实资金损失**：退款执行两次 | spec §3.1a.1 + T07 四条单测断言 |
| M-2 | 脱敏点在 hub（接收端），返回值已先过网络与 provider 日志 | 违反公司脱敏红线 | spec §3.1a.2 + T07 脱敏断言 |
| M-3 | `trustedArgs` 的**值**来自网络；`reject(recheckOutput, shownUi)` 的比对跑在 provider 侧 = provider 自己比对自己 | 用户确认屏 100 元、实际执行 10000 元 | spec §3.4 + T08 单测 |
| S-1 | T09 验收无断言条数 | 断言被删也不变红 | T09 要求实测后写 `N passed` |
| S-2 | 「不得自行重试」从来只是 javadoc 文字 | provider 重试 × hub 重试 指数放大 | T10 新增 grep 门禁 + 双向自证 |
| S-3 | `RunContextPropagator` 跨进程**静默**失效 | 基于 ThreadLocal 的鉴权静默失守 | spec §3.1a.3 |

### 评审中排除的两个疑点（避免过度设计）

| 疑点 | 核实 | 结论 |
|---|---|---|
| `ToolAccessPolicy` 是否受跨进程影响 | `InvokeToolUseCase:184`，在 transport **之前** | **不受影响**，权限判定始终在 hub |
| `ArgsDigest` 两侧算出是否一致 | 摘要只在 hub 侧算（`:122` 与 `RunOrchestrator:784`），provider 不参与 | **不受影响** |

### 顺带发现的既有缺陷（**不在本 change 修**）

`argsDigest` 的"规范化"两处口径不同：`RunOrchestrator:785` 用 `new TreeMap<>(args).toString()`（Java Map 格式、键已排序），`InvokeToolUseCase:122` 用 `req.arguments().toString()`（Jackson JSON、键序为插入序，**且注释写着"规范化 JSON"但没排序**）。

目前不出故障——令牌校验两侧都走 `RunOrchestrator` 那条，gateway 的摘要只进审计日志。与 provider 拓扑无关，属独立缺陷，建议另开 change 收敛为同一工具方法。

## 经验沉淀

1. **「治理位置不变」不等于「治理语义不变」**。spec 初稿写「Schema 校验 / 幂等 / 超时 / 重试 / 脱敏 / 审计全部仍在 `InvokeToolUseCase`」就以为安全了，但超时能否中断、数据是否出进程、调用方与被调方是否同信任域——这些前提在跨进程后全变了。**做拓扑变更时，要对每一项既有机制逐条问「它依赖的进程内前提还在吗」**，而不是只看代码位置有没有动。已在 spec §3.1a 做成逐项核对表。
2. **评审要能证伪，不要凭感觉**。本轮只写「先 grep 到行号再判断」的问题，结果 3 条 MUST FIX 全部指向具体代码行；同时主动排除了 2 个我一开始怀疑但实际不成立的疑点（`ToolAccessPolicy`、`ArgsDigest`）——**排除同样有价值，它避免了为不存在的问题加设计**。
3. **别把能当场查的事留给"他人评审"**。初稿的自评段写了「建议他人检查 `ArgsDigest`」，随后我自己查掉了，并顺带发现一个既有缺陷。写"建议他人"之前先问一句：我现在能不能查？

## 阶段 3 进度：T01–T04 完成（结构准备阶段）

按用户决定的节奏，先做「纯结构准备」四步并停下。这四步的共同特征是**验收即现有的 161 passed**——零行为变化可机械证明。

| task | 内容 | 验收实测 |
|---|---|---|
| T01 | 契约加 `provider` 段 + 2 条 if/then；`ToolManifest.Provider` record | `check-contracts` 0；**3 个 invalid 逐个确认被拒**；e2e **161 passed** |
| T02 | `ToolMeta` / `ParamMeta` 两个 record 下沉 spi，容器留 runtime | `mvnw test` 0；`check-module-deps` 0；e2e **161 passed** |
| T03 | `AnnotatedToolHandler` → spi、`ManifestDeriver` → contracts | git 识别为 **rename**；e2e **161 passed** |
| T04 | `ToolTransport` 改签名 + `InProcessToolTransport` + 按协议分派 | 新增 **11** 条 dispatch 单测；e2e **161 passed**；`pnpm -C .harness run ci` **0** |
| T05 | provider-starter 骨架（薄依赖 + JDK 17 + 双向认证基础） | 新增 **13** 条认证单测；**7** 条新门禁全部双向自证；e2e **161 passed**；`pnpm -C .harness run ci` **0** |

### T05 发现的真实阻塞：「provider 下限 17」原本没生效

spec 只说 provider-starter 设 `release=17`。实测发现它依赖的 `spi` / `contracts` 编译为 **major 65（JDK 21）**——JDK 17 进程加载即 `UnsupportedClassVersionError`。**只改 provider 自己的 pom 等于没改**。

连带发现 parent pom 在 compiler plugin 里硬编码 `<release>21</release>`，子模块的属性覆盖不生效。已改为属性驱动。

最终字节码实测：

| 模块 | major | 含义 |
|---|---|---|
| spi / contracts / provider-starter | **61** | JDK 17，provider 闭包可加载 |
| runtime / registry / gateway / web-mvc / hub starter | **65** | JDK 21 |

### T05 的 7 条新门禁（全部双向自证）

| 门禁 | 注入的违规 | 结果 |
|---|---|---|
| provider 禁依赖 hub 模块 | 加 `spark-rooter-runtime` | ✗ 变红 |
| provider 禁依赖 Spring AI | 加 `spring-ai-openai` | ✗ 变红 |
| provider 禁绑 web 栈 | 加 `spring-boot-starter-web` | ✗ 变红 |
| provider 必须 pin release 17 | 改回 21 | ✗ 变红 |
| spi / contracts 必须 pin 17 | spi 改回 21 | ✗ 变红 |
| **产物字节码 ≤ major 61** | 注入一个真的 JDK 21 class | ✗ 变红（诊断准确） |
| 身份红线覆盖 provider | provider 源码加 `userId()` | ✗ 变红 |

字节码那条尤其必要：pom 的意图与实际产物**可能脱节**（改 parent 配置、加未覆盖 release 的新模块），只查 pom 会漏。第一次自证时我改 pom 插件配置没能让字节码变成 65（Maven 未重编），于是改用「直接注入一个 javac --release 21 编出的 class」，才真正看到红。

### T05 的两处共享抽取（避免两侧漂移）

| 抽取 | 位置 | 若不抽会怎样 |
|---|---|---|
| `SparkToolSignature` | spi | 签名规则（public / 非 final / 非配置类 / 参数形状）两侧各写一份，一侧放宽就出现同一工具在单体合法、微服务非法 |
| `PlatformMapper` | contracts | 序列化口径两侧各配一份，同一 `@SparkTool` 在两种形态下推导出不同的金额表示 / 时间格式，跨形态迁移静默破坏契约 |

`PlatformMapper` 抽取时踩了一个坑：我本想用 `findAndRegisterModules()` 让模块「缺失时优雅退化」，**实测直接崩**——它按 ServiceLoader 扫全 classpath，任一被发现的模块实例化失败就整体抛 `ServiceConfigurationError`（被 classpath 上一个无 joda-time 依赖的 JodaModule 打挂）。宿主 classpath 不可控，平台 mapper 必须只显式注册自己要的模块。已实测三项行为：金额 → `"128.50"`、时间 → ISO-8601、缺键 `Optional` → `empty`。

### T06–T11：认证接端点 → HTTP 传输 → 注册期覆盖校验 → 双进程示例 → 规则同步 → 回归

| task | 内容 | 验收实测 |
|---|---|---|
| T06 | 注册端点接认证（`RegistrationGuard`）+ provider 执行端点（认证 / 幂等 / 脱敏） | 9 + 10 条单测；e2e **161 passed** |
| T07 | `HttpToolTransport` + `ProviderEndpointResolver` | 13 条单测；单体日志 `transports=[IN_PROCESS]`（不多一个 HTTP 客户端）；**161 passed** |
| T08 | 注册期确认覆盖校验（M-3 的实际缺口） | 7 + 2 条单测；**161 passed** |
| T09 | `examples/provider-demo`（JDK 17 独立进程）+ `e2e-provider.sh` | **15 passed, 0 failed** |
| T10 | 规则与文档同步 + 「不得自行重试」门禁 | doctor 0；门禁双形态自证 |
| T11 | 五套全量回归 | 161 / **15** / 7 / 12 / 106，`.harness ci` **0** |

### 评审 M-3 的结论修正：我原来的判断是错的

评审时我写「跨进程后 `reject(recheckOutput, shownUi)` 的比对跑在 provider 侧，provider 自己比对自己返回的值，等于没比对」，并据此写了一个通用的 `TrustedArgsConsistency` 字面比对器。

编码期读代码发现**前提不成立**，于是删掉了那个类：

- `RecheckRegistry` 由本地 Spring `ConfirmationRecheck` Bean 构建。微服务形态下这些 Bean 在 **provider**，hub 根本没有远程工具的 recheck；
- `reject()` 与 `trustedArgs()` **都在 hub 执行**，只有 recheck 工具的调用走远程。所以「provider 自己比对自己」这个场景当前不存在；
- 领域实现（`RefundRecheck`）已经在做 shown-vs-trusted 比对，且按自己拥有的组件 ID + label 取值。我那个通用 label 匹配既重复又会误判（屏上是 `"459.00 CNY"`，trustedArgs 是 `"459.00"`，字面相等会把每次正常确认都拒掉）。

**真实缺口比我描述的窄，但确实存在**：hub 的 `ConfirmationCoverageSelfCheck` 跑在自己的 `ApplicationReadyEvent`，provider 在**它自己的** `ApplicationReadyEvent` 才推 Manifest——两个进程无顺序保证，高风险远程工具通常在 hub 自检通过之后注册，**完全绕过那道检查**。虽仍 fail-closed（确认时 `INTERNAL_ERROR`），但故障点从「启动即失败」退化为「用户点确认那一刻才失败」。

处置：`RegisterToolUseCase` 在写入前调 `ConfirmationCoveragePolicy`（接口在 `registry/api/`，实现在 runtime——两模块只经 api 包通信）。需确认工具缺确认屏或 recheck → **注册即拒绝**。

### T06–T11 的三处自纠

1. **`ProviderAuth.outboundToken()` 无参是设计错误**。hub 要调多个 provider、各自密钥不同，一个无参方法表达不了。首版让 hub 侧只能传一个"默认出站令牌"，调第二个 provider 必然 401。改为 `outboundToken(serviceName)`。
2. **`@ConditionalOnProperty(name = "tokens[0]")` 对 Map 永不成立**。`tokens[0]` 是 List 语法。导致 hub 明明配了密钥却不装配认证，provider 全部推送 401——**跨进程 e2e 第一次跑就是 8 项红，正是它暴露的**。改为运行期判空返回 null。
3. **provider-demo 起初复用 order-service，撞 toolId 冲突**。host-demo 自己已在进程内注册 `order.*`，provider 再推同名 `toolId@version` 被 Registry 按 409 拒绝。改为 provider 自带 `inventory.stock.get`——真实微服务本就是每个服务暴露自己的领域。

### 跨进程 e2e 的价值（单测测不到的三个问题）

`e2e-provider.sh` 第一次运行 **8 项红**，全部是单测无法覆盖的装配与集成问题：`ProviderAuth` 条件装配失效、toolId 冲突、host-demo jar 未重新打包（SNAPSHOT 依赖不刷新——与 T01 踩的是同一个坑）。这验证了 spec 把「端到端示例」列为独立 task 是对的。

### T05 的一处门禁误判（改代码而非放宽门禁）

`SparkToolSignature` 的 javadoc 提到 `@Configuration` 时被组件注解门禁判为违规——正则分辨不了注释文本与真注解。**选择改写措辞**（「Spring 的配置类注解」）而不是给门禁开例外：门禁宁可严格，一旦为注释开口子，真注解也可能溜过去。


### 编码期修正的两处 spec 判断偏差（均因当场核实依赖方向而发现）

1. **`ManifestDeriver` 该放 contracts 而非 spi**。spec §3.3 写「下沉 spi」，但它需要 `contracts.SchemaValidator`，而 `contracts → spi` 已是既有方向，放进 spi 会造成 `spi → contracts → spi` 循环。`check-module-deps` 明确规定 spi 不得依赖任何 `com.sparkrooter` artifact。改放 contracts（已依赖 spi，是能同时看到两者的最低层级）。
2. **`ToolTransport` 同理该放 contracts**。它的签名需要 `ToolManifest`。spec 原以为它留在 spi 只改签名即可——实际改签名就越界了。

### T04 的设计自纠：不该用 `instanceof` 锁定传输实现

初版让 Gateway 的构造器断言「IN_PROCESS 的传输必须是 `InProcessToolTransport` 实例」，以便 `registerHandler` 转交给它。**6 个新测试当场报错暴露了这个错误**：这使接口不可替换，与我自己写的 `@ConditionalOnMissingBean`（本意就是允许宿主用装饰器替换）直接矛盾。

修法是看清 `registerHandler` 的真实需求——`SparkToolScanner` 要的是「把 handler 注册进进程内传输」，本来就不必经过 Gateway。于是 handler 注册表完全归 `InProcessToolTransport`，Gateway 不再认识任何具体实现，只按协议查表。

**教训**：测试报错时先问「是测试写错了，还是测试发现了真问题」。这次是后者——我差点为了迁就自己的实现去改测试。

### 三条关键断言的双向自证

门禁不自证等于没有。逐条注入违规实现，确认变红后恢复：

| 注入的违规实现 | 结果 |
|---|---|
| 移除「结果未知不重试」守卫 | ✗ `remoteTimeoutIsNotRetried` **[结果未知时不得重试]** 变红 |
| 让未装配协议静默回落 in-process | ✗ `manifestWithUnassembledProtocolIsToolNotFound` 变红 |
| 移除输入 Schema 校验 | ✗ `inputValidationStillRunsBeforeTransport` **[输入校验必须在传输之前]** 变红 |
| 全部恢复 | ✓ 11 passed |

第二条尤其重要：它守的是「远程工具被就近执行成同名本地工具」这一最危险的误实现。

## 新增门禁清单（全部双向自证）

| 门禁 | 守什么 |
|---|---|
| provider pom 禁 hub 模块 / Spring AI / web 栈 | 薄依赖承诺 |
| spi / contracts / provider 必须 pin release 17 | provider 宿主可能是 JDK 17 |
| **产物字节码 major ≤ 61** | pom 意图与实际产物会脱节 |
| 身份红线覆盖 provider 源码 | 身份归宿主 |
| 工具实现不得自行重试（`@Retryable` 简名 + FQN / `RetryTemplate` / 手写循环） | provider 重试 × hub 重试 = 指数放大 |

**两次 regex 门禁的教训**：`@Retryable\b` 只抓简名，用 FQN 注入不变红；与 T05 的组件注解门禁同一手法（`@(?:org\.springframework\.…\.)?X`）才完整。**自证要覆盖"绕过写法"，不只是最直白的那一种。**

## 阶段 2 评审发现（3 MUST FIX 全部已落实）

评审只提「能用代码证伪」的问题，每条先 grep 到行号再判断 spec 是否覆盖。三条 MUST FIX 有同一个模式——**进程内成立的假设在跨进程后失效**，而 spec 初稿只声明了「治理位置不变」，没检查语义是否仍成立：

| 编号 | 问题 | 后果 | 落实位置 |
|---|---|---|---|
| M-1 | hub 超时后 `cancel(true)` 只能中断自己的等待线程，provider 侧业务继续跑；`RetryPolicy` 照常重试 | **真实资金损失**：退款执行两次 | spec §3.1a.1 + T07 四条单测断言 |
| M-2 | 脱敏点在 hub（接收端），返回值已先过网络与 provider 日志 | 违反公司脱敏红线 | spec §3.1a.2 + T07 脱敏断言 |
| M-3 | `trustedArgs` 的**值**来自网络；`reject(recheckOutput, shownUi)` 的比对跑在 provider 侧 = provider 自己比对自己 | 用户确认屏 100 元、实际执行 10000 元 | spec §3.4 + T08 单测 |
| S-1 | T09 验收无断言条数 | 断言被删也不变红 | T09 要求实测后写 `N passed` |
| S-2 | 「不得自行重试」从来只是 javadoc 文字 | provider 重试 × hub 重试 指数放大 | T10 新增 grep 门禁 + 双向自证 |
| S-3 | `RunContextPropagator` 跨进程**静默**失效 | 基于 ThreadLocal 的鉴权静默失守 | spec §3.1a.3 |

### 评审中排除的两个疑点（避免过度设计）

| 疑点 | 核实 | 结论 |
|---|---|---|
| `ToolAccessPolicy` 是否受跨进程影响 | `InvokeToolUseCase:184`，在 transport **之前** | **不受影响**，权限判定始终在 hub |
| `ArgsDigest` 两侧算出是否一致 | 摘要只在 hub 侧算（`:122` 与 `RunOrchestrator:784`），provider 不参与 | **不受影响** |

### 顺带发现的既有缺陷（**不在本 change 修**）

`argsDigest` 的"规范化"两处口径不同：`RunOrchestrator:785` 用 `new TreeMap<>(args).toString()`（Java Map 格式、键已排序），`InvokeToolUseCase:122` 用 `req.arguments().toString()`（Jackson JSON、键序为插入序，**且注释写着"规范化 JSON"但没排序**）。

目前不出故障——令牌校验两侧都走 `RunOrchestrator` 那条，gateway 的摘要只进审计日志。与 provider 拓扑无关，属独立缺陷，建议另开 change 收敛为同一工具方法。

## 经验沉淀

1. **「治理位置不变」不等于「治理语义不变」**。spec 初稿写「Schema 校验 / 幂等 / 超时 / 重试 / 脱敏 / 审计全部仍在 `InvokeToolUseCase`」就以为安全了，但超时能否中断、数据是否出进程、调用方与被调方是否同信任域——这些前提在跨进程后全变了。**做拓扑变更时，要对每一项既有机制逐条问「它依赖的进程内前提还在吗」**，而不是只看代码位置有没有动。已在 spec §3.1a 做成逐项核对表。
2. **评审要能证伪，不要凭感觉**。本轮只写「先 grep 到行号再判断」的问题，结果 3 条 MUST FIX 全部指向具体代码行；同时主动排除了 2 个我一开始怀疑但实际不成立的疑点（`ToolAccessPolicy`、`ArgsDigest`）——**排除同样有价值，它避免了为不存在的问题加设计**。
3. **别把能当场查的事留给"他人评审"**。初稿的自评段写了「建议他人检查 `ArgsDigest`」，随后我自己查掉了，并顺带发现一个既有缺陷。写"建议他人"之前先问一句：我现在能不能查？

## README 重写（用户原始诉求）

按「黄金开源项目 README 标准」重写根 `README.md`（202 → 289 行），并补 `examples/provider-demo/README.md`。

结构调整：居中标题区 + 一句话价值主张 + 徽章 + 导航链接 → 效果示例（先让人看懂能干什么）→ **为什么是这样设计**（三条贯穿全部代码的约束）→ 功能表 → 快速开始 → 接入 → 两种拓扑 → 架构 → 部署 → 安全 → 门禁 → 已知限制。

两处刻意保留原文的地方：原 README 的「效果示例」四行与安全模型措辞已经很准，重写时只做结构归位，不为改而改。

**所有数字与链接都实测核对过**（此前有把后端示例数写到前端命令上的先例）：9 契约 / 10 SSE 事件 / 5 白名单组件 / 6 平台模块 + 2 starter / 14 tools from 6 beans / 9 项自检 / 161·15·7·12·106 五套回归；全部相对链接脚本校验可达。

## 阶段 4 评审发现（3 MUST FIX 全部已修）

只提能用失败测试或 grep 证明的问题，不写「我觉得不妥」。

| 编号 | 问题 | 后果 | 证据 |
|---|---|---|---|
| F-1 | `ExecutionContext` 在 try 之外构造，请求体缺必填字段 → 未捕获 `IllegalArgumentException` | **500 + 堆栈**，而这本是客户端错误；状态码误导调用方去重试 | 先写断言看到红：`IllegalArgumentException: runId is blank` |
| F-2 | `ProviderToolRegistry` 用 `LinkedHashMap`，启动期写、web 请求线程读 | 无 happens-before 保证 → 偶发 `NOT_FOUND`，症状随机极难排查 | 对照 hub 侧同职责的 `InProcessToolTransport` 用的是 `ConcurrentHashMap` |
| F-3 | provider 的 `base-path` 可配，hub 的 `INVOKE_PATH` 硬编码 | 「看起来能配、配了就坏」：改了配置 hub 静默 404 | grep 两处常量 |

F-3 连带加了**跨模块常量一致性门禁**，并特意让「常量被改名 → 门禁瞎了」本身也报错——门禁因符号改名而静默失效是本项目踩过两次的坑（T05 字节码门禁、T10 重试门禁）。

### 2 条 SHOULD 记录未改（附理由与逃生门）

| 编号 | 问题 | 不改的理由 |
|---|---|---|
| S-1 | `HttpToolTransport` 每次新建 `RestClient`（`HttpURLConnection` 不池化） | 读超时须按 Manifest 逐次设置，而 factory 超时是实例级的；复用需按 timeout 分桶或换客户端。属性能优化非正确性问题，已有逃生门（替换 Bean），压测数据出来前不做投机优化 |
| S-2 | provider 幂等默认进程内，多实例可能绕过 | 跨实例强一致需外部中间件，与「不依赖外部中间件」的既有定位冲突。关键是别让人误以为万无一失——已在 javadoc / `agent-safety.md` §8.3 / README 三处写明限制与替换方式 |

### 评审中排除的四个疑点

`ManifestPublisher` 记完整异常会否泄漏令牌（**否**：hub 401 无响应体）、`ConcurrentHashMap` 是否破坏 `manifests()` 顺序承诺（**否**：承诺只针对仍是 List 的 manifests）、推送失败不阻断启动是否过宽（**合理**）、`mcp` 保留枚举是否半成品（**符合用户决策**）。

**排除同样有价值**：它避免了为不存在的问题加设计。
