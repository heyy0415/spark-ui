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
