# Coding Report v1 — Phase A（契约）

- change：`feat-agent-tool-platform-20260903`
- 阶段：3 编码实现（Phase A：T02 → T01 → T03 → T04）
- 日期：2026-09-03
- 所属端：contracts
- 依据：spec v3.1 §5、`rules/contracts.md`、`specs/00-contract-spec.md`、`rules/agent-safety.md` §2 §3

## 1. 改动文件

| 文件 | 端 | 行数 | 任务 |
|---|---|---|---|
| `.harness/contracts/ui-schema.schema.json` | contracts | 105 | T02 |
| `.harness/contracts/examples/ui-schema.example.json` | contracts | 62 | T02 |
| `.harness/contracts/examples/ui-schema.result.example.json` | contracts | 22 | T02 |
| `.harness/contracts/error-response.schema.json` | contracts | 39 | T01 |
| `.harness/contracts/intent-request.schema.json` | contracts | 46 | T01 |
| `.harness/contracts/action-request.schema.json` | contracts | 22 | T01 |
| `.harness/contracts/run-summary.schema.json` | contracts | 38 | T01 |
| `.harness/contracts/examples/{error-response,intent-request,action-request,run-summary}.example.json` | contracts | 4 文件 | T01 |
| `.harness/contracts/sse-events.schema.json` | contracts | 216 | T03 |
| `.harness/contracts/examples/sse-events.{10 事件}.example.json` | contracts | 10 文件 | T03 |
| `.harness/contracts/tool-manifest.schema.json` | contracts | 95 | T04 |
| `.harness/contracts/tool-search.schema.json` | contracts | 66 | T04 |
| `.harness/contracts/tool-invoke.schema.json` | contracts | 69 | T04 |
| `.harness/contracts/examples/tool-manifest.example.json` | contracts | — | T04 |
| `.harness/contracts/examples/tool-manifest.refund-create.example.json` | contracts | — | T04 |
| `.harness/contracts/examples/tool-search.example.json` | contracts | — | T04 |
| `.harness/contracts/examples/tool-invoke.example.json` | contracts | — | T04 |

合计 9 schema、20 example，1070 行。

**Harness 侧顺带修复（编码过程中发现，属 Hashimoto 法则范围）**：

| 文件 | 变更 |
|---|---|
| 18 个 `.md` / `.mjs`（agents、rules、skills、scripts、CLAUDE.md、AGENTS.md、docs、本 change 的 spec / tasks） | 无 `run` 的 pnpm 简写（ci / doctor / new-change / check-*）全部改为 `pnpm -C .harness run <script>` |
| `.harness/scripts/harness-doctor.mjs` | 新增守护：扫描 Harness 与根文档，发现无 `run` 的 pnpm 简写即 error |
| `.harness/agents/platform-owner.md` | §2 注明必须带 `run` 及原因 |

## 2. 新增公共契约（无端点 / 出口变更）

9 个 `$id` 形如 `https://strato.local/contracts/v1/{name}.schema.json`。跨文件 `$ref` 两处：`run-summary.currentUi` 与 `sse-events.ui-replace.data.ui` / `ui-patch.data.components[]` 引用 `ui-schema`。

## 3. 关键决策

1. **`if/then` 在 Ajv strict 模式下的写法**。首版按常规 JSON Schema 写的条件式被 Ajv `strictTypes` / `strictRequired` 拒绝编译（4 个 schema）。统一改为：`if` 与 `then` 都显式带 `"type": "object"`，`then` 中被 `required` 的属性同时出现在同级 `properties`（值为 `true`）。这是 Ajv 2020 strict 的要求，不是 JSON Schema 规范要求，但它让 Schema 更明确，故采纳而非降级 `strict`。
2. **`Form.props.fields[]` 进入契约**。评审 v3 P01 指出 `formData` 白名单来源需要契约支撑，故 `ui-schema` 对 `type = Form` 的组件用 `if/then` 强制 `props.fields[]` 结构；其他组件 `props` 保持自由 JSON。确认屏示例已按 spec §4.1 含 `OrderCard + RefundConfirmCard + Form`。
3. **`action-request.formData` 值只允许标量**。用 `anyOf` 三个标量类型替代 union `type`（后者被 strict 拒绝），并加 `propertyNames` 正则与 `maxProperties: 16`，从契约层就阻止嵌套对象注入。
4. **`tool-search.response.tools[]` 六字段硬锁**。`additionalProperties: false` + `required` 六字段，反例 `baseUrl` 被拒（见 §4）。
5. **`sse-events` 根为 `oneOf` 单事件**。示例拆为 10 个文件，与 `check-contracts.mjs` 的 `{stem}.*.example.json` 匹配规则对齐；每个事件的 `data` 都 `additionalProperties: false`，且不含任何"原文"字段，只有 `summary` / `message` 这类面向用户的短文本。
6. **`run-summary` 新增 `failureCode`**（spec §5 未列）。`state = FAILED` 时前端需要知道原因码以选择提示文案；enum 与 `sse-events.run-failed.code` 相同。属对 spec §5 的**小幅超出**，记入 §6 偏差。
7. **无 `run` 的 pnpm 简写与 pnpm 内置命令冲突**。验收时发现 `pnpm ci` 是 pnpm 自带子命令（`ERR_PNPM_CI_NOT_IMPLEMENTED`），`pnpm doctor` 同理，导致我们写在 CLAUDE.md 里的门禁命令根本不会执行脚本。全仓 18 处改为 `run` 形式，并在 doctor 加守护防止回退。

## 4. 验收记录（命令与真实输出）

```
$ pnpm -C .harness run check-contracts
check-contracts: 9 schemas OK                                   exit=0

$ node（Ajv strict）反例探针，cwd=.harness
probe1 high+never rejected: true | /risk/confirmation must be equal to constant
probe2 extra baseUrl rejected: true | /response/tools/0 must NOT have additional properties
probe3 sideEffect+idempotency none rejected: true
probe4 description length 501 rejected: true
probe5 formData non-scalar rejected: true
probe6 unknown component type "Foo" rejected: true
probe7 Form without fields rejected: true
ui-schema type enum length: 7

$ python3 diff intent-request.example.json vs spec §4.1
intent-request identical to spec §4.1: True

$ grep -c "http" .harness/contracts/examples/ui-schema*.json
ui-schema.result.example.json:0
ui-schema.example.json:0

$ ls .harness/contracts/*.schema.json | wc -l          → 9
$ ls .harness/contracts/examples/*.example.json | wc -l → 20

$ pnpm -C .harness run doctor                           exit=0（0 errors, 0 warnings）
$ 植入无 run 的 ci 简写后 run doctor              exit=1（守护生效）
$ pnpm -C .harness run ci                               exit=0
  check-contracts 0 / check-module-deps 0 / fronted 0 / backed skipped
```

task 验收逐条：T01 ✓（4 schema + diff 一致）、T02 ✓（enum 7、无 http）、T03 ✓（1 schema + 10 example = 11 行 ✓）、T04 ✓（9 schemas OK + 两条反例被拒）。

## 5. agent-safety 六条自查

| § | 结论 |
|---|---|
| §1 四面职责 | 契约层不涉及；`tool-search` 无任何寻址 / 地址字段，`tool-invoke.request` 亦无地址字段，Runtime 无法从契约拿到领域服务位置 |
| §2 发现 | 六字段硬锁 ✓；`description ≤ 500` ✓；`toolId` 正则限定格式 ✓ |
| §3 确认 | `confirmationToken` 不透明字符串 ✓；`formData` 键正则 + 标量值 + `Form.fields[]` 声明 ✓；`confirmation.required.expiresAt` 暴露过期时间 ✓ |
| §4 前端边界 | `ui-schema` 无 URL / HTML / 脚本字段 ✓；`type` enum 封闭 ✓ |
| §5 Gateway | `tool-invoke.executionContext` 含 `runId` / `toolCallId` / `idempotencyKey` / `traceId` ✓ |
| §6 流式 | 10 事件 `data` 全部 `additionalProperties: false`，无原文字段 ✓ |

## 6. 与 spec 的偏差

- `run-summary` 增加可选 `failureCode`（`FAILED` 时必填）。spec §5 未列，但 §4.2 已定义该 enum；前端需要它选择失败文案。建议阶段 4 评审确认后回写 spec §5。
- `error-response` 增加可选 `details[]`（字段级校验明细）。spec §5 只写三字段；`details` 可选、不影响两端最小实现。

## 7. 已知限制 / 后续

- `inputSchema` / `outputSchema` 内嵌 JSON Schema 只约束到 `type: object`，其内部合法性由后端 `SchemaValidator` 在注册时编译校验（T05b / T06）。
- 前端 Zod 投影（T13）需逐字段对照本报告 §1 的 9 个文件；`Form.fields[].type` 三值、`action.style` 三值、`state` 六值、`code` 两组 enum 必须一致。

---

# Phase B 版本锁定（T05a 前置，2026-09-03）

来源：本机 `~/.m2/settings.xml` 将所有仓库镜像到 `http://nexus.zhuanspirit.com/nexus/content/groups/public`，直接读取该镜像的 `maven-metadata.xml`（Maven Central 搜索 API 与 repo1 在本网络不可达）。取各自主线的最新稳定版：

| 坐标 | 版本 |
|---|---|
| `org.springframework.boot:spring-boot-dependencies` | **3.5.16** |
| `org.springframework.ai:spring-ai-bom` | **1.1.8** |
| `com.networknt:json-schema-validator` | **1.5.9** |
| `com.diffplug.spotless:spotless-maven-plugin` | **2.46.1** |
| `org.apache.maven.plugins:maven-enforcer-plugin` | **3.6.3** |
| `org.apache.maven.plugins:maven-compiler-plugin` | **3.16.0** |
| `org.apache.maven.plugins:maven-wrapper-plugin` | **3.3.4** |
| `org.apache.maven:apache-maven`（wrapper 下载目标） | **3.9.16** |

本机环境：`mvn -v` 显示 Maven 3.9.16 但默认 Java 为 1.8；JDK 21 在 `~/.jenv/versions/21`，由 `.harness/scripts/mvn.mjs` 强制注入 `JAVA_HOME`。

Phase B 执行模式：用户手动实现，Owner 提供分步指引与代码；每步完成后用户确认再进入下一步。

---

# T05a 完成记录（2026-09-04）

执行方式：用户手动实现骨架主体（父 POM、platform-spi 7 接口、app 模块 4 个 Java 文件、application.yml、.gitignore）；Owner 验收时发现两项缺失并代为补齐：6 个空壳子模块 `pom.xml`、Maven Wrapper。

## 改动文件

| 文件 | 说明 |
|---|---|
| `backed/pom.xml` | 父 POM：Boot 3.5.16 / Spring AI 1.1.8 / networknt 1.5.9 BOM；compiler 3.16.0 `-Xlint:all -Werror -Xlint:-processing`；enforcer 3.6.3（JDK ≥ 21、Maven ≥ 3.9）；spotless 2.46.1 绑定 verify |
| `backed/mvnw`、`backed/mvnw.cmd`、`backed/.mvn/wrapper/maven-wrapper.properties` | wrapper 3.3.4，distributionUrl 指向内网 Nexus 的 apache-maven 3.9.16 |
| `backed/platform-spi/pom.xml` + `src/main/java/com/strato/spi/{Principal,ExecutionContext,ToolHandler,ToolManifestSource,ToolResolver,PrincipalPermissionResolver,SelfCheck}.java` | 零 Spring 依赖，仅 jackson-databind |
| `backed/{contracts-java,tool-registry,tool-gateway,agent-runtime}/pom.xml`、`backed/domains/{order-service,refund-service}/pom.xml` | 空壳，仅依赖 platform-spi；domains 下带 `<relativePath>../../pom.xml</relativePath>` |
| `backed/app/pom.xml` + `src/main/java/com/strato/app/{StratoApplication,SelfCheckRunner,SelfCheckProperties}.java`、`infra/InMemoryPrincipalPermissionResolver.java`、`src/main/resources/application.yml` | 唯一依赖全部 7 个子模块的装配点；`finalName=app`；权限表 user_001 三权限 / user_002 两权限 |
| `.harness/scripts/check-module-deps.mjs` | **Harness 修复**：见下 |

## 验收（真实输出）

```
$ node .harness/scripts/mvn.mjs -q -B spotless:apply        exit=0
$ node .harness/scripts/mvn.mjs -q -B verify                exit=0   → backed/app/target/app.jar 25.3 MB
$ pnpm -C .harness run check-module-deps                    exit=0   ✓ backend module dependency direction OK
$ grep -rln "org.springframework" backed/platform-spi/src   (empty)
$ java -jar app.jar（JDK 21）→ 2s 后 /actuator/health      {"status":"UP"}
$ grep -c "selfcheck: running 0 checks" $DEPLOY/backend.log 1
$ grep -c " ERROR " $DEPLOY/backend.log                     0
$ 植入 tool-registry → refund-service 依赖后 check-module-deps  exit=1（守护生效），已还原
```

## Harness 修复：`check-module-deps.mjs` 存在两处会导致误报的缺陷

首次对真实 pom 运行时报 10 条 violation，全部为误报。根因：
1. 提取领域模块 `artifactId` 时取全文第一个 `<artifactId>`，命中的是 `<parent>` 块里的 `strato-backed`，于是"领域模块 ID 集合"变成 `{strato-backed}`。
2. 判定依赖时对整个 pom 全文做 `includes`，`<parent>` 里的 `strato-backed` 被当成依赖。

修复：提取 ID 前先剥掉 `<parent>` 块；判定依赖只扫描 `<dependencies>` 块内容。修复后用植入违规验证守护仍能触发。此前该脚本在 `backed/pom.xml` 不存在时直接跳过，所以缺陷一直未暴露——**属于"从未在真实输入上运行过的门禁"**，与 Phase A 发现的 pnpm 简写问题同类。

## 与指引的偏差
- SPI 源文件首行的 `// Xxx.java` 标记已删除（指引中的文件名提示，非代码）。

---

# 执行模式变更（2026-09-04）

用户确认：自 T05b 起，Phase B / C / D 全部由 Owner 自动执行，不再采用手动实现 + 指引模式。每个 task 完成后运行其验收命令并记录真实输出。

---

# T05b / T06 / T07 完成记录（2026-09-04，Owner 自动执行）

## 改动文件

**T05b contracts-java**
- `backed/contracts-java/pom.xml`：依赖 spi / jackson / networknt 1.5.9 / jakarta-validation / spring-context；资源插件把 `.harness/contracts/{*.schema.json,examples/*.example.json}` 复制进 jar；antrun 3.2.0 在 `generate-resources` 生成 `contracts/examples/INDEX`（jar 内无法列目录）
- `src/main/java/com/strato/contracts/SchemaValidator.java`：networknt V202012，`SchemaMapper` 把 `https://strato.local/contracts/v1/*` 映射到 `classpath:contracts/*`；9 契约预编译；`validateWithInlineSchema` 供 Gateway 校验工具输入 / 输出
- `ContractViolationException.java`、`infra/ContractsConfiguration.java`、`infra/selfcheck/ContractsSelfCheck.java`
- `model/`：`ErrorResponse`、`IntentRequest`、`ActionRequest`、`UiSchema`、`RunSummary`、`RunFailureCode`、`SseEvent`、`ToolManifest`、`ToolSearch`、`ToolInvoke`（10 文件，9 契约 + 共用的 RunFailureCode）

**T06 tool-registry**
- `pom.xml`；`domain/{DomainException,ToolVersionConflictException,ToolRegistryRepository,DiscoveryPolicy}`；`application/{RegisterToolUseCase,SearchToolsUseCase}`；`infra/{InMemoryToolRegistryRepository,RegistryToolResolver,StartupManifestRegistrar}`；`api/{ToolRegistryController,RegistryExceptionHandler}`；`README.md`

**T07 domains**
- `order-service`：`pom.xml`、`domain/{Order,OrderRepository}`、`infra/{InMemoryOrderRepository,OrderDetailGetHandler,OrderListSearchHandler,OrderManifestSource}`、`tool-manifests/{order.detail.get,order.list.search}.json`、`README.md`
- `refund-service`：`pom.xml`、`domain/{Refund,RefundRepository,EligibilityPolicy,OrderLookup}`、`application/RefundService`、`infra/{InMemoryRefundRepository,SeededOrderLookup,Refund{EligibilityCheck,Preview,Create,StatusGet}Handler,RefundManifestSource,selfcheck/RefundIdempotencySelfCheck}`、`tool-manifests/*.json`（4）、`README.md`

**app（T06/T07 暴露的问题修复）**
- `infra/InMemoryPrincipalPermissionResolver.java` + `application.yml`：权限表从 `Map<"user@tenant", [...]>` 改为显式 `grants[] {userId, tenantId, permissions}`
- `SelfCheckRunner.java`：监听方法加 `@Order(Integer.MAX_VALUE)`

**Harness**
- `.harness/scripts/check-module-deps.mjs`：domain/ 包扫描改为"文件直接父目录为 domain"

## 关键决策

1. **INDEX 文件**：jar 内无法枚举目录，`ContractsSelfCheck` 需要遍历 20 个示例，故构建期用 antrun 把示例文件名写入 `contracts/examples/INDEX`。示例增删无需改 Java。
2. **`SchemaMapper` 返回 `AbsoluteIri`**：networknt 1.5.9 的 `SchemaMapper.map` 签名为 `AbsoluteIri → AbsoluteIri`，非 `SchemaLocation`；从 jar 反编译核实后修正。
3. **权限表配置结构**：Spring Boot 宽松绑定对 Map 键中的 `@` 与值中的 `:`（`refund:create`）处理不可靠，首轮 T06 验收时两名用户均拿到 0 权限。改为显式列表后绑定稳定；`application.yml` 加注释禁止改回。
4. **事件监听顺序**：`@Order` 标在类上对 `@EventListener` 方法**无效**，必须标在方法上。首轮 selfcheck 先于注册执行，导致后续依赖已注册工具的自检必然失败；已改为方法级 `@Order`，日志确认 `startup registration done` 在 `selfcheck: running` 之前。
5. **领域模块互不依赖**：refund-service 需要订单状态但不能 import order-service，引入 `OrderLookup` 端口 + `SeededOrderLookup` 实现（与 order-service seed 一致）。真实系统替换为 RPC / 事件。
6. **`check-module-deps` 第二次修复**：`com.strato.domain.order.*` 的 `domain` 是模块分组，不是 DDD 分层包，原"路径含 domain"判定把 13 个 infra 文件误报。改为只看直接父目录。植入真实违规验证守护仍触发。

## 验收（真实输出）

```
$ node .harness/scripts/mvn.mjs -q -B verify                       exit=0
$ pnpm -C .harness run check-module-deps                           exit=0（植入 domain/ Spring import → exit=1，已还原）
$ java -jar app.jar → 日志顺序
    18 permission table loaded: 2 principals
    28 startup registration done: 6 tools from 2 sources
    29 selfcheck: running 2 checks
$ grep -c "registered tool"                                        6
$ grep -c "selfcheck: contracts 9 schemas, 20 examples OK"         1
$ grep -c "RefundIdempotencySelfCheck.*idempotent OK"              1
$ POST /internal/tool-registry/tools（refund.create@2.1.0 重复）   HTTP 409 TOOL_VERSION_CONFLICT，body 通过 error-response
$ POST /search user_001 refund                                     4 tools；字段恰 6 个；body 通过 tool-search.response（Ajv strict）
$ POST /search user_002 refund                                     3 tools（无 refund.create）
$ POST /search 缺 principal                                        HTTP 400 REQUEST_INVALID，body 通过 error-response
$ GET /tools/refund.create/versions                                ["2.1.0"]
$ grep RestClient|WebClient|HttpClient backed/tool-registry/src    (none)
$ grep " ERROR " backend.log                                       0
$ INDEX 行数 20；jar 内 contracts/*.json 29 个
```

## agent-safety 自查
- §1：Registry 无转发端点、无出向 HTTP 客户端（grep 证明）；领域模块 pom 不含 gateway / registry / runtime。
- §2：搜索按 status ∈ {active, canary} + permission 过滤；响应项六字段由 record 类型固定，并经契约 `additionalProperties: false` 二次校验；`description ≤ 500` 由契约在注册时拦截。
- §3–§6：本批不涉及。

---

# T08 完成记录（2026-09-04）

## 改动文件
- `backed/tool-gateway/pom.xml`（依赖 spi / contracts / web / validation；**不含** domains / registry / runtime）
- `domain/{GatewayException,RetryPolicy,IdempotencyStore,AuditSink,ArgsDigest}`；`application/InvokeToolUseCase`；`infra/{InMemoryIdempotencyStore,LogAuditSink,GatewayExecutorConfiguration}`；`api/{ToolGatewayController,GatewayExceptionHandler}`；`README.md`

## 关键决策
1. **寻址先于输入校验**：输入校验需要 Manifest 的 inputSchema，所以顺序为 寻址 → 输入校验 → 鉴权 → 幂等 → 调用 → 输出校验 → 脱敏 → 审计。与 spec §2.2 文字顺序（输入校验在前）的差异仅在于取 Manifest 这一步必须最先发生，语义不变。
2. **失败也审计**：`GatewayException` 路径同样写审计行，`status` 为 `failed:<code>`，保证"每次调用恰一行"。
3. **重试策略纯函数化**：`RetryPolicy.allowedRetries` 只在 `idempotency = required` 或 `sideEffect = false` 时返回 `maxRetries`，否则 0；有副作用且非幂等的工具绝不自动重试。
4. **超时用固定线程池 + `CompletableFuture.get(timeout)`**：符合公司规范"禁止显式 new Thread"；线程由 `GatewayExecutorConfiguration` 的 ThreadFactory 命名 `gateway-tool-N`。
5. **HTTP 状态映射**：INPUT_INVALID→400、FORBIDDEN→403、TOOL_NOT_FOUND→404、TIMEOUT / HANDLER_ERROR / OUTPUT_INVALID→502（下游失败）。error-response 契约的 `code` enum 没有 502 专用值，复用 `INTERNAL_ERROR`；记为对契约的已知取舍，Runtime 侧会把 502 转成 SSE 的 `TOOL_EXECUTION_FAILED` / `TOOL_OUTPUT_INVALID`。

## 验收（真实输出）
```
$ node .harness/scripts/mvn.mjs -q -B verify                          exit=0
$ POST /invoke refund.preview 缺 orderId                              HTTP 400 REQUEST_INVALID   通过 error-response
$ POST /invoke refund.create by user_002                               HTTP 403 FORBIDDEN         通过 error-response
$ POST /invoke refund.preview 10001 by user_001                        HTTP 200 succeeded         通过 tool-invoke.response（Ajv strict）
$ POST /invoke refund.nope@9.9.9                                       HTTP 404 NOT_FOUND         通过 error-response
$ POST /invoke refund.create 10002 同 idempotencyKey ×2                 refundId 相同 rf_6075b3280356；refund.status.get → refunds=1
$ 审计行字段数（grep -o "[a-zA-Z]*=" | wc -l）                          9
$ 审计行总数（7 次调用含失败与重放）                                     7
$ pnpm -C .harness run check-module-deps                              exit=0
$ grep " ERROR " backend.log                                          0
```
审计样例：`audit runId=run_t08 toolCallId=tc_3 toolId=refund.preview version=1.3.0 principal=user_001@tenant_001 argsDigest=eb5144acdf8e… status=succeeded durationMs=1 traceId=trace_t08`

## agent-safety 自查
§5 八项全部落地（输入校验 / 双重鉴权中的用户侧 / 幂等 / 寻址 / 超时重试 / 输出校验 / 脱敏 / 审计 9 字段）。Agent 侧鉴权（服务身份）首期由内网部署边界承担，spec §3 已列为非目标。

---

# T09a / T09b / T10a / T10b / T10c / T11 完成记录（2026-09-04）—— Phase B 收口

## 改动文件
- `backed/agent-runtime/pom.xml`：依赖 spi / contracts / tool-registry / tool-gateway / web / validation / **`spring-ai-openai` + `spring-ai-client-chat`（库，非 starter）**
- `domain/{RunState,Step,Plan,Run,RunRepository,DomainRouter,ConfirmationToken,ConfirmationTokenStore,RunFailure}`（无框架依赖）
- `application/port/{LlmClient,ToolRegistryClient,ToolGatewayClient,RunEventSink}`；`application/{UiSchemaBuilder,ConfirmationTokenService,RunOrchestrator,RuntimeClockConfiguration}`
- `infra/llm/{PromptBuilder,LlmPlanDraft,ToolSelectionValidator,RuleBasedLlmClient,SpringAiLlmClient,LlmConfiguration}`；`infra/{InMemoryRunRepository,InMemoryConfirmationTokenStore}`；`infra/inprocess/{InProcessToolRegistryClient,InProcessToolGatewayClient}`；`infra/selfcheck/{PlanSelfCheck,TokenSelfCheck}`
- `api/{AgentRunController,SseRunEventSink,RuntimeExceptionHandler,RuntimeExecutorConfiguration}`；`README.md`
- `backed/README.md`（T11）
- **Harness**：`.harness/scripts/sse-parse.mjs`（SSE 帧解析）、`.harness/scripts/e2e-backend.sh`（spec §6.2 第 8–15 条自动化验收，24 项断言）、`package.json` 增 `e2e-backend`、doctor 必需文件列表同步

## 关键决策
1. **Spring AI 用库不用 starter**。`spring-ai-starter-model-openai` 的自动配置会在无 API key 时于启动期抛异常（audio / image / embedding 全部要 key），与 spec"无 key 回退规则规划器"直接冲突。改为依赖 `spring-ai-openai` + `spring-ai-client-chat`，`LlmConfiguration` 手工装配 `OpenAiApi → OpenAiChatModel → ChatClient`，`internalToolExecutionEnabled(false)` 在每次 `prompt().options(...)` 上设置。
2. **令牌白名单来源**。`ConfirmationToken.allowedFormKeys` 在签发时固定为 `Form` 声明的字段集合（首期只有 `reason`），生成 UI 后用 `UiSchemaBuilder.formKeys(ui)` 反向核对两者一致，不一致直接 `INTERNAL_ERROR`——防止 UI 与令牌漂移。
3. **重校验也走 Gateway 且发完整事件三元组**。确认后先经 `ToolGatewayClient` 调 `refund.eligibility.check`（idempotencyKey 带 `recheck` 后缀，避免与首轮同键被幂等表短路），再执行 `refund.create`。事件序列与 spec §4.0 / §6.2.9 完全一致。
4. **进程内适配只 import 对方 `application` 用例**。`InProcessToolRegistryClient → SearchToolsUseCase`、`InProcessToolGatewayClient → InvokeToolUseCase`；grep 证明 runtime 未 import registry / gateway 的任何 `infra` 或 `domain`。Gateway 的全部管线（校验 / 鉴权 / 幂等 / 审计）在进程内调用下照常执行。
5. **SSE 用 `SseEmitter` + 独立线程池**。控制器立即返回 emitter，编排在 `agent-run-N` 线程执行；`sse-ping-N` 线程每 15s 发注释帧；`close()` 幂等。
6. **验收脚本化**。首轮手工 curl + grep 因 Spring 输出 `event:xxx`（无空格）而全部解析失败，遂写 `sse-parse.mjs`（兼容有无空格、多行 data、注释帧）与 `e2e-backend.sh`（24 项 `check`），成为可重复执行的门禁。

## 验收（`bash .harness/scripts/e2e-backend.sh` 真实输出，24 passed / 0 failed）
```
selfcheck ×5（contracts 9/20、idempotent、plan 3 steps、invalid toolId rejected、token 4-way rejected）      ✓
§6.2.11 缺身份头                                        HTTP 401 UNAUTHENTICATED                            ✓
§6.2.8  POST /agent/runs 事件序列                       run.started tool.selected tool.started tool.completed tool.selected tool.started tool.completed ui.replace confirmation.required  ✓
        ui.replace components                           ['OrderCard','RefundConfirmCard','Form']            ✓
§6.2.10 GET run-summary（等待中 / 完成后）              WAITING_CONFIRMATION / COMPLETED                    ✓
§6.2.9  confirm 事件序列                                tool.selected tool.started tool.completed tool.selected tool.started tool.completed ui.replace run.completed  ✓
        result components                               ['ResultCard']                                      ✓
§6.2.12 令牌重放                                        run.failed CONFIRMATION_REJECTED；refund.create 成功审计行 = 1   ✓
§6.2.12b formData 注入 amount                           run.failed CONFIRMATION_REJECTED；10002 退款数 = 0    ✓
§6.2.13 refund.status.get 10001                         refunds.length = 1                                  ✓
无能力路径（"今天天气怎么样"）                           run.started message.delta run.completed             ✓
未知 runId                                              404                                                 ✓
§6.2.14 审计字段 9 / §6.2.15 用户原文 0 / ERROR 0                                                             ✓
```
补充校验：31 个 SSE 帧全部通过 `sse-events` 契约（Ajv strict）；两份 run-summary 通过 `run-summary` 契约；`grep alibaba|langchain4j` 无；domain/ 包与 platform-spi 无 Spring；`check-module-deps` 0 违规。

**全仓门禁**：`pnpm -C .harness run ci` → check-contracts 0 / check-module-deps 0 / fronted 0 / backed 0；`doctor` 0 errors。

## agent-safety 六条自查（Phase B 整体）
| § | 结论 |
|---|---|
| §1 四面 | Runtime 只经端口调 Gateway / Registry（grep 证明无 domains import）；Registry 无转发端点、无 HTTP 客户端；Gateway 不选工具 |
| §2 发现 | 规则路由先于模型；候选按权限过滤（user_002 少 1 个）；模型输出 toolId 候选外即拒（自检证明）；description 转义 + 500 截断 |
| §3 确认 | 令牌随机 / 一次性 / 10 分钟 / argsDigest / formData 白名单四项拒绝均由自检与 e2e 证明；确认后经 Gateway 重校验 |
| §4 前端边界 | principal 只来自请求头；GET run-summary 他人 Run 返回 404 不泄露存在 |
| §5 Gateway | 八步管线 + 9 字段审计（T08） |
| §6 流式 | 事件发出前经契约校验；data 无原文 / 堆栈 / 凭据；用户消息在日志中 0 次 |

## 与 spec 的偏差
- Gateway 失败到 HTTP 状态：`TIMEOUT / HANDLER_ERROR / OUTPUT_INVALID → 502 + INTERNAL_ERROR`（error-response enum 无 502 专用码）。Runtime 内部已把 Gateway 异常映射为 SSE 的 `TOOL_EXECUTION_FAILED` / `TOOL_OUTPUT_INVALID`，对前端无影响。
- `run-summary.failureCode` 与 `error-response.details[]` 两处契约超出（Phase A 已记录）在本阶段被实际使用，建议阶段 4 评审后回写 spec §5。

---

# T12 – T17b 完成记录（2026-09-04）—— Phase C 前端

## 改动文件
- `fronted/package.json`：依赖 antd 6.6.2 / antd-mobile 5.42.3 / @ant-design/icons；devDep vite-node；`lint` 增 `check-registry.mjs`；`verify-examples` 脚本
- `fronted/vite.config.ts`、`tsconfig.app.json`：只读别名 `@contracts/*` → `.harness/contracts`；`server.fs.allow`；proxy **仅** `/agent/runs` 与 `/actuator`
- `fronted/.oxlintrc.json`：antd / antd-mobile 仅 `src/shared/ui/**`；`@contracts/*` 仅 `src/pages/**` 与 `scripts/**`
- `fronted/src/shared/config/env.ts`：`VITE_API_BASE_URL` 默认 `''`
- `shared/ui/{device/DeviceContext.ts, theme/AppThemeProvider.tsx}`、`app/providers/{DeviceProvider,AppProviders}.tsx`（T12）
- `entities/agent-run/{model/types.ts, api/agentRunApi.ts, index.ts}`（T13，6 个契约的 Zod 投影）
- `shared/api/{sseClient.ts, httpClient.ts}`（T14）
- `shared/ui/generate/{types.ts, componentRegistry.ts, SchemaRenderer.tsx, UnknownComponent.tsx, ActionBar.tsx, desktop/*.tsx ×8, mobile/*.tsx ×8}`（T15a / T15b / T16）
- `fronted/scripts/{check-registry.mjs, verify-examples.ts}`、`pages/schema-playground/**`（T15c）
- `features/agent-chat/{model/runView.ts, api/useAgentRun.ts, ui/AgentChatPanel.tsx}`（T17a）
- `pages/agent/**`、`app/router/router.tsx`、`pages/home/HomePage.tsx`（T17b）
- `fronted/public/favicon.svg`、`index.html`
- **Harness**：`.harness/scripts/e2e-frontend.mjs`（spec §6.3.4 第 2–5 步自动化，puppeteer-core 驱动本机 Chrome，21 项断言）；`package.json` 增 `e2e-frontend`；doctor 必需文件同步

## 关键决策
1. **注册表按端型分裂、Schema 不分裂**。`desktopRegistry` / `mobileRegistry` 键集合相同，`SchemaRenderer` 依 `useDevice()` 选表；组件 props 只有一份 `PROPS_SCHEMAS`（Zod），先校验再渲染。`check-registry.mjs` 把契约 enum、两张表、PROPS_SCHEMAS、实现文件五方绑死。
2. **未知类型不抛错**。`SchemaRenderer` 以字符串查表，缺失时渲染 `UnknownComponent`（`role="alert"`）并 `console.error`，其余组件照常；playground 的 `unknown` 示例故意绕过 Zod 以模拟"后端升级、前端未升级"。
3. **feature 层持有 SSE 归约，entities 只做纯函数**。`reduceEvent(view, event)` 是纯函数，`useAgentRun` 用 TanStack Query 缓存 `AgentRunView` 并在 SSE 每帧 `setQueryData`；表单值经 `formRef` 收集，只在确认动作时回传。
4. **`exactOptionalPropertyTypes` 下用条件展开**而非放宽 tsconfig。
5. **proxy 只代理 API 前缀**。`/agent` 同时是 SPA 路由和 API 前缀，整前缀代理会把页面请求送到 8080 → 404 Whitelabel。

## 验收（真实输出）
```
pnpm -C fronted run ci                       exit 0（typecheck / oxlint / check-deps / check-registry / prettier / build 253 kB gz 78 kB）
pnpm -C fronted run verify-examples          16 examples OK
node .harness/scripts/e2e-frontend.mjs       e2e-frontend: 21 passed, 0 failed
  §6.3.4-2  /dev/schema?example=confirm @1280   device=desktop, ant-*>0, adm-*=0, 3 components, console 0
  §6.3.4-3  同 URL @375                          device=mobile, adm-*>0, ant-*=0, console 0
  §6.3.4-4  /dev/schema?example=unknown          UnknownComponent[mystery] ×1，Card[known] ×1，console.error 含 "unknown component type"，无其他 error
  §6.3.4-5  /agent 主链路 @1280                  工具进度 2 → 确认屏 3 组件 + confirm-refund 按钮 → 选原因 → 结果屏 ResultCard → 工具进度 4，console 0
pnpm -C .harness run doctor                  0 errors, 0 warnings
```
截图：`deployment/ui-playground-desktop.png`、`ui-playground-mobile.png`、`ui-unknown.png`、`ui-desktop-confirm.png`、`ui-desktop-flow.png`。

## 验收过程中修掉的问题
| 现象 | 根因 | 修复 |
|---|---|---|
| `/agent` 页面 404 Whitelabel | vite proxy 把整个 `/agent` 前缀转给后端 | 只代理 `/agent/runs` |
| 发送后「请求失败（404）」 | `VITE_API_BASE_URL` 默认 `/api`，实际请求成了 `/api/agent/runs` | 默认改为 `''`（同源） |
| 桌面端 adm-* 计数 2 | antd-mobile 挂在 body 的 `adm-px-tester` 测量节点 | e2e 排除该节点，业务渲染计数仍须为 0 |
| unknown 场景 console.error 2 次 | React StrictMode 开发期双渲染 | 断言改为"至少一次且无其他 error" |
| 步骤 5 找不到 `.ant-select-selector` | antd 6 重写了 Select DOM | 用契约字段名 `#reason` + 键盘选择 |
| favicon 404 计入 console error | 无 `public/favicon.svg` | 补文件 |

## agent-safety §4 自查
- 白名单渲染：组件查表 + props Zod；未知类型占位（e2e 步骤 4 证明）。
- 不执行模型代码：UI Schema 里没有任何字符串被当作代码 / URL / HTML 使用（grep `dangerouslySetInnerHTML` / `href=` 在 generate/ 下为 0）。
- 确认只回传 token + formData；principal 只来自请求头常量，pageContext 不参与鉴权。
- antd / antd-mobile import 越界 → oxlint 红；FSD 反向依赖 → `check-deps.mjs` 红。

## 与 spec 的偏差
- spec §6.3.4 第 4 步写"console.error 恰 1 次"，开发模式 StrictMode 下为 2 次；自动化断言按"≥1 且无其他 error"执行，建议阶段 4 回写 spec 措辞。
- spec 未定义 `VITE_API_BASE_URL` 默认值；实现取同源 `''`，写入 `fronted/README.md`。

---

# 阶段 4 回修记录（2026-09-04）—— 响应 `coding/review/code_review_v2.md`（REVISION REQUIRED，3 MUST FIX / 12 SHOULD）

## MUST FIX
| # | 问题 | 修复 | 证据 |
|---|---|---|---|
| M1 | 三个 Controller 只用 `@Valid`，契约的 `additionalProperties:false` / pattern / const 在边界失效 | `SchemaValidator.bind(contract, fragment, JsonNode, Class)`：先按契约（或 `#/$defs/request` 子定义）校验原始 JSON 再转 record；`AgentRunController`（intent-request / action-request）、`ToolGatewayController`（tool-invoke#request）、`ToolRegistryController.search`（tool-search#request）全部改走 `bind`；三处 advice 增加 `HttpMessageNotReadableException → 400`，Gateway advice 补 `ContractViolationException → 400` | e2e「评审 M1」6 条：多余字段 400、`uiSchemaVersion:"2.0"` 400、`formData.reason` 为对象 400（先于 404）、`runId:"bad id"` 400、非法 JSON 400 |
| M2 | 并发 / 重放确认把执行中的 Run 打成 FAILED | `RunOrchestrator.confirm` 按 runId `synchronized`；状态 / principal / 令牌未知（新 `ConfirmationTokenService.TokenUnknown`）三类**前置拒绝**只向该连接发 `run.failed{CONFIRMATION_REJECTED}` 并关闭，**不改 Run 状态**；令牌消费后的失败才 `run.fail()`；锁与中间结果在 Run 终态时清理 | e2e「评审 M2」：同一 Token 并发两次 → 恰 1 条 `run.completed`、1 条 `run.failed`，summary `COMPLETED`，订单 10002 退款数 1 |
| M3 | 计划内 `amount`（模型可填）优先于试算结果，确认的金额 ≠ 执行的金额 | 双重防线：(a) `ToolSelectionValidator` 对需确认步骤禁止模型填写 `amount`（`TRUSTED_ONLY_ARGS`），Prompt 同步说明；(b) 确认后 `amount` **一律**取重校验 `refundableAmount`，且必须等于确认屏 `RefundConfirmCard.props.amount`，否则 `CONFIRMATION_REJECTED` | e2e「评审 M3」：结果屏「退款金额」== 确认屏金额（128.00） |

## SHOULD（本轮一并处理）
- S2 `application` 反向依赖 `infra.llm`：新增 `application/ToolDisplayNames`，`LlmConfiguration` 与编排器都从它取名。
- S3 / S4 Gateway 失败形态：`InProcessToolGatewayClient` 把 `GatewayException` 转为契约 `tool-invoke.response{status=failed,error.code}`；Runtime 按 `error.code` 结构化映射（`OUTPUT_INVALID → TOOL_OUTPUT_INVALID`，其余 `TOOL_EXECUTION_FAILED`），删除异常文本嗅探。spec §4.2 回写。
- S5 权限不足 → `CONFIRMATION_REJECTED`：确认路径上 Gateway 返回 `FORBIDDEN` 时映射为 `CONFIRMATION_REJECTED`。
- S6 超时 `f.cancel(true)`。幂等占位顺序保持不变（先查后写；副作用工具本身在领域服务内也按 idempotencyKey 幂等，双保险），在评审 v3 中说明。
- S7 非法 JSON → 400（三处 advice）。
- S8 前端流结束无终态 → `phase=failed{INTERNAL_ERROR,"连接中断，请重试"}`。
- S9 提交前按 `Form.props.fields[].required` 本地校验，缺失抛 `FormIncompleteError`（不发请求、不烧令牌），面板显示「请先填写：退款原因」。
- S10 路由级 `RouteErrorBoundary`。
- S11 `check-module-deps`：全文匹配 FQN、按包路径任一段 `domain` 判定（排除 `com.strato.domain.<svc>` 基础包）；用植入 `// org.springframework.stereotype.Service` 注释验证会红（1 violation）后还原。
- S12 e2e-backend 补 §6.2 第 5–7 条（search 权限过滤 + 契约校验、409、Gateway 400/403）。
- 未处理（记录）：S1 进程内适配依赖对方 `application` 用例而非 `api` 包——spec §2 措辞 "api 包公开用例接口"；当前 registry / gateway 的 `api` 包只有 Controller，暴露接口需新增一层，留待 HTTP 适配 change 一并处理，并已在 spec v3.2 中不改变措辞、在 summary 经验沉淀记录。

## 验收（真实输出）
```
node .harness/scripts/mvn.mjs -q -B verify          exit 0
bash .harness/scripts/e2e-backend.sh                e2e-backend: 46 passed, 0 failed
pnpm -C fronted run ci                              exit 0
node .harness/scripts/e2e-frontend.mjs              e2e-frontend: 21 passed, 0 failed
pnpm -C .harness run ci                             check-contracts 0 / check-module-deps 0 / fronted 0 / backed 0
pnpm -C .harness run doctor                         0 errors, 0 warnings
```

## 第 2 轮评审（`code_review_v3.md`，APPROVED，0 MUST FIX / 6 SHOULD）后的处理
| # | 意见 | 处理 |
|---|---|---|
| N1 | `CompletableFuture.cancel(true)` 不中断任务 | 改 `ExecutorService.submit` + `Future.cancel(true)`（会中断工作线程） |
| N2 | 金额比对只认 preview 缓存，与确认屏展示回退源不同源 | 新增 `shownRefundAmount(runId)`：直接从已下发的 `lastUi` 里取 `RefundConfirmCard.props.amount` 比对，"用户看到的" 与 "比对的" 同源 |
| N3 | 确认锁内做 Gateway 调用，重复确认排队占满 `agent-run` 线程池 | `ReentrantLock.tryLock()`：拿不到锁立即 `rejectRequest`，不排队；锁在 Run 终态时移除 |
| N4 | 进程内适配依赖对方 `application` 用例而非 `api` 包接口 | **推迟到 HTTP 适配 change**；spec §2 v3.2 已把措辞改为与实现一致（对方 `api` 包目前只有 Controller） |
| N5 | `deployment/` 里 backend.log 与事件日志非同一次运行 | 阶段 7 deploy-verify 一次性生成并冻结全部产物（e2e-backend 会重写 backend.log 与全部事件日志） |
| N6 | Gateway 幂等 check-then-act | **推迟**：领域服务 `RefundService.create` 已按 `(tenantId, idempotencyKey)` 幂等兜底，进程内单线程编排下无并发同 key 调用；Gateway 侧改为"先占位后填充" 记入下一 change |
| L4 | e2e M3 比对在双空串时恒真 | 增加 `executed amount non-empty` 断言 |

复验：`mvn verify` 0；`e2e-backend.sh` **47 passed / 0 failed**；`e2e-frontend.mjs` 21/21；`pnpm -C .harness run ci` 四段 0；doctor 0。

### 回修后自查发现并修复
- 我在 N1–N3 回修时写的 `InProcessToolGatewayClient` import 了 `com.strato.gateway.domain.GatewayException`，违反 backend-standard §4（不得依赖对方 `infra` / `domain`）。修复：失败→契约 Response 的转换下沉为 `InvokeToolUseCase.executeToResponse`，适配器只依赖对方 `application`。
- **Hashimoto**：`check-module-deps.mjs` 新增机械检查——runtime / registry / gateway 三者之间出现 `com.strato.<peer>.(infra|domain).` 即红；用植入注释验证 1 violation 后还原。`backend-standard.md` §4 与 `agent-runtime/pom.xml` 注释同步为"`api` 包接口或 `application` 用例类（过渡）"。
