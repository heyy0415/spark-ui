# Coding Report v1 — test-e2e-playwright-fake-llm-20260911

- **阶段**: 3 编码实现
- **依据**: `request_analysis/spec.md`（v3）、`request_analysis/tasks.md`（v3）
- **编码顺序**: T00 → T01a → T01b → T02 → T03a → T03b → T04 → T05 → T06（契约无变更，无 contracts task）

## 0. 门禁结果（真实退出码）

| 命令 | 退出码 |
|---|---|
| `node .harness/scripts/mvn.mjs -q -B install` | 0（含 131 单测） |
| `pnpm -C spark-ui run ci` | 0 |
| `pnpm -C .harness run ci` | 0（7 步全绿） |
| `pnpm -C .harness run doctor` | 0 |
| `pnpm -C .harness run check-module-deps` | 0 |
| `SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh` | 0 —— **161 passed, 0 failed** |
| `SPARK_PORT=8091 bash .harness/scripts/e2e-frontend.sh` | 0 —— **7 passed** |
| `SPARK_PORT=8091 bash .harness/scripts/deploy-verify.sh` | 0 —— **12 passed, 0 failed** |

对比基线：e2e-backend 此前无模型时 60/101，deploy-verify 此前 8/12。两者首次在无模型机器上全绿。

## 1. 改动文件

### spark-rooter（平台模块，唯一一处）

| 文件 | 变化 | 说明 |
|---|---|---|
| `spark-rooter-runtime/.../infra/llm/PlanValidator.java` | +58 行 | 新增 `public static LlmClient.Decision decide(...)`，把 `LlmPlanner` 的 dispatch（none / clarify / plan 三路 + `EntityMissing → Clarify`）搬进来；`missingEntity` 由 package-private 改 `public`；新增 `replyOr` 私有方法与 `log` |
| `spark-rooter-runtime/.../infra/llm/LlmPlanner.java` | −44 行 | dispatch 改为调用 `decide`；删掉已无用的 `replyOr` 与 `Optional` / `Plan` import。**校验失败重试一次的逻辑仍留在本类**（它是「喂回模型」的模型侧逻辑，替身没有） |

纯提取，无行为变化。131 个单测（含 `PlanValidatorTest`）全过即回归证据。

**一处顺带修掉的潜在 bug**：原 `draft.action().trim().toLowerCase()` 用宿主默认 locale。土耳其 locale 下 `"CLARIFY".toLowerCase()` 得到 `clarıfy`（无点 i），匹配不上 `case "clarify"`，会静默落到 `default` 走 plan 路径——模型返回大写 action 是可能的（prompt 未强制小写）。提取时改为 `toLowerCase(Locale.ROOT)`。已用 JDK 21 在 `tr` locale 下验证：`CLARIFY` 默认 locale 得 `clarıfy`、ROOT 得 `clarify`，`NONE` / `PLAN` 两种 locale 一致（不含大写 I）。

### spark-rooter（宿主示例）

| 文件 | 变化 |
|---|---|
| `examples/host-demo/.../e2e/FakeLlmPlanner.java` | 新增 271 行 |
| `examples/host-demo/README.md` | 新增「e2e 假规划器」一节 |

### spark-ui

| 文件 | 变化 |
|---|---|
| `playwright.config.ts` | 新增 |
| `e2e/tsconfig.json`、`e2e/global.d.ts` | 新增 |
| `e2e/playground.spec.ts` | 新增（step 2/3/4/6 → 5 个 test，19 条断言） |
| `e2e/chat-flow.spec.ts` | 新增（step 5 → 1 个 test，19 条断言） |
| `e2e/inline-intents.spec.ts` | 新增（step 7 → 1 个 test，16 条断言） |
| `package.json` | devDep `@playwright/test@1.63.0`；scripts `e2e` / `e2e:install`；`typecheck` 追加 `tsc -p e2e/tsconfig.json` |
| `.oxlintrc.json` | 新增 `e2e/**` override（env node+browser、关 `no-console` 与 `no-underscore-dangle`） |
| `.gitignore` | 加 `e2e-report/`、`test-results/` |

`tsconfig.node.json` 未动（spec §2.4 的要求）。

### harness

| 文件 | 变化 |
|---|---|
| `scripts/e2e-frontend.sh` | 新增（替代 `.mjs`） |
| `scripts/e2e-frontend.mjs` | **删除** |
| `scripts/preview-console.mjs` | puppeteer-core → playwright；stdout 格式与退出码语义不变 |
| `scripts/e2e-backend.sh` | 7 条断言改写 + 3 处注释措辞 |
| `scripts/deploy-verify.sh` | 无 `SPARK_LLM_API_KEY` 时追加 `--spring.profiles.active=e2e` + 打印规划器（**无 `check` 行变更**，`git diff` 已验证） |
| `scripts/harness-doctor.mjs` | required 换名；**新增 playwright 版本一致性检查** |
| `package.json` | devDep 换 `playwright@1.63.0`，删 `puppeteer-core`；`e2e-frontend` 指向 sh |
| `rules/backend-standard.md` §7 | 新增测试替身边界 |
| `rules/agent-safety.md` §2 | 新增测试替身与「不做规则兜底」的关系说明 |
| `skills/deploy-verify/SKILL.md` | 产出要求新增「必须记录实际规划器」 |
| 根 `README.md` | 门禁段命令与断言口径；「前端 e2e 另需本机 Chrome」→ Playwright 自带 |

## 2. 关键决策

**`decide` 的边界划在哪。** 只处理单次草案，`RunFailure` 一律上抛。重试属于「把原因喂回模型」的模型侧行为，替身没有这个能力，放进 `decide` 会让替身继承一个用不上的循环。

**fake 只替「理解」，不替「核实」。** `FakeLlmPlanner` 产出 `PlanDraft` 后调 `PlanValidator.decide`，与真模型同一路径。它不能自己构造 `Plan`——那样 e2e 就验不到校验边界了。这条已写进两份规则。

**整数参数按 schema 夹紧。** 真模型受 prompt 约束会遵守 `inputSchema`，替身必须模仿。用例 ⑲「最近 100 单订单」因此填 `limit=50`（`OrderTools` 的 `max = 50`），30 条种子减 1 条 DELETED = 29 行，与断言一致。不夹紧会被 `assertValueMatches` 拒，而替身没有重试。

**动词判定顺序：当前消息优先，其次继承 `pendingMessage`。** 否则「删除订单」出澄清屏后用户说「第二个的物流」会被错误拼回「删除」。用例 ㉒'' 专门验这一点。

**`reuseExistingServer: false`。** 本机手动起的 dev server 没有 `SPARK_BACKEND`，会静默代理到 8080——于是 e2e 连到一个没有 fake 规划器的后端，失败原因极难定位。编码期真踩到了这个坑（chat-flow 与 inline-intents 首轮全红）。

## 3. spec 之外的发现（均已核实并补进 spec v3）

**发现 1：失实断言不止 4 条，是 7 条。**

`:289` / `:324` / `:325` 同为 `03a7838` 遗留：该 commit 把 `RunOrchestrator.java:508` 的
`ClarificationScreen.build(…, IntentVerbs.verbLabel(message, domain), …)` 改为硬编码 `"选择"`（`IntentVerbs` 随规则规划器删除，全仓已无该类），但没同步断言。实测澄清屏按钮恒为 `label="选择"`、`intent="选择 订单 10030"`，**与规划器无关，真模型下同样失败**。

连带印证了一件事：`PICK` 取到的 intent 不含动词，所以 fake 处理澄清屏点选**必须**靠 `pendingMessage` 继承上一轮动词——spec §2.2 规则表末行的设计得到实测验证。

**发现 2：断言总数静态推算屡次算错。**

v1 评审与 spec v1/v2 都用 161（「全部 check 展开上限」），我静态推算得 154，实测是 161。漏算的是 `confirm_run()` 函数体内 4 条 `check` × 4 次调用。这恰好印证了 v2 评审 M-1 的判断：**这个数字不该做门禁**。验收已改为环境无关的 `0 failed`。

**发现 3：`pnpm run dev -- --port` 的 `--` 陷阱。**

pnpm 会把 `--` 一并透传，vite 收到 `vite -- --port 5199` 时把 `--` 当位置参数，端口静默失效，dev server 仍起在 5173，于是 Playwright 的 `webServer` 等 120s 超时。已在配置里注释。

## 4. agent-safety 六条边界自查

| 条 | 结论 |
|---|---|
| §1 四面职责 | 未变。fake 只替换 `LlmClient`（决策面的理解环节），编排 / 发现 / 执行三面未动 |
| §2 工具发现 | 未变。fake 仍在 Registry 返回的有限候选内选工具，产出经 `PlanValidator` 核实；新增一条规则明确替身的 profile 隔离要求 |
| §3 确认机制 | 未变。fake 不接触令牌签发与校验；多步链的前置步骤由 `@SparkPrerequisite` 决定，`decide` 照旧校验「需确认步骤前置齐全」 |
| §4 前端边界 | 未变。e2e 用例点的都是 `data-intent`（预写自然语言）与 `data-action-id`，没有绕过前端边界的操作 |
| §5 Gateway | 未变 |
| §6 流式输出 | 未变。fake 不产生新的 SSE 事件类型 |

补充：fake 的日志只打一行装配 WARN，不打用户原话（`e2e-backend.sh` 的 `§6.2.15 user text in log` 5 条断言全绿即证据）。

## 5. 已知限制

- **`decide` 本身没有专门的单测**。它是纯提取，由 131 个既有单测 + 161 条 e2e 断言双重回归，但没有直接针对 `decide` 三路分支的单元测试。建议在下一个 change 补（属第 1 项测试基建的延伸）。
- `FakeLlmPlanner` 只覆盖 e2e 脚本发送的消息形态，不是通用理解能力。这是设计选择，已在类注释与 spec 非目标中说明。
- e2e 未接进 `ci.mjs`（spec 非目标，留给改造清单第 4 项 CI 一并决定）。
- `e2e-frontend.sh` 需要 8091 与 5199 两个端口独占，被占时退出码 2。
