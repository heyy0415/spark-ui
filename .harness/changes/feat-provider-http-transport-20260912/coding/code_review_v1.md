# 代码评审 v1 — feat-provider-http-transport-20260912

## 0. 独立性声明

**自评审，不满足独立性要求。** 本仓 subagent 通道返回 `400 专用渠道限制`，无法派独立评审 agent。作者与评审者是同一个 agent，存在系统性盲区：我倾向确认自己的设计意图已实现，而「意图」与「实际行为」的差距正是评审要找的。

本轮的降偏差做法：**只提能用失败测试或 grep 证明的问题**。每条 MUST FIX 都先写出一个会红的断言或指出具体行号，再改代码。凡属「我觉得不妥」但拿不出证据的，归入 SHOULD 或不写。

**建议他人复核的重点**（按价值排序）：

1. §2.1 `ExecutionContext` 缺字段曾冒成 500——同类「请求体字段未校验就用」的地方我可能还有遗漏；
2. §3.1 `HttpToolTransport` 每次新建 `RestClient`（`HttpURLConnection` 不池化）的性能取舍；
3. §3.2 provider 幂等默认进程内，多实例部署下的实际有效性。

## 1. 结论

**APPROVED WITH FIXES** —— 评审中发现 **3 项 MUST FIX，全部已修并回归**；2 项 SHOULD 记录未改（理由见 §3）。

改动 74 文件、+1323/−680。五套回归全绿：`e2e-backend` **161**、`e2e-provider` **15**、`e2e-frontend` **7**、`deploy-verify` **12**、前端单测 **106**；`pnpm -C .harness run ci` **0**。

## 2. MUST FIX（已修）

### F-1 ｜ 请求体缺必填字段冒成 500 + 堆栈

**位置**：`ProviderInvokeController.invoke`，`ExecutionContext` 构造在 try 之外（原第 89 行 vs try 在第 105 行）

**证据**：先写断言，确认它真的红：

```
java.lang.IllegalArgumentException: runId is blank
  at ProviderInvokeControllerTest.missingRequiredContextFieldIsClientError:253
```

**问题**：`ExecutionContext` 对 `runId` / `toolCallId` / `sessionId` / `idempotencyKey` 四个字段 fail-fast（构造器抛 `IllegalArgumentException`）。但它由**网络请求体**构造，且在 try 之外——缺任一字段 → 未捕获异常 → **500 + 堆栈**，而这本是客户端错误。

公司规范要求「Controller 等边界层必须进行必要的参数校验、处理异常，并返回可理解的信息」。500 + 堆栈两条都违反：状态码误导（让调用方以为是 provider 故障而重试），堆栈可能泄漏内部实现。

**修法**：包进 try，映射为 **400**，WARN 记原因（不记请求体）。

**回归**：11 条 provider 端点单测全过。

### F-2 ｜ 启动期写入的 Map 对请求线程无 happens-before 保证

**位置**：`ProviderToolRegistry` 用 `LinkedHashMap` / `ArrayList`

**核对**：写入在 `ProviderToolScanner.afterSingletonsInstantiated()`（`SmartInitializingSingleton`，早于 web 容器接受请求）；读在 **web 请求线程**。

**问题**：时序上写确实早于读，但普通 `LinkedHashMap` 的写入对其他线程**没有内存可见性保证**——没有任何同步点建立 happens-before。这类问题概率低、症状随机（偶发 `NOT_FOUND`），极难排查。

对照：hub 侧同职责的 `InProcessToolTransport` 用的是 `ConcurrentHashMap`。两侧口径本就该一致。

**修法**：`ConcurrentHashMap` + `CopyOnWriteArrayList`。

**顺序承诺核对**：`manifests()` 的 javadoc 承诺「顺序与注册顺序一致」——`CopyOnWriteArrayList` 仍是 List，保序；`handlers` 只做键查找，无外部使用方依赖顺序（已 grep 确认）。承诺未破坏。

### F-3 ｜「看起来能配、配了就坏」的端点路径

**位置**：`ProviderInvokeController` 的 `@RequestMapping("${spark.provider.base-path:/spark/tools}")` vs `HttpToolTransport.INVOKE_PATH = "/spark/tools/invoke"`（硬编码）

**问题**：provider 侧把路径做成可配，hub 侧是常量。**只有一边可配**：任何人改 `spark.provider.base-path`，hub 就静默 404——工具注册成功、规划正常、点了没反应。这比「不能配」糟得多。

**修法**：去掉可配性，provider 侧改为 `public static final String BASE_PATH`，两侧注释互指。

**同时加机械门禁**（注释「必须一致」守不住跨模块常量），双向自证：

| 注入 | 结果 |
|---|---|
| 改 provider 的 `BASE_PATH` | ✗ `endpoint path mismatch: hub INVOKE_PATH="/spark/tools/invoke" but provider BASE_PATH="/spark/v2/tools"` |
| 把常量改名 | ✗ `cannot read INVOKE_PATH / BASE_PATH constants (renamed?); the check is now blind` |
| 恢复 | ✓ |

第二条特意加：**门禁因符号改名而静默失效**是我此前踩过的坑（T05 的字节码门禁第一次自证就没红），必须让「门禁瞎了」本身也报错。

## 3. SHOULD（记录未改，附理由）

### S-1 ｜ `HttpToolTransport` 每次调用新建 `RestClient`

`SimpleClientHttpRequestFactory` 基于 `HttpURLConnection`，**不做连接池**。每次工具调用建一次客户端，高频场景下有 TCP 握手开销。

**不在本 change 改的理由**：读超时必须按 Manifest 的 `execution.timeoutMs` 逐次设置，而该 factory 的超时是实例级的——要复用就得按 timeout 值分桶缓存，或换 Apache HttpClient / JDK HttpClient 并单独管理超时。这是性能优化而非正确性问题，且已有明确逃生门（替换该 Bean）。

已在类 javadoc 与 README「已知限制」写明。真实压测数据出来前不做投机优化。

### S-2 ｜ provider 幂等默认进程内

`ProviderIdempotencyStore` 是 `ConcurrentHashMap`，**多实例部署时 hub 重试可能落到另一实例而绕过缓存**。

**不改的理由**：跨实例强一致需要共享存储（Redis 等），而「不依赖外部中间件」是本项目的既有定位（全内存状态已是已知限制）。关键是**别让人误以为跨进程幂等已万无一失**——已在类 javadoc、`agent-safety.md` §8.3、README「已知限制」三处显式写出该限制与替换方式。

容量策略是「超 10000 条整体清空」，简单可预测；LRU 需要额外结构，当前无证据表明必要。

## 4. 逐项核对

| 项 | 结论 |
|---|---|
| 契约变更是否破坏性 | ✓ 非破坏。`provider` 新增且 `in-process` 下禁止出现，既有单体 Manifest 不改仍合法；`$id` / `schemaVersion` 未动 |
| 契约先行 | ✓ T01 前置于全部实现；3 个 invalid 示例**逐个确认被拒** |
| 单体零回归 | ✓ 每个 task 都跑 `e2e-backend` **161**；单体日志 `transports=[IN_PROCESS]`（不多一个 HTTP 客户端）、零 provider 痕迹 |
| 治理是否下放到 transport | ✓ Schema 校验 / 策略 / 幂等 / 超时 / 重试 / 脱敏 / 审计全在 `InvokeToolUseCase`；11 条 dispatch 单测断言顺序未变 |
| 远程路径是否同样受治理 | ✓ 单测断言输入校验在 transport 之前、输出校验与审计在之后；e2e 断言 hub 审计留痕 |
| 重试语义（M-1） | ✓ `REMOTE_TIMEOUT` / `REMOTE_FAILED` 不重试，`NOT_FOUND` / `UNREACHABLE` 可重试；13 条单测 + 自证（弄反则 6 条红） |
| 跨进程幂等（M-1） | ✓ `idempotencyKey` 随请求传递；provider 侧去重；e2e 断言重放不重复执行 |
| 发送端脱敏（M-2） | ✓ provider 返回前脱敏，与 hub 同 `SENSITIVE_KEYS` 口径，含嵌套对象与数组 |
| 双向认证（§3.5） | ✓ 常量时间比较；令牌绑 serviceName（自证：改成"令牌在集合里就放行"则红）；未配认证 = 拒绝远程注册而非放行 |
| 身份边界 | ✓ provider 源码禁 `userId` / `tenantId` / `Principal`（门禁覆盖，自证可红）；`RunContextPropagator` 不跨进程且启动 WARN |
| 密钥是否进日志 | ✓ 逐处 grep 确认：`ManifestPublisher` / `RegistrationGuard` / `ProviderInvokeController` 均只记 serviceName 与原因；401 无响应体；`Denied` 消息有专门断言不含令牌 |
| 薄依赖 | ✓ 5 条 pom 门禁 + **产物字节码 major ≤ 61**，全部双向自证 |
| JDK 17 可用性 | ✓ `provider-demo` 真以 JDK 17 编译打包并跑通 15 条 e2e——不是靠版本号推断 |
| 模块依赖方向 | ✓ `ConfirmationCoveragePolicy` 放 `registry/api/`（runtime↔registry 只经 api 包，见 project-structure §2），不是 `application` |
| 门禁是否自证 | ✓ 本 change 新增 7 条门禁全部双向自证；其中 2 条首次自证未红（FQN 绕过、Maven 未重编），修正后重证 |

## 5. 评审中排除的疑点（避免过度设计）

| 疑点 | 核对 | 结论 |
|---|---|---|
| `ManifestPublisher` 记完整异常会否泄漏令牌 | 查实际 401 日志，grep 令牌值 | **未泄漏**：hub 的 401 无响应体，异常里只有状态码 |
| `ConcurrentHashMap` 破坏 `manifests()` 的顺序承诺 | 读 javadoc + grep 使用方 | **未破坏**：承诺只针对 `manifests`（仍是 List），`handlers` 无人依赖顺序 |
| 推送失败不阻断启动是否过于宽松 | 对照设计意图 | **合理**：领域服务本职是业务能力，hub 暂不可达不该让它起不来；失败记 ERROR 保留堆栈 |
| `mcp` 枚举保留但不实现是否算半成品 | 对照非目标 | **符合决策**：用户明确「不需要做 mcp」，契约保留值但注册时拒绝，好于删枚举再改契约 |

## 6. 阶段 2 评审的三条 MUST FIX 落实情况

| 编号 | 落实 | 断言 |
|---|---|---|
| M-1 超时重试导致重复执行 | spec §3.1a.1 + `RetryPolicy` 协议维度 + provider 幂等 | 13 + 11 条单测；自证弄反则 6 条红；e2e 断言重放 |
| M-2 脱敏点过晚 | spec §3.1a.2 + provider 返回前脱敏 | 2 条单测（含嵌套 / 数组） |
| M-3 `trustedArgs` 值可信性 | **结论修正**（见下） | 7 + 2 条单测 |

**M-3 的评审结论是错的，编码期已纠正**：我当时说「比对跑在 provider 侧 = provider 自己比对自己」。读代码发现 `reject()` 与 `trustedArgs()` **都在 hub 执行**，只有 recheck 工具调用走远程；且领域实现早已按自己拥有的组件 ID + label 做比对。我写的通用 label 匹配既重复又会误判（屏上 `"459.00 CNY"` vs 参数 `"459.00"`，字面相等会拒掉每次正常确认），已删除该类。

**真实缺口比我描述的窄但确实存在**：hub 自检与 provider 推送分别在两个进程的 `ApplicationReadyEvent`，顺序无保证 → 高风险远程工具通常绕过启动检查。虽仍 fail-closed（确认时 `INTERNAL_ERROR`），但故障点从「启动即失败」退化为「用户点确认那一刻才失败」。已改为注册时校验（`ConfirmationCoveragePolicy`）。

## 7. 遗留与已知限制

1. **本评审非独立**（见 §0）。
2. **commit 仅本地，未 push**。依公司规则「仅在用户明确要求时执行 git commit / git push」与「禁止直接 push 到主分支」。
3. §3 两条 SHOULD 未改，理由与逃生门已在代码注释、规则文档、README 三处写明。
4. **provider 重启漏推需人工介入**：hub 侧对账补偿未实现（非目标，已在 README 已知限制）。
5. **不做服务发现 / 不做 MCP / 只支持 Java 注解声明工具**：均为用户明确决策的非目标。
