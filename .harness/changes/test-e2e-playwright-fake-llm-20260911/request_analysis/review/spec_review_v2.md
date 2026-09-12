# Spec Review v2 — test-e2e-playwright-fake-llm-20260911

- **mode**: plan
- **评审对象**: `request_analysis/spec.md`（v2）、`request_analysis/tasks.md`（v2）
- **依据**: `expert-reviewer/SKILL.md`（plan 必查项）、`rules/{project-structure,backend-standard,agent-safety,contracts,dev-workflow}.md`
- **独立性**: 读 v1 评审仅为确认 4 条 MUST FIX 的处置，每条均回到源码/脚本复核，未采信 v2 的「已修订」声明。
- **verdict**: **REVISION REQUIRED**（MUST FIX 1 条）

> 说明：本轮原计划用多 agent 工作流并行评审，7 个 subagent 全部因 API 渠道限制（`400 专用渠道限制`）失败，工作流返回的 `APPROVED` 是空结果集算出的假值，已弃用。本文为主 agent 逐条手工核对的结果。

---

## 0. v1 MUST FIX 复核

| v1 条目 | 处置 | 证据 |
|---|---|---|
| M-1 `vite preview` 下 `/dev/schema` 不存在 | **已解决** | spec §2.4 改用 dev server（5173）。核对 `apps/chat/vite.config.ts:10–14`：`proxy` 为独立 const，`server.proxy` 与 `preview.proxy` 共用同一对象 → `SPARK_BACKEND` 注入确实无需改配置，spec 该声明成立 |
| M-2 3 条断言依赖不存在的日志 + 「不改 check」矛盾 | **部分解决** | 失实性已核实：`spark-rooter/**/src/main` 下 `grep 'source=memory\|route runId'` 零命中。§2.3 的处置方向（换验证手段、保留用例编号）正确。但**断言总数 158 算错**，见 M-1 |
| M-3 `tsconfig.node.json` 无 DOM lib | **已解决** | spec §2.4 改为新增 `e2e/tsconfig.json` extends `tsconfig.base.json`，`tsconfig.node.json` 不动。方向正确 |
| M-4 deploy-verify 无模型仍 8/12 | **已解决** | spec §2.5 采纳「无 key 自动加 e2e profile」推荐方案。核对 `deploy-verify.sh:43` 期望序列为两次 `tool.*` 后 `confirmation.required`，对应 `RefundTools.java:153` 的 `@SparkPrerequisite({"refund.eligibility.check","refund.preview"})` 两个只读前置 —— fake 的退款链与该序列一致，不冲突 |

---

## 1. 逐条意见

### M-1 ｜ MUST FIX ｜ 离线断言总数 158 算错；161 是「全部 check 展开数」而非任何一种环境的实跑数

- **位置**: spec §2.3 末段、§6.2（验收「158 passed」）、§1（「161 条离线断言」）；tasks T02 验收
- **核对**（命令可复现）:

  ```
  grep -c 'check "' .harness/scripts/e2e-backend.sh          → 145（字面行数）
  循环展开：:42 的 for 12 元素 → +11；:46 的 for 2 元素 → +1；:381 的 for 5 元素 → +4
  展开后总数 = 145 + 16 = 161
  ```

  **161 是「所有 check 全部执行」的上限，不是离线实跑数。** 三个条件块各自扣减：

  | 块 | 守卫 | 含 check | 三变量全空时 |
  |---|---|---|---|
  | `:163–168` ③ | `[ "$LIVE_LLM" = 1 ]` | 2 | 不执行 |
  | `:365–372` ⑥ | `LIVE_LLM=1` 时 skip，else 执行 | 2（在 else） | **执行** |
  | `:386–392` | `[ -n "${SPARK_LLM_BASE_URL:-}" ]` | 3 | 不执行 |
  | `:393–397` | `[ "$LIVE_LLM" = 1 ]` | 2 | 不执行 |

  三种环境的实跑数：

  | 环境 | 实跑 check 数 |
  |---|---|
  | 三变量全空（本机、CI 默认） | **154** |
  | 只设 `BASE_URL`、无 `API_KEY` | 157 |
  | 全设（LIVE） | 159 |

- **问题**:
  1. v1 评审 I-1 把 LIVE-only 数成 7 条（`:166,167,388–390,395,396`），但 `:388–390` 的守卫是 `[ -n "$SPARK_LLM_BASE_URL" ]` 而非 `LIVE_LLM`，只设 BASE_URL 不设 KEY 时会执行。v2 沿用了这个错误基数。
  2. spec §1 说「161 条离线断言」、§6.2 验收「158 passed」——三变量全空时实际应为 **154 - 0 = 154**（§2.3 的 4 条改写不减少条数，`:311`/`:337`/`:398` 是改写不是删除，`:166` 本就是 LIVE-only 不计入离线）。**158 与 154 差 4，验收标准直接不可达。**
  3. 更根本的问题：**断言数随环境变量组合变化**，把一个固定数字写进验收标准本身就脆弱。
- **建议**（择一）:
  - **推荐**：验收改为「`0 failed` 且退出码 0」，断言总数只作参考记录（spec 与 README 写「三变量全空 154 / LIVE 159」并注明口径）。这既可程序化校验又不受环境影响。
  - 次选：保留数字但改为 154，并在 spec 注明「指三变量全空环境；只设 BASE_URL 为 157，LIVE 为 159」。
  - 同时修正 §1 的「161 条离线断言」表述，与 README:186 一并改。

### S-1 ｜ SHOULD ｜ 验收「以实测输出为准」等于没有断言

- **位置**: spec §2.3 末「spec 以实测输出为准，验收写 158 并允许 T02 修正为实测值并同步 README」；§6.2 同款措辞；tasks T02 验收
- **核对**: `dev-workflow.md` 阶段 1 门禁要求「验收标准全部可程序化校验」；`expert-reviewer/SKILL.md` plan 必查项同款。「允许修正为实测值」意味着任何实测结果都算通过。
- **问题**: 这条措辞把一个可校验断言变成了自我实现的预言，与门禁精神冲突。它的存在也正是 M-1 没被及早发现的原因——spec 作者知道 158 不确定，于是加了免责条款而不是去数清楚。
- **建议**: 采纳 M-1 的推荐方案（验收 `0 failed`）后，删除这两处「以实测为准」措辞。断言总数写进 spec 时给出计算式与命令，而非一个裸数字。

### S-2 ｜ SHOULD ｜ Playwright `webServer` 跑 `vite` 绕过 `predev`，core dist 缺失时 e2e 失败信息不可读

- **位置**: spec §2.4 第 1 段（webServer 起 `vite --port 5173 --strictPort`）；tasks T03a
- **核对**: `apps/chat/package.json` 的 `predev` 指向 `./scripts/ensure-core-dist.mjs`，`pnpm dev` 会触发；但 Playwright 的 `webServer.command` 若直接写 `vite ...` 则绕过 pnpm 生命周期钩子。spec §2.4 第 3 段已在 `e2e-frontend.sh` 里加了 `[ -d spark-ui/packages/core/dist ] || exit 2`（S-5 的落实），但**直接跑 `pnpm -C spark-ui run e2e` 时没有这层保护**。
- **建议**: `webServer.command` 用 `pnpm --filter spark-chat run dev`（走 predev），或在 `playwright.config.ts` 的 `globalSetup` 里加同样的 dist 存在性检查。tasks T03a 的输出里明确写清楚用哪种。

### S-3 ｜ SHOULD ｜ 5173 端口冲突：`strictPort` 会让本机开着 dev server 的开发者直接失败

- **位置**: spec §2.4 第 1 段
- **核对**: `vite.config.ts:38–39` 的 `server.port: 5173, strictPort: true`。e2e-backend / deploy-verify 都因 8080 冲突改用了 `SPARK_PORT=8091` 的模式，前端这里没有对应机制。
- **建议**: `playwright.config.ts` 的端口从环境变量读（如 `SPARK_FRONT_PORT`，默认 5199 避开 5173），`baseURL` 与 `webServer.url` 同源。现行 `e2e-frontend.mjs` 的 `SPARK_FRONT_BASE` 也是这个用意（README:188 用的就是 5199），迁移时不要丢掉。

### S-4 ｜ SHOULD ｜ ⑳ / ㉒' 改用 `decision … kind=Planned` 后，验证意图确有减弱，spec 应明示

- **位置**: spec §2.3 表格第 1、2 行（「验证意图不变」）
- **核对**: 原 `⑳ source=memory logged` 验证的是「实体来自会话记忆补位」，`㉒' source=memory(ordinal)` 验证「序数指代解析成功」。换成 `decision runId=… kind=Planned planner=fake-e2e` 后，只能证明「产出了可执行计划」，**无法区分实体是来自记忆补位还是模型从原话直接提取**。
- **问题**: spec 写「验证意图不变」不准确。不过这两个用例的**结果断言**（下一条 check 验 `tool.selected` 的 toolId 与 args 里的 orderId）其实已经覆盖了「补位成功」的实质——因为原话里确实没有 ID，能选对工具并填对 ID 就说明补位生效了。所以验证强度损失有限，但措辞要诚实。
- **建议**: §2.3 表格的「处置」列改为「改用 `decision … kind=Planned` 证明产出可执行计划；实体补位的实质验证由同用例的 `tool.selected` args 断言承担（原话无 ID 却填对 ID 即补位生效）」。不要写「验证意图不变」。

### L-1 ｜ LOW ｜ `e2e/tsconfig.json` 与根 `typecheck` 的 `playwright.config.ts` 重复覆盖

- **位置**: spec §2.4 第 2 段（`include: ["./**/*.ts", "../playwright.config.ts"]`）
- **核对**: `tsconfig.node.json` 的 include 是 `["apps/chat/vite.config.ts", "packages/core/vite.config.ts"]`，不含 `playwright.config.ts`，所以无重复。spec 声明「`tsconfig.node.json` 不动」成立。
- **建议**: 无需改动，此条仅记录核对结论，供编码阶段免于重复排查。

### L-2 ｜ LOW ｜ T03a 「可与 T01b 并行」需确认 playground 用例不依赖后端

- **位置**: tasks T03a 依赖说明
- **核对**: `e2e-frontend.mjs` 的 step 2/3/4/6 走 `/dev/schema?example=…`，从 `@contracts` 直接 import 示例 JSON 渲染，不发后端请求。所以并行成立。但 T03a 的**验收**写了「后端可起」，措辞多余。
- **建议**: T03a 验收删掉后端相关前置，明确写「playground 用例不依赖后端，可在 T01b 完成前跑」。

### I-1 ｜ INFO ｜ 已核对成立的事实

- **T00 分层无问题**：`PlanValidator.java:9` 已 `import com.sparkrooter.runtime.application.port.LlmClient`，新增 `decide` 不引入新的 import 方向；`check-module-deps.mjs` 的 `peerRule` 只管 runtime/registry/gateway 三模块之间，同模块内 `infra → application` 不在检查范围。
- **limit 夹紧方案成立**：`OrderTools.java:87` `max = 50`；`orders.json` 30 条种子，`OrderTools.java:163` 过滤 `status != DELETED` 后 29 条 → `limit=50` 返回 29 行，与 `e2e-backend.sh:303` 期望的 29 一致。
- **M-4 步数不冲突**：`deploy-verify.sh:43` 与 `e2e-backend.sh:89/167/277` 用的是同一个期望序列（两次 `tool.*` + `ui.replace` + `confirmation.required`），对应 `RefundTools.java:153` 的两个只读前置；`refund.create` 在确认后执行，不在该序列内。
- **v1 的 `missingEntity` 可见性指正属实**：`PlanValidator.java:203` 确为 package-private，v2 已在 §2.1 改为「改 public」。
- **`intent-request.example.json` 的 message** 为 `帮我把订单 10001 退款`，落在 spec §2.2 规则表第 2 行的「退款」分支，M-4 的覆盖成立。

---

## 2. plan 必查项

| 项 | 结果 |
|---|---|
| 「非目标」存在且非空 | ✓ 且已消除 v1 指出的与 §1/§6 冲突（§3 第 1、2 条明确了「换验证手段不算改语义」「改启动参数不算改 check」） |
| 每条验收可被命令 / 断言校验 | ✗ §6.2 的「158 passed」不可达（M-1）；「以实测为准」措辞使其失去约束力（S-1）。其余 7 条可校验 |
| 风险章节 ≥1 失败模式 + 缓解 | ✓ 8 条，且新增 T00、T05 两处风险均有对应条目 |
| task 标注所属端；contracts task 前置 | ✓ 契约影响 NONE，无 contracts task |
| 跨端结构列契约文件 | N/A |
| 每个 task ≤ 0.5 天 | ✓ v1 的 T01 / T03 已拆为 T01a/b、T03a/b；T00 为纯提取、T02 为 4 行改写、T05 约 6 行，均在范围内 |

## 3. 回退

`REVISION REQUIRED` → 回阶段 1 修订 spec / tasks（第 2 轮，上限 3 轮）。

修订范围很小：M-1（断言数改为 `0 failed` 口径 + 修正 §1 的 161）、S-1（删「以实测为准」）、S-2/S-3（webServer 命令与端口）、S-4（措辞）、L-2（T03a 验收措辞）。无需重做设计。
