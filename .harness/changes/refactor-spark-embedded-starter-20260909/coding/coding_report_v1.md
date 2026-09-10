# Coding Report v1 — refactor-spark-embedded-starter-20260909

日期：2026-09-10 · 基线：change 4 末态（e2e-backend 109/109、e2e-frontend 35/35、deploy-verify 12/12、自检 7）

## 提交（按 tasks.md v2 顺序）

| commit | task | 内容 |
|---|---|---|
| `c8b9b36` refactor(spark-rooter) | T01 | `backed → spark-rooter`，模块 / Maven 坐标 / Java 包 / `SPARK_*` 改名，脚本与规则同步；纯机械 |
| `3b14ec8` refactor(spark-ui) | T02 | `fronted → spark-ui`、`@spark-ui/core`、`Spark*` 导出、契约 `$id`、lockfile 重生成 |
| `405c78b` ci(harness) | T03 | `check-rename.mjs` 纳入 ci / doctor（下一 change 删除） |
| `241ab70` feat(contracts) | T04 | 去 `pageContext / principal`，`executionContext.sessionId`，`authorization.permission` 可选，`Card.actions`；示例 26 → 27 |
| `bbe8922` feat(spi) | T05 | 注解集合、`ToolContext`、宿主端口、删 `Principal`；`examples/demo-support` |
| `0b02df9` refactor(registry,gateway) | T06 | 去身份与鉴权步骤、`sessionId` 幂等域、`AuditSink` / `ToolAccessPolicy` 端口 |
| `e358c1a` refactor(runtime) | T07a | `sessionId` 双绑定令牌、去 pageContext、上下文传播；e2e 去身份头 |
| `c41f03b` refactor(web-mvc) | T07b | Web 层剥离为 `spark-rooter-web-mvc`；平台模块去 starter-web（红线） |
| `7c8368a` feat(starter) | T08 | `spark-rooter-spring-boot-starter` 自动装配；平台模块去 Spring 组件注解（红线） |
| `eaef57c` feat(starter) | T09a | `SparkToolScanner` / `ManifestDeriver` / `AnnotatedToolHandler` / `ToolMetaRegistry`；两个自检 |
| `b0acfbe` refactor(examples) | T09b | 四领域改 `@SparkTool`（12 tools / 4 beans），删 Handler / Manifest JSON；parity 12/12 |
| `8246356` feat(examples) | T10 | `examples/host-demo` 独立宿主；删 `app`；e2e ⑭ ⑭' ㉖ |
| `f5d4de5` feat(runtime) | T11 | `ArgumentExtractor`（别名 / 数量 / 相对时间）、规划器填参、校验器值校验 |
| `5d45502` feat(runtime) | T12 | 会话记忆、序数指代、澄清屏、`Card.actions`；e2e ⑰–㉕ + ㉓ 二次启动 |
| `9485f00` feat(spark-ui) | T13 | 去 pageContext / 身份，`Card.actions` 渲染，`AgentChatPanel({conversationId, baseUrl?, fetch?})`；e2e-frontend 37 |
| `27ae150` docs(harness) | T15 | 规则 / wiki / Skill / README 同步 |
| （本 commit） | T14 / T16 | 脚本改造随 T10–T13 落地；全链路验收、产物冻结、本报告 |

## 验收（真实输出）

```
pnpm -C .harness run ci（clean dist）           exit 0：check-rename / check-contracts（9 / 27）/ check-module-deps / check-seed / spark-ui / spark-rooter install / host-demo mvn -o package 全 0
SPARK_PORT=8091 bash e2e-backend.sh（规则）      145 passed / 0 failed（含 ㉓ 以 --spring.profiles.active=e2e-ttl 第二次启动）
SPARK_PORT=8091 bash deploy-verify.sh            12 passed / 0 failed；selfcheck all OK 9（平台 8 + refund.create idempotent）
SPARK_FRONT_BASE=5199 node e2e-frontend.mjs      37 passed / 0 failed
pnpm -C .harness run doctor                      0 errors 0 warnings
host-demo 启动日志                                spark-rooter: 14 tools registered from 6 beans（12 领域 + demo.whoami + 自检 echo）；SessionIdResolver demo WARN
GET /internal/tool-registry/tools/order.list.search/versions  inputSchema == {status enum 5, limit min 1 max 50 default 20, additionalProperties false, 无 required}
spec §6.1–6.4 grep                               strato 0 / fronted|backed 0 / 平台模块组件注解 0 / userId|tenantId|Principal 0 / core pageContext|entityType|chips 0 / lockfile strato 0
```

### 植入反例（先红后绿并还原）

| 门禁 | 植入 | 结果 |
|---|---|---|
| check-rename | `spark-ui/README.md` 加 `Strato` | 红 |
| check-contracts | 示例加 `pageContext` / `tool-search` request 加 `principal` / `Card.actions` 7 项 | 各红 |
| check-module-deps | gateway pom 加 `spring-boot-starter-web` / runtime 源码加 `@Component` / order-service pom 依赖 `spark-rooter-runtime` | 各 1 violation |
| SparkToolScanner | `final` 方法 / 两方法同 id / In 非 record / 空 description | 各启动失败并指明原因 |
| starter 条件装配 | 宿主定义 `AuditSink` Bean | `--debug` 报告 `sparkRooterAuditSink … Did not match（found beans … hostAuditSink）` |
| ManifestParitySelfCheck | 推导与旧 JSON 首次比对 | 真实发现 `limit.default` 差异（有意变化，回写 legacy JSON）与 IntNode / LongNode 不等（归一化修复） |

## 关键决策与偏差

1. **`examples/demo-support` 模块**（spec 未列）：内核删 `Principal` 后，示例领域的租户来源需要一个宿主侧 mock（`DemoUserContext`）；放在独立纯 JDK 模块避免领域互相 import，也是真实宿主替换点的示意。`check-module-deps` 已把它列入领域允许依赖。
2. **`app` 模块在 T09b 保留到 T10**：tasks 写 T09b 删 `app`，但 T09b–T10 之间会没有可运行宿主，e2e 断档；改为 T10 host-demo 就位后同一 commit 删除。
3. **legacy Manifest 两处有意差异回写**：`order.list.search` / `product.list.search` 的 `limit` 增 `default: 20`（`@SparkDefault` 写进 schema 是 spec 的要求）；`refund.status.get` 行对象补 `additionalProperties:false`（推导器对 record 一律收紧，handler 输出恰为四字段）。其余 10 个 Manifest 逐字段一致。
4. **记忆补位改为懒补位**（T13 时发现）：spec 写「当前消息缺实体类型时用记忆补位」，前端 e2e 暴露「查看商品 P-1003」后说「有什么商品」会被补成详情；改为只在目标工具确实缺实体（拦截或 `MissingEntity`）时才补，序数指代仍立即解析。
5. **`AnnotatedToolHandler` 异常口径**：宿主异常只保留类名 + 首行，不带 cause，避免业务数据经堆栈进日志 / 响应。
6. **e2e ㉔ 的 sessionId 不一致**：demo `SessionIdResolver` = conversationId，HTTP 层构造不出「同 Run 不同 session」，改为「令牌用于另一 Run → 拒绝」+ 自检 `session-mismatch rejected` 双覆盖。
7. **e2e-frontend 37 而非 36**：「返回列表」拆为「按钮存在」+「点击后回到 Table」两条，另加 `product-detail` 示例渲染。
8. **host-demo 离线打包前删 `target/`**：`mvn -o package` 在 target 已存在时 boot repackage 沿用旧 jar；离线又没缓存 clean 插件，ci 段用 `rmSync`。
9. **LIVE 模式未跑**：本机 shell 无 `SPARK_LLM_*`；规划器代码路径（`SpringAiLlmClient.fillDefaults` / preflight / validate）与规则模式共用同一抽取与校验，LIVE 复验留给阶段 7 / 用户环境。

## agent-safety 自查

- §1：Runtime 只经 `ToolGatewayClient`；Registry 无转发端点。
- §2：候选不再按用户过滤（内核无身份），`ToolAccessPolicy` 可选；模型只在候选内选工具，参数值过 JSON Schema，实体值 == 抽取值；澄清屏只调 `clarifiesEntity` 声明的只读工具。
- §3：令牌绑 `runId + actionId + argsDigest + conversationId + sessionId`；三个需确认工具都有领域 recheck 与确认屏（`ConfirmationCoverageSelfCheck`）。
- §4：前端只发自然语言（输入框 / chip / Table 与 Card 行内指令同一 `send`）；无 pageContext / 身份 / 业务字段。
- §5：Gateway 经 Spring 代理调用，宿主 `@Aspect` 在 e2e ⑭ 真实拒绝；反面路径 ⑭' 证明 Controller 拦截器不保护 spark 调用（README 与 agent-safety 明写）。
- 日志卫生：无用户原文（`user text in log` 0）；LLM 三项不进日志；doctor 密钥形态扫描 0。

## Hashimoto 沉淀（已入 summary.md）

- python 批量锚点替换：一次 `edit()` 内任一锚点失配会让后续锚点静默不生效 → 每批替换后 grep 复核（本 change 因此漏改三次）。
- BSD sed 不识别 `\b` → 改名类批量替换用 perl。
- 推导 Manifest 与手写 JSON 比对经字符串往返归一化数字节点。

---

# 阶段 4 回修记录 v1（响应 `coding/review/code_review_backend_v1.md`：4 MUST / 10 SHOULD；`code_review_frontend_v1.md`：1 MUST / 4 SHOULD / 8 LOW）

## 后端

| # | 意见 | 处理 | 证据 |
|---|---|---|---|
| M-1 | `check-module-deps` 身份标识符红线未实现；三条既有规则正则仍旧包名（`com.spark.` / `com.sparkrooter.domain.`）静默失效 | 新增 `IDENTITY` 规则（7 个平台模块源码禁 `\b(userId\|tenantId\|Principal)\b`）；三处正则改 `com\.sparkrooter\.` / `examples\.`；组件注解规则同时抓 FQN（S-8）；**每条正则配内置正样本自测**，改名后失效会直接红（Hashimoto） | 植入 runtime `String userId` → 红；自测 6 条 |
| M-2 | spi `OrderSnapshotProvider.snapshot(String tenantId, …)` 含 `tenantId`；报告声称 grep 0 不实 | `OrderSnapshot(Provider)` 从 spi 迁到 `examples/demo-support`（示例领域间的跨域只读端口是宿主 / 示例侧概念，tenantId 是示例业务字段）；module-deps 把 `examples.support` 列为共享支撑模块；文档同步 | 平台模块 grep 0 且由门禁守 |
| M-3 | 「user text in log」断言的短语已无人发送，恒 0 | e2e 改为对本轮实际发送的 5 条消息逐条 grep 0；deploy-verify 同步 | 5 条断言全 0 |
| M-4 | 澄清屏后 `remember()` 用 `LastTable("", ids)` 覆盖 clarify 写入，「第二个」失效 | `clarified` 集合标记出过澄清屏的 Run，`remember()` 不覆盖；`LastTable` 增 `pendingMessage`（挂起原话）；纯序数指代消息路由沿用记忆领域（`source=memory`），规划消息 = 原话 + 「第二个」 | e2e ㉒'：「申请售后」→ 澄清屏 → 「第二个」→ 确认屏标题 == 「订单 rows[1].id」，日志 `source=memory(ordinal)` |
| S-1 | Runtime 发现路径给 `ToolAccessPolicy` 的 sessionId 恒 null | `ToolSearchPort.search(request, sessionId)` / `ToolRegistryClient.search(request, sessionId)`；编排器透传 `run.sessionId()`；HTTP 直调与自检仍为 null | — |
| S-2 | 记忆只按可伪造的 conversationId 键控 | spi `ConversationMemory.put/find(sessionId, conversationId, …)`；内存实现键 `sessionId/conversationId` | e2e ㉔：B 用 A 的 conversationId 说「申请售后」→ 澄清屏而非 A 的订单 |
| S-3 | 双绑定退化为单因子；㉔ 只测 runId 绑定 | host-demo 增 `DemoSessionIdResolver`（`@Profile("e2e")`，`user:conversationId`），e2e 主启动带 `--spring.profiles.active=e2e`；㉔ 改为真实 HTTP 级断言：A 的令牌用 `X-Demo-User: userb` 提交 → `CONFIRMATION_REJECTED` + 日志 `session mismatch` + B 读 A 的 Run 404 + 无 refund.create 审计。默认 profile 仍走 demo WARN | ㉔ 6 条 |
| S-4 | 澄清 intent 回拼原话：超长截掉 id / 含 `<` 违反契约 → INTERNAL_ERROR | intent 改为「`<动词标签> <实体名> <id>`」（如「申请售后 订单 10029」），不回拼原话 | ㉒ 点选仍到确认屏 |
| S-5 | 自检 echo 工具以 active 进 Registry，污染候选与 `domains()` | `ManifestDeriver`：`spark` 域（自检专用）注册为 `draft`，Gateway 可直调、Registry 不可发现 | ProxyInvocationSelfCheck 仍 OK |
| S-6 | `RunRepository` 无 TTL；`lastUi` 只增不减 | `InMemoryRunRepository(ttl, clock)` + `RunRepository.evictExpired()`；`spark.runtime.run-ttl`（默认 1h）；编排器每次终态淘汰过期 Run 并清 `lastUi / stepOutputs / inputSchemas / confirmLocks / clarified` | — |
| S-7 | 校验器另起 `JsonSchemaFactory` + 裸 mapper | `assertValueMatches(…, SchemaValidator)` 复用 `validateWithInlineSchema`；`validate()` 增 validator 参数，两个 LlmClient / PlanSelfCheck 注入 | — |
| S-8 | 组件注解规则可被 FQN 绕过 | 并入 M-1 | 自测含 FQN 样本 |
| S-9 | `entityArgs()` 同名不同类型 last-wins 且每次重算 | `register()` 增量维护并在冲突时启动失败；`entityArgs()` 只做一次 map 合并 | — |
| S-10 | ⑭' 拦截器只拦 `/demo/**`，与 spark 路径无交集，同义反复 | host-demo 增 `/demo/orders`（直接调同一个 `OrderTools.list`）；⑭' 断言 guest 直调 403、admin 200、guest 经 spark 同能力成功 | ⑭' 3 条 |

## 前端

| # | 意见 | 处理 |
|---|---|---|
| M-1 | `baseUrl ?? ''` 永久短路 `VITE_API_BASE_URL` | `AgentChatPanel` 缺省 `baseUrl ?? env.VITE_API_BASE_URL` |
| S-1 | `getRun` 绕开 `request()`，裸 `Error`、不读 env | `RequestOptions` 增 `fetch? / baseUrl?`；`getRun` 回到 `request()` |
| S-2 | 破坏性契约变更原地改 v1 无说明 | `contracts.md` §5a 补「无外部消费方、沿用 v1；首个外部消费方后失效」 |
| S-3 | `check-rename` 按子串放行仓库根目录名，掩盖 `servers.json` 的本机绝对路径 | 删根目录名放行；`servers.json` 改相对路径 `"."` |
| S-4 | 门禁只证明合法示例通过，投影放宽不可见 | `.harness/contracts/examples/invalid/*.invalid.json`（6 条：pageContext 残留、components 重复、principal 残留、userId/tenantId 残留、Card.actions 7 项、intent 含 URL）；`check-contracts` 断言 Ajv 拒绝、`verify-examples` 断言 Zod 拒绝（4 条前端投影） |
| L-1 / L-2 / L-3 / L-5 / L-6 | README / 注释仍写 `onIntent` 只来自 Table；e2e 注释 8 示例；verify-examples 注释 16；`components` 缺 uniqueItems；placeholder「这个订单」 | 全部修正；`components` 增 `.refine` 唯一性 |
| L-4 / L-7 / L-8 | 基线手写时间戳；§5a 未记 `$id`；`transport.fetch` 方法调用 | 基线保留 note（`--write-baseline` 会覆盖 recordedAt，改动仅 1 KB 记录）；`$id` 变更已在 §5a 首段与 T02 提交说明；`getRun` 现经 `request()` 内 `(doFetch ?? fetch)(…)` 非方法调用 |

## 回修后复验

```
pnpm -C .harness run ci（clean dist）             exit 0（check-contracts 9 schema / 27 example + 6 invalid rejected；verify-examples 23 OK, 4 invalid rejected）
e2e-backend 规则（8091，--spring.profiles.active=e2e）  155 passed / 0 failed（+ ㉒' 3 条、㉔ 6 条、⑭' 3 条、日志卫生 5 条）
deploy-verify（8091）                              12 passed / 0 failed
e2e-frontend（5199）                               37 passed / 0 failed
植入 runtime `String userId` → check-module-deps 红；doctor 0
```
