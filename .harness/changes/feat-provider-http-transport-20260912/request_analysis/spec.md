# Spec: feat-provider-http-transport-20260912

> 改造清单第 11–14 项。让 Spark 除「单体内嵌」之外，再支持「分布式微服务」接入：领域服务作为独立 Spring Boot 进程（provider）声明工具，hub 经 HTTP 调用它们。

## 1. 背景与问题

当前 Spark 只支持一种拓扑：领域服务与 Spark 内核**同进程**（`examples/host-demo` 形态）。`@SparkTool` 扫描出的 `ToolHandler` 由 Spring 注入到 `InvokeToolUseCase`，调用是进程内反射。

微服务架构下这不成立：订单服务、退款服务是独立部署的进程，hub 里没有它们的 `ToolHandler` 实例。

### 1.1 现状核实（写 spec 前逐项 grep 确认）

| 事实 | 结论 | 影响 |
|---|---|---|
| `ToolTransport` 已作为「预留」接口存在 | **签名对 HTTP 不成立**：`invoke(ToolHandler handler, ...)` 要求先有本地 handler 实例，远程工具没有 | 必须改签名（见 §3.1） |
| `ToolTransport` 的实现与使用方 | **零实现、零使用**（grep 无命中） | 改签名无破坏成本 |
| `tool-manifest.schema.json` 的 `protocol` 字段 | 已有 enum `["in-process","http","mcp"]`，注明「首期只实现 in-process」 | 契约已预留，但**缺少「去哪调」的坐标** |
| `POST /internal/tool-registry/tools` 注册端点 | **已存在**，接受任意 JSON Manifest | provider 推送可复用，不必新造端点 |
| 该端点的认证 | **无任何认证** | 跨服务后成为安全漏洞（见 §1.2） |
| `ConfirmationRecheck` | `reject()` / `trustedArgs()` 是**领域策略**，运行在领域模块 | 跨服务时判定必须留在 provider 侧（见 §3.4） |
| `InvokeToolUseCase` 的调用点 | `handlers.get(manifest.key())` → `handler.handle(args, ctx)`（第 265 / 311 行） | 分派点明确，改动面收敛 |

### 1.2 必须同时解决的安全问题

`/internal/tool-registry/tools` 无认证。单体内网下尚可接受（端点不对外暴露），但 provider 跨服务推送后，**任何能访问该端点的调用方都能注册任意工具**——包括伪造一个 `risk.level=high` 但 `risk.confirmation=never` 的工具，从而绕过整条确认链路。

契约层的 `if/then`（`high ⇒ required`）能拦住这一条，但拦不住「低风险伪装」：注册一个 `sideEffect=false` 的假查询工具，实际执行转账。

因此调用方认证不是「顺便加的」，而是本 change 的**前置必要条件**。

## 2. 目标与非目标

### 目标

1. **契约**：`tool-manifest` 增 `provider` 段，`protocol=http` 时必填服务坐标（第 11 项）。
2. **模块**：拆出 `spark-provider-spring-boot-starter`——薄依赖，**不含 Spring AI、不含 web、不含 runtime/registry/gateway**，JDK 下限 **17**（第 12 项）。
3. **传输**：Gateway 按 `protocol` 分派到 `InProcessToolTransport` / `HttpToolTransport`；provider 侧暴露执行端点；provider 启动时推送 Manifest 到 hub；调用方认证（第 13 项）。
4. **确认**：跨服务的确认重校验——令牌校验留在 hub，领域判定在 provider 执行（第 14 项）。
5. **不回归**：单体形态（`examples/host-demo`）行为零变化。

### 非目标

- **不做 MCP**（用户明确决策）。`protocol` 的 `mcp` 枚举值保留在契约里但不实现，注册时拒绝。
- **不做服务发现**（Eureka / Nacos / K8s DNS）。provider 坐标用配置化 base URL，宿主要接注册中心自己实现 `ProviderEndpointResolver`。
- **不做 hub 主动拉取**（用户选了 provider 推送）。provider 重启漏推的自愈留给后续 change。
- **不发包**（本仓仅提供代码）。
- **不改前端**：provider 拓扑对前端完全透明——前端始终只发自然语言，不知道工具在哪个进程。

## 3. 设计

### 3.1 `ToolTransport` 改签名（决策 1）

```java
// 现在（对 HTTP 不成立）
JsonNode invoke(ToolHandler handler, JsonNode args, ExecutionContext ctx);

// 改为
JsonNode invoke(ToolManifest manifest, JsonNode args, ExecutionContext ctx);
```

由实现自己决定「查本地 handler」还是「发 HTTP」。这是唯一能同时表达两种传输的形状。

- `InProcessToolTransport`：`handlers.get(manifest.key())` → 反射调用。**承载现有全部行为**，宿主 ThreadLocal 经 `RunContextPropagator` 传递、方法级切面照常触发（**仅此形态成立**，见 §3.1a.3）。
- `HttpToolTransport`：读 `manifest.provider()` 的坐标，POST 到 provider 的执行端点。

`ToolTransport` 零实现零使用，改签名无破坏成本。

**Gateway 的责任边界不变**：Schema 校验、访问策略、幂等、超时、重试、脱敏、审计**全部仍在 `InvokeToolUseCase`**，transport 只负责「把 args 送到工具、把结果拿回来」。这条是本设计的核心约束——否则远程工具会绕过执行面的治理。

> 但「留在原处」≠「语义不变」。其中 4 项治理在跨进程后会失效或变质（超时无法中断、重试不安全、幂等覆盖面缩小、脱敏点过晚），逐项处置见 **§3.1a**。这是评审 M-1 / M-2 揪出的缺口——初稿只声明了位置不变，没检查语义是否仍成立。

### 3.1a 跨进程后失效或变质的进程内机制（评审 M-1 / S-3）

spec 初稿声明「Gateway 治理全留在原处」，但**并非每项治理在远程语义下都仍然成立**。逐项核对结果：

| 机制 | 进程内 | `protocol=http` 时 | 处置 |
|---|---|---|---|
| 输入 Schema 校验 | hub 侧，调用前 | **不变**（仍在 hub） | 无需改动 |
| `ToolAccessPolicy` | `InvokeToolUseCase:184`，transport **之前** | **不变**（仍在 hub） | 无需改动（已核实） |
| 幂等 `IdempotencyStore` | 同 `sessionId + idempotencyKey` 命中 claim，覆盖全部重复 | **只覆盖 hub 自己的重复调用**，覆盖不到 provider 侧的重复执行 | 见下 §3.1a.1 |
| 超时 `Future.get` + `cancel(true)` | 真正中断工作线程 | **只中断 hub 的等待线程**，provider 侧业务继续跑 | 见下 §3.1a.1 |
| 重试 `RetryPolicy` | 前一次已被中断，重试安全 | 前一次可能仍在执行甚至已成功 → **重复扣款** | 见下 §3.1a.1 |
| 输出脱敏 `SENSITIVE_KEYS` | 返回值不出进程，脱敏只影响审计日志 | 返回值**先过网络**，中间环节（provider 日志 / 网关 / 链路追踪）已见原文 | 见下 §3.1a.2 |
| `RunContextPropagator` | 宿主 ThreadLocal 带到工具线程 | **静默失效**（`capture()` 返回不透明 `Object`，设计上不可序列化） | 见下 §3.1a.3 |
| `ArgsDigest` | hub 侧计算 | **不变**（provider 不参与摘要，已核实） | 无需改动 |

#### 3.1a.1 超时 / 重试 / 幂等（评审 M-1，最高优先）

**失败场景**：退款工具 `sideEffect=true, idempotency=required, maxRetries=2, timeoutMs=3000`，provider 实际耗时 3.2s。hub 3s 超时 → `cancel(true)` 只中断 hub 等待 → 按 `RetryPolicy` 重试 → provider 第二次执行退款 → **用户被退两次款**。进程内不会发生，因为 `cancel(true)` 真的中断了第一次执行。

硬性要求：

1. **`protocol=http` 时，`TimeoutException` 一律不触发重试**。`RetryPolicy` 增加协议维度：远程超时的结果是「未知」而非「失败」，重试未知操作不安全。连接被拒（`ConnectException`，确定未到达 provider）仍可重试。
2. **HTTP 客户端超时必须 ≤ `manifest.execution().timeoutMs()`**，且区分连接超时与读超时；否则 hub 的 `Future.get` 先炸而连接仍挂着，耗尽连接池。
3. **`ExecutionContext.idempotencyKey` 必须随请求传到 provider**（该字段已存在且有非空校验）。provider-starter 提供**默认的幂等去重能力**（同 key 的结果缓存 + 重复请求返回首次结果），不让每个业务自己写——否则「`idempotency=required` 只是声明、没人实现」。
4. 幂等责任在文档里写清：hub 的 claim 防的是 **hub 侧重复发起**；provider 的去重防的是 **网络重传与 hub 重试**。两层都要有。

#### 3.1a.2 敏感数据的跨进程边界（评审 M-2）

公司规范要求「日志、异常、上报与联调数据涉及敏感信息必须先脱敏」。脱敏点选在接收端时，中间环节已经泄漏。

1. **provider 侧在返回前脱敏**（provider-starter 提供，复用与 hub 相同的 `SENSITIVE_KEYS` 口径），hub 侧脱敏保留为第二道；
2. `baseUrl` **允许 `http://`**（内网部署与本地联调常见，强制 HTTPS 会逆向导致使用者绕过整个机制），但：
   - 用 `http://` 时**启动日志 WARN**，明示传输未加密；
   - 文档写明生产应使用 HTTPS 或 mTLS；
   - pattern 保持 `^https?://`。

#### 3.1a.3 `RunContextPropagator` 在 http 形态下不生效（评审 S-3）

`capture()` 返回不透明 `Object`，设计上不可跨进程。而 `ToolAccessPolicy` 的 javadoc 写着「宿主要按用户判定就从自己经 `RunContextPropagator` 恢复的上下文取」——这条指引在 http 形态下**对 provider 侧不成立**。

危险在于它是**静默**失效：宿主 ThreadLocal 为空不会抛异常，只会让基于它的鉴权判定走默认分支。宿主若把工具方法上的 `@PreAuthorize` 当最后一道防线，跨进程后可能静默失守。

要求：

1. 文档与 javadoc 显式声明「`RunContextPropagator` 只在 in-process 形态生效」；
2. provider 侧要拿身份，只能靠 (a) hub 传来的 `sessionId`（宿主自己映射，内核不解释其含义）或 (b) provider 宿主自己的网关鉴权；
3. provider-starter 启动时若检测到宿主注册了 `RunContextPropagator` 实现，**WARN 提示它不会跨进程生效**——避免宿主误以为有效。

### 3.2 契约：`tool-manifest.provider` 段（第 11 项）

```json
"provider": {
  "type": "object",
  "additionalProperties": false,
  "required": ["serviceName"],
  "properties": {
    "serviceName": { "type": "string", "pattern": "^[a-z][a-z0-9-]{1,62}$" },
    "baseUrl":     { "type": "string", "pattern": "^https?://[^\\s]+$" },
    "instanceId":  { "type": "string", "maxLength": 128 }
  }
}
```

约束（`allOf` / `if-then`，与既有两条风格一致）：

- `protocol = "http"` ⇒ `provider` **必填**且 `provider.serviceName` 必填；
- `protocol = "in-process"` ⇒ `provider` **不得出现**（单体形态无坐标概念，出现即为配置错误）。

`baseUrl` 可选的理由：接注册中心的宿主只提供 `serviceName`，由 `ProviderEndpointResolver` 解析实际地址。两者都缺时注册失败。

`instanceId` 仅用于审计与排障（哪个实例注册的），不参与寻址。

### 3.3 `spark-provider-spring-boot-starter`（第 12 项）

**依赖下限 JDK 17**（hub 保持 21）。理由：provider 装在别人的业务服务里，企业存量大量停留在 17；hub 是本项目自己部署的进程，可以用 21。

#### 3.3a 编码期发现：共享契约层必须一并降到 17（spec 初稿漏项）

只把 provider-starter 设为 `release=17` **不够**。provider 依赖 `spi` + `contracts`，而这两个模块当时编译为 **major 65（JDK 21）**——JDK 17 的进程加载它们会直接 `UnsupportedClassVersionError`。「provider 下限 17」这个决策若只改 provider 自己的 pom，等于没生效。

处置（已实测源码兼容后执行）：

| 模块 | release | 理由 |
|---|---|---|
| `spark-rooter-spi` | **17** | 共享契约层取两边下限 |
| `spark-rooter-contracts` | **17** | 同上 |
| `spark-provider-spring-boot-starter` | **17** | provider 宿主可能是 17 |
| runtime / registry / gateway / web-mvc / hub starter | 21 | hub 是本项目自己部署的进程 |

同时发现 parent pom 在 compiler plugin 里**硬编码** `<release>21</release>`，属性 `maven.compiler.release` 被它覆盖、子模块改不动。已改为 `<release>${maven.compiler.release}</release}`，子模块可覆盖。

两条机械门禁守住它（均已双向自证）：pom 层面断言三个模块 pin 了 17；**产物层面**逐个 class 检查 major ≤ 61——pom 里的意图与实际字节码可能脱节（改了 parent 配置、加了未覆盖 release 的新模块），只查 pom 会漏。

pom 只依赖：

```
spark-rooter-spi          （@SparkTool / ToolHandler / ExecutionContext / ConfirmationRecheck）
spark-rooter-contracts    （ToolManifest / SchemaValidator）
spring-boot-autoconfigure （@ConditionalOnMissingBean 等）
spring-web                （RestClient，仅用于启动推送 Manifest）
```

**禁止依赖**：`spring-ai-*`、`spark-rooter-runtime`、`spark-rooter-registry`、`spark-rooter-gateway`、`spark-rooter-web-mvc`、`spring-boot-starter-web`。

这条是红线：provider 不该因为"声明了几个工具"就被拖进一个 LLM 客户端和整套 Agent Runtime。用脚本门禁守（见 §6）。

**共用代码**（依赖已逐个 grep 核实，结论与初稿假设不同，已据实修正）：

| 类 | 实际依赖 | 处置 |
|---|---|---|
| `AnnotatedToolHandler`（77 行） | 仅 spi + Jackson | **下沉 spi**，两侧共用 |
| `ManifestDeriver`（319 行） | spi + Jackson + contracts.SchemaValidator + **runtime.ToolMetaRegistry** | **下沉 spi**，但需先解开对 runtime 的依赖（见下） |
| `SparkToolScanner`（119 行） | gateway + registry + runtime + Spring 容器（`ApplicationContext` / `SmartInitializingSingleton` / `AopUtils`） | **留在 hub starter**。provider 侧另写一个扫描器——它的职责完全不同（provider 不注册到本地 Registry、不注入 Gateway，而是推送到远端 hub），强行共用会把 hub 模块拖进 provider |

解开 `ManifestDeriver` → `runtime` 的依赖：它只用到 `ToolMetaRegistry.ToolMeta` / `.ParamMeta` 两个 **record**（纯数据：`String` / `List` / `Map` + spi 的 `ParamFormat`，已核实无 Spring / 无 runtime 类型），而 `ToolMetaRegistry` 这个**容器类**被 runtime 的 8 个类重度使用（规划器、校验器、自检）。

故：**两个 record 下沉 spi，容器类留在 runtime**。`ManifestDeriver` 依赖下沉后的 record，不再依赖 runtime；runtime 侧 `ToolMetaRegistry` 改为引用 spi 的 record（对其 8 个使用方是纯 import 变更，无行为变化）。

provider 不需要 `ToolMetaRegistry` 容器——它不做规划。`ManifestDeriver` 产出的 `ToolMeta` 在 provider 侧直接丢弃（只取 `manifest`）。

provider 侧需要一个**极薄的 web 端点**接收 hub 的调用。但 provider-starter 不能依赖 `spring-boot-starter-web`（那会强制宿主用 web 栈）——provider 宿主本身几乎必然已有 web 栈，所以：端点用 `@RestController` 声明但 starter 只依赖 `spring-web`（编译期），运行期由宿主已有的 web starter 提供。若宿主无 web 栈则该 Bean 不装配（`@ConditionalOnClass`）。

### 3.4 跨服务确认（第 14 项，决策 3）

令牌校验**留在 hub**（一次性、绑定 `runId` / `actionId` / `argsDigest` / `conversationId` / `sessionId`），领域判定**在 provider 执行**：

```
hub:      校验 confirmationToken（一次性、绑定五元组）
  ↓ POST {provider}/spark/tools/confirm-recheck
provider: 调 recheckToolId → 跑 reject() / trustedArgs() → 返回判定
  ↓ { "rejected": null, "trustedArgs": { "amount": "128.00" } }
hub:      合并 trustedArgs → 执行正式工具（仍经 Gateway 全套治理）
```

这样领域知识零泄漏到 hub——符合「内核不懂业务」。

**安全要点一：键的越界**。`trustedArgs` 的键必须与确认屏 Form 字段互斥（既有 `trustedArgKeys()` 静态声明 + 启动自检的约束继续有效）。跨服务后新增风险：provider 返回的 `trustedArgs` 含未声明的键时，hub **必须拒绝**而不是静默合并——否则 provider 可以覆盖任意参数。

**安全要点二：值的可信性**（评审 M-3）。进程内时 `trustedArgs` 的值由领域代码在本进程算出，hub 与领域同信任域。跨进程后 hub 收到的是 provider **通过网络声称**的值。若 provider 配置错（连到错误数据源）或被攻破，会出现「用户确认屏看到 100 元、实际执行 10000 元」。

而 `reject(recheckOutput, shownUi)` 的 `shownUi` 参数本是为「比对展示值」设计的，跨进程后这个比对跑在 provider 侧——**provider 自己比对自己返回的值，等于没比对**。

**决策：hub 侧增加一致性校验**。hub 手里有确认屏原文，对 `trustedArgs` 中「在确认屏展示过的字段」做**纯字面比对**，不一致即拒绝执行 + 审计告警，对用户提示「金额已变动，请重新确认」。

- 这不需要领域知识（只是字符串比对），不违反「内核不懂业务」；
- 能挡住 provider 配置错误这类**非恶意故障**，而非只防攻击；
- 代价：用户确认期间金额真的变了（如优惠到期）会被拒——但这种情况本来就该让用户重新确认，属正确行为。

### 3.5 调用方认证（第 13 项，§1.2 的解法）

两个方向都要认证：

| 方向 | 端点 | 认证 |
|---|---|---|
| provider → hub | `POST /internal/tool-registry/tools` | provider 出示共享密钥；hub 校验并把 `serviceName` 与密钥绑定 |
| hub → provider | `POST {provider}/spark/tools/invoke` 等 | hub 出示共享密钥；provider 校验 |

机制：HTTP header 承载不透明 token，**常量时间比较**（防时序侧信道）。默认实现是配置化共享密钥（`spark.provider.token` / `spark.hub.provider-tokens.{serviceName}`），宿主可用 mTLS / 网关鉴权替换（定义同类型 Bean 即覆盖，与其他端口一致）。

**密钥未配置时的行为**：`fail-fast` 拒绝启动，而不是"放行"。理由与 `SessionIdResolver` 的既有决策一致——安全相关的缺省不能是宽松的。单体形态不装配这些 Bean，故不受影响。

**绑定 serviceName 的必要性**：只校验「密钥有效」不够——服务 A 的密钥不该能注册服务 B 的工具。hub 校验 `manifest.provider.serviceName` 与密钥对应的服务一致。

## 4. 影响面

| 端 | 改动 |
|---|---|
| 契约 | `tool-manifest.schema.json` 增 `provider` 段 + 2 条 if/then；补示例（http 形态合法 + 3 个 invalid）；`sync-contracts` 同步后端副本 |
| spi | `ToolTransport` 改签名；下沉 `AnnotatedToolHandler` + `ManifestDeriver` + `ToolMeta` / `ParamMeta` 两个 record（`SparkToolScanner` **不下沉**，见 §3.3）；新增 `ProviderEndpointResolver`、`ProviderAuth` |
| runtime | `ToolMetaRegistry` 改为引用 spi 的 record（8 个使用方纯 import 变更，无行为变化） |
| contracts | `ToolManifest` record 增 `Provider` |
| gateway | `InvokeToolUseCase` 的调用点改为经 `ToolTransport` 分派；新增 `InProcessToolTransport` / `HttpToolTransport` |
| registry | 注册时校验 `protocol` / `provider` 一致性；拒绝 `mcp` |
| web-mvc | 注册端点加认证 |
| **新模块** | `spark-provider-spring-boot-starter` |
| **新示例** | 一个独立 provider 进程示例，证明端到端可用 |
| 前端 | **零改动**（拓扑对前端透明） |
| 规则 | `project-structure.md` 模块依赖红线增 provider；`backend-standard.md` 增 provider 约束；`agent-safety.md` 增跨服务确认边界 |

## 5. 风险

| 风险 | 后果 | 缓解 |
|---|---|---|
| transport 分派把 Gateway 治理绕过去 | 远程工具无 Schema 校验 / 无幂等 / 无审计 —— 执行面形同虚设 | §3.1 硬约束：治理全留在 `InvokeToolUseCase`，transport 只传数据。加单测断言远程路径同样经过校验与审计 |
| provider 返回未声明的 `trustedArgs` 键 | provider 可覆盖任意参数，绕过确认屏 | hub 侧硬校验键集合 ⊆ `trustedArgKeys()`，超出即拒绝 + 审计 |
| 密钥缺省放行 | 任何调用方可注册伪造工具 | fail-fast 拒绝启动（§3.5） |
| provider-starter 悄悄依赖 hub 模块 | 薄依赖承诺失效，provider 被拖进 Spring AI | 脚本门禁扫 pom（§6），自证可变红 |
| 单体形态回归 | 现有 host-demo 行为变化 | `InProcessToolTransport` 承载原逻辑；三套 e2e 全量回归必须与现在完全一致（161/7/12） |
| 共用类下沉引入循环依赖 | 编译失败或模块红线破裂 | 已在阶段 1 核实完毕（§3.3），record 下沉 + 容器留 runtime |
| **远程超时后重试导致重复执行** | **真实资金损失**（退款/扣款执行两次） | §3.1a.1：http 形态超时不重试 + provider 侧默认幂等去重 + HTTP 超时 ≤ Manifest 超时 |
| **敏感数据在 hub 脱敏前已过网络与 provider 日志** | 违反公司脱敏红线 | §3.1a.2：provider 返回前先脱敏，hub 为第二道；http 明文时启动 WARN |
| **provider 声称的 trustedArgs 值与确认屏不一致** | 用户确认 100 元、实际执行 10000 元 | §3.4：hub 侧对确认屏展示过的字段做字面比对，不一致即拒绝 + 审计 |
| 宿主误以为 `RunContextPropagator` 跨进程有效 | 基于 ThreadLocal 的鉴权静默失守 | §3.1a.3：文档 + javadoc 显式声明，provider 启动时检测到实现即 WARN |

## 6. 验收

1. `pnpm -C .harness run ci` **0**（含新模块编译与模块依赖门禁）。
2. `pnpm -C .harness run check-contracts` **0**；新增示例覆盖 http 形态 + **≥3 个 invalid**（`protocol=http` 缺 provider、`protocol=in-process` 带 provider、`serviceName` 非法）。
3. **新增门禁**：`check-module-deps` 断言 `spark-provider-spring-boot-starter` 的 pom 不含 `spring-ai`、`spark-rooter-runtime/registry/gateway/web-mvc`、`spring-boot-starter-web`。**必须双向自证**（插入违规变红、恢复变绿）。
4. **新增门禁**：provider-starter 源码不出现 `userId` / `tenantId` / `Principal`（身份归宿主，与平台模块同红线）。
5. provider-starter 以 **JDK 17** 编译通过（`maven.compiler.release=17`），且 hub 仍为 21。
6. 端到端证明：新增的独立 provider 示例进程 + hub，完成一次「搜索工具 → 规划 → 确认 → 跨服务重校验 → 执行」全链路，日志可断言。
7. **单体零回归**：`e2e-backend.sh` **161 passed, 0 failed**、`e2e-frontend.sh` **7 passed**、`deploy-verify.sh` **12 passed, 0 failed** —— 数字与本 change 前完全一致。
8. 单测：transport 分派、provider 认证（含常量时间比较）、`trustedArgs` 键越界拒绝、契约投影，各有覆盖；`mvnw test` 0。

## 7. 回退

契约 `provider` 段是**新增可选字段**（`protocol=in-process` 时不得出现），对单体形态无影响。回退 = 撤掉 transport 分派、删 provider 模块与示例、还原注册端点认证。单体路径始终由 `InProcessToolTransport` 承载，回退不触碰它。

## 8. 开放项核实结果（阶段 1 内当场验完，不留到编码期）

| 开放项 | 核实方式 | 结果 |
|---|---|---|
| 三个共用类是否依赖 Spring / hub 模块 | 逐个 grep `^import` | **与初稿假设不同**。`AnnotatedToolHandler` 干净可下沉；`ManifestDeriver` 依赖 `runtime.ToolMetaRegistry`；`SparkToolScanner` 深度绑定 gateway + registry + runtime + Spring 容器。§3.3 已按实际情况重写（record 下沉 / 容器留 runtime / 扫描器不共用） |
| `RestClient` 会否带进额外依赖 | grep 既有用法与 pom | **无风险**。`spring-web` 已是平台模块既有模式（`runtime` / `registry` / `gateway` 三个 pom 都只引 `spring-web` 而不引 `spring-boot-starter-web`），`RestClient` 正来自其中。provider-starter 沿用同一写法 |
| provider 无 web 栈时的降级 | 设计决策 | `@ConditionalOnClass` 不装配端点，但**启动日志必须 WARN 明示**「本 provider 未暴露执行端点，hub 无法调用」。静默降级会导致工具注册成功但永远调不通，排障成本极高 |

**因核实而修正的设计**：`SparkToolScanner` 不再列为共用——provider 侧另写扫描器。这不是重复实现，两者职责本质不同：hub 侧扫描 = 注册到本地 Registry + 注入 Gateway；provider 侧扫描 = 推送到远端 hub + 注册本地执行端点。强行共用会把 gateway / registry / runtime 拖进 provider，直接违背薄依赖目标。
