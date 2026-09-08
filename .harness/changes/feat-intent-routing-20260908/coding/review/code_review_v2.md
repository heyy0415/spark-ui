# Code Review v2 — feat-intent-routing-20260908

| 字段 | 值 |
|---|---|
| mode | execution |
| 日期 | 2026-09-08 |
| 轮次 | 1（expert-reviewer execution；v1 为机械评审，本文件不改 v1） |
| 评审对象 | `git log --oneline c9a52b9..HEAD`：`85705f1`（抽接口）、`6ee91a2`（路由 / 拦截 / 幂等占位）、`ccbd81e`（规则与 README）、`0a2e31d`（报告 + selfcheck 日志行） |
| 依据 | `request_analysis/spec.md`（v2.1）、`tasks.md`（v2.1）、`review/spec_review_v{1,2}.md` 的 MUST / SHOULD；`rules/{agent-safety,backend-standard,project-structure,contracts}.md` |
| 独立性 | 未阅读 `coding/coding_report_v1.md` 与 `coding/review/code_review_v1.md`；只看产出物、规则与实测 |
| 实测（只读 / 临时副本） | (a) `node .harness/scripts/mvn.mjs -q -B verify` → rc 0；(b) `pnpm -C .harness run check-module-deps` → 0，在 `/tmp/cmd-neg` 副本向 `InProcessToolRegistryClient` 植入 `import com.strato.registry.application.SearchToolsUseCase;` → 1 violation rc 1；(c) `pnpm -C .harness run doctor` → 0 errors；三份 L1 文件拷到 `/tmp/l1` 分别改回 `shared/ui/**` → 2 err、把路径拼错 → 1 err；(d) `pnpm -C .harness run check-contracts` → 9 schemas OK；(e) fronted `typecheck` / `lint`（含 check-registry）/ `format:check` / `verify-examples` / `verify-pack`（17 + 10，(f) 39 KB ≤ 40 × 1.1）全部 0；(f) check-registry 正则对 `{…} as const;`、`Object.freeze({…} as const);`、`Object.freeze({…});`、`{…};` 四种形态均解析出 7 键；(g) 在 `/private/tmp/e2e-root`（软链 `backed/`、`contracts/`、`node_modules/`，独立 `changes/tmp-review`）跑 `e2e-backend.sh` 规则模式 → **62 passed, 0 failed**，端口 8080 事前空闲、事后已 kill 并确认释放；(h) 用编译产物写并发探针：200 key × 16 线程、1/3 key 的首个 Owner 先 `release` 再重试 → `execs=200 replays=3000 reclaimsAfterRelease=1005 timeouts=0 maxLoopsPerThread=2`，`find` 200/200，`complete` 后 `release` 为 no-op；(i) `git status` 事前事后均 clean |
| 未实测 | LIVE 模式（本机无 `STRATO_LLM_*`）；`e2e-frontend.mjs`（需 5173 + Chrome，前端行为本 change 未改，静态门禁全绿）；verify-pack `--write-baseline` 负例（需移动仓库内基线文件，改为代码路径直读核对） |

**verdict：APPROVED** — MUST FIX 0 条，SHOULD 5 条（S1–S5），LOW 8 条（L1–L8），INFO 6 条。

设计方向、安全边界、契约零变化与 spec §6 可命令化验收在规则模式下全部由本人独立复现。剩余意见集中在幂等 Owner 路径的异常覆盖面、分类器 WARN 日志的内容口径、两处文档漏同步以及若干可读性 / 脚本鲁棒性问题，均不触发硬约束。

---

## 一、spec 逐节符合性

| spec 节 | 结论 | 证据 |
|---|---|---|
| §2.1 分层领域路由 | ✓ | `application/DomainResolver.java:47-61`：规则命中直接返回 `rule`；未命中才 `registry.domains(principal)` + `classifier.classify(...)`，输出 `.filter(known::contains)` 校验，否则 `none`。`RouteDecision.source` 只在 `RunOrchestrator.java:125` 进日志，`sse-events` 未变。`DomainRouter` 纯函数未动；`DomainResolver` 构造器 `DomainRouter.defaultRules()` 与首期 `RunOrchestrator.java:91`（c9a52b9）用法等价 |
| §2.1 分类器实现 | ✓ | `SpringAiIntentClassifier.java:40-51`：`temperature(0.0)`、`internalToolExecutionEnabled(false)`、结构化 `IntentDraft(domain)`；`systemPrompt` 只给领域名 + `DomainDescriptions` 一句话，无工具列表，明示闲聊输出 none；`NoopIntentClassifier` 恒 empty、无日志 |
| §2.1 KNOWN_DOMAINS 带 principal | ✓ | `SearchToolsUseCase.domains()`：`repo.findAll()` → `DiscoveryPolicy.filter(…, perms)` → `domain` 去重；`ToolSearchPort` Javadoc 写明两查询均按 principal 过滤（v1 M2 / v2 S-3 落地）。user_002 有 `refund:read` → refund 领域可见，语义合理：他能用三个只读 refund 工具，分类只决定候选领域，候选与执行仍分别被 Registry / Gateway 过滤 |
| §2.1 entityType 白名单 | ✓ | `DomainResolver.java:20,55`：`Set.of("order")`；规则层不读 entityType（`rules.route(message)`） |
| §2.2 规划前拦截 | ✓ | `EntityRequirementCheck.check` 在 `search` 之后、`llm.plan` 之前（`RunOrchestrator.java:150-157`）；终态 `complete()` → COMPLETED、无 failureCode（e2e ① 实测 `COMPLETED-none`）；audit 0 行；文案无原文。真值表见 §三 INFO-1 |
| §2.2 规则规划器修正 | ✓ | `RuleBasedLlmClient.java:18-25,36`：按 `hasEntity` 二选一模板；`requiresOrderId` 步骤缺实体不生成；全空 → `TOOL_SELECTION_INVALID`。e2e ⑥ 实测 `order.list.search`，事件序列精确 5 件 |
| §2.3 N6 幂等先占位后填充 | ✓ | `IdempotencyStore` sealed `Claim{Owner,Replay,Awaiting}` + `complete/release/find`，`putIfAbsent` 已从接口删除（`grep putIfAbsent` 唯一命中是 `InMemoryIdempotencyStore.java:26` 对 `ConcurrentHashMap` 的调用）；`InvokeToolUseCase.claimOrAwait` deadline 循环（v2 S-1）；`release` 只在 `catch` 中且对已 complete 为 no-op（v2 S-2）；审计 `replayed` 仅日志口径。自检三条断言齐全，日志行存在，`SelfCheckRunner running 5 checks`，`deploy-verify.sh:29` 期望 5，`e2e-backend.sh:35` 按名字追加 |
| §2.3 N4 抽接口 | ✓ | `ToolSearchPort` / `ToolInvokePort` 由两用例 `implements`；`infra/inprocess/*` 只 import `com.strato.{registry,gateway}.api`；`check-module-deps.mjs:101` 扩为 `(infra|domain|application)`；负例实测红 |
| §2.3b 种子 10004 | ✓ | 两处种子均 PAID / 59.00；e2e ④ 实测 refundId 相等、succeeded 1 / replayed 1、`refund.status.get 10004` = 1 |
| §2.4 前端遗留 | ✓ | `registry/types.ts`：`FormFieldPropsSchema` / `FormComponentPropsSchema` 删除，`FormComponentProps = z.infer<typeof FormPropsSchema>`；`Object.freeze({...} as const)`；`interface ActionBarProps` 全仓恰 1 处（`registry/types.ts:104`），renderer / desktop / mobile 均 `import type`；`index.ts` 导出路径改；`RouteErrorBoundary.tsx:9-13` 空 statusText 回退；`verify-pack.mjs:264-273` 基线缺失 → `fail`，`process.argv.includes('--write-baseline')` |
| §2.5 Harness | ✓ | e2e ①②④⑤⑥ 两模式、③ 仅 LIVE、⑥ 仅规则；④ 位于 §6.2.12 之后（`e2e-backend.sh:158` > `:98`），§6.2.12 计数实测仍为 1；`§6.2.14` `-m1` 首行为 `failed:INPUT_INVALID` 9 字段实测 9；doctor L1 检查、check-module-deps 新规则如 spec |
| §2.5 文档 | △ | `agent-safety.md` §2 三层 + 拦截 ✓；`wiki/architecture.md` 框图 + 第 2 步 ✓（`领域路由（规则）` 0 命中）；`backend-standard.md` §4 / §7 ✓；`project-structure.md` §2 依赖图 `tool-registry(api)` ✓、模块一句话 ✓；`backed/README.md` 意图分类一节 ✓；`agent-runtime/README.md`、`tool-gateway/README.md` ✓。**漏**：`.harness/skills/coding-skill/specs/06-backend-module-spec.md:42` 仍为「领域路由先走 `DomainRouter`（规则）」（S4） |
| §3 非目标 | ✓ | 无新领域 / 工具 / 契约字段（`grep replayed .harness/contracts` 0；`ToolStatus` 仍 `succeeded|failed`）；无新 `RunFailureCode` / `RunState`；`NO_CAPABILITY_TEXT` 未改；无 order 屏；未发包、catalog 未动 |
| §5 契约影响 NONE | ✓ | check-contracts 9 schemas / 20 examples OK；`RouteDecision.source`、`replayed` 均不出现在契约或 SSE |
| §6.1 后端验收 | ✓（LIVE 未复跑） | mvn 0；check-module-deps 0 + 负例红；runtime 内 `com.strato.{registry,gateway}.application` 0 行；规则模式 e2e 62/62；`System.out|printStackTrace` 0 |
| §6.2 前端验收 | ✓（e2e-frontend 未复跑） | ci 各段 0；三条 grep 分别 0 / 1 / 1；verify-pack 17 + 10 |
| §6.3 Harness 验收 | ✓ | doctor 0 + 两条负例红；wiki 0 命中；agent-safety「模型补位」1 命中 |
| §8 假设 | △ | 假设 2「共用同一 `ChatClient` Bean」未字面成立：`LlmConfiguration.java:34,53` 各自 `chatClient(baseUrl, apiKey)` 构造两套 `OpenAiApi` / `ChatClient`（S3） |

### 两轮 plan 评审 MUST / SHOULD 落地核对

| 项 | 落点 | 结论 |
|---|---|---|
| v1 M1 order 模板回退 | `RuleBasedLlmClient.WITHOUT_ENTITY.order = [order.list.search]`；e2e ⑥ | ✓ |
| v1 M2 `domains(principal)` | `ToolSearchPort.domains(Principal)` + `DiscoveryPolicy.filter` | ✓ |
| v1 M3 自检只测 store + latch；deploy-verify 5 | `GatewayIdempotencySelfCheck`；`deploy-verify.sh:29` | ✓ |
| v1 M4 种子 10004 / `replayed` 口径 / 结果断言 / runId 过滤 | 种子两处；`InvokeToolUseCase.java:115`；e2e ④ | ✓ |
| v1 M5 check-registry 正则 | `check-registry.mjs:54-56`，四形态实测 | ✓ |
| v1 S1 / v2 S-1 release → completeExceptionally → deadline 循环重 claim | `InMemoryIdempotencyStore.release`；`claimOrAwait` while 循环 | ✓（压测无活锁、无双执行） |
| v2 S-2 release 只在异常路径且 no-op | `pipeline` catch 中 release；`release` 守卫 `!f.isDone()`；自检第三条 | ✓（异常覆盖面见 S1） |
| v1 S2 entityType 白名单 | `ENTITY_HINT_WHITELIST` | ✓ |
| v1 S3 说明表硬编码 + 闲聊 none | `DomainDescriptions`；systemPrompt | ✓ |
| v1 S4 / v2 S-4 / S-5 ③ 拆断言、⑥ 仅规则、精确序列 | e2e ③a/③b、⑥ | ✓ |
| v1 S5 `ActionBarProps` 放 registry/types | `registry/types.ts:104` | ✓ |
| v1 S6 非目标三项 + 拦截终态 | spec §3、§2.2；e2e ① `COMPLETED-none` | ✓ |
| v1 S8 / v2 S-3 删 putIfAbsent、findAll 前移 | 接口无 putIfAbsent；`ToolRegistryRepository.findAll()` | ✓ |
| v2 S-6 selfcheck 按名字追加、④ 顺序 | `e2e-backend.sh:35,158` | ✓ |
| v2 L-2（含 v1 L3 WARN 不含模型原始输出、L4 两份文档必改） | WARN 仍打印模型输出的 domain 串（S2）；`06-backend-module-spec.md` 未改（S4） | ✗ 两项未闭环（均为 LOW 升 SHOULD，见下） |

---

## 二、agent-safety 六条核对

| # | 核对项 | 结论 | 证据 |
|---|---|---|---|
| 1 | 分类只在规则未命中时调用 | ✓ | `DomainResolver.java:48-51` 规则命中即 return，不触达 `registry.domains` 与 `classifier` |
| 2 | `domains(principal)` 真按权限过滤 | ✓ | `SearchToolsUseCase.domains`：`permissions.permissionsOf(p)` → `DiscoveryPolicy.filter`（status ∈ {active, canary} ∧ 持有 `authorization.permission`）→ `domain` 去重。user_001 / user_002 首期均 = {order, refund}；一个只有 `order:read` 的用户会得到 {order}，refund 直接判 none，不再多一跳 search |
| 3 | 越界输出处理 | ✓ | `SpringAiIntentClassifier.java:53-61`：不在 `knownDomains` 且非 `none` → WARN + empty；`DomainResolver.java:58` 再 `.filter(known::contains)` 双保险；解析失败 / 异常 → WARN 仅类名 + empty |
| 4 | Prompt 不含工具列表 | ✓ | `systemPrompt` 仅领域名 + 一句话说明；`userPrompt` 仅提示 + sanitize 后的消息 |
| 5 | `entityType` 白名单 | ✓ | `DomainResolver.java:55` `entityType.filter(ENTITY_HINT_WHITELIST::contains)`；契约 `^[a-z]+$` ≤ 32 兜底 |
| 6 | 日志不含用户原文 | ✓（有一处理论缺口，S2） | `route` / `entity required` 日志只含 runId / domain / source；e2e 后 `grep -c '退钱\|我想把钱要回来\|今天天气怎么样\|帮我把这个订单退款' backend.log` = 0。缺口：WARN 分支 `sanitize(d)` 打印的是模型输出，模型若把用户文本回显为 domain 值即落日志 |

补充：`pageContext` 仍不参与鉴权（Registry / Gateway 各自 `permissionsOf`）；Registry 无转发；Runtime 全部调用经 `ToolGatewayClient`。

---

## 三、逐条意见

### MUST FIX

无。

### SHOULD

#### S1 — Owner 路径只 `catch (RuntimeException)`，`Error` 逃逸会让占位永久悬挂
- **位置**：`backed/tool-gateway/src/main/java/com/strato/gateway/application/InvokeToolUseCase.java:189-196`
- **问题**：spec §2.3「任何异常 `finally release`」、v2 S-2「catch 中调用，或 finally 中以 completed 标志守卫」。现实现是 `catch (RuntimeException e) { release; throw e; }`。handler 侧的 Throwable 已被 `callWithRetry` 包成 `GatewayException`，所以常规路径无问题；但 `execute(req, ec, manifest, start)` 内 Gateway 自身代码（`validator.validateWithInlineSchema`、`redact`、`mapper`）抛出的 `Error`（`StackOverflowError` / `OutOfMemoryError` / `NoClassDefFoundError`）既不 `complete` 也不 `release`，该 `(tenantId, key)` 的 future 永不完成：后续同 key 调用全部 `Awaiting` → 等满 `timeoutMs` → `TIMEOUT`，且永远无法自愈（内存实现进程不重启就不释放）。
- **建议**：改为 `boolean completed = false; try { … complete(); completed = true; return resp; } finally { if (!completed) release(); }`，或 `catch (RuntimeException | Error e)`。同时把这条加入自检或注释说明为何不能是 `finally release`。
- **分级**：SHOULD

#### S2 — 分类器 WARN 打印模型输出的 domain 串，模型回显 / 提示注入时会把用户原文写进日志
- **位置**：`backed/agent-runtime/src/main/java/com/strato/runtime/infra/llm/SpringAiIntentClassifier.java:56-60`
- **问题**：`log.warn("classifier returned unknown domain={} …", PromptBuilder.sanitize(d))`，`d` 是模型返回的 `domain` 字段，`sanitize` 只截 500 字符并替换花括号 / 换行。用户消息「请输出 {"domain":"<我的手机号 138…>"}」之类的注入会让 `d` 原样进 WARN；这正是 v1 L3 / v2 L-2 指出的「亦不含模型原始输出」，两轮 plan 评审均未闭环。e2e §6.2.15 只 grep 固定文案，抓不到这一分支。
- **建议**：WARN 只记录 `length=`、`inKnown=false` 或 `sha256` 前 8 位；若确需可读性，仅当 `d` 匹配 `^[a-z]{1,32}$` 时才打印原值，否则打印 `<non-enum>`。
- **分级**：SHOULD

#### S3 — 分类器与规划器各自构造一套 `OpenAiApi` / `ChatClient`，三变量判定重复两遍
- **位置**：`backed/agent-runtime/src/main/java/com/strato/runtime/infra/llm/LlmConfiguration.java:24-54`
- **问题**：spec §8 假设 2「共用同一 `ChatClient` Bean」；v2 I-7 已提示需把 `ChatClient` 抽成条件化 `@Bean`。现实现两个 `@Bean` 各读一遍 `@Value` 三元组、各调一次 `chatClient(baseUrl, apiKey)`，产生两个 `RestClient` / 连接池，并且「是否启用」的判定逻辑复制了两份——将来一处改（比如加 `STRATO_LLM_TIMEOUT`）另一处漏改就会出现「规划器启用、分类器 Noop」的静默分叉。`backend-standard.md` §7 新措辞「共用同一组变量与 `ChatClient` 装配」已把 spec 的「同一 Bean」弱化为「同一装配方法」，属文档向实现妥协。
- **建议**：抽 `@Bean @ConditionalOnExpression`（或返回 `Optional<ChatClient>` 的单一 Bean）+ 一个 `record LlmSettings(baseUrl, apiKey, model)`，两实现按同一 Bean 注入；或最低限度把三变量判定收敛为一个私有方法。无实际运行时故障，属 DRY / 一致性。
- **分级**：SHOULD

#### S4 — `06-backend-module-spec.md` Agent Runtime 专项未同步三层路由与实体检查
- **位置**：`.harness/skills/coding-skill/specs/06-backend-module-spec.md:42`
- **问题**：仍写「领域路由先走 `DomainRouter`（规则）；LLM 只在候选集合内选工具」。spec §2.5 与 tasks T08 明确列入，v2 L-2 / L4 已指出该文件「实测存在、必改」。coding-skill 是阶段 3 的 L2 上下文，下一个 change 的编码 Agent 读到的将是旧模型（没有 `DomainResolver` / `IntentClassifier` / `EntityRequirementCheck`），与 `agent-safety.md` §2 冲突。
- **建议**：改为「领域路由 `DomainResolver`：`DomainRouter` 规则 → `IntentClassifier`（仅该 principal 可见领域枚举，越界 none）→ none；`EntityRequirementCheck` 在规划前拦截缺实体；LLM 只在候选集合内选工具…」。
- **分级**：SHOULD

#### S5 — `pipeline` 内局部变量 `replayed` 遮蔽同名 `ThreadLocal` 字段；`claimOrAwait` 以 `null` 表示 Owner
- **位置**：`backed/tool-gateway/src/main/java/com/strato/gateway/application/InvokeToolUseCase.java:57,184-186,211-213,250-253`
- **问题**：字段 `private final ThreadLocal<Boolean> replayed` 与 `pipeline` 里 `ToolInvoke.Response replayed = claimOrAwait(...)` 同名，`asReplayed` 又写字段——同一方法族里 `replayed` 有两种类型两种含义，读者要靠作用域推断。`claimOrAwait` 的 `case Claim.Owner o -> return null` 把「本线程拿到执行权」编码成 `null`，调用方靠 `if (replayed != null)` 分流；`ThreadLocal` 只是为了把 `pipeline` 的「是否重放」带回 `execute` 的审计处。三者叠加使幂等这段最关键的代码可读性最差。`ThreadLocal` 本身正确：`execute(Request)` 非重入（handler 在 executor 线程），`set(FALSE)` 在 try 前、`remove()` 在 finally，顺序无误。
- **建议**：让 `pipeline` 返回一个小 record `Outcome(Response response, boolean replayed)`（或 `Claim` 风格 sealed），去掉 `ThreadLocal` 与 `null` 哨兵；局部变量改名 `fromOthers` / `shared`。行为不变，`mvn verify` 即可回归。
- **分级**：SHOULD

### LOW

- **L1** `DomainResolver.java:52-57`：`registry.domains(principal)` 在调用 `classifier.classify` 之前无条件执行。`NoopIntentClassifier` 场景下每条规则未命中的消息都白做一次 `findAll` + `DiscoveryPolicy.filter`（首期 6 个 manifest，成本可忽略）。可把 `known` 改为 `Supplier<Set<String>>` 交给分类器按需取，或 Noop 分支短路。
- **L2** `IdempotencyStore.Claim.Awaiting` 直接暴露可写 `CompletableFuture`（v2 L-3 未采纳）；等待方可 `complete()` 污染 Owner 结果。首期只有 `InvokeToolUseCase` 一个调用方，风险受控；建议 `CompletionStage` 或 `Response await(long, TimeUnit)`。
- **L3** `InvokeToolUseCase.java:207`：等待 deadline 取 `execution.timeoutMs`，而 Owner 的 `callWithRetry` 最多跑 `(maxRetries+1) × timeoutMs`；`refund.create` maxRetries=0 不受影响，将来任何 `idempotency=required` 且 `maxRetries>0` 的工具会让等待方先于 Owner 超时（v2 L-3 已提）。建议 `RetryPolicy.allowedRetries(manifest)+1` 乘上去。
- **L4** `.harness/scripts/harness-doctor.mjs:220`：用 `Array.find` 取「第一条含 `antd` 且含 `import|出现`」的行。当前三文件命中正确（`platform-owner.md:21` 技术栈表因无 `import|出现` 被跳过）；但任何人日后在硬约束行之前写一句「antd 6 出现于桌面端」都会让 doctor 误红（fail-closed，不是漏检）。建议改为 `lines.filter(...)` 后要求「至少一行含 `packages/core/src/components/**`」。
- **L5** `.harness/scripts/e2e-backend.sh:185`：⑤ 用 `≥ 3` 判定。规则模式实测 7 条 `route` 行、LIVE 模式 ≥ 8，阈值只能抓「日志格式整体损坏」，抓不到「某条 Run 漏打」。可改为按本次脚本发起的 Run 数精确断言（规则模式 7）。不是恒真——若 `route` 日志被删或改名即红。
- **L6** `.harness/scripts/e2e-backend.sh:187`：末尾 `pkill` 后不等待进程退出即打印结果；本人实测脚本返回后 JVM 仍存活约 1–2 s。若紧接着跑 `deploy-verify.sh`，其 `cleanup; sleep 1` 后的 8080 探测可能仍看到旧进程而 `exit 2`。建议 `pkill … ; for i in 1..10; do lsof … || break; sleep 1; done`。
- **L7** `.harness/scripts/lib/change-dir.mjs:51` + `lib/change-dir.sh:4`（本 change 触及的 e2e / deploy-verify 均 source 它，属既有脚本，非本 change 引入）：CLI 判定 `fileURLToPath(import.meta.url) === process.argv[1]` 在软链路径下（本人从 `/tmp/e2e-root` 调用，`/tmp → /private/tmp`）两侧不等 → 什么都不打印、退出码 0 → `DEPLOY=""`，`|| exit $?` 守卫失效，脚本继续跑并把 `backend.log` 写到 `/`。用 `realpath` 比较或在 `.sh` 里加 `[ -n "$DEPLOY" ] || exit 2`。这是一条 Hashimoto 素材：守卫只看退出码不看输出。
- **L8** `summary.md:18` 写「tasks.md（9 task）」，v2.1 已是 11 task；`RunOrchestrator.java:139-141` 对 `selectedEntity` 的判空与 `:117-118` 的 `selected` 重复，可直接用 `selected`；`DomainResolver.RouteDecision` 以 `Optional` 作 record 组件（Java 惯例不建议），可改为可空 `String` + `Optional<String> domain()` 访问器。

### INFO

- **I1 `EntityRequirementCheck` 真值表（按 manifest `required` 与 `application.yml` 权限核实）**：refund / user_001：候选 4，`required` 全含 `orderId` → 无实体或 `type≠order` 拦截；refund / user_002：候选 3（无 `refund.create`），仍全含 → 拦截；order / user_001 与 user_002：候选 {`order.detail.get`(需), `order.list.search`(无 required)} → `allMatch` 为假 → 放行。`type.equals(selected.type())` 区分大小写，契约 `^[a-z]+$` 保证只会是小写，`RuleBasedLlmClient.hasEntity` 用同一比较，口径一致。`candidates.isEmpty()` 分支从 `RunOrchestrator` 不可达（`:142` 先判空），但对纯函数是必要守卫——`Stream.allMatch` 对空流返回 true，去掉守卫会把空候选误判为「全部需实体」。
- **I2 `entityType=refund`（白名单外）的双保险成立**：refund 领域 → `satisfied=false` → 拦截，不进规划；order 领域 → 不拦截 → `hasEntity=false` → `order.list.search`。即便有人绕过拦截直接到 `RuleBasedLlmClient`，`WITHOUT_ENTITY.refund = List.of()` → `steps.isEmpty()` → `TOOL_SELECTION_INVALID`，不会拿空参撞 Gateway。
- **I3 幂等并发语义复核（压测 + 代码）**：`release` 先 `remove(k, f)` 再 `completeExceptionally`，被唤醒的等待方再 claim 时 key 已空 → Owner，不存在「拿到已异常完成的 future 再 spin」的窗口（若顺序反过来才会短暂空转）；`complete` 对已 release 的 key（`store.get` 为 null）是 no-op，但 Owner 路径永远是 complete 成功即 return、失败才 release，不会先 release 再 complete，结果不会丢；`claim` 分支 `existing.isCompletedExceptionally()` → `Awaiting(existing)` 在当前实现里实际不可达（已异常完成的 future 总是先被 remove），保留作为防御无害。200 key × 16 线程 + 1/3 首执行失败：`execs == keys`、无 TIMEOUT、单线程最多 2 轮 claim。等待方在 Owner 因 `TIMEOUT` / `OUTPUT_INVALID` release 后会重跑 handler——对 `refund.create` 由 refund-service 自身幂等（`RefundIdempotencySelfCheck` 覆盖）兜底，属预期的纵深防御。
- **I4 审计 `replayed` 口径**：`execute` 用 `ThreadLocal` 判定后写 `status=replayed`，`resp.durationMs` 为 Owner 原值；`ToolStatus` 契约枚举未变，`.harness/contracts` 无 `replayed`。首期 §6.2.12「`refund.create succeeded = 1`」：④ 放在其后，且 §6.2.12 第二次确认在 Runtime 被 `CONFIRMATION_REJECTED` 拦下、不到 Gateway，实测仍 1；§6.2.14 `-m1` 取首条 `failed:INPUT_INVALID`，9 字段实测 9。重放响应的 `toolCallId` 是 Owner 的（`tc_idem1`）而非本次请求的（`tc_idem2`）——首期 `cached.get()` 已如此，非本 change 引入。
- **I5 前端依赖方向**：`registry/types.ts` 新增 `import … from '../schema/uiSchema'`（基线 c9a52b9 时 registry 不 import schema）。`schema/` 不 import `registry/`，方向为 renderer → registry → schema → zod，无环；与 v2 S5 的核对一致。`FormComponentProps` 改为契约级 `FormPropsSchema` 推导后，`options` item 变 `.strict()` 且 `label/value` 有 min/max，desktop / mobile `Form.tsx` 只读 `f.options ?? []` 的 `label/value`，结构类型不变，typecheck 通过；`Object.freeze({...} as const)` 类型为 `Readonly<{…}>`，`SchemaRenderer` 的 `keyof typeof PROPS_SCHEMAS` 索引不受影响（typecheck 通过）。check-registry 正则依赖 PROPS_SCHEMAS 的键在 2 空格缩进、闭合 `}` 在列 0——prettier 保证；若日后在字面量里内联 `z.object({\n})`，会多解析出一个键，属既有约束。
- **I6 未复跑项的替代证据**：LIVE 模式——`deployment/route3_events.log` 存在且为完整主链路序列，但本人不采信自述；建议阶段 7 在有 key 的环境重跑 `e2e-backend.sh` 并把 `grep -c "source=model"` 结果记入 `deployment/`。`e2e-frontend` 21/21——本 change 对 `apps/chat` 只改了 `RouteErrorBoundary` 的标题回退，core 公共 API 名与数量不变（verify-pack (c)(e) 17 + 10 实测），`preview-*.png` 已在 `deployment/`。

---

## 四、结论

**APPROVED**。spec v2.1 各节与两轮 plan 评审的全部 MUST / SHOULD 在实现中落地；agent-safety 六条边界成立；契约零变化；规则模式 e2e 62/62、mvn verify、check-module-deps（含负例）、doctor（含两条负例）、fronted 全套门禁均由本人独立复现。S1–S5 建议在阶段 5 推送前一并修掉（S1 是一行 `finally` 守卫，S2 是一行日志内容，S4 是一段文档），不需要再开评审轮次；L7 建议作为 Harness 经验沉淀写入 `summary.md`。
