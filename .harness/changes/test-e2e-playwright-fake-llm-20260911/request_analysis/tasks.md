# Tasks: test-e2e-playwright-fake-llm-20260911

编码顺序：runtime 开放 API → fake 规划器（骨架 → 全量）→ 后端 e2e 断言清理 → spark-ui（Playwright）→ harness 脚本 → deploy-verify → 文档。每个 task ≤ 0.5 天。`harness` 端指 `.harness/scripts`、`.harness/rules`、`.harness/skills`、根 README。

> **v2 修订**：新增 T00（S-1 开放 `decide`）；T01 拆为 T01a / T01b、T03 拆为 T03a / T03b（S-3）；T02 从「只改注释」扩为「清理 4 条失实断言」（M-2）；新增 T05（deploy-verify，M-4）。契约影响 NONE，无 contracts task。
>
> **v3 修订**（依据 `review/spec_review_v2.md`）：T02 验收改为 `0 failed` 口径（M-1/S-1）；T03a 的 webServer 改走 `pnpm run dev`、端口用 `SPARK_FRONT_PORT`（S-2/S-3）、依赖降为无（L-2）；T06 的 README 输出不再写死断言数。

## T00 runtime：开放 `PlanValidator.decide` 最小公共 API

- **目标**：让 fake 与 `LlmPlanner` 走同一条「草案 → 决策」路径，避免复制约 40 行 dispatch 逻辑。
- **所属端**：spark-rooter（spark-rooter-runtime）
- **输入**：`LlmPlanner.java:100–132`（switch + `EntityMissing` 捕获 + `replyOr`）；`PlanValidator.java:203`（`missingEntity` 当前为 package-private）
- **输出**：`PlanValidator` 新增 `public static LlmClient.Decision decide(PlanDraft, LlmClient.PlanRequest, ToolDisplayNames, ToolMetaRegistry, Set<String> trustedOnlyArgs, SchemaValidator)`；`missingEntity` 改 `public`；`LlmPlanner` 改为调用 `decide`（校验失败重试一次的模型侧逻辑仍留在 `LlmPlanner`）。纯提取，无行为变化。
- **验收**：`node .harness/scripts/mvn.mjs -q -B install` 退出 0（`PlanValidatorTest` 等 131 单测全过）；`git diff --stat` 显示 `LlmPlanner.java` 净减行、`PlanValidator.java` 净增行；`pnpm -C .harness run check-module-deps` 退出 0。
- **依赖**：无

## T01a FakeLlmPlanner 骨架与单步形态

- **目标**：fake 装配生效，覆盖无上下文依赖的单步用例。
- **所属端**：spark-rooter（examples/host-demo）
- **输入**：T00 的 `decide`；spec §2.2 规则表前 6 行中的单步部分；`e2e-backend.sh` 对应用例
- **输出**：`examples/host-demo/src/main/java/com/example/demo/e2e/FakeLlmPlanner.java`（`@Component @Profile({"e2e","e2e-ttl"})`，构造注入 `ToolDisplayNames` / `ToolMetaRegistry` / `SchemaValidator` / `ObjectProvider<ConfirmationRecheck>` 取 `trustedArgKeys`）；覆盖 `none`（天气）、`order.list.search`（含 status 别名与 integer 按 schema `maximum` 夹紧）、`product.list.search`、`product.detail.get`、`order.logistics.get`、`order.detail.get`；启动 WARN 一行。
- **验收**：`mvn.mjs -q -B install` 0；host-demo 重打包 0；以 e2e profile 启动后 `backend.log` 含 `planner=fake-e2e`；上述单步用例对应的 `check` 全绿（T01a 允许多步 / 上下文用例仍红）；无 profile 启动日志不含 `fake-e2e`。
- **依赖**：T00

## T01b FakeLlmPlanner 上下文解析、澄清与多步链

- **目标**：后端 e2e 在无模型环境下全绿。
- **所属端**：spark-rooter（examples/host-demo）
- **输入**：T01a 骨架；spec §2.2 规则表剩余行；`ConversationMemory` 的 `entities` / `lastRowIds` / `pendingMessage`
- **输出**：上下文解析（记忆实体 / `lastRowIds` 序数「第二个」/ `pendingMessage` 动词继承 / 当前动词优先）；`clarify` 分支（`missing=[{entity:"order"}]` 用英文类型名，见 L-2）；多步链（删除 = `order.detail.get` + `order.delete`；退款 = `refund.eligibility.check` + `refund.preview` + `refund.create`；售后 = `aftersale.list.get` + `aftersale.create`）；澄清屏点选 intent 处理；`host-demo/README.md` 一句说明。
- **验收**：`SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh` 除 T02 待清理的 4 条外全绿；`backend.log` 含 `clarification screen runId=… entity=order`。
- **依赖**：T01a

## T02 e2e-backend：清理 4 条失实断言 + 注释语义

- **目标**：删除随规则规划器下线而失实的日志依赖，脚本注释与现状一致，离线全绿。
- **所属端**：harness
- **输入**：spec §2.3 的 4 行清单（`:311` / `:337` / `:398` / `:166`）；`RunOrchestrator.java:174`（`decision runId=… kind=… planner=…`）与 `:538`（`clarification screen`）
- **输出**：4 条断言改用现有日志验证，**用例编号与验证语义不变**；注释「规则规划器 / rule mode / 规则模式」→「fake planner」；`echo "  - ③ skipped (rule mode)"` → `(no model: fake planner)`；⑥ 段标题同步。其余 `check` 一律不动。
- **验收**：`grep -n "source=memory\|route runId" .harness/scripts/e2e-backend.sh` 无命中；`grep -n "规则规划器\|rule mode\|规则模式" .harness/scripts/e2e-backend.sh` 无命中；`SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh` → **`0 failed`** 且退出 0。断言数按 spec §1 口径核对（三变量全空实测 161），不一致则在本 task 记录原因——**数字只作记录，门禁是 `0 failed`**。
- **依赖**：T01b

## T03a Playwright 接入与 playground 用例

- **目标**：Playwright 跑起来，`/dev/schema` 相关 19 条断言迁完。
- **所属端**：spark-ui
- **输入**：`e2e-frontend.mjs` step 2/3/4/6；`router.tsx:13`（`/dev/schema` 仅 DEV）；`vite.config.ts` 的共用 `proxy`
- **输出**：根 devDeps `@playwright/test: 1.63.0`；scripts `e2e` / `e2e:install`；`spark-ui/playwright.config.ts`（**webServer 用 `pnpm --filter spark-chat run dev --port $SPARK_FRONT_PORT`**——走 pnpm 生命周期以触发 `predev` 的 core dist 守护，见 S-2；端口取 `SPARK_FRONT_PORT` 默认 **5199** 避开本机 5173，`baseURL` 与 `webServer.url` 同源，见 S-3）；`spark-ui/e2e/playground.spec.ts`（step 2/3/4/6，19 条）；`spark-ui/e2e/tsconfig.json`（extends `tsconfig.base.json` 保留 DOM lib，`types: ["node"]`）+ `e2e/global.d.ts`（`Window.__sparkSeen`）；根 `typecheck` 追加 `tsc -p e2e/tsconfig.json --noEmit`；`.gitignore` 加 `e2e-report/` `test-results/`；oxlint override `e2e/**`（`env: { node: true, browser: true }`，允许 console）。`tsconfig.node.json` 不动。
- **验收**：`pnpm -C spark-ui run e2e:install` 0；`pnpm -C spark-ui run e2e -- e2e/playground.spec.ts` → 19 passed（**playground 用例从 `@contracts` 直接 import 示例 JSON 渲染，不发后端请求，无需后端在跑**，见 L-2）；`pnpm -C spark-ui run typecheck` 0；`pnpm -C spark-ui run lint` 0。
- **依赖**：无（可与 T00 / T01a / T01b 并行）

## T03b 主链路与行内指令用例

- **目标**：54 条断言迁完。
- **所属端**：spark-ui
- **输入**：`e2e-frontend.mjs` step 5（主链路）、step 7（行内指令）
- **输出**：`spark-ui/e2e/chat-flow.spec.ts`（step 5）、`spark-ui/e2e/inline-intents.spec.ts`（step 7）；选择器与断言 1:1 保留。
- **验收**：后端以 e2e profile 起在 8091 时 `SPARK_BACKEND=http://localhost:8091 pnpm -C spark-ui run e2e` → `54 passed`；`pnpm -C spark-ui run ci` 0。
- **依赖**：T03a、T01b

## T04 harness 脚本：e2e-frontend.sh 与 preview-console 换实现

- **目标**：一条命令跑前端 e2e 并把报告落进 change；摆脱本机 Chrome。
- **所属端**：harness
- **输入**：`e2e-backend.sh` 的启动 / 端口 / cleanup 模板；`preview-console.mjs`；`deploy-verify.sh:54`
- **输出**：新 `.harness/scripts/e2e-frontend.sh`（前置 `[ -d spark-ui/packages/core/dist ] || exit 2` 并提示先跑 `pnpm -C spark-ui build`，见 S-5）；删 `e2e-frontend.mjs`；`preview-console.mjs` 改用 `playwright`；`.harness/package.json` 加 devDep `playwright: 1.63.0`（**与 spark-ui 的 `@playwright/test` 精确同版本**）、删 `puppeteer-core`、`e2e-frontend` script 指向 sh；`harness-doctor.mjs` required 更新 + 新增两处 playwright 版本一致性检查。
- **验收**：`SPARK_PORT=8091 bash .harness/scripts/e2e-frontend.sh` 0 且 `$DEPLOY/e2e-frontend/index.html` 存在；spec §6.5 两条 grep 无命中；`pnpm -C .harness run doctor` 0（含版本一致性检查，故意改版本号可复现红）。
- **依赖**：T03b

## T05 deploy-verify：无模型自动 e2e profile

- **目标**：阶段 7 门禁在无模型机器上可达，且报告不掩盖规划器来源。
- **所属端**：harness
- **输入**：`deploy-verify.sh:30`（启动行）、`:42–51`（Run 段 4 条断言）
- **输出**：无 `SPARK_LLM_API_KEY` 时启动追加 `--spring.profiles.active=e2e`，有 key 时保持现状；stdout 与 `preview_report.md` 打印 `planner=<实际实现名>`。**不改任何 `check`**。
- **验收**：`SPARK_PORT=8091 SPARK_CHANGE=<本 change> bash .harness/scripts/deploy-verify.sh` → `12 passed, 0 failed` 退出 0；stdout 含 `planner=fake-e2e`；`git diff .harness/scripts/deploy-verify.sh` 不含 `check "` 行变更。
- **依赖**：T04

## T06 文档与规则

- **目标**：README / 规则 / Skill 与新脚本一致，fake 替身的边界写进规则。
- **所属端**：harness
- **输入**：spec §2.6
- **输出**：`README.md` 门禁段（e2e-frontend 命令、Playwright 自带 chromium、e2e-backend 断言数按 spec §1 口径写明三种环境而非单一数字）；`backend-standard.md` §7 一句；`agent-safety.md` §2 追加一句（S-2）；`deploy-verify/SKILL.md`、`frontend-doctor/SKILL.md` 中 Chrome / puppeteer 措辞。
- **验收**：spec §6.5 grep 无命中；`pnpm -C .harness run doctor` 0；`pnpm -C .harness run ci` 0。
- **依赖**：T05
