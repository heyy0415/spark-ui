# Spec Review v1 — test-e2e-playwright-fake-llm-20260911

- **mode**: plan
- **评审对象**: `request_analysis/spec.md`、`request_analysis/tasks.md`
- **依据**: `expert-reviewer/SKILL.md`（plan 必查项）、`rules/{project-structure,backend-standard,agent-safety,contracts,dev-workflow}.md`
- **独立性**: 只读 spec / tasks，未读任何编码侧自评；所有事实均核对源码 / 脚本 / 依赖元数据（见各条「核对」）。
- **verdict**: **REVISION REQUIRED**（MUST FIX 4 条）

---

## 0. 结论摘要

方向正确：`LlmClient` 是 `@ConditionalOnMissingBean` 的 SPI（`RuntimeBeans.java:155`），宿主覆盖不违反 §1–§3；fake 产出 `PlanDraft` 再过 `PlanValidator.validate` 能保住三条硬边界。但 spec 有四处「按文实现必然达不到验收」的矛盾：

1. `vite preview` 下 `/dev/schema` 路由不存在（`env.DEV` 门控），Playwright step 2/3/4/6 共 19 条断言必失。
2. `e2e-backend.sh` 有 3 条断言依赖运行时**根本不打**的日志（`source=memory` / `route runId=.* source=`），真模型下也失败；「不改任何 check」与「161/161」互斥。
3. `tsconfig.node.json`（`lib: ["ES2023"]`，无 DOM）接管 e2e 文件后 `page.evaluate(() => document…)` 必报 TS 错，验收 7 不可达。
4. deploy-verify 无 profile 启动 → `UnavailablePlanner` → 「端到端一条 Run」4 条必失，spec §1 目标（阶段 7 不再部分通过）与 §3 非目标（不改 deploy-verify）自相矛盾且未明示取舍。

---

## 1. 逐条意见

### M-1 ｜ MUST FIX ｜ Playwright `webServer` 用 `vite preview`，但 `/dev/schema` 只在 DEV 存在

- **位置**: spec §2.2 第 1 段（`webServer` 起 `vite preview --port 4173`）；tasks T03 输出 `playground.spec.ts（step 2/3/4/6）`
- **核对**:
  - `spark-ui/apps/chat/src/app/router/router.tsx:13`：`...(env.DEV ? [{ path: 'dev/schema', … }] : [])`；`env.DEV` 来自 `import.meta.env.DEV`（`shared/config/env.ts`）。`vite preview` 服务的是 `vite build` 产物，`DEV=false`，该路由不注册 → 命中 `not-found`。
  - 现行 `e2e-frontend.mjs:26` 默认 `BASE=http://localhost:5173`（dev server），README:188 亦用 dev 端口。step 2（5 条）、3（4 条）、4（4 条）、6（6 条）共 19 条断言全部依赖 `/dev/schema`。
- **问题**: 按 spec 实现，54 条中 19 条在 `vite preview` 下必失；验收 4「54 passed」不可达。
- **建议**: `webServer.command` 改为 dev server（`pnpm --filter spark-chat exec vite --port 5173 --strictPort`，`baseURL` 5173；`predev` 已守护 core dist），与现行 e2e 基线一致；prod 产物的 console.error 校验继续由 deploy-verify 的 `preview-console` 负责。若坚持 preview，则必须在 spec 内声明「step 2/3/4/6 改用 `vite build --mode development` 产物」并说明它不是生产 bundle——不推荐。同时把 `SPARK_BACKEND` 注入方式改为对 `server.proxy`（已有同一 `proxy` 对象，无需改 `vite.config.ts`）。

### M-2 ｜ MUST FIX ｜ 「不改任何 check」与「161/161」互斥：3 条断言依赖不存在的日志

- **位置**: spec §2.1 末段、§3 第 1 条、§6.2、§7 第 2 行（「不改断言，改 fake」）；tasks T01 验收、T02「不改任何 `check`」
- **核对**（`grep -rn 'source=\|route runId' spark-rooter --include=*.java` 在 `src/main` 下**零命中**；最近一次 `backend.log` 中 `route runId` 计数 0）:

  | 行 | 断言 | 依赖的日志 | 真模型下 |
  |---|---|---|---|
  | `e2e-backend.sh:311` | `⑳ source=memory logged` | `runId=… source=memory` | **也失败**（规则时代日志，`refactor-llm-planner-domain-free` 已删） |
  | `e2e-backend.sh:337` | `㉒' source=memory(ordinal) logged` | `source=memory(ordinal)` | **也失败** |
  | `e2e-backend.sh:398` | `⑤ route decisions logged ≥3` | `route runId=.* source=` | **也失败** |
  | `e2e-backend.sh:166`（仅 LIVE） | `③a route by model` | `route runId=… domain=refund source=model` | **也失败**（不在离线 161 内，但 spec 声称语义不变） |

  另 1 条按 §2.1 规则表实现会失败但真模型可过：
  | `e2e-backend.sh:303` | `⑲ 最近 100 单 → 29 rows` | 规则表写「`最近 N 单` → `limit=N`」；`OrderTools.java:87` `max = 50` → `PlanValidator.assertValueMatches` 抛 `TOOL_SELECTION_INVALID`，fake 无 LlmPlanner 的二次重试 → run.failed | 真模型被 prompt 规则 2 约束会填 ≤ 50 |

- **问题**: 前三条无论谁当规划器都必失，「改 fake 不改断言」的唯一出路是让 fake 伪造 `route … source=memory` 日志——这是用测试替身伪造内核审计输出，不可接受（spec §7 自己也否定了这条路）。第四条是规则表本身的缺口。
- **建议**:
  1. spec §3 非目标改为「不改 `check` 的**用例编号与语义**；删除 3 条已随规则规划器下线而失实的日志断言（311 / 337 / 398），并同步 ③a（166）」，同时把它们的验证意图改写为**现有日志**可承载的断言：`decision runId=<id> kind=Planned planner=fake-e2e`（`RunOrchestrator.java:174`）与 `clarification screen runId=… entity=order`（`:538`）。验收数值相应改为 `158 passed`（README:186 一并改）。
  2. §2.1 规则表 `最近 N 单` 改为「`limit = min(N, inputSchema.maximum)`（模仿模型遵守 schema）」，或在 fake 内对 integer 参数按 `inputSchema` 的 `maximum/minimum` 夹紧。
  3. 把「对照 161 条 check 找出仍失败者」的结论写进 spec（本表即是），不要留给编码阶段现场发现。

### M-3 ｜ MUST FIX ｜ `tsconfig.node.json` 无 DOM lib，e2e 的 `page.evaluate` 回调必报错

- **位置**: spec §2.2 第 2 段（`tsconfig.node.json` include 加 `e2e/**/*.ts`）；tasks T03 输出 / 验收（`pnpm -C spark-ui run ci` 0）
- **核对**: `spark-ui/tsconfig.node.json` = `{ lib: ["ES2023"], types: ["node"], include: [两个 vite.config.ts] }`，覆盖了 base 的 `["ES2022","DOM","DOM.Iterable"]`。迁移后的 spec 必然含 `page.evaluate(() => document.querySelectorAll(...))`、`window.__sparkSeen`、`e.className`、`x.disabled`（原脚本 :58 / :122–127 / :175 / :233）。`skipLibCheck` 只跳过 `.d.ts`，用户代码里的 `document` / `window` / `HTMLElement` 会报 `Cannot find name`。`window.__sparkSeen` 还需 `declare global` 增补。
- **问题**: 验收 7「typecheck 覆盖 e2e 且退出 0」按文不可达。
- **建议**: 新增 `spark-ui/e2e/tsconfig.json`：`extends ../tsconfig.base.json`，`compilerOptions.types: ["node"]`（保留 base 的 DOM lib），`include: ["./**/*.ts", "../playwright.config.ts"]`，附 `e2e/global.d.ts` 声明 `Window.__sparkSeen`；根 `typecheck` script 追加 `tsc -p e2e/tsconfig.json --noEmit`；`tsconfig.node.json` 不动。oxlint override `e2e/**` 需同时 `env: { node: true, browser: true }`。

### M-4 ｜ MUST FIX ｜ deploy-verify 在无模型机器上仍 8/12，spec 目标与非目标冲突且未明示

- **位置**: spec §1 第 1 条（「上两个 change 的阶段 7 都因此只能部分通过」）、§2.2 末（「deploy-verify.sh 不改逻辑」）、§3 第 1 条、§6.6
- **核对**: `deploy-verify.sh:30` 无 `--spring.profiles.active` → fake 不装配 → `UnavailablePlanner`（`LlmFactory.java:45–47`）→ `:43 event sequence`、`:47 confirm reaches run.completed`、`:49 run-summary state`、`:50 backend.log has this run` 4 条必失，`:45` 的 `TOKEN=` 抽取还会抛 python 异常。§6.6 只验收 `preview-console`，对这 4 条沉默。`dev-workflow.md` 阶段 7 门禁明确要求「示例 Run 走通至 `run.completed`」。
- **问题**: spec 既以「阶段 7 部分通过」为动机，又把 deploy-verify 列为非目标，结果阶段 7 门禁在无模型机器上依旧不可达，且读者不知道这是有意为之。
- **建议**（择一并写进 spec）:
  - **推荐**: deploy-verify 保持「生产形态启动」验 boot / health / preview / console；「§3 端到端一条 Run」段落改为：有 `SPARK_LLM_API_KEY` 时按现状跑；无模型时以 `--spring.profiles.active=e2e` **再起一个**实例只跑这一段（或整脚本在无 key 时自动加 e2e profile，并在 `preview_report.md` / stdout 明确打印 `planner=fake-e2e`），使阶段 7 门禁离线可达且报告不掩盖规划器来源。改动量小于 10 行。
  - 备选: 明示「deploy-verify 验生产形态，Run 段需要真模型；无模型时该 4 条按 `③` 的方式 `skipped (no model)`」，并在 `dev-workflow.md` 阶段 7 门禁加注「无模型环境以 e2e-backend 的 fake 链路替代」。这条会削弱阶段 7，只作次选。

---

### S-1 ｜ SHOULD ｜ 宿主直接依赖 `runtime.infra.llm`，spec 应明示这是 SPI 面的一部分（或收敛）

- **位置**: spec §2.1 第 2 段、§7 第 3 行
- **核对**: `LlmClient` 在 `runtime.application.port`（public interface）；`PlanDraft`（public record）、`PlanValidator.validate` / `missingEntity`（public static；`missingEntity` 实际是 **package-private** `static Optional<String> missingEntity(...)`，`PlanValidator.java:203`——spec「三者均 public（已核对）」**不成立**）。`ToolDisplayNames`、`ToolMetaRegistry`、`ConfirmationRecheck` 均 public，starter 已把它们注册为 Bean，可注入。`check-module-deps` 的 `PLATFORM_SRC` 与 `DOMAIN_WORDS` 不扫 `examples/host-demo`，验收 8 成立。
- **问题**: （a）`missingEntity` 可见性核对有误，host-demo 无法调用；（b）宿主 import `com.sparkrooter.runtime.infra.llm.*` 违背 `backend-standard.md` §4「跨模块只依赖对方 api 包」的精神（虽该条针对平台模块间）。此外 LlmPlanner 的 dispatch（none / clarify / plan、`EntityMissing → Clarify`、`replyOr` 默认文案）是私有逻辑，fake 若要「同一套校验路径」必须复制约 40 行。
- **建议**: 在 runtime 开放一个最小公共入口（如 `PlanValidator.decide(PlanDraft, PlanRequest, ToolDisplayNames, ToolMetaRegistry, Set<String> trustedOnlyArgs, SchemaValidator) : Decision`，把 LlmPlanner 的 switch + `EntityMissing` 捕获搬进去，LlmPlanner 与 fake 共用），并把 `missingEntity` 改 public。spec §7 第 3 行改为「需在 runtime 开放最小 API」，并列入 tasks（属 spark-rooter 端、先于 T01）。

### S-2 ｜ SHOULD ｜ `agent-safety.md` §2 需与 fake 共存的措辞

- **位置**: spec §2.3（只改 `backend-standard.md` §7）
- **核对**: `agent-safety.md` §2：「未配置模型时 `UnavailablePlanner` 直接失败，不做规则兜底」。宿主 e2e profile 下装一个按关键词选工具的规划器，字面上就是「规则兜底」。
- **建议**: §2 追加一句「测试替身（fake planner）只允许在宿主工程内以 profile 隔离装配，生产 profile 下不得存在；替身产出的草案同样必须经 `PlanValidator`」。与 `backend-standard.md` §7 的新句互相呼应。

### S-3 ｜ SHOULD ｜ T01 / T03 超 0.5 天，建议拆分

- **位置**: tasks T01、T03
- **核对**: T01 = 8 类消息形态 × 上下文解析（记忆实体 / `lastRowIds` 序数 / `pendingMessage` 动词继承 / 当前动词优先）× 澄清 × 与 161 条断言逐条对齐 × 启动 WARN × README，且要处理 M-2 揭示的断言清理与 S-1 的 runtime API 开放；T03 = Playwright 配置 + 54 条断言迁移 + 3 个 spec 文件 + gitignore / tsconfig / oxlint + M-1 / M-3 的修正。
- **建议**: T01 → T01a（runtime 开放 `decide` API + fake 骨架 + 单步形态：none / list / product / logistics / detail，跑通对应用例）、T01b（上下文解析 + 澄清 + 退款 / 售后 / 删除链 + 全量 e2e-backend 全绿 + 断言清理）；T03 → T03a（Playwright 接入 + `playground.spec.ts` step 2/3/4/6 + e2e tsconfig）、T03b（`chat-flow.spec.ts` + `inline-intents.spec.ts`）。依赖链相应调整。

### S-4 ｜ SHOULD ｜ `preview-console.mjs` 依赖来源：选 `.harness/package.json` 直接声明

- **位置**: spec §2.2 第 3 段；tasks T04（二选一未定）
- **核对**: `.harness/package.json` 描述「Harness 自用脚本的依赖」，`puppeteer-core` 本就在其 devDeps —— 脚本自身依赖放这里符合既有约定；`createRequire` 指向 `spark-ui/node_modules` 会让 `.harness` 依赖 `spark-ui` 的安装树，且 pnpm 严格布局下 `spark-ui/node_modules/playwright` 不一定存在（`playwright` 是 `@playwright/test` 的传递依赖）。浏览器缓存默认在 `~/Library/Caches/ms-playwright/`（当前只有 `chromium-1161`；`playwright-core@1.63.0` 的 `browsers.json` 要求 `chromium 1243`，已核实），**同版本**的 `playwright` 与 `@playwright/test` 共用同一 `chromium-1243` 目录，不会二次下载。
- **建议**: `.harness/package.json` devDeps `playwright: 1.63.0`（与 spark-ui 的 `@playwright/test` **精确同版本**，并在 spec 写明「两处版本必须一致，否则触发第二份 chromium 下载」）；`harness-doctor` 可加一条版本一致性检查（Hashimoto）。

### S-5 ｜ SHOULD ｜ `e2e-frontend.sh` 前置缺「前端已构建」

- **位置**: spec §2.2 第 3 段「前置 host-demo.jar 已构建、`playwright install chromium` 已执行」
- **核对**: 若按 M-1 改用 dev server，`predev` 只守护 core dist；若仍用 preview，则需 `apps/chat/dist`。两种情况都要求 `pnpm -C spark-ui build:core`（或整 `build`）先跑过。
- **建议**: 前置补「`pnpm -C spark-ui build` 已执行（或 `pnpm -C .harness run ci` 已通过）」，脚本启动前 `[ -d spark-ui/packages/core/dist ] || exit 2` 给出可读错误。

---

### L-1 ｜ LOW ｜ `@Profile` 写法前后不一致
- **位置**: spec §2.1 第 1 条写 `@Profile("e2e")`，第 4 条写 `@Profile({"e2e","e2e-ttl"})`；tasks T01 用后者。统一为后者。

### L-2 ｜ LOW ｜ `PlanValidator.validate` 不在 `steps` 为空时走 clarify
- **位置**: spec §2.1 规则表第 3 行「`action=clarify`，steps 放目标工具（args 缺 orderId）」
- **核对**: `LlmPlanner` 的 clarify 分支调用的是 `missingEntity`（不调 `validate`）；`missingEntity` 先看 `missing[]`，再从 `steps[0]` 的 `required` 实体参数反推。规则表要求同时给 `missing` 与 `steps` 是安全的冗余，保留即可；但注意 `missing[].entity` 必须是 `order`（英文类型名），`normalizeEntityType` 才能命中。

### L-3 ｜ LOW ｜ 验收 3 的日志片段
- **位置**: spec §6.3「含 `planner UNAVAILABLE`」
- **核对**: `LlmFactory.java:46` 实际文案 `…not fully set; planner UNAVAILABLE — every request will fail…`，`grep 'planner UNAVAILABLE'` 可命中；`planner=fake-e2e` 由 `PlanSelfCheck.java:53` 与 `RunOrchestrator.java:174/199` 输出，验收 2 成立。

### I-1 ｜ INFO ｜ 数值核对
- e2e-backend：全部 `check` 168 条（含 3 处循环展开：`:43`×12、`:47`×2、`:382`×5），LIVE-only 7 条（`:166,167,388–390,395,396`）、离线-only 2 条（`:369,370`）→ 离线 **161** ✓，LIVE 166。M-2 落地后离线为 **158**。
- e2e-frontend.mjs：`check`/`checkTrue` 调用 49 处，step 6 三条在 1280 / 375 各执行一次 → **54** ✓。
- deploy-verify：12 条 ✓（README:187）。
- `@playwright/test@1.63.0` `engines.node >=20`，本机 node v20.20.2 ✓；`SPARK_BACKEND` 经 `pnpm run e2e` → Playwright `webServer` 子进程继承 `process.env` → `vite.config.ts:9` 读取 ✓（dev / preview 用同一 `proxy` 对象）。
- T02 引用的行号（15、162–170、364–371）与脚本一致 ✓。
- `spark-ui/.gitignore` 已有 `dist` / `node_modules`，需新增 `e2e-report/`、`test-results/` ✓（spec 已列）。

---

## 2. plan 必查项

| 项 | 结果 |
|---|---|
| 「非目标」存在且非空 | ✓（但与 §1 / §6 冲突，见 M-2 / M-4） |
| 每条验收可被命令 / 断言校验 | ✓ 形式上可校验；4 / 7 / 6 按文不可达（M-1 / M-3 / M-4） |
| 风险章节 ≥1 失败模式 + 缓解 | ✓ |
| task 标注所属端；contracts task 前置 | ✓（契约影响 NONE，无 contracts task） |
| 跨端结构列契约文件 | N/A |
| 每个 task ≤ 0.5 天 | ✗ T01 / T03（S-3） |

## 3. 回退

`REVISION REQUIRED` → 回阶段 1 修订 spec / tasks（第 1 轮，上限 3 轮）。
