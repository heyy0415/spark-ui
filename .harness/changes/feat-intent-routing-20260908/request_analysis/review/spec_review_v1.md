# Spec Review v1 — feat-intent-routing-20260908

| 字段 | 值 |
|---|---|
| mode | plan |
| 日期 | 2026-09-08 |
| 轮次 | 1 |
| 评审对象 | `request_analysis/spec.md`（v1）、`request_analysis/tasks.md`（v1） |
| 依据 | `rules/{agent-safety,backend-standard,project-structure,contracts,dev-workflow}.md`；`skills/expert-reviewer/SKILL.md` plan 必查 6 项；现状代码（agent-runtime / tool-gateway / tool-registry / app / domains manifests）、`.harness/scripts/*`、`fronted/packages/core/src/*`、`fronted/scripts/verify-pack.mjs`；前两个 change 的经验沉淀 |
| 独立性 | 未阅读任何编码 Agent 自评；只看产出物与仓库现状 |

**verdict：REVISION REQUIRED** — MUST FIX 5 条（M1–M5），SHOULD 8 条（S1–S8），LOW 5 条，INFO 4 条。

方向无需推翻：「规则优先 + 模型补位 + 代码兜底」与「规划前拦截」两项决策与 agent-safety §2 兼容，契约零变化成立。5 条 MUST FIX 全部属于同一类根因——**验收命令 / 自检在当前代码与数据上跑不通，或设计段与验收段互相矛盾**，这正是前两个 change 经验沉淀反复强调的（「验收命令必须在真实工具链上先跑一遍」「设计段与验收段各写各的」）。其中 M5 已用最小夹具实测证伪，M3 / M4 从现状代码可直接推出。

---

## 一、plan 模式必查 6 项

| # | 必查项 | 结论 | 备注 |
|---|---|---|---|
| 1 | 「非目标」章节存在且非空 | ✓ 通过 | §3 共 6 条；但漏了三项高风险「顺手改」（见 S6） |
| 2 | 每条验收标准都可被命令或断言校验 | ✗ 不通过 | §6.1 用例 ④「`status=succeeded` 恰 1」在现状审计语义下必为 2（M4）；④ 所用订单在 e2e 时序中已无可退订单（M4）；§6.2 `Object.freeze` 会让 `check-registry` 红（M5）；`SelfCheckRunner` 4→5 未同步 `deploy-verify.sh`（M3） |
| 3 | 风险章节列出 ≥1 个失败模式与缓解措施 | ✓ 通过 | §7 共 7 行；「幂等占位在 handler 异常时未释放」一行的缓解遗漏了等待方语义（S1） |
| 4 | 每个 task 标注所属端，contracts task 排在依赖它的 task 之前 | ✓ 通过 | 9 个 task 均有「所属端」；本 change 无 contracts task，§5 明确 NONE |
| 5 | 涉及跨端结构的 task 列出对应契约文件 | ✓ 通过（有条件） | 无新跨端结构；但 §2.2 的拦截若被实现为新 `RunFailureCode` 会变成 `run-summary.failureCode` 契约变更，spec 应在非目标里封死（S6） |
| 6 | 每个 task 工作量 ≤ 0.5 天 | △ 部分 | T07（doctor 新检查 + 5 条 e2e + 自检期望）与 T04（2 新类 + 2 改动 + 规划器修正 + 日志）超 0.5 天，建议拆分（S7）；依赖图无环 |

---

## 二、逐条意见

### MUST FIX

#### M1 — §2.2 拦截规则在 order 领域失效，且 spec 自相矛盾
- **位置**：spec §2.2 第 1、3、4 条；§4.3；tasks T04「`RuleBasedLlmClient`：`orderId` 缺失时跳过需 `orderId` 的模板步骤；全跳过 → `TOOL_SELECTION_INVALID`」
- **问题**：判定规则是「**全部**候选都要求实体才拦截」。order 领域两个候选中 `order.list.search` 的 `inputSchema` 无 `required`（`backed/domains/order-service/src/main/resources/tool-manifests/order.list.search.json`），因此「订单」+ 无 `pageContext` **不会被拦截**，放行进规划。规则规划器 `RuleBasedLlmClient.TEMPLATE.order = ["order.detail.get"]`（`infra/llm/RuleBasedLlmClient.java:830-833`）只有一个需要 `orderId` 的步骤；按 spec 的修正「缺 `orderId` 不生成该步骤」→ 步骤全空 → `ToolSelectionValidator` 抛 `TOOL_SELECTION_INVALID` → 用户看到「暂时无法为该请求制定可执行的方案」+ `run.failed`。这比首期的 `INPUT_INVALID` 只是换了一句同样莫名的错误文案，**比拦截提示更差**，而 spec 却断言「这条在拦截之后理论不可达，作为纵深防御」——对 order 领域可达，规则模式下必达。用户在 `/` 直接输入「查一下订单」就是这条路径。
- **建议**（三选一，写进 spec 并加 e2e 用例「`订单` + 无 pageContext（规则模式）」）：
  1. 规则规划器 order 模板改为「有 `orderId` → `order.detail.get`；无 → `order.list.search`（args `{}`）」，让「至少一个候选不需实体则放行」这条规则在规则模式下也有可执行结果；
  2. 或拦截规则改为「规划器**将要选择**的工具需要实体而无实体」（规则模式可知，真模型模式需先规划再校验，改动更大）；
  3. 或首期把拦截判定收窄为「该领域**存在**需实体的候选且无实体 → 拦截」并把文案改成中性的「请先在页面上选择一个订单」（order 领域会误拦「搜索订单」，需在 spec 里明示取舍）。
  同时删除「理论不可达」的措辞，或改为「refund 领域不可达；order 领域由 (1) 保证不触发」。
- **分级**：MUST FIX

#### M2 — `ToolRegistryClient.domains()` / `ToolSearchPort.domains()` 是一次不带 principal 的 Registry 查询
- **位置**：spec §2.1「`KNOWN_DOMAINS` 来自 Registry 当前已注册的领域集合（`ToolRegistryClient.domains()` 新方法）」；§8 假设 1；tasks T03「`ToolRegistryClient` 增 `Set<String> domains()`…经 T01 的 `ToolSearchPort` 暴露」
- **问题**：agent-safety §2 明文「Registry 查询必须带 `principal`（userId、tenantId），Registry 先按租户、权限、状态、风险策略过滤再返回」，且该文件头部声明「以下边界任一被破坏即 MUST FIX」。`domains()` 无 principal，返回的是**全租户全权限**的领域全集。就用户可见结果而言确实无泄露（模型判到无权领域 → search 过滤为空 → 与 none 同一句 `NO_CAPABILITY_TEXT`），但它引入了第一个不受 principal 约束的 Registry 读路径，并让分类 Prompt 把无权领域名也喂给模型，与规则的字面与精神都不符。此外 spec 把它放进名为 `ToolSearchPort` 的接口，语义上是「搜索」以外的枚举能力，接口职责开始混。
- **建议**：改为 `Set<String> domains(ToolSearch.Principal principal)`，Registry 侧实现 = `repo` 全部 manifest 经 `DiscoveryPolicy.filter(…, perms)` 后取 `domain` 去重。副产品：`KNOWN_DOMAINS` 变成「该用户可见领域」，无权领域直接判 none，不再多一跳 search。若坚持放在 `ToolSearchPort`，在接口 Javadoc 写明「发现面的两个只读查询：候选 / 领域枚举，均按 principal 过滤」；否则拆 `ToolDiscoveryPort`。§8 假设 1 同步改写。
- **分级**：MUST FIX

#### M3 — Gateway 幂等自检按 spec 描述无法注入「人工 ToolHandler」；自检计数 4→5 漏改 `deploy-verify.sh`
- **位置**：spec §2.3、§6.1「新增 `IdempotencySelfCheck`（gateway 层）：并发两次同 key 调用只执行一次 handler…`SelfCheckRunner` 计数 4 → 5，e2e 期望同步」；tasks T02「新增 `gateway/infra/selfcheck/GatewayIdempotencySelfCheck`（用一个人工 `ToolHandler` 记录调用次数…）」
- **问题**：
  1. `InvokeToolUseCase` 的 handler 表在构造时由 Spring 注入的 `List<ToolHandler>` 一次性建成不可变 Map（`application/InvokeToolUseCase.java:63-74`），管线第 1 步还要 `resolver.resolve(toolId, version)` 拿到 Manifest、第 3 步查权限。一个「人工 ToolHandler」要走到 handler 只有两条路：(a) 注册成 Spring Bean——它会成为真实 handler 表的一员，还需要一份真 Manifest 注册进 Registry 与一条权限，这是把测试夹具带进生产装配；(b) 在自检里私建一个 `InvokeToolUseCase`（自备 `ToolResolver` / `PrincipalPermissionResolver` / `AuditSink` / `List<ToolHandler>`）——可行，但 spec / tasks 均未写，编码 Agent 极可能落到 (a)。另一个可行方案是**直接测 `IdempotencyStore` 的 claim 语义**（两线程同 key `claim`：恰一个拿到空、另一个阻塞到 `complete` 后拿到同一 `Response`；`release` 后再 `claim` 可再次拿到空），不碰 `InvokeToolUseCase`。
  2. `.harness/scripts/deploy-verify.sh:29` 硬编码 `check "selfcheck all OK" 4 …`，spec 只写「e2e 期望同步」，阶段 7 必红。此外 `SelfCheckRunner` 日志是 `running {} checks`，`e2e-backend.sh` 并没有断言这个数字，spec §6.1 的「计数 4 → 5」找不到落点。
- **建议**：spec §2.3 明确自检对象与构造方式（推荐：自检只测 `IdempotencyStore`，用 `CountDownLatch` 控制 `complete` 时机以**保证**第二个 `claim` 真正落在 PENDING 期；并额外覆盖「A `release` 后 B 能重新 claim」）。§6.1 / T07 把 `deploy-verify.sh:29` 的 4 改 5 列入输出；若要断言 `running 5 checks`，在 e2e 里加对应 `check`。
- **分级**：MUST FIX

#### M4 — e2e 用例 ④ 在现有时序与审计语义下不可能通过；§4.5 自相矛盾
- **位置**：spec §2.5 ④；§4.5「审计 A `succeeded`、B `succeeded`（重放不另记审计…）」；§6.1「用例 ④：`grep -c 'toolId=refund.create .*status=succeeded' backend.log`（该订单）恰 1」
- **问题**：
  1. **无可退订单**。种子只有 3 个订单（`InMemoryOrderRepository.java:20-22`）：10001 在 §6.2.9 已退款、10002 在「评审 M2」已退款、10003 被 `RefundIdempotencySelfCheck` 启动时退款。`EligibilityPolicy.evaluate(…, alreadyRefunded=true)` 直接不可退，`RefundService.create` 抛 `IllegalStateException` → 两次响应都是 `failed:HANDLER_ERROR`，`output.refundId` 不存在，「恰 1 条 succeeded」为 0。
  2. **重放也写 `status=succeeded` 审计**。现状 `pipeline` 命中缓存 `return cached.get()` 后回到 `execute`，仍 `audit.record(... resp.status().name() ...)`（`InvokeToolUseCase.java:100-111`）。改为 claim 后等待方拿到最终结果同样会走这条审计。所以两次调用 = 2 行 `status=succeeded`，与 §6.1「恰 1」矛盾；§4.5 一句话里同时写「B `succeeded`」和「重放不另记审计」，互斥。
  3. **并发不可保证**。两条后台 `curl` 之间的启动抖动是毫秒级，而内存 handler 本身也是毫秒级完成；第二次请求多数时候会落在「已有最终结果」的重放分支而非「PENDING 等待」分支。用例名义上测 claim，实际大概率只测了旧的重放路径。
  4. **§6.1 grep 无法按订单过滤**。审计行不含 `orderId`（`LogAuditSink` 9 字段无参数原文），「（该订单）恰 1」这一限定没有可执行形式。
- **建议**：
  - 新增种子订单 `10004`（PAID，专供 e2e ④），并把「新增一条 order 种子数据」列入 §2 范围（§3 非目标只说不新增领域 / 工具 / 契约，种子数据不在其内，但要显式写出）。
  - 明确审计口径：重放 / 等待方拿到已完成结果时审计 `status=replayed`（仅日志词汇，非契约），这样 `status=succeeded` 恰 1、`status=replayed` 恰 1，审计轨迹完整且可断言。§4.5 相应改写。
  - e2e ④ 只断言**结果**：两响应 `output.refundId` 相等、`refund.status.get 10004` 返回 1 条、`status=succeeded` 恰 1；把「只执行一次 handler / claim 阻塞」这类**路径**断言交给 M3 的自检用 latch 确定性证明。
  - 删除「（该订单）」限定，改用 `runId` 或 `toolCallId` 过滤（两次 curl 各自指定 `runId=run_e2e_idem`、不同 `toolCallId`）。
- **分级**：MUST FIX

#### M5 — `Object.freeze(PROPS_SCHEMAS)` 会让 `check-registry.mjs` 找不到 `PROPS_SCHEMAS`，`fronted ci` 红
- **位置**：spec §2.4 第 1 条；§6.2「`grep -n "Object.freeze(" …/registry/types.ts` ≥ 1」；tasks T05「`Object.freeze(PROPS_SCHEMAS)`（check-registry 正则兼容）」
- **问题**：`fronted/scripts/check-registry.mjs:45` 的正则是 `/export const PROPS_SCHEMAS = \{([\s\S]*?)\n\} as const;/`，只接受 `= {` 开头、`} as const;` 结尾。实测：对 `export const PROPS_SCHEMAS = Object.freeze({ … } as const);` 该正则返回 `null` → `fail('cannot find PROPS_SCHEMAS')` → `pnpm lint` 非 0 → §6.2 第 1 条「`pnpm -C fronted run ci` 0」不成立。T05 括注「check-registry 正则兼容」是一句**未经验证的机制断言**（与上一 change 三轮 MUST FIX 同型），且 T05 的输入 / 输出都没有列 `check-registry.mjs`。同文件 `keysOf()` 对 `desktopRegistry` 已经接受 `Object.freeze(` 两种写法，说明这个坑上次已经踩过一遍。
- **建议**：T05 输入 / 输出补 `fronted/scripts/check-registry.mjs`，正则改为与 `keysOf` 同款（`(?:Object\.freeze\()?\{…\}(?: as const)?\)?;`），并在 T05 验收加一条负例：临时把 `PROPS_SCHEMAS` 改回非 freeze 形态也应仍然绿（两种形态都可解析）。
- **分级**：MUST FIX

---

### SHOULD

#### S1 — `release` 后等待方的语义未定义，会白等满 `timeoutMs`
- **位置**：spec §2.3 N6；§7「幂等占位在 handler 异常时未释放」一行
- **问题**：A claim 成功、执行失败、`finally release`；此时 B 正阻塞在 A 的 `CompletableFuture` 上。spec 只说 `release` 让「下一次重试可执行」，没说 B 拿到什么。若 `release` 只是 `map.remove(key)`，B 会等到 `execution.timeoutMs` 后返回 `TIMEOUT`——而 A 早在几毫秒前就失败了，B 的错误码还与真相不符。
- **建议**：`release` 必须 `completeExceptionally` 该 future；等待方收到异常后**重新 claim 一次**（此时能拿到空位自己执行）或直接以 A 的失败码返回；spec 写明选哪一种并进自检（M3 的「release 后可再 claim」用例覆盖）。
- **分级**：SHOULD

#### S2 — §2.1 关于 `entityType` 在规则层的措辞自相矛盾；`entityType` 进 Prompt 前需白名单
- **位置**：spec §2.1 最后一段「规则层也把 `entityType` 加入匹配（`order` 实体 + 无关键词 → 不直接判定，仍交给模型）」；tasks T03 Prompt「可选『用户正在查看一个 {entityType}』」
- **问题**：「加入匹配」与「不直接判定」是相反的动作；T04 的表述「`entityType` 只作为分类提示，不单独判定」才是想要的语义。另外 `selectedEntity.type` 是 pageContext（agent-safety §4：不可信输入），契约只限 `^[a-z]+$` ≤ 32，任何小写单词都能进 Prompt；虽不能放宽权限，但仍是一段直接拼进系统提示的用户可控字符串。
- **建议**：删掉「规则层也把 entityType 加入匹配」整句，统一为 T04 措辞；分类器只在 `entityType` 落在已知实体白名单（首期 `{order}`）时才附提示，否则忽略；spec §7 第 1 行的缓解补上这一条。
- **分级**：SHOULD

#### S3 — 分类 Prompt 的「领域一句话说明」来源未定；闲聊必须能判 none；用例 ② 在真模型模式下期望不稳
- **位置**：spec §2.1「Prompt 只给领域名与一句话说明」；§2.5 ②「`entityType = order` 但消息『今天天气怎么样』→ 无能力路径」；§6.1「有 LLM 变量：同脚本全部通过」
- **问题**：Manifest 没有领域级描述字段，`KNOWN_DOMAINS` 又来自 Registry，那「一句话说明」放在哪、领域集合与说明表不一致时怎么办，spec 没说。② 在规则模式下无分类器，必然 none，测不到「不误路由」；在真模型模式下「今天天气怎么样」+「用户正在查看一个订单」的提示很可能被判 `order`，接着 order 领域因 `order.list.search` 无 required 而放行（M1），真模型再随机规划——② 会在 LIVE 模式下不稳定地红，而 §6.1 要求 LIVE 模式全绿。
- **建议**：说明表硬编码在 runtime（`Map<String,String>`），Registry 返回的领域若无说明则只给领域名；Prompt 明示「与任何领域无关的请求（闲聊、天气等）输出 none」；② 在两种模式下都跑，LIVE 模式把「事件恰为 `run.started message.delta run.completed`」作为期望，并在 spec §7 承认这一条依赖模型行为、失败时视为 Prompt 缺陷而非代码缺陷。
- **分级**：SHOULD

#### S4 — 用例 ③ 在真模型下把「分类正确」与「整条主链路」绑在一起断言，脆弱
- **位置**：spec §2.5 ③；§4.2；§6.1「有 LLM 变量…含 ③」
- **问题**：③ 期望「事件序列与首期主链路一致」——这同时要求分类器判 refund、规划器给出 3 步含 `refund.create`。首期经验（summary 末段）已记录真模型曾省略 `refund.create`，需要靠 Prompt 才稳住；现在再叠一层分类不确定性，脚本会把分类失败与规划失败混成一个红。
- **建议**：③ 拆两条断言：③a `grep "route runId=<runId> domain=refund source=model"` 恰 1（只证分类）；③b 事件序列（沿用现有主链路断言）。任一红时报告能直接指出是哪一层。
- **分级**：SHOULD

#### S5 — `ActionBarProps` 收敛到 `renderer/ActionBar.tsx` 会形成 renderer ↔ components 的类型环
- **位置**：spec §2.4 第 2 条；tasks T05「`ActionBarProps` 只在 `renderer/ActionBar.tsx` 定义，desktop / mobile `import type`」
- **问题**：三处定义确认完全相同（`actions: UiAction[]; disabled?: boolean; onAction`），去重方向正确。但 `renderer/ActionBar.tsx` 已 import `components/desktop/ActionBar` 与 `components/mobile/ActionBar`；让后两者反向 `import type` 前者，就是一个仅靠 `import type` 擦除才不成环的依赖回路。project-structure §1 允许 `import type` 跨层，oxlint 也未开 `import/no-cycle`，所以能过门禁，但 core 内既有方向是 renderer → components → schema/registry，反向引用会成为先例。
- **建议**：把 `ActionBarProps` 放到叶子模块（`registry/types.ts` 已承载 `RenderedComponentProps` 这类共享 props 类型），renderer 与两端实现都从那里 import；`index.ts` 的 `export type { ActionBarProps }` 改路径即可，公共 API 名不变，verify-pack (e) 仍 10 类型。
- **分级**：SHOULD

#### S6 — 非目标漏三项最容易「顺手改」的点
- **位置**：spec §3
- **问题**：(1) 拦截路径最省事的实现是新增 `RunFailureCode.ENTITY_REQUIRED` + `run.failed`，那是 `run-summary.failureCode` / `sse-events` 的契约变更，与 §5 NONE 冲突；(2) `NO_CAPABILITY_TEXT` 与拦截文案相邻，编码时容易被「统一润色」；(3) 拦截路径的 Run 终态是 COMPLETED 还是 FAILED、`run-summary` 里 `failureCode` 是否为空，spec 未写。
- **建议**：§3 增加：不新增 `RunFailureCode` / `RunState`；不改 `NO_CAPABILITY_TEXT` 与现有 `userMessage` 文案；不改任何路径的 SSE 事件顺序。§2.2 / §4.3 写明拦截 Run 终态 = `COMPLETED`、`GET /agent/runs/{runId}` 无 `failureCode`，并在 e2e ① 加一条 `state == COMPLETED` 断言。
- **分级**：SHOULD

#### S7 — T07 / T04 超 0.5 天；T07 混合了两件互不相关的事
- **位置**：tasks T04、T07
- **问题**：T07 = doctor 新检查（含负例验证）+ 5 条 e2e（其中 ③④ 需要真模型 / 并发调试）+ 自检期望，且依赖 T02、T04 全部完成后才能开工，是关键路径上的最大块；T04 = 2 个新类 + `RunOrchestrator` 改造 + 规划器修正 + 日志 + 4 条 curl 验收。
- **建议**：T07 拆为 T07a「doctor L1 一致性检查」（无依赖，可与 Phase A 并行）与 T07b「e2e 新用例」（依赖 T02、T04）；T04 若吸收 M1 的 order 模板修正，拆出 T04b「`RuleBasedLlmClient` 缺实体修正 + order 无实体 e2e」。
- **分级**：SHOULD

#### S8 — `IdempotencyStore` 接口取舍未定：`putIfAbsent` 删不删、`find` 留不留
- **位置**：spec §2.3；tasks T02「保留 `find` 供只读查询」
- **问题**：`putIfAbsent` 与 `find` 在仓库内的唯一调用方都是 `InvokeToolUseCase.pipeline`（已核实）。claim 模型下 `putIfAbsent` 语义与 `claim/complete` 重叠，保留会留下两套互不感知的写路径；`find` 目前没有任何「只读查询」调用方，属预留。
- **建议**：删除 `putIfAbsent`（接口无外部实现者，不存在兼容问题）；`find` 要么删除、要么在 spec 指出本 change 内的具体调用方（如 M3 自检用它断言终态），否则违反「不为单次使用 / 未来场景预留」。
- **分级**：SHOULD

---

### LOW

- **L1** §2.5 ⑤ `grep -c "source=" backend.log ≥ 1`：`source=` 过于泛化（Spring / Logback 日志可能出现同串）。改为 `grep -c "route runId=.* source=\(rule\|model\|none\)"`。
- **L2** tasks T01 验收 `grep -rn "\.application\." … | grep "registry\|gateway"` 靳靠大小写差异（`ToolRegistryClient` 的 R 大写）才不误报 runtime 自己的 `application.port.ToolRegistryClient`；统一为 spec §6.1 的 `grep -rn "com.strato.registry.application\|com.strato.gateway.application"`。
- **L3** §4.4 WARN 日志「不含用户原文」应扩展为「也不含模型原始输出」——结构化输出解析失败时最自然的写法是把 raw text 打进 WARN，而 raw text 可能复述用户消息。
- **L4** spec §2.5 / T08 多处写「若存在」：`backed/agent-runtime/README.md` 存在；`06-backend-module-spec.md` 第 41 行有「Agent Runtime 专项」段。两处都是**必改**，去掉「若」。
- **L5** §6.3「植入 `CLAUDE.md` 硬约束行改回 `shared/ui/**` → 红」：doctor 新检查需同时约束「含 `packages/core/src/components/**`」与「不含 `shared/ui/**`」，spec 已写两条，但 T07 验收只写了第二条的负例；补第一条负例（把路径改错拼写 → 红）。

### INFO

- **I1** Form schema 去重可行且安全：契约级 `FormPropsSchema` 的 `options` 约束（`min(1).max(64)`、item `.strict()`）严于原 `FormFieldPropsSchema`，但所有经 `parseUiSchema` / `UiComponentSchema.superRefine` 进入渲染器的 UI 已按契约级校验过，`UiSchemaBuilder` 产出 3 个 option；`e2e-frontend` 21 条不受影响。唯一暴露面是绕过 `parseUiSchema` 直接调 `SchemaRenderer` 的外部接入方传 `options: []`，这本来就是契约违规。
- **I2** `--write-baseline` 透传已实测：`pnpm run verify-pack -- --write-baseline` 的 `process.argv` 为 `["--","--write-baseline"]`，`pnpm run verify-pack --write-baseline` 为 `["--write-baseline"]`；实现必须用 `process.argv.includes('--write-baseline')`，不能按位置取。
- **I3** Spring 装配无歧义：`SearchToolsUseCase implements ToolSearchPort` 后各自只有一个 Bean，Controller 按类、runtime 按接口注入都能唯一解析。`check-module-deps.mjs` 现有 peers 规则正则 `(infra|domain)` 直接扩为 `(infra|domain|application)` 即可覆盖三模块互相引用（gateway / registry 目前不引用对方 application，扩展无副作用）。
- **I4** 每条未命中规则的消息（含闲聊）从「0 ms 回复」变为「一次模型调用」；§7 已承认。建议 `route` 日志加 `classifierMs=` 便于后续评估是否需要更短的负向规则表。

---

## 三、tasks.md 机械核对

| 项 | 结论 |
|---|---|
| 六要素（目标 / 所属端 / 输入 / 输出 / 验收 / 依赖） | 9/9 齐全 |
| ≤ 0.5 天 | T04、T07 超（S7） |
| 依赖无环 | ✓（T01→T03→T04→T07→T08→T09；T02、T05、T06 汇入 T07） |
| 验收可命令化 | T02（M3）、T05（M5）、T07（M3/M4）不成立；其余成立 |
| 输入 / 输出与 spec 一致 | T05 漏 `check-registry.mjs`（M5）；T07 漏 `deploy-verify.sh`（M3）；T02 自检对象与 spec §6.1 描述不一致（M3） |

## 四、结论

**REVISION REQUIRED**。请在 spec v2 中：合并 M1–M5 的设计与验收改写；S1–S8 择优吸收（S1、S6 建议直接采纳，成本一行到数行）；L1–L5 顺手修。按前两个 change 的经验，v2 提交前请把 §6 中每一条含命令的验收在现有代码上先跑一遍（尤其 ④ 的订单可退性与审计计数），把首次实测结果附在验收条目后。
