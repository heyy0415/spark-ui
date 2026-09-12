# 需求评审 v1 — feat-provider-http-transport-20260912

## 0. 独立性声明

自评审。subagent 通道返回 `400 专用渠道限制`，无法派独立评审。为降低「作者视角确认自己」的偏差，本轮的做法是**只提能用代码证伪的问题**——每条发现都先 grep 到具体行号，再判断 spec 是否覆盖。凡属"我觉得不妥"但拿不出代码依据的，不写进来。

## 1. 结论

**CHANGES REQUESTED** —— 3 项 MUST FIX、3 项 SHOULD（S-3 在补充核实后追加）。

spec 的主干设计（transport 按 Manifest 寻址、provider 推送、领域判定留 provider）没有问题，三个决策点都是对的。问题集中在**「进程内隐含假设」在跨进程后失效**这一类——spec 声明了「Gateway 治理全留在原处」，但没有逐项检查那些治理机制是否在远程语义下仍然成立。这正是最容易出事的地方。

## 2. MUST FIX

### M-1 ｜ 超时与重试会双层叠加，且远程重试可能重复扣款

**位置**：spec §3.1「Gateway 的责任边界不变」；`InvokeToolUseCase` 第 300–317 行

**核对**：hub 侧现状是

```java
int retries = RetryPolicy.allowedRetries(manifest);   // 第 300 行
long timeoutMs = manifest.execution().timeoutMs();    // 第 301 行
for (int attempt = 0; attempt <= retries; attempt++) {
  Future<JsonNode> f = executor.submit(() -> handler.handle(args, ctx));
  return f.get(timeoutMs, TimeUnit.MILLISECONDS);     // 第 317 行
}
```

进程内语义下这是对的：`f.cancel(true)` 能中断工作线程，超时即真正停止。

**问题**：换成 HTTP 后，`f.cancel(true)` **只能中断 hub 的等待线程，不能停止 provider 侧正在执行的业务**。于是：

1. hub 认为超时 → 按 `RetryPolicy` 重试 → provider 收到第二次请求；
2. 而第一次请求可能仍在 provider 侧执行中，甚至已经成功（只是响应没赶上 hub 的 deadline）；
3. `RetryPolicy.allowedRetries` 的放行条件是 `idempotency == required || !sideEffect`。**注意 `idempotency=required` 只表示"声明了幂等"，不表示 provider 真的实现了幂等**——进程内时幂等由 hub 的 `IdempotencyStore` 兜住（同 `sessionId + idempotencyKey` 会命中 claim），但**跨进程后 hub 的 claim 只覆盖 hub 自己的重复调用，覆盖不到 provider 侧的重复执行**。

**失败场景**（具体）：退款工具 `sideEffect=true, idempotency=required, maxRetries=2, timeoutMs=3000`。provider 实际耗时 3.2s。hub 3s 超时 → 重试 → provider 第二次执行退款。**用户被退两次款**。

进程内不会发生：`f.cancel(true)` 真的中断了第一次执行。

**要求**：

1. spec 必须明确：**`protocol=http` 时，超时不得触发重试**，除非 provider 侧独立实现了幂等保护。
2. 幂等责任在跨进程下必须重新划分——写清楚 hub 的 `IdempotencyStore` 覆盖什么、不覆盖什么，以及 provider 侧要不要自己存 `idempotencyKey`。
3. `ExecutionContext.idempotencyKey` 必须随 HTTP 请求传到 provider（已核实该字段存在且非空校验），provider 侧据此做去重——这应是 provider-starter 提供的默认能力，不是让每个业务自己写。
4. HTTP 超时要分「连接超时」与「读超时」，且读超时**必须** ≤ `manifest.execution().timeoutMs()`，否则 hub 的 `Future.get` 先炸、连接却还挂着。

**分级**：MUST FIX。这是会导致真实资金损失的语义变化，且 spec 目前一个字都没提。

### M-2 ｜ 「治理全留在原处」对输入 Schema 校验成立，但对**输出**校验和脱敏不成立

**位置**：spec §3.1；`InvokeToolUseCase` 第 355–358 行（脱敏）

**核对**：现状顺序是 寻址 → 输入校验 → 访问策略 → 幂等 → 调用 → **输出校验 → 脱敏** → 审计。脱敏逻辑 `SENSITIVE_KEYS = {password, token, secret, apiKey}` 作用在工具**返回值**上。

**问题**：进程内时，工具返回值从未离开本进程，脱敏只影响「写进审计日志的内容」。跨进程后，工具返回值**先经过网络**才到 hub——这意味着：

1. provider 的响应体在传输途中已含敏感字段（HTTPS 可缓解，但 spec 没要求 HTTPS，`baseUrl` 的 pattern 是 `^https?://`，明确允许 http）；
2. provider 侧的日志、网关日志、链路追踪都可能记录原始响应体，**在 hub 脱敏之前**。

公司规范明确要求「日志、异常、上报与联调数据涉及敏感信息必须先脱敏」。跨进程后脱敏点选在接收端，中间环节已经泄漏。

**要求**：

1. spec 增一节说明跨进程的敏感数据边界：provider 侧在**返回前**就应脱敏（provider-starter 提供，复用同一 `SENSITIVE_KEYS` 口径），hub 侧脱敏作为第二道；
2. 明确 `baseUrl` 是否允许 `http://`。建议：允许（内网部署常见、便于本地联调），但**必须在启动日志 WARN**，并在文档写明生产应用 HTTPS 或 mTLS。若不允许则改 pattern 为 `^https://`。

**分级**：MUST FIX。涉及公司红线（敏感信息脱敏），且是拓扑变更引入的新暴露面。

### M-3 ｜ `trustedArgs` 的键越界校验写了，但**值的可信性**没管

**位置**：spec §3.4「安全要点」

**核对**：`ConfirmationRecheck.trustedArgs(recheckOutput)` 的设计意图是「用**可信来源**覆盖 / 补齐参数（如金额）」——它存在的理由就是不信任前端传来的金额，改用领域侧重新查到的值。

**问题**：spec 要求「键集合 ⊆ `trustedArgKeys()`，超出即拒绝」，这拦住了「provider 覆盖任意参数」。但跨进程后有一个进程内不存在的新问题：**`trustedArgs` 的值现在来自网络**。

进程内时，`trustedArgs` 的值由领域代码在本进程算出，hub 与领域在同一信任域。跨进程后，hub 收到的是 provider 声称的值——如果 provider 被攻破或配置错（连到了错误的数据源），hub 会拿一个错误的金额去执行真实扣款，而**确认屏给用户展示的是另一个金额**。

`reject(recheckOutput, shownUi)` 的第二个参数 `shownUi` 正是为「比对展示值」设计的。跨进程后这个比对跑在 provider 侧——**provider 自己比对自己返回的值**，等于没比对。

**要求**：spec 必须回答——`trustedArgs` 的值与确认屏展示值不一致时，谁来发现？两个选项：

- (a) hub 侧对 `trustedArgs` 做一次「与确认屏展示值一致性校验」（hub 有 `shownUi`，不需要领域知识，只做字面比对 + 差异即拒绝）；
- (b) 明确接受这个信任假设，写进 spec 的信任模型：hub 完全信任已认证的 provider，provider 被攻破等价于领域服务被攻破。

我倾向 (a)：它不需要领域知识（纯字面比对），成本低，且能挡住 provider 配置错误这类非恶意故障。但这是设计决策，需要明确写下来而不是留空。

**分级**：MUST FIX。确认链是本项目的核心安全机制，跨进程后的信任边界必须显式声明。

## 3. SHOULD

### S-1 ｜ T09 的端到端验收缺少可断言的数字

**位置**：tasks T09 验收「全链路脚本退出码 0，关键日志逐条断言」

**核对**：对照已有的 `e2e-backend.sh`（161 passed）、`deploy-verify.sh`（12 passed），本仓的验收惯例是**明确断言数）**。T09 只写「逐条断言」，没给条数。

**问题**：无数字的验收会退化为「跑通了就算过」。若某条断言在重构中被顺手删掉，验收仍然绿。前一个 change 的评审 S-2 提过同类问题，当时的解法是把数字写进验收。

**建议**：T09 编码时先实测断言条数，写进 tasks 与 summary；验收改为 `N passed, 0 failed`。

### S-2 ｜ provider 侧「不得自行重试」缺机械门禁

**位置**：tasks T10 输出「`backend-standard.md`：provider 侧约束（…不得自行重试…）」

**核对**：`ToolHandler` 的 javadoc 已写「实现类不得自行重试」，但这条从来只是文字约定，无门禁。

**问题**：跨进程后这条约束的后果被放大——provider 自己重试 + hub 重试 = 指数级放大（M-1 的场景会更糟）。

**建议**：至少加一条 grep 门禁扫 provider 示例与 domains 模块，禁止 `@Retryable` / `RetryTemplate` / 手写 `for (attempt`。不求完备，但要让明显的写法变红。

## 4. 核对清单

| 项 | 结论 |
|---|---|
| 目标 / 非目标是否明确 | ✓ 非目标明确排除 MCP、服务发现、hub 拉取、发包 |
| 每条验收可命令化 | ✓ 8 条可命令化；T09 缺数字（S-1） |
| 风险章节 ≥1 失败模式 + 缓解 | ✓ 6 条，但**漏了 M-1 / M-2 / M-3 三类跨进程语义失效** |
| 契约 task 前置 | ✓ T01 前置于全部实现 |
| 契约变更是否破坏性 | ✓ 非破坏性（`provider` 新增且 `in-process` 下禁止出现，现有 Manifest 仍合法） |
| 每个 task ≤ 0.5 天 | ⚠️ T05 / T07 / T09 偏大（新模块 / 新传输 / 双进程示例），建议编码时按输出项再切 |
| 单体零回归有明确判据 | ✓ 161 / 7 / 12 / 106 四个数字 |
| 现状核实是否充分 | ✓ spec §1.1 七项 + §8 三项开放项当场验完，且**核实推翻了两处初稿假设**（`SparkToolScanner` 不可下沉、`ManifestDeriver` 依赖 runtime），这点做得对 |
| 安全问题是否主动识别 | ✓ 主动发现注册端点无认证并升级为前置条件（§1.2）；但跨进程的脱敏与信任边界仍有缺口（M-2 / M-3） |

## 5. 评审自评：本轮的盲区

自评审的结构性弱点在于我只会检查「我想到要检查的东西」。本轮三条 MUST FIX 有一个共同模式——**进程内成立的假设在跨进程后失效**（cancel 能中断 / 数据不出进程 / 调用方与被调方同信任域）。我是靠逐行读 `InvokeToolUseCase` 才发现的。

**仍可能存在同类未发现问题**。写完上述条目后我沿这条线索又查了三处治理机制（结果见下方"补充核实"）：`ToolAccessPolicy` 排除、`RunContextPropagator` 确认失效并追加 S-3。

`ArgsDigest` 那条我接着查了（不留给"他人"）：**风险排除**——摘要只在 hub 侧计算（`InvokeToolUseCase:122` 与 `RunOrchestrator:784`），provider 不参与，跨进程不影响令牌绑定。

但查证时撞见一个**既有**不一致（**非本 change 引入，划为范围外**）：两处摘要的"规范化"口径不同——

| 位置 | 规范化方式 | 实际文本 |
|---|---|---|
| `RunOrchestrator:785` | `new TreeMap<>(args).toString()` | Java Map 格式 `{a=1, b=2}`，键已排序 |
| `ArgsDigest.of` 调用点 `InvokeToolUseCase:122` | `req.arguments().toString()`（Jackson） | JSON 格式 `{"a":"1","b":"2"}`，键序为插入序 |

两者对同一参数算出的摘要必然不同，且 gateway 那条的注释写着"规范化 JSON"但**没有排序**。目前不出故障，因为令牌校验两侧都走 `RunOrchestrator` 那条，gateway 的摘要只进审计日志。

**不在本 change 修**（与 provider 拓扑无关，属独立缺陷），但建议另开 change 收敛为同一个工具方法。已记入本评审供后续参考。

**建议他人评审时继续沿"进程内假设失效"这条线索检查**：审计的 `sessionId` 在两侧如何对齐、`traceId` 跨进程如何串联（链路追踪断了会让跨服务排障极难）。

> `RunContextPropagator` 这条我没升级为 MUST FIX，因为我判断不了「宿主 ThreadLocal 不跨进程传」是缺陷还是正确设计。但 spec 应当**显式说明**它在 http 形态下不生效，否则宿主会误以为自己的 ThreadLocal 能传到 provider。归为需补充说明项（S-3）。

### 评审后补充核实（写完上述条目后继续查证，修正了两处判断）

| 疑点 | 核实 | 结论 |
|---|---|---|
| `ToolAccessPolicy` 跑在哪 | `InvokeToolUseCase` 第 **184** 行 —— 在 transport 调用**之前** | **不受跨进程影响**，权限判定始终在 hub 侧。原疑点排除 |
| `RunContextPropagator` 能否跨进程 | `capture()` 返回**不透明 `Object`**，设计上不可序列化；`examples/host-demo/DemoContextPropagator` 传的是 `DemoUserContext` ThreadLocal | **确认不跨进程**。且 `ToolAccessPolicy` 的 javadoc 明确写「宿主要按用户判定就从自己经 RunContextPropagator 恢复的上下文取」——这条指引在 http 形态下对 provider 侧不成立 |

### S-3 ｜ `RunContextPropagator` 在 http 形态下静默失效

**位置**：spec 全文未提

**问题**：宿主在单体形态下用 `RunContextPropagator` 把 `SecurityContextHolder` / MDC / 自定义 ThreadLocal 带到工具线程，provider 侧的方法级权限切面依赖它。改成 http 后，provider 是另一个进程，**这些 ThreadLocal 全部为空**，而且是**静默**为空——不会抛异常，只会让基于 ThreadLocal 的鉴权判定拿到 null 然后走默认分支。

宿主若把「工具方法上的 `@PreAuthorize`」当作最后一道防线，跨进程后这道防线可能静默失守。

**要求**：spec 增一节「http 形态下失效的进程内机制」，至少列出 `RunContextPropagator`，并说明 provider 侧要拿身份只能靠：(a) hub 传递的 `sessionId`（已在 `ExecutionContext` 里，内核不解释其含义，但宿主可自己映射）；(b) provider 宿主自己的网关鉴权。**不能**指望 ThreadLocal。

**分级**：SHOULD（不是代码缺陷，是必须写明的语义边界；但若宿主误解，后果等同鉴权绕过）。
