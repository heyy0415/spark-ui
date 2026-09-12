# Spec: test-e2e-playwright-fake-llm-20260911

> 改造清单第 3 项：e2e 摆脱真模型与本机 Chrome。后端加一个仅 e2e profile 装配的确定性规划器 `FakeLlmPlanner`（宿主 host-demo 内，不进内核），前端 e2e 迁到 Playwright（自带浏览器、自动等待、trace）。
>
> **v2 修订**（依据 `review/spec_review_v1.md`，4 条 MUST FIX 已核实成立）：M-1 webServer 改 dev server；M-2 改写 4 条失实断言并夹紧 limit；M-3 新增 `e2e/tsconfig.json` 而非改 `tsconfig.node.json`；M-4 deploy-verify 无模型时自动加 e2e profile。另采纳 S-1～S-5、L-1～L-3。
>
> **v3 修订**（依据 `review/spec_review_v2.md`，1 条 MUST FIX）：M-1 断言数不再作为验收断言——静态推算屡次算错（漏了 `confirm_run()` 函数体 4 条 × 4 次调用），门禁改用环境无关的 `0 failed`；三变量全空的实测值 **161** 已由 T02 实跑确认，仅作记录。另采纳 S-1（删「以实测为准」免责措辞）、S-2（webServer 走 `pnpm run dev` 以触发 predev）、S-3（端口用 `SPARK_FRONT_PORT`，默认 5199）、S-4（⑳/㉒' 验证强度措辞）、L-2（T03a 验收措辞）。
>
> **v3 编码期补入**：T01a 实测发现 `:289` / `:324` / `:325` 三条断言同为 `03a7838` 遗留（澄清屏按钮文案由 `IntentVerbs` 推导改为硬编码「选择」），一并改写，§2.3 表格已扩为 7 行。

## 1. 背景

- `e2e-backend.sh` 的断言大部分依赖模型规划；本机无 `SPARK_LLM_*` 时大量失败，只剩启动 / 自检 / 契约 400 类断言有效。上两个 change 的阶段 7 都因此只能「部分通过」。
  - **断言数口径**（v3 修正，T02 实测校准）：静态推算不可靠——`grep -c 'check "'` 只得 145 行，实际还有三处 `for` 循环展开（`:42`×12、`:46`×2、`:386`×5）与 `confirm_run()` 函数体内 4 条 check × 4 次调用，且 `:391` 块的守卫是 `[ -n "$SPARK_LLM_BASE_URL" ]` 而非 `LIVE_LLM`。**实测口径**：三变量全空 **161**（T02 实跑，`0 failed`），③ 与 `:391` 块 skip；LIVE 环境会多跑 ③ 的 2 条与 `:391`/`:398` 的 5 条、少跑 ⑥ else 的 2 条。
  - 结论：断言数随环境组合变化且静态推算易错，**不作为验收断言**（见 §6.2），只在报告里记录实测值。
- `e2e-frontend.mjs` 用 puppeteer-core 驱动写死路径 `/Applications/Google Chrome.app/...` 的本机 Chrome，只能在装了 Chrome 的 macOS 上跑；主链路 step 5 / 7 同样依赖真模型。`preview-console.mjs` 同样绑本机 Chrome。
- 两套 e2e 都不进 `ci.mjs`，阶段 6 CI（改造清单第 4 项）配置前必须先让它们可在无外网、无本机 Chrome 的 Linux 上稳定运行。
- 已确认：`LlmClient` 是 `@ConditionalOnMissingBean` 的 SPI（`RuntimeBeans.java:146`），宿主定义同类型 Bean 即覆盖；host-demo 已有 `@Profile("e2e")` 用法（`DemoSessionIdResolver`）；`@playwright/test` 1.63.0 engines `>=20`，本机 node v20.20.2 满足，缓存的 chromium 是 1161 而 1.63 需 1243，需一次下载。
- **v2 新增背景**：`e2e-backend.sh` 有 4 条断言依赖 `refactor-llm-planner-domain-free` 已删除的规则时代日志（`source=memory` / `route runId=… source=`），全仓 `src/main` 下零命中，真模型下同样失败。它们是历史遗留，本 change 一并清理。

## 2. 范围（In Scope）

### 2.1 runtime：开放规划决策的最小公共 API（S-1）

`LlmPlanner` 把「`PlanDraft` → `Decision`」的 dispatch 写在私有方法里（`LlmPlanner.java:100–132`：`none → NoCapability`、`clarify → missingEntity`、`plan → validate` 且 `EntityMissing → Clarify`、`replyOr` 默认文案）。fake 若要走同一条校验路径，只能复制约 40 行——复制即漂移。

- 在 `PlanValidator` 新增 `public static LlmClient.Decision decide(PlanDraft draft, LlmClient.PlanRequest req, ToolDisplayNames names, ToolMetaRegistry meta, Set<String> trustedOnlyArgs, SchemaValidator validator)`，把上述 switch 与 `EntityMissing` 捕获搬进去。
- `LlmPlanner` 改为调用 `decide`（行为不变，仅提取；校验失败重试一次的逻辑仍留在 `LlmPlanner`，因为它是「喂回模型」的模型侧逻辑）。
- `missingEntity` 由 package-private 改 `public`（评审已指出 spec v1「三者均 public」有误）。
- 这是本 change 唯一触碰平台模块的改动，属纯提取，无行为变化，由上一 change 的 `PlanValidatorTest` 回归守护。

### 2.2 后端：host-demo 内的 e2e 假规划器

- 新增 `examples/host-demo/src/main/java/com/example/demo/e2e/FakeLlmPlanner.java`（`@Component @Profile({"e2e", "e2e-ttl"})`，实现 `com.sparkrooter.runtime.application.port.LlmClient`）。**放在宿主而非内核**：它必须认识示例领域的动词与工具（「退款」→ `refund.*`），内核 `DOMAIN_WORDS` 红线禁止这些词。host-demo pom 已传递依赖 runtime（经 starter），无需新增依赖。
- 行为：模型输出的**确定性替身**，产出与真模型同形态的 `PlanDraft`，然后调用 §2.1 的 `PlanValidator.decide`，保证 e2e 验证的是「校验 + 编排 + 网关 + 领域」而不是绕过校验的捷径。规则表只覆盖 e2e 脚本实际发送的消息形态：

| 消息形态 | 草案 |
|---|---|
| 含「天气」等无匹配动词 | `action=none`，reply 礼貌回复 |
| 含 5 位订单号（`订单 10002` / 裸 `10030`）或上下文可解析（「第二个」→ `lastRowIds[1]`、「它 / 刚才那单」/ 省略 → `entities.order`） | 按动词选目标：`物流` → `order.logistics.get`；`详情` → `order.detail.get`；`删除` → `order.detail.get` + `order.delete`；`退款 / 退钱 / 钱要回来` → `refund.eligibility.check` + `refund.preview` + `refund.create`；`售后` → `aftersale.list.get` + `aftersale.create` |
| 动词需要订单但原话与上下文都无 ID | `action=clarify`，`missing=[{entity:"order"}]`（英文类型名，`normalizeEntityType` 才命中，见 L-2），steps 放目标工具（args 缺 orderId） |
| 「看看我的订单 / 我想查看最近订单 / 订单」 | `order.list.search`，`最近 N 单` → `limit = min(N, inputSchema.properties.limit.maximum)`，`已发货 / 已支付 / 已完成 / 已退款 / 已取消` → status 别名 |
| 「有什么商品」 | `product.list.search` |
| `查看商品 P-1003 的详情` | `product.detail.get{productId}` |
| 澄清屏点选 intent「选择 订单 10001 …」 | 与「订单 10001 + 挂起原话动词」同处理：取 `ctx.pendingMessage()` 的动词 |

- **整数参数按 schema 夹紧**（M-2 第 4 条）：fake 对所有 integer 参数按候选 `inputSchema` 的 `maximum` / `minimum` 夹紧后再填，模仿真模型遵守 prompt 规则 2 的行为。用例 `⑲ 最近 100 单订单` 因此填 `limit=50` 而非 100，避免 `PlanValidator.assertValueMatches` 抛 `TOOL_SELECTION_INVALID`（fake 没有 `LlmPlanner` 的二次重试）。
- `name()` 返回 `"fake-e2e"`；启动 WARN 一行「e2e 假规划器已装配，仅供测试」。与 `UnavailablePlanner` 一样不含任何外部 IO。
- 默认 / Docker 无 profile 启动**不装配**，仍是真模型或 `UnavailablePlanner`。

### 2.3 e2e-backend.sh：清理 4 条失实断言（M-2）

全仓 `src/main` 下 `grep 'source=memory\|route runId'` 零命中，以下断言自 `refactor-llm-planner-domain-free` 删除规则规划器后即失实，**真模型下也失败**，非本 change 引入：

| 行 | 原断言 | 依赖的日志 | 处置 |
|---|---|---|---|
| `:311` | `⑳ source=memory logged` | `runId=… source=memory` | 改写为 `decision runId=<id> kind=Planned planner=fake-e2e`（`RunOrchestrator.java:174` 实际输出）；补位的实质验证由同用例的 `tool.selected` args 断言承担（见下方说明） |
| `:337` | `㉒' source=memory(ordinal) logged` | `source=memory(ordinal)` | 同上，改用 `decision … kind=Planned` |
| `:398` | `⑤ route decisions logged ≥3` | `route runId=.* source=` | 改写为 `grep -c 'decision runId=.* kind=' ≥ 3` |
| `:166`（仅 LIVE） | `③a route by model` | `route runId=… domain=refund source=model` | 同步改为 `decision runId=… kind=Planned planner=` |
| `:289` | `⑯ row action label` 期望「删除订单」 | `IntentVerbs.verbLabel(message, domain)` | 改期望为 **「选择」**（见下方说明） |
| `:324` | `㉒ row action label` 期望「申请售后」 | 同上 | 改期望为 **「选择」** |
| `:325` | `㉒ row action intent has id` 要求 intent 含「售后」 | 同上 | 改为只要求 intent 含行 id（`选择 订单 10001` 形态） |

- **`:289` / `:324` / `:325` 三条同为 `03a7838` 遗留**（编码阶段 T01a 实测发现，v3 补入）：该 commit 把 `RunOrchestrator.java:508` 的 `ClarificationScreen.build(…, IntentVerbs.verbLabel(message, domain), …)` 改为硬编码 `"选择"`（`IntentVerbs` 随规则规划器一并删除，全仓已无该类），但未同步这三条断言。现行澄清屏的行内按钮恒为 `label="选择"`、`intent="选择 {entityLabel} {id}"`，**与规划器实现无关，真模型下同样失败**。
- 连带影响：`:327` 的 `PICK` 取 `rows[0].actions[0].intent`，实际值形如 `选择 订单 10001`，**不含动词**。因此 fake 处理澄清屏点选时必须靠 `ctx.pendingMessage()` 继承上一轮动词（spec §2.2 规则表末行已如此设计，此处得到实测印证）。

- **用例编号保持不变**，只把「验证手段」从已下线的日志换成现有日志。澄清链路另有 `clarification screen runId=… entity=order`（`RunOrchestrator.java:538`）可用。
- ⑳ / ㉒' 的验证强度说明（v3 修正，原写「验证意图不变」不准确）：`decision … kind=Planned` 只能证明产出了可执行计划，无法区分实体来自记忆补位还是原话提取。**实体补位的实质验证由同用例已有的 `tool.selected` args 断言承担**——原话里没有 ID 却能填对 ID，即补位生效。改写后验证强度基本保持，但不是「意图不变」。
- **断言条数不变**：4 条都是改写而非删除，`:166` 本就是 LIVE-only 不计入离线数。三变量全空环境下实跑 **161**（T02 实测）。
- `e2e-backend.sh` 其余 `check` 断言与用例编号一律不改；注释里「规则规划器 / rule mode」改成「fake planner」，「意图路由 ③（仅 LIVE）」「⑥（仅规则模式）」的跳过条件语义不变。

### 2.4 前端：Playwright 迁移

- **webServer 用 dev server**（M-1）：`/dev/schema` 路由由 `env.DEV` 门控（`router.tsx:13`），`vite preview` 下不注册，step 2/3/4/6 共 19 条断言必失。改为 dev server，与现行 `e2e-frontend.mjs` 基线一致。`vite.config.ts` 的 `proxy` 对象 dev 与 preview 共用同一份（`vite.config.ts:10–14` 已核对），`SPARK_BACKEND` 注入方式无需改动。生产 bundle 的 console.error 校验继续由 deploy-verify 的 `preview-console` 负责，两者分工不变。
  - **命令用 `pnpm --filter spark-chat run dev`**（v3 修正，S-2）：直接跑 `vite` 会绕过 `predev` 钩子（`apps/chat/package.json` 的 `predev` → `scripts/ensure-core-dist.mjs`），core dist 缺失时报错不可读。走 pnpm 生命周期即自动守护。
  - **端口从环境变量读**（v3 修正，S-3）：`vite.config.ts:38–39` 是 `port: 5173, strictPort: true`，本机开着 dev server 时 e2e 直接失败。`playwright.config.ts` 用 `SPARK_FRONT_PORT`（默认 **5199**，与 README:188 现行 `SPARK_FRONT_BASE` 口径一致），`baseURL` 与 `webServer.url` 同源，命令追加 `--port $SPARK_FRONT_PORT`。
- `spark-ui/` 新增 `e2e/`：`playwright.config.ts`（`testDir: ./e2e`，单 project chromium，`baseURL` 取自 `SPARK_FRONT_PORT`，`reporter: [['list'], ['html', { open: 'never', outputFolder: 'e2e-report' }]]`，`trace: 'retain-on-failure'`，`retries: 0`），`e2e/*.spec.ts` 把 `e2e-frontend.mjs` 的 step 2–7 逐条迁为 `test()`，断言 1:1 保留（54 条），选择器不变。
- **e2e 独立 tsconfig**（M-3）：`tsconfig.node.json` 的 `lib: ["ES2023"]` 覆盖了 base 的 DOM lib，接管 e2e 文件后 `page.evaluate(() => document…)` 必报 `Cannot find name`。改为新增 `spark-ui/e2e/tsconfig.json`：`extends ../tsconfig.base.json`（保留 DOM lib），`compilerOptions.types: ["node"]`，`include: ["./**/*.ts", "../playwright.config.ts"]`；附 `e2e/global.d.ts` 声明 `Window.__sparkSeen`；根 `typecheck` script 追加 `tsc -p e2e/tsconfig.json --noEmit`。`tsconfig.node.json` 不动。
- 根 `package.json`：devDeps `@playwright/test: 1.63.0`；scripts `e2e: playwright test`、`e2e:install: playwright install chromium`。`.gitignore` 加 `e2e-report/`、`test-results/`。oxlint override `e2e/**` 设 `env: { node: true, browser: true }` 并允许 console。
- `.harness/scripts/e2e-frontend.sh`（新，替代 `e2e-frontend.mjs`）：前置检查 `[ -d spark-ui/packages/core/dist ] || exit 2` 并提示先跑 `pnpm -C spark-ui build`（S-5）→ 起 host-demo（`--spring.profiles.active=e2e`，端口 `SPARK_PORT`，默认 8091）→ `SPARK_BACKEND=http://localhost:$PORT pnpm -C spark-ui run e2e` → 收集 `spark-ui/e2e-report` 与 `test-results` 到 `$DEPLOY/e2e-frontend/` → 关后端。
- 删除 `e2e-frontend.mjs`；`preview-console.mjs` 改为 Playwright 实现，行为与输出格式不变（`agent-input=1|0`、退出码 = error 总数）。依赖放 `.harness/package.json` devDeps `playwright: 1.63.0`（S-4），**与 `spark-ui` 的 `@playwright/test` 精确同版本**——版本不一致会触发第二份 chromium 下载（两者同版本共用 `~/Library/Caches/ms-playwright/chromium-1243`）。`harness-doctor` 加一条两处版本一致性检查（Hashimoto）。`.harness/package.json` 去掉 `puppeteer-core`，`e2e-frontend` script 指向新 sh。

### 2.5 deploy-verify：无模型时自动加 e2e profile（M-4）

`deploy-verify.sh:30` 无 `--spring.profiles.active` → `UnavailablePlanner` → `:43` `:47` `:49` `:50` 4 条必失，`:45` 的 `TOKEN=` 抽取还会抛 python 异常。而 `dev-workflow.md` 阶段 7 门禁明确要求「示例 Run 走通至 `run.completed`」。

- 脚本在**无 `SPARK_LLM_API_KEY` 时自动追加 `--spring.profiles.active=e2e`**，有 key 时保持现状（生产形态）。
- 两种情况都在 stdout 与 `preview_report.md` 打印实际规划器（`planner=fake-e2e` 或 `planner=<真模型实现名>`），报告不掩盖规划器来源。
- 改动量约 6 行，`check` 断言一条不改。阶段 7 门禁因此在无模型机器上可达。

### 2.6 文档与门禁

- `README.md` 开发与质量门禁段：e2e-frontend 命令改为 `SPARK_PORT=8091 bash .harness/scripts/e2e-frontend.sh`，注明「需先 `pnpm -C spark-ui run e2e:install`」；「前端 e2e 另需本机 Chrome」改为「Playwright 自带 chromium」；e2e-backend 注明「无模型时由 host-demo 的 e2e 假规划器驱动，全部断言可离线跑」，断言数按 §1 口径写（三变量全空 161（实测）），不写单一数字。
- `harness-doctor.mjs` required：`scripts/e2e-frontend.mjs` → `scripts/e2e-frontend.sh`；新增 playwright 版本一致性检查。
- `deploy-verify/SKILL.md`、`frontend-doctor/SKILL.md`（若提到 puppeteer / Chrome）同步。
- `backend-standard.md` §7 加一句：「测试用假规划器只能放在宿主工程并以 profile 隔离，内核不得出现」。
- `agent-safety.md` §2 追加（S-2）：「测试替身（fake planner）只允许在宿主工程内以 profile 隔离装配，生产 profile 下不得存在；替身产出的草案同样必须经 `PlanValidator`」——与 `backend-standard.md` §7 呼应，避免「宿主装一个按关键词选工具的规划器」在字面上与「不做规则兜底」冲突。
- `host-demo/README.md` 加「e2e profile 装配 `FakeLlmPlanner`，仅测试」。

## 3. 非目标（Out of Scope）

- 不改 `e2e-backend.sh` 的**用例编号与验证语义**；§2.3 列出的 4 条因日志下线而失实的断言换验证手段，不算改语义。其余断言一律不动。
- 不改 `deploy-verify.sh` 的任何 `check` 断言；§2.5 只改启动参数与报告输出。
- 不把 e2e 接进 `ci.mjs`（改造清单第 4 项 CI 一并决定：本地 ci 是否跑 e2e）。
- 不做 Playwright 的移动端真机 / WebKit project（保留 375 视口模拟）。
- `FakeLlmPlanner` 不追求覆盖真模型的全部理解能力，只覆盖 e2e 脚本里出现的消息形态；不进内核、不进 `examples/domains`。
- 不改前端实现、不改契约。
- §2.1 的 `decide` 提取不改变任何运行时行为，不做「顺手」重构 `LlmPlanner` 的其他部分。

## 4. 核心场景

- 无 `SPARK_LLM_*` 的机器：`SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh` → `0 failed`（T02 实测 161 条）；`bash .harness/scripts/e2e-frontend.sh` 54/54，产出 HTML 报告与失败 trace；`bash .harness/scripts/deploy-verify.sh` 12/12（自动 e2e profile）。
- 有真模型的机器：默认 / Docker 启动不受影响，仍走 `LlmPlanner`；deploy-verify 保持生产形态。
- 运行链路：Runtime → `LlmClient`（e2e 下为 fake）→ `PlanValidator.decide` → 编排 → Gateway → 领域 → 前端，除规划器实现外全部真实。

## 5. 契约影响

- **NONE**。

## 6. 验收标准

1. `node .harness/scripts/mvn.mjs -q -B install` 退出 0（含上一 change 的 131 单测，`PlanValidatorTest` 回归 `decide` 提取）；`cd spark-rooter/examples/host-demo && rm -rf target && mvn -q -B -o package -DskipTests` 退出 0。
2. `SPARK_PORT=8091 SPARK_CHANGE=<本 change> bash .harness/scripts/e2e-backend.sh` 输出 **`0 failed`** 且退出码 0；`backend.log` 含 `planner=fake-e2e`，不含 `BOOT FAILED`。
   - 断言数只作记录不作断言（v3 修正，M-1/S-1）：三变量全空实测 161，随 `SPARK_LLM_*` 组合变化（口径见 §1）。**以 `0 failed` 为门禁**，避免把环境相关的数字写死。T02 在报告里记录实测 passed 数并与 §1 口径核对，不一致则说明原因。
3. 无 profile 启动 host-demo（`java -jar ... --server.port=8092`）日志不含 `fake-e2e`，含 `planner UNAVAILABLE`（本机无模型，文案见 `LlmFactory.java:46`）；验证后关闭。
4. `pnpm -C spark-ui run e2e:install` 退出 0；`SPARK_PORT=8091 SPARK_CHANGE=<本 change> bash .harness/scripts/e2e-frontend.sh` 退出 0，Playwright 汇总 `54 passed`，`$DEPLOY/e2e-frontend/` 含 HTML 报告。
5. `grep -rn "puppeteer" .harness spark-ui --include=*.mjs --include=*.json --include=*.md | grep -v node_modules | grep -v changes` 无命中；`grep -n "Google Chrome" .harness/scripts/*.mjs .harness/scripts/*.sh` 无命中。
6. `SPARK_PORT=8091 SPARK_CHANGE=<本 change> bash .harness/scripts/deploy-verify.sh` 输出 `12 passed, 0 failed`，退出 0；stdout 含 `planner=fake-e2e`。
7. `pnpm -C .harness run ci` 退出 0；`pnpm -C .harness run doctor` 退出 0（含新增的 playwright 版本一致性检查）；`pnpm -C spark-ui run ci` 退出 0（`typecheck` 已覆盖 `e2e/tsconfig.json`）。
8. `pnpm -C .harness run check-module-deps` 退出 0（fake 规划器在 host-demo，不触碰平台模块红线；`PLATFORM_SRC` 与 `DOMAIN_WORDS` 不扫 `examples/host-demo`，已核对）。

## 7. 风险与权衡

| 风险 | 缓解 |
|---|---|
| fake 规划器变成「第二套规则引擎」并漂移到内核 | 只放 host-demo、`@Profile({"e2e","e2e-ttl"})` 隔离、`backend-standard.md` §7 与 `agent-safety.md` §2 双写禁令；它产出 `PlanDraft` 后必须过 `PlanValidator.decide`，与真模型同一校验路径 |
| §2.1 提取 `decide` 改动平台模块，可能引入行为差异 | 纯移动代码，不改分支逻辑；`LlmPlanner` 与 fake 共用同一实现；上一 change 的 `PlanValidatorTest` + e2e 全量断言双重回归 |
| e2e-backend 某些断言隐含真模型措辞（如 message.delta 文案） | fake 的 reply 文案与 `UnavailablePlanner` / 编排器默认文案对齐；§2.3 已逐条对照全部 `check` 给出仍失败者清单，不留给编码阶段现场发现 |
| 改写 4 条断言被误读为「降低门禁」 | 这 4 条真模型下同样失败（`src/main` 零命中已核实），属历史遗留而非本 change 放宽；用例编号保留，补位的实质验证由同用例 args 断言承担（§2.3） |
| 断言数随环境变量变化，写死数字会误导 | v3 起门禁只认 `0 failed` + 退出码 0；§1 给出三种环境的口径与计算式供核对 |
| Playwright chromium 首次下载需外网（约 150 MB） | `e2e:install` 独立 script；CI（第 4 项）用官方镜像或缓存；`.harness` 与 `spark-ui` 版本必须精确一致，否则下载两份 |
| 迁移后断言数漂移 | 逐 step 对照原脚本 `check(` 调用数（54），spec 验收精确到 54 |
| `preview-console.mjs` 换 Playwright 后 deploy-verify 的 grep 解析 | 保持 stdout 格式 `agent-input=1|0` 与退出码语义不变 |
| deploy-verify 自动加 profile 后「验的不是生产形态」 | 只在无 key 时降级，且强制打印 `planner=fake-e2e`；有 key 的机器（含发布前验证）仍是完整生产形态 |
| 8080 被 IDE 手动实例占用 | 全部脚本默认 `SPARK_PORT=8091`，与 deploy-verify 一致 |
