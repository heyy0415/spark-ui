# Change Summary: test-e2e-playwright-fake-llm-20260911

| 字段 | 值 |
|---|---|
| Change ID | test-e2e-playwright-fake-llm-20260911 |
| 类型 | test |
| 状态 | PUSHED |
| 负责人 | Platform Owner Agent |
| 涉及端 | spark-rooter（host-demo）/ spark-ui / harness（契约无变更） |
| 起止时间 | 2026-09-11 ~ 2026-09-12 |
| Spec | [request_analysis/spec.md](request_analysis/spec.md) |
| Tasks | [request_analysis/tasks.md](request_analysis/tasks.md) |

## 阶段进度表

| # | 阶段 | 状态 | 评审轮次 | 产出 | 时间 |
|---|---|---|---|---|---|
| 1 | 需求分析 | DONE | — | spec.md v3, tasks.md v3（v1 评审 4 条 + v2 评审 1 条 MUST FIX 均已修订） | 2026-09-11 |
| 2 | 需求评审 | DONE | 2/3 | spec_review_v1.md（REVISION REQUIRED，4 MUST FIX）、spec_review_v2.md（REVISION REQUIRED，1 MUST FIX）；v3 已逐条落实，剩余为 SHOULD / LOW 且均已采纳 | 2026-09-11 |
| 3 | 编码实现 | DONE | — | coding_report_v1.md（T00–T06 全部完成，全量门禁 exit 0） | 2026-09-12 |
| 4 | 编码评审 | DONE | 1/2 | code_review_v1.md（APPROVED，0 MUST FIX / 2 SHOULD）；**独立性不满足**：subagent 通道不可用，由编码者自评，局限已在文件 §4 标注 | 2026-09-12 |
| 5 | 代码推送 | DONE | — | 本地 commit `55cc589`（26 文件，+999/−543）；push 待用户执行 | 2026-09-12 |
| 6 | CI 验证 | DEFERRED | — | 仓库尚无 `.github/workflows`；本 change 的门禁已在本地全绿（`pnpm -C .harness run ci` exit 0），CI 验证由改造清单第 4 项配置后的首次运行覆盖 | 2026-09-12 |
| 7 | 部署验证 | DONE | — | preview_report.md（**12 passed, 0 failed**，`planner=fake-e2e`） | 2026-09-12 |
| 8 | 用户确认 | TODO | — | — | — |

## 契约变更
- NONE

## 关键成果

| 门禁 | 本 change 前 | 本 change 后 |
|---|---|---|
| `e2e-backend.sh`（无模型） | 60 / 101 | **161 passed, 0 failed** |
| `e2e-frontend`（无模型） | 依赖本机 Chrome，主链路需真模型 | **7 passed**（Playwright 自带 chromium） |
| `deploy-verify.sh`（无模型） | 8 / 12 | **12 passed, 0 failed** |

三套验收首次在「无 `SPARK_LLM_*`、无本机 Chrome」的机器上全绿，为改造清单第 4 项（CI）解除前置阻塞。

## 遗留债务

- **S-1：`PlanValidator.decide` 缺直接单测**。提取无行为变化，由 131 单测 + 161 条 e2e 间接覆盖，但三路分支（含 `EntityMissing → Clarify`、两处 `replyOr` 默认文案）没有分支级快速回归。建议下一 change 补 `PlanValidatorDecideTest`，约 6 个用例。
- **S-2：shell 脚本未经 shellcheck**。本机未安装，只跑了 `bash -n`。编码期实际踩到一个 `bash -n` 查不出的问题（`$FRONT_PORT）` 全角括号被当作变量名一部分，运行时 `unbound variable`）。归入改造清单第 4 项 CI。
- **替身规则未命中时静默走 `none`**。将来新增 e2e 用例若不在 `FakeLlmPlanner` 规则表内，会静默返回「无可用能力」而非报错。建议为替身加一条「未命中任何规则」的 WARN 日志让缺口显形（属新增行为，未在本 change 做）。

## 经验沉淀
- spec v1 写「`PlanValidator.validate` / `missingEntity` / `PlanDraft` 三者均 public（已核对）」，实际 `missingEntity` 是 package-private。**教训**：spec 里写「已核对」必须附可复现的 grep 命令与行号，评审据此复查；后续 spec 的事实性断言一律带命令。
- `e2e-backend.sh` 有 4 条断言依赖 `refactor-llm-planner-domain-free` 删除的日志（`source=memory` / `route runId=… source=`），三个 change 无人发现——因为本机无模型时它们混在大量「模型相关失败」里。**Hashimoto 候选**：`e2e-backend.sh` 的每条 `grep` 型断言所依赖的日志片段，应有脚本校验其在 `src/main` 中存在（拟在第 4 项 CI change 中落为 `check-log-assertions.mjs`）。
- **断言总数被连续算错三次**：spec v1/v2 与 v1 评审 I-1 用 161（把「全部 `check` 展开上限」当实跑数）；v2 评审静态推算得 154（漏了 `confirm_run()` 函数体 4 条 × 4 次调用）；T02 实测才确定三变量全空实跑 **161**——巧合等于上限，因为漏算的 +12 与未执行的 −5 以及别处差额相抵。**教训**：验收里的数字若随环境变化且需要静态推算，就不该作为门禁——`for` 循环、被调多次的函数、非 `LIVE_LLM` 的守卫（如 `:391` 用的是 `[ -n "$SPARK_LLM_BASE_URL" ]`）都会让推算失准。改用 `0 failed` 这类环境无关的断言。已写进 spec §1 口径表。
- **spec 用「以实测为准」给不确定的数字兜底**，等于把可校验断言变成自我实现的预言，正是它掩盖了上一条。**Hashimoto 候选**：`request-analysis` skill 的 checklist 加一条「验收标准不得含『以实测为准』『按实际调整』类免责措辞」。
- **多 agent 评审工作流全军覆没**：7 个 subagent 全部 `400 专用渠道限制`，工作流按空结果集算出 `APPROVED`。**教训**：workflow 返回的聚合结论必须先看 `agents_error` 计数，全失败时的「零 finding」不等于「无问题」。
