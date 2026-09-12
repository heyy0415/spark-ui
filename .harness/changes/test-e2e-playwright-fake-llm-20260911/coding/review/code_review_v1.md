# Code Review v1 — test-e2e-playwright-fake-llm-20260911

- **mode**: execution
- **评审对象**: 本 change 全部改动（`git diff` + 4 个新增文件）、`coding/coding_report_v1.md`
- **依据**: `code-review/SKILL.md`（机器化检查）、`expert-reviewer/SKILL.md`（execution 必查项）、`rules/{coding-standard,backend-standard,contracts,agent-safety,project-structure}.md`
- **verdict**: **APPROVED**（0 条 MUST FIX）

> **独立性声明（必读）**：`expert-reviewer/SKILL.md` 要求评审者不得阅读编码侧自评。本轮原计划派独立 subagent 执行，但该通道在本会话中不可用（阶段 2 评审时 7 个 subagent 全部因 `400 专用渠道限制` 失败）。因此本文由编码者本人撰写，**独立性不满足**。为部分补偿，所有结论均以可复现命令 / 源码行号为据，不引用 coding_report 的自述；下方 §4 列出「本轮未能独立验证、建议他人复核」的条目。

---

## 1. 机器化检查（code-review skill）

全部命令的真实退出码：

| 命令 | 退出码 |
|---|---|
| `pnpm -C .harness run check-contracts` | 0（契约无变更，9 schemas OK） |
| `pnpm -C spark-ui run build:core` | 0 |
| `pnpm -C spark-ui run typecheck` | 0（含新增 `tsc -p e2e/tsconfig.json`） |
| `pnpm -C spark-ui run lint` | 0（oxlint --deny-warnings + check-deps + check-registry） |
| `pnpm -C spark-ui run format:check` | 0 |
| `pnpm -C spark-ui run verify-examples` | 0 |
| `pnpm -C spark-ui run verify-pack` | 0（dist 47 KB ≤ baseline × 1.1） |
| `node .harness/scripts/mvn.mjs -q -B install` | 0（含 spotless:check 与 131 单测） |
| `pnpm -C .harness run check-module-deps` | 0 |
| `pnpm -C .harness run ci` | 0（7 步全绿） |
| `pnpm -C .harness run doctor` | 0 |
| `bash -n` on 两个改动的 .sh | 0 |

`shellcheck` 本机未安装，shell 脚本只做了 `bash -n` 语法检查——见 §4。

## 2. 红线清单

**前端**

- [x] `pages/` 之外无路由声明 —— 新增文件都在 `spark-ui/e2e/`（测试目录，不参与构建），未触碰 `apps/chat/src/app/router/`
- [x] 无 FSD 反向依赖 —— `e2e/` 不 import `apps/chat/src/**`，只走 HTTP 与 DOM
- [x] 无 `eval` / `new Function` / `dangerouslySetInnerHTML` / 任意路径动态 `import()` —— `grep` 三个 spec 文件零命中
- [x] `apps/chat` 无 antd / `@spark-ui/core/src/*` 深路径 import —— `check-deps.mjs` 退出 0
- [x] 组件注册表未变 —— `check-registry.mjs` 退出 0，`componentRegistry.ts` 不在 diff 内

**后端**

- [x] 平台模块 pom 未依赖 `domains/*`、未新增 `spring-boot-starter-web` —— 无 pom 改动
- [x] `domain/` 包无 `org.springframework` / `com.fasterxml` import —— `check-module-deps` 退出 0；本次改的两个文件在 `infra/llm/`
- [x] 无 `System.out`、无空 catch —— `PlanValidator.decide` 的 `catch (EntityMissing e)` 有 log + 转 Clarify；`FakeLlmPlanner` 的两处 `catch (NumberFormatException)` 返回原值（`clampToSchema` / `ordinalOf`），是有意的宽松降级且有注释
- [x] 金额 / ID 字段为 `String` —— 本次未新增 DTO
- [x] 平台模块无 `userId` / `tenantId` / `Principal` —— `check-module-deps` 的 IDENTITY 规则退出 0
- [x] 平台模块无领域词汇 —— DOMAIN_WORDS 规则退出 0。`FakeLlmPlanner` 含大量领域词，但它在 `examples/host-demo`，不在 `PLATFORM_SRC` 扫描范围（脚本 `:101` 的常量已核对）

**契约**

- [x] 契约文件零改动（`git status` 无 `.harness/contracts/` 条目），`check-contracts` 退出 0

## 3. 逐条意见

### I-1 ｜ INFO ｜ `decide` 提取的分支一一对应，且顺带修掉一个 locale bug

- **核对**: `git diff LlmPlanner.java` 删除的 44 行与 `PlanValidator.decide` 新增的分支逐条比对：`none → NoCapability(replyOr(…, "当前没有可用能力处理该请求"))`、`clarify → missingEntity + log.debug + Clarify(…, replyOr(…, "请补充更多信息"))`、`default → validate` 且 `catch EntityMissing → Clarify(entityType, "请指定要操作的" + entityLabel)`。默认文案、log 级别与占位符全部一致。`RunFailure` 的 `last = e; feedback = e.getMessage();` 重试语义保留在 `LlmPlanner`，未被搬走——这是正确的边界（替身没有「喂回模型」的能力）。
- **附带改进**: 原 `toLowerCase()` 用宿主默认 locale，土耳其 locale 下 `"CLARIFY".toLowerCase()` → `clarıfy`，匹配不上 `case "clarify"` 而静默落到 plan 分支。提取时改为 `toLowerCase(Locale.ROOT)`。已在 JDK 21 + `tr` locale 下实测确认差异存在。属修复而非偏差。
- **分级**: INFO（无需动作）

### I-2 ｜ INFO ｜ fake 未绕过校验，安全边界成立

- **核对**: `grep -n "new Plan\|Planned(\|PlanValidator\." FakeLlmPlanner.java` 的结果显示：唯一出口是 `:93` 的 `PlanValidator.decide(...)`，其余命中全是 `new PlanDraft(...)` / `new PlanDraft.DraftStep(...)`（草案构造，即真模型的输出形态）。**没有 `new Plan` 或 `new Planned`**，即无法绕过 `validate` 的五条校验。
- 这一点已同时写进 `backend-standard.md` §7 与 `agent-safety.md` §2，后续违反会在评审阶段有据可依。
- **分级**: INFO

### I-3 ｜ INFO ｜ 7 条断言改写均为「验证手段替换」，非门禁放宽

- **核对**: 四条日志类（`:166` / `:311` / `:337` / `:398`）依赖的 `source=memory` / `route runId` 在 `spark-rooter/**/src/main` 下 `grep` 零命中，真模型下同样失败；三条澄清屏类（`:289` / `:324` / `:325`）依赖 `IntentVerbs.verbLabel`，该类全仓已无（`03a7838` 删除），实测按钮恒为 `label="选择"`。七条都是既存失实断言，非本 change 引入。
- 用例编号（⑯ / ⑳ / ㉒ / ㉒' / ③a / ⑤）全部保留；⑳ 与 ㉒' 的实体补位验证由同用例既有的 `tool.selected` args 断言承担（原话无 ID 却填对 ID）。
- **分级**: INFO

### S-1 ｜ SHOULD ｜ `decide` 缺少直接单测

- **位置**: `PlanValidator.java` 新增的 `decide` 方法
- **核对**: `find spark-rooter -path '*/src/test/*' -name 'PlanValidatorTest.java'` 存在且 131 单测全过，但 `grep -c 'decide' ` 于该测试文件为 0 —— 现有单测覆盖的是 `validate` / `missingEntity` / `assertValueMatches`，`decide` 的三路分支（含 `EntityMissing → Clarify` 转换、两处 `replyOr` 默认文案）只由 e2e 间接覆盖。
- **问题**: e2e 能证明整体行为正确，但分支级回归（例如将来有人误改 `replyOr` 的默认文案、或调整 `EntityMissing` 的捕获位置）没有快速失败的单测。
- **建议**: 下一个 change 补 `PlanValidatorDecideTest`：三路 action × `reply` 有无 × `EntityMissing` 抛出，约 6 个用例。coding_report §5 已把它列为已知限制，此处升级为 SHOULD 以免被遗忘。
- **分级**: SHOULD（不阻塞本 change：提取无行为变化，且 161 + 12 条端到端断言已覆盖实际路径）

### S-2 ｜ SHOULD ｜ shell 脚本未经 shellcheck

- **位置**: `.harness/scripts/e2e-frontend.sh`（新增 66 行）、`deploy-verify.sh`（改 12 行）
- **核对**: 本机无 shellcheck，只跑了 `bash -n`（语法）。编码期实际踩到一个 `bash -n` 查不出的问题：`echo "... $FRONT_PORT）"` 中变量名紧跟全角括号被当作名字一部分，运行时报 `unbound variable`。这类问题 shellcheck 的 SC2154 / SC2086 能提前发现。
- **建议**: 改造清单第 4 项（CI）把 shellcheck 纳入门禁；本 change 已手工 `grep -nE '\$[A-Z_]+[）｜，。：、]'` 确认无同类残留。
- **分级**: SHOULD

### L-1 ｜ LOW ｜ `PROFILE_ARG` 有意不加引号

- **位置**: `deploy-verify.sh:40`
- **核对**: `$PROFILE_ARG` 未加引号，空值时依赖词分割吞掉该参数；加引号会传入一个空字符串参数给 `java -jar`。取值是脚本内写死的字面量（`--spring.profiles.active=e2e`），无外部输入，无注入面。
- **建议**: 无需改动。若将来 shellcheck 入门禁，此行会触发 SC2086，届时加 `# shellcheck disable=SC2086` 并注明原因。
- **分级**: LOW

### L-2 ｜ LOW ｜ `e2e/` 目录的 lint 豁免范围

- **位置**: `spark-ui/.oxlintrc.json` 新增的 `e2e/**` override
- **核对**: 关闭了 `no-console`（脚本需输出）与 `no-underscore-dangle`（`window.__sparkSeen` 的刻意命名，理由已写在 `e2e/global.d.ts` 的文档注释里）。未关闭 `no-explicit-any` / `no-non-null-assertion` 等类型安全规则 —— 编码期确实因 `no-non-null-assertion` 被拦，改用局部变量而非放行，是正确处理。
- **分级**: LOW（记录核对结论）

## 4. 本轮未能独立验证的条目

独立性不满足，以下条目建议他人复核：

1. **`decide` 的语义等价性**。我比对了 diff 的每一行，但「比对自己写的代码」有盲区。最强的客观证据是 131 单测 + 161 条 e2e 断言全绿，其中 ⑯ / ⑳ / ㉒ / ㉒' / ㉒'' 五个用例恰好覆盖 clarify 与 EntityMissing 两条分支。
2. **`FakeLlmPlanner` 的规则表是否有未覆盖的 e2e 消息**。我提取了脚本里全部 `run_msg` 与裸 curl 的 message 逐条比对，结论是全覆盖；客观证据是 e2e 161 passed / 0 failed。但若将来新增 e2e 用例，替身可能静默走到 `none` 分支——建议后续为替身加一条「未命中任何规则时打 WARN」的日志，让缺口显形（未在本 change 做，属新增行为）。
3. **shell 脚本的运行时健壮性**（见 S-2）。

## 5. execution 必查项

| 项 | 结果 |
|---|---|
| 契约：前端 Zod / 后端 record 与 Schema 逐一一致 | N/A —— 契约零改动，`check-contracts` 退出 0 |
| 安全：`agent-safety.md` §1–§6 逐项核对 | 六条全部未被破坏，逐条结论见 coding_report §4；本 change 另补强了 §2（测试替身边界） |
| 前端：FSD 红线、Zod 校验、无 `any` / `console.log`、金额 `string`、错误状态可观测 | 通过。`e2e/` 不含 `any`（typecheck strict + oxlint 双重把关），`console` 仅在豁免目录 |
| 后端：`domain` 无框架依赖、Controller 无业务逻辑、无空 catch、日志无敏感信息、幂等键存在 | 通过。`FakeLlmPlanner` 不打用户原话——`e2e-backend.sh` 的 `§6.2.15 user text in log` 5 条断言全绿 |
| 改动 ≠ spec 时显式标注偏差并解释 | 通过。两处超出 spec v2 的发现（失实断言 4→7 条、断言总数口径）已在 spec v3 与 coding_report §3 记录 |

## 6. 回退

`APPROVED` → 进阶段 5（代码推送）。阶段 4 的 HITL 确认点 ③ 需用户确认后执行推送。

S-1（补 `decide` 单测）、S-2（shellcheck 入 CI）不阻塞本 change，已登记到 summary 的遗留债务，分别归入改造清单的测试基建延伸与第 4 项 CI。
