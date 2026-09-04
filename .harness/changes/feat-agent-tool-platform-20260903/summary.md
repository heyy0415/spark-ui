# Change Summary: feat-agent-tool-platform-20260903

| 字段 | 值 |
|---|---|
| Change ID | feat-agent-tool-platform-20260903 |
| 类型 | feat |
| 状态 | DEPLOY VERIFY（阶段 5/6 完成，阶段 7 进行中） |
| 负责人 | Platform Owner Agent |
| 涉及端 | contracts / backed / fronted / harness |
| 起止时间 | 2026-09-03 ~ — |
| Spec | [request_analysis/spec.md](request_analysis/spec.md) |
| Tasks | [request_analysis/tasks.md](request_analysis/tasks.md) |

## 阶段进度表

| # | 阶段 | 状态 | 评审轮次 | 产出 | 时间 |
|---|---|---|---|---|---|
| 1 | 需求分析 | DONE（v3.1） | — | spec.md（7 章）, tasks.md（25 task） | 2026-09-03 |
| 2 | 需求评审 | DONE | 3/3 | v1 → v2 → v3 **APPROVED**（0 MUST FIX，7 SHOULD 已吸收为 v3.1）；HITL ② 用户确认「先完成 Phase B 之前的任务」 | 2026-09-03 |
| 3 | 编码实现 | DONE | — | Phase A：9 schema + 20 example。Phase B（T05a–T11）：`e2e-backend.sh` 24/24。Phase C（T12–T17b）：`fronted ci` 0、`verify-examples` 16 OK、`e2e-frontend.mjs` 21/21、5 张截图。Phase D（T18）：`wiki/api-contracts.md`、`fronted/README.md`、本文契约段已同步；`doctor` 0、`run ci` 见下 | 2026-09-03 ~ 2026-09-04 |
| 4 | 编码评审 | DONE | 2/2 | `code_review_v1.md`（机械项全绿）→ `code_review_v2.md` **REVISION REQUIRED**（3 MUST FIX：入站契约校验缺失 / 确认并发破坏 Run 状态 / 确认金额未重校验；12 SHOULD）→ 回修 + spec v3.2 回写 → `code_review_v3.md` **APPROVED**（0 MUST FIX，6 SHOULD：N1–N3 已修，N4/N6 推迟到下一 change，N5 在阶段 7 解决）。e2e-backend 47/47、e2e-frontend 21/21、`run ci` 0。**等待 HITL ③** | 2026-09-04 |
| 5 | 代码推送 | DONE | — | HITL ③ 用户「继续」；`git init`（main）+ 根 `.gitignore`；首次提交 `a6c7a03`（309 文件，lefthook pre-commit / commit-msg 通过）；本轮阶段 5–7 产物随后追加提交。尚无远端 | 2026-09-04 |
| 6 | CI 验证 | DONE | — | `pnpm -C .harness run ci` 四段 0 → `ci_result/ci_summary.md`（bundle baseline：最大 chunk 84 kB gzip；app.jar 34.4 MB） | 2026-09-04 |
| 7 | 部署验证 | IN PROGRESS | — | 新增 `scripts/deploy-verify.sh` + `preview-console.mjs`（后端 health、vite preview 4173 代理、经预览走通一条 Run 至 `run.completed`、预览页 console.error、体积报告，一次性冻结 `deployment/`）。首次运行 8080 被 IDEA 手动实例占用 → 脚本增加端口独占前置检查（退出码 2）。**等待用户停掉 IDEA 实例后重跑** | 2026-09-04 |
| 8 | 用户确认 | TODO | — | — | — |

## 契约变更
新增 9 个（`.harness/contracts/`，20 个示例全部通过 `check-contracts`）：

| 文件 | 方向 | 使用方 |
|---|---|---|
| `error-response.schema.json` | 后端 → 前端 | 所有 4xx/5xx |
| `intent-request.schema.json` | 前端 → Runtime | `POST /agent/runs` |
| `action-request.schema.json` | 前端 → Runtime | `POST /agent/runs/{runId}/actions/{actionId}` |
| `run-summary.schema.json` | Runtime → 前端 | `GET /agent/runs/{runId}` |
| `ui-schema.schema.json` | Runtime → 前端 | `ui.replace` / `ui.patch` 载荷，7 个白名单组件 |
| `sse-events.schema.json` | Runtime → 前端 | 10 种事件 |
| `tool-manifest.schema.json` | 领域服务 → Registry | 注册 |
| `tool-search.schema.json` | Runtime ↔ Registry | 发现 |
| `tool-invoke.schema.json` | Runtime ↔ Gateway | 执行 |

阶段 3 实际使用但超出 spec §5 的两处：`run-summary.failureCode`、`error-response.details[]`（待阶段 4 评审后回写 spec）。

## 经验沉淀
- 阶段 1 v1 有 14/18 task 漏「输入」要素 → request-analysis Skill 的 checklist 已有此项但未被执行；后续考虑在 harness-doctor 中加 tasks.md 六要素机械校验。
- 阶段 1 v1 用"放宽 lint 例外"解决 ThemeProvider 的 antd import → 方向错误，正确做法是把封装挪进 `shared/ui/theme/`；已修 `05-styling-spec.md`。
- 阶段 1 v1 未定义 `ToolHandler` 归属，按字面会形成 Maven 循环依赖 → 新增 `platform-spi` 模块并写入 `project-structure.md` §2，`check-module-deps` 扩展到 gateway。
- 阶段 1 v1 后端 task 验收依赖可运行 app 但 app 在末尾才产出 → 拆 T05a 先出最小可启动骨架。
- 阶段 1 v2 的验收条与设计互斥（platform-spi 用 JsonNode 却 grep 禁 Jackson）→ 写验收时必须逐条对照设计段落；已按 project-structure §2 "无 Spring 依赖" 拆分 grep。
- 阶段 1 v2 漏了 `GET /agent/runs/{runId}` 的契约 → 新增 run-summary；教训：每个端点在 §5 表里必须有一行，评审 checklist "涉及跨端结构的 task 列出对应契约文件" 应扩展为 "每个端点对应一个 schema"。
- 阶段 1 v2 领域服务注册机制未定 → 引入 `ToolManifestSource` 端口，由 Registry 拉取，保证 domains ↛ registry。
- 阶段 2 v3 指出金额单位在 coding-standard（分）与 contracts / domain-model（元）不一致 → 统一为"元、两位小数"，已改 coding-standard §3 与 coding-skill。
- 阶段 2 三轮共 46 条意见，其中 6 条 MUST FIX 全部源于"设计段落与验收段落各写各的"→ 建议 request-analysis Skill 增加一步："每条验收标准回指其对应的设计段落编号"。
- 阶段 3 Phase A：CLAUDE.md 里的门禁命令（无 `run` 的 ci 简写）与 pnpm 内置子命令冲突，实际从未执行过脚本 → 全仓改为 `run` 形式，并在 harness-doctor 加守护。教训：任何写进 L1 记忆的命令，第一次写下时就要真的跑一遍并看退出码。
- 阶段 3 Phase A：JSON Schema `if/then` 在 Ajv strict 下需显式 `type` 与 `properties` 声明 → 已写入 coding_report 决策 1；建议回写 `00-contract-spec.md`「必备」段。
- 阶段 3 T05a：`check-module-deps.mjs` 在真实 pom 上首次运行即 10 条误报（artifactId 取到 `<parent>`、依赖判定扫全文）→ 已修并用植入违规反向验证。教训与 Phase A 相同：门禁脚本必须在真实输入上跑过并且用一条已知违规证明它会红，否则"绿"没有意义。建议：harness-doctor 增加"每个 check-* 脚本附带一个 fixture 负例"的要求。
- 阶段 3 T06：Spring Boot 宽松绑定吞掉 Map 键 `@` 与值 `:`，权限表全空但启动无任何报错 → 改显式列表。教训：配置绑定结果必须在启动日志打印数量（已加 `permission table loaded: N principals`）。
- 阶段 3 T06：`@Order` 标在类上对 `@EventListener` 无效，selfcheck 抢在注册前跑 → 改方法级。已写进 06-backend-module-spec 待办。
- 阶段 3 T07：`check-module-deps` 第二次误报（模块分组目录名 domain 被当成 DDD 分层包）→ 判定改为直接父目录。同一脚本两次修 bug，印证"负例 fixture"建议应尽快落地。
- 阶段 3 T10c：Spring AI starter 在无 key 时拒绝启动（speech/image/embedding 自动配置全部要 key）→ 改为依赖 `spring-ai-openai` + `spring-ai-client-chat` 库并手工装配。已写入 06-backend-module-spec 踩坑清单。
- 阶段 3 T10c：Spring `SseEmitter` 输出 `event:xxx` 无空格，手工 grep 解析全部失效 → 写 `sse-parse.mjs` 与 `e2e-backend.sh` 把 §6.2 验收脚本化（24 断言）。教训：SSE 这类协议验收不能靠一次性 shell 拼接，必须有可重复脚本。
- 阶段 3 Phase C：`VITE_API_BASE_URL` 默认 `/api` 是骨架期遗留，与后端真实前缀 `/agent/runs` 不一致；vite proxy 把 SPA 路由 `/agent` 整前缀转给后端。两者都只有在浏览器里真跑主链路才能发现 → 写 `e2e-frontend.mjs`（puppeteer-core + 本机 Chrome，21 断言）把 spec §6.3.4 脚本化。教训与 T10c 相同：静态 CI 全绿 ≠ 功能可用，前端也必须有可重复的浏览器级验收。
- 阶段 3 Phase C：antd 6 改了 Select 内部 DOM 类名，验收脚本靠 `.ant-select-selector` 定位失败 → 改用契约字段名 `#reason`。教训：验收脚本应绑定契约（字段名、`data-component-id`、`data-action-id`），不要绑定第三方库内部类名。
- 阶段 3 Phase C：StrictMode 双渲染使 "console.error 恰 1 次" 这种精确计数断言在开发模式天然不成立 → 断言改为"存在且无其他 error"；spec 写验收时应避免对副作用次数做精确计数。
- 阶段 4：机械 code-review 全绿，但独立评审仍找出 3 条 MUST FIX，且三条都是"机器查不到的语义问题"：`@Valid` 让人误以为契约已在边界生效（实际 `additionalProperties` / pattern / const 全部失效）；并发确认把成功退款报告成 FAILED；模型可填 `amount` 绕过"用户确认的就是执行的"。教训：(1) Controller 的 `@RequestBody` 必须走 `SchemaValidator.bind`，建议在 harness-doctor / check-module-deps 增加 "api 包内出现 `@Valid @RequestBody` 即红" 的机械检查；(2) 任何"一次性令牌 + 状态机"的确认路径都要有并发两次的 e2e 用例；(3) 有副作用步骤的金额类参数必须列为 trusted-only，模型与前端都不得决定。
- 阶段 4：第 2 轮评审指出 `CompletableFuture.cancel(true)` 不会中断任务——第 1 轮的 S6 "修复" 只是让注释和实际行为相反。教训：涉及并发原语的修复必须写明依据（JDK 文档条目），评审 checklist 应加 "并发 / 取消 / 超时相关改动需引用 API 语义"。
- 阶段 4：两轮评审共 15 + 6 条，5 条与 `deployment/` 证据不自洽有关（日志与事件不是同一次运行）。教训：验收产物必须由**一个**脚本一次性生成并冻结，阶段 7 的 deploy-verify 以此为门禁。
- 阶段 7：deploy-verify 第一次跑在了用户 IDEA 里手动启动的后端上——health UP、自检行数 0、confirm 被拒（那个实例的内存状态不是干净的），4 项失败全是"验收对象不对"而不是代码问题。教训：任何验收脚本第一步必须确认端口归自己独占，已把该检查写进 `deploy-verify.sh`（退出码 2 并打印占用者 PID），`e2e-backend.sh` 亦应同样处理。
