# Spec Review v1 — refactor-spark-embedded-starter-20260909

- mode: plan
- 评审对象：`request_analysis/spec.md` v1、`tasks.md` v1
- 依据：expert-reviewer plan 清单；project-structure / contracts / agent-safety / backend-standard；对照现有 `backed/` 12 个 Manifest、`.harness/scripts/**`、`fronted/pnpm-lock.yaml`
- 用户已定决策不复议（embedded-only、方法级注解、全量改名、内核无身份、前端只发自然语言、不拆 agent 包、记忆 + 澄清屏）

## 必查项

| 项 | 结果 |
|---|---|
| 非目标章节非空 | ✓（9 条） |
| 验收全部可命令 / 断言校验 | ✗ 部分：§6.3「≥ 34」、§6.1 第 4 条「即证明」、T15「人工核对」——见 M-4 / S-6 |
| 风险 ≥ 1 失败模式 + 缓解 | ✓（8 条） |
| task 标所属端、contracts 先于消费方 | ✓；T13 依赖 T04 正确 |
| 跨端结构 task 列契约文件 | ✓ |
| task ≤ 0.5 天 | ✗ T07 / T09 超——见 M-3 |

## 发现

| # | 位置 | 问题 | 建议 | 分级 |
|---|---|---|---|---|
| M-1 | spec §2.4 / §7「反射调用绕过宿主 AOP」 | 「`getBean` 取代理 + `AopUtils.getTargetClass` 找注解」只覆盖了「被代理的 Bean」这一种情况。三种真实漏洞未提：① 宿主 `@SparkTool` 方法所在类没有任何切面时 `getBean` 返回裸实例，此时宿主若靠 **Controller 级拦截器**做权限（很常见），spark 走不到 Controller，权限完全失效；② CGLIB 代理对 `final` 方法 / `final` 类不生效，切面静默不触发；③ `@SparkTool` 方法标在**接口默认方法**或被 `@Transactional`/`@Async` 包裹时，`Method.invoke(proxy, …)` 需用接口 `Method` 对象而非目标类的，否则 `IllegalArgumentException`。§6.2 只用一个 `@Aspect` 验收，验不出 ①②。 | §2.4 增：扫描器对 `final` 类 / `final` 方法 / 非 public 方法**启动失败并指明原因**；`Method` 取法用 `ClassUtils.getMostSpecificMethod` + `AopUtils.selectInvocableMethod`；§2.5 与 README 明写「宿主权限必须是方法级（AOP / `@PreAuthorize` / 方法内显式校验），Controller 级拦截器对 spark 调用无效」；§6.2 增反例：植入 `final` 方法 → 启动失败；植入只在 Controller 拦截器里做权限的 demo 路径 → e2e 证明 spark 能绕过（用它说明红线而不是当 bug） | MUST FIX |
| M-2 | spec §2.7 / §7「令牌绑 conversationId」 | `conversationId` 由前端生成、可伪造，令牌与记忆都绑它，等于「知道会话号就能确认别人的高风险操作」。§7 缓解列了 `argsDigest` 与「确认屏显式展示实体」，但 `argsDigest` 是计划参数摘要，攻击者拿到令牌串时参数已经定了，`argsDigest` 不构成第二因素；「显式展示」是给受害者看的，攻击者不看。默认实现在生产是漏洞。 | §2.7 改：`SessionIdResolver` 默认实现**标记为 demo-only**（启动 WARN，与 LLM 未配置同级别），并在 README 接入指南把「实现 `SessionIdResolver` 绑到宿主登录态」列为**生产必做项**；令牌 `consume` 时同时校验 `conversationId` 与 `sessionId` 两者；§7 缓解改为如实描述「默认实现无隔离」 | MUST FIX |
| M-3 | tasks T07、T09 | T07 = 去 principal / pageContext + 令牌改绑 + **把三个模块的 `api/` 包挪成新模块** + 三个 pom 换依赖；T09 = 扫描器 + Manifest 推导 + 代理调用适配 + `ToolMeta` 注册表 + **四个领域服务重写为注解形态** + 删 `app`。各自 ≥ 1.5 天，且「移动 / 重命名与逻辑分开提交」的约定被 T07 自身违反。 | T07 拆 T07a（去身份 / pageContext / 令牌 sessionId，逻辑）、T07b（`api/` 包移出成 `web-mvc`，纯移动 + pom）；T09 拆 T09a（扫描器 + 推导器 + 适配器 + 自检，用一个测试 Bean 验收）、T09b（四个领域服务改注解形态 + 删 `app`）；依赖图同步 | MUST FIX |
| M-4 | spec §6.1 第 4 条、§6.3 第 3 条、T15 验收 | 「`host-demo` 主类包名为 `com.example.demo`，启动后 12 工具注册成功即证明装配不靠扫描」——但 T08 验收里临时 `app` 已做同一件事，且 `SmartInitializingSingleton` 扫描器扫的是全部 Bean，本来就与包名无关，该断言不证明「AutoConfiguration 不依赖包扫描」；真正要证的是**平台自身 Bean** 不靠 `@ComponentScan`。「e2e-frontend ≥ 34」是下限式断言，可被删用例满足。T15「人工核对 + 行数 ≤ 6」不可程序化。 | §6.1 第 4 条改为机械断言：`grep -rn "@Component\|@Service\|@Repository\|@Configuration\|@ComponentScan" spark-rooter-{runtime,registry,gateway,web-mvc}/src` 0（已在 T08 验收，复用）；§6.3 写死数量（删 URL 参数相关 N 条、增 1 条，给出精确期望值）；T15 改为 grep 白名单文件清单 + 每条命中行必须含「宿主」或「ToolAccessPolicy」字样 | MUST FIX |
| S-1 | spec §2.4 类型映射表 | 现有 12 个 Manifest 的输出含：可选嵌套对象（`order.detail.get.logistics` req=false、`aftersale.list.get.order`）、必填嵌套对象（`address`）、`List<record>`（6 处）。表里有 `List<record>→array` 但**没写嵌套 record → object、`Optional<record>` / 可空 → 非 required**；`enum` 只提了 `@SparkParam.enums`（字符串），Java `enum` 类型未提；`outputSchema` 说「不需注解，输出全开放」但 `additionalProperties:false` 下 record 的 `null` 组件如何表达（现 Manifest 用「省略字段」而不是 `null`）未定。 | §2.4 补：嵌套 record → `object`（递归，`additionalProperties:false`）；`Optional<T>` 或 `@Nullable` 组件 → 不进 `required`，序列化时 `null` 省略（平台 ObjectMapper `NON_ABSENT`）；Java `enum` → `enum` 数组（常量名）；`Instant`/`LocalDate` → `date-time`/`date`。并在 T09a 验收加「12 个推导 schema 与现有 JSON 逐一 diff 为空」 | SHOULD |
| S-2 | spec §2.1 / T03、`change-dir.mjs` | `.harness/changes/**` 排除在 `check-rename` 外是对的，但脚本本身读 `STRATO_CHANGE` 环境变量、`e2e-backend.sh` 把产物写到 `deployment/`——变量改名后，旧 change 目录名含 `strato`（`feat-strato-ui-monorepo-20260904`）会被 `change-dir` 的候选扫描读到，状态 DONE 被排除，没问题；但 `check-rename` 若实现成「全树 grep 排除 `.harness/changes`」，`.harness/scripts/lib/change-dir.mjs` 注释里的 `STRATO_CHANGE` 示例字符串仍会命中。spec 未说明 `pnpm-lock.yaml` 的 `importers` 键（`@strato-ui/core`）需要 `pnpm install` 重写，`git mv` 不会改它。 | T03 验收增：`pnpm -C spark-ui install` 后 `grep -c strato pnpm-lock.yaml` 0；T01 明写 lockfile 由 install 重生成而非手改 | SHOULD |
| S-3 | spec §2.6 澄清屏 / §4.5 | 澄清屏由 Runtime 调 `order.list.search` 并**覆写**行内 `actions`。覆写发生在 `ScreenRegistry.result()` 之后（已过契约校验）还是之前？若之后，覆写产物未再过校验；若之前，Runtime 要改领域 `ScreenBuilder` 的输出，与「屏归领域」分层冲突。`CLARIFY_LIST_TOOL` 表把实体类型硬绑到具体 toolId，宿主没声明 `order.list.search` 时退回提示——但宿主可能有别名工具（`my.orders.list`），表无法配置。 | §2.6 定：Runtime 先调列表工具拿**原始输出**，用 `ClarificationScreen`（runtime 内通用投影：只认输出里的 `items[]`，每项取 `@SparkParam.entity` 标记的字段为 id）自己出 Table，不经领域 `ScreenBuilder`，再过 `ScreenRegistry.toUi` 校验；澄清列表工具改为**注解声明**：`@SparkTool(clarifiesEntity = ORDER)` 标在列表工具上，扫描器建 `entity → toolId` 表，内核不写死 | SHOULD |
| S-4 | spec §2.6 抽取层「数量」 | 数量映射规则「整型且 `aliases` 含『单 / 条 / 个』」把单位词塞进 `aliases`（本意是枚举别名），语义混用；「最近 5 单」里的 5 应映射到 `size` 还是 `limit`（现有 `order.list.search` 参数叫 `limit`，spec §2.4 示例改叫 `size`）未统一。 | `@SparkParam` 增 `unit = {"单","条"}` 独立属性；示例宿主参数名与现有 Manifest 保持 `limit`（e2e 断言复用），或明确改名并列入 T09b 验收 | SHOULD |
| S-5 | spec §2.3 `ToolAccessPolicy` 入参「`HttpServletRequest`」 | 端口在 spi（零 Spring / 零 Servlet），入参不能是 `HttpServletRequest`；且 Registry 候选过滤在 Runtime 线程池里执行，此时 Servlet 请求上下文已不在当前线程。 | 入参改为 `(String toolId, String sessionId)`，宿主要用户就用自己的 ThreadLocal（需宿主在 `SessionIdResolver` 阶段捕获并传播，spec 写明「Runtime 异步线程不继承请求线程的 ThreadLocal，宿主需用 `TaskDecorator` 或在 `SessionIdResolver` 里编码」）；同理 §2.5「方法体读 `UserContext`」也受此影响——**这是 M-1 的另一面**：Gateway 反射调用发生在 `agent-run-*` 线程，宿主 ThreadLocal 为空。必须在 spec 写死解决方案：starter 提供 `spark.runtime.context-propagation`（默认 `InheritableThreadLocal` 不可靠 → 用 `TaskDecorator` 端口 `RunContextPropagator`，宿主实现捕获 / 恢复自己的上下文；示例宿主实现它） | SHOULD（与 M-1 合并修） |
| S-6 | spec §6.2 / T14 selfcheck 数 | spec §6.2 写「自检 7/7」，T14 写「新增 `ProxyInvocationSelfCheck`，共 8」；T09a 验收又提它。 | 统一为 8，spec §2.10 与 §6.2 同步 | SHOULD |
| S-7 | spec §2.10 e2e 编号 | ⑰–㉕ 共 9 条，但 ㉒ 一条包含「澄清屏 + 点选 → 确认屏」两个断言组，㉓ 依赖 e2e-ttl profile 需**单独起一次进程**（脚本现状单进程），未说明。 | ㉓ 改为运行期可调：示例宿主暴露 `spark.runtime.memory-ttl` 可经 actuator `env` 改？不可（内存 TTL 在 Bean 构造期读）。改为 e2e 脚本第二次以 `--spring.profiles.active=e2e-ttl` 启动只跑 ㉓；tasks T14 标注 | SHOULD |
| S-8 | spec §2.9 | `tool-manifest.authorization.permission` 改可选后，`ManifestDeriver` 从 `@SparkTool` 生成时 `authorization` 整个节点填什么？现契约 `authorization` 是 required 对象。 | 明确：`authorization: {}`（空对象）或删 required；契约与推导器一致 | SHOULD |
| L-1 | spec §2.3 属性表 | `spark.runtime.sse-timeout` 默认 60s，但 LIVE 模式规划实测 9–39s，change 4 已把 e2e 的 SSE_T 调到 90s | 默认 90s 或写明 LIVE 建议值 |
| L-2 | spec §2.4 `@SparkTool` 必填 `domain` | 领域路由关键词表 `DomainRouter` 仍是内核硬编码 4 个领域；宿主声明 `domain = "coupon"` 后路由永远到不了 | 非目标里写明「本 change 路由关键词表不可配置，宿主新增领域需配 `spark.routing.domains.<name>.keywords`」并加进 §2.3 属性表，或列为已知限制 |
| L-3 | tasks T01 | Java 包 `com.strato.domain.*` → `com.sparkrooter.domain.*`，但领域服务在 T09b 移到 `examples/`，包名应为 `com.example.*` 或 `com.sparkrooter.examples.*`，两次改名 | T01 领域服务包名一次到位 `com.sparkrooter.examples.<svc>` |
| L-4 | spec §2.2 | `host-demo` 用 `spring-boot-starter-parent` 且离线 `-o`：本机 `~/.m2` 需已有该 parent 及全部传递依赖（root 构建会拉到），但 `spring-boot-maven-plugin` 与 `-o` 组合首次可能缺插件 | T10 验收前加一次在线 `mvn -q package`，随后 `-o` 复跑 |
| I-1 | spec §2.6 | 相对时间「这个月」默认到今天还是整月？`since` 只有下界 | 写明只产 `since`，上界不产 |
| I-2 | spec §3 | i18n 非目标未列：动词表 / 别名 / 澄清文案全中文 | 加一条非目标 |

## verdict: REVISION REQUIRED（4 MUST FIX，8 SHOULD）

## 最小补丁清单（v2 一次到位）

1. **§2.4** 增「代理与线程」小节：`final` 类 / 方法 / 非 public → 启动失败；`Method` 取法；宿主权限必须方法级；`RunContextPropagator` 端口（宿主捕获 / 恢复 ThreadLocal），示例宿主实现；§6.2 增 `final` 方法反例与「Controller 拦截器绕过」演示用例。
2. **§2.7 / §7 / §2.3 端口表**：默认 `SessionIdResolver` 标 demo-only + 启动 WARN；README 生产必做；`consume` 同时校 conversationId 与 sessionId；§7 如实写「默认无隔离」。
3. **tasks**：T07 → T07a / T07b；T09 → T09a / T09b；依赖图更新；T01 领域包名一次到位。
4. **§6**：第 4 条改机械 grep；§6.3 前端用例数写死；T15 验收改 grep 白名单。
5. **§2.4 类型表**：嵌套 record / `Optional` / Java enum / 时间类型；`NON_ABSENT`；T09a 验收「12 个推导 schema 与现 JSON diff 为空」。
6. **§2.6**：澄清屏由 Runtime `ClarificationScreen` 从原始输出投影并过 `toUi`；`@SparkTool(clarifiesEntity=…)` 替代内核硬表；`@SparkParam.unit` 独立于 `aliases`；参数名沿用 `limit`。
7. **§2.3 `ToolAccessPolicy`** 入参 `(toolId, sessionId)`。
8. **自检 8 / e2e ㉓ 二次启动 / `authorization` 空对象 / lockfile install / L-1 L-2 I-2** 各一句。
