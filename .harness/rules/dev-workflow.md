# Rule: 开发流程规范（8-Stage Pipeline）

> 这是 Harness 的**结构化执行**支柱。无论需求大小，所有变更走相同 8 阶段——**流程一致性 优先于 流程效率**。
> 每阶段三要素：**入场条件 / Skill 加载 / 质量门禁**；门禁必须**可程序化校验**。

---

## 阶段 0 — 启动与上下文加载（Bootstrap）

| 项 | 内容 |
|---|---|
| 入场 | 任何会话开始；或 `/clear` 之后 |
| Skill | `project-analysis`（仅当首次进入项目或 .harness/ 有重大更新时） |
| 产出 | （无）；只是确保 Owner Agent 已读取 Rules + 当前 change 的 `summary.md` |
| 门禁 | `pwd` 为仓库根 && `.harness/agents/platform-owner.md` 已被加载 |

---

## 阶段 1 — 需求分析（Request Analysis）

| 项 | 内容 |
|---|---|
| 入场 | 用户输入新需求 |
| Skill | `request-analysis` |
| 产出 | `changes/{id}/request_analysis/spec.md` + `tasks.md` |
| 门禁 | 两个文件存在；`spec.md` 含「背景 / 范围 / 非目标 / 验收标准 / 风险」5 个章节；`tasks.md` 每个 task 含「目标 / 输入 / 输出 / 验收 / 依赖」5 项 |

---

## 阶段 2 — 需求评审（Plan Review）

| 项 | 内容 |
|---|---|
| 入场 | 阶段 1 完成 |
| Skill | `expert-reviewer`（plan 模式） |
| 产出 | `request_analysis/review/spec_review_v{n}.md`（n 递增，旧版本不删） |
| 门禁 | 评审 verdict 为 `APPROVED` |
| 失败回退 | `REVISION REQUIRED` → 回阶段 1，循环 ≤ 3 轮，超出升级 Human-in-the-Loop |
| **HITL 确认点 ②** | 评审通过后向用户展示计划摘要，用户确认进入阶段 3 |

---

## 阶段 3 — 编码实现（Coding）

| 项 | 内容 |
|---|---|
| 入场 | 阶段 2 通过 + 用户确认 |
| Skill | `coding-skill`（分层 Spec：契约 1 + 后端 1 + 前端 5；编码顺序 contracts → spark-rooter → spark-ui） |
| 产出 | 代码变更 + `coding/coding_report_v{n}.md`（含改动文件列表、所属端、关键决策记录） |
| 门禁 | `pnpm -C .harness run check-contracts` 通过；`pnpm -C spark-ui typecheck` 通过；`node .harness/scripts/mvn.mjs -q -B compile` 通过；改动符合 `project-structure.md` 红线 |

---

## 阶段 4 — 编码评审（Code Review）

| 项 | 内容 |
|---|---|
| 入场 | 阶段 3 完成 |
| Skill | `code-review` + `expert-reviewer`（execution 模式） |
| 产出 | `coding/review/code_review_v{n}.md` |
| 门禁 | `pnpm -C .harness run ci` 退出码 0；评审 verdict APPROVED；分级意见均无 MUST FIX；`agent-safety.md` 六条边界逐项核对 |
| 失败回退 | 编译/lint 错误 → 回阶段 3；语义错误且 ≤2 轮 → 回阶段 3；超出 → HITL |
| **HITL 确认点 ③** | 评审通过后用户确认进入推送阶段 |

---

## 阶段 5 — 代码推送（Commit & Push）

| 项 | 内容 |
|---|---|
| 入场 | 阶段 4 通过 + 用户确认 |
| Skill | （无，Owner 直接执行） |
| 产出 | git commit；commit footer 含 `Change: {change-id}` |
| 门禁 | `git status` 干净；commit message 符合 Conventional Commits |

---

## 阶段 6 — CI 验证（CI Verification）

| 项 | 内容 |
|---|---|
| 入场 | 阶段 5 完成 |
| Skill | （CI 中触发） |
| 产出 | `ci_result/ci_summary.md`（各步骤退出码、前端 bundle 大小、后端 jar 大小） |
| 门禁（**程序化**） | `pnpm -C .harness run ci` 退出码 == 0（= check-contracts + 前端 typecheck/lint/format/build + 后端 `mvnw verify`） |
| 失败回退 | 编译 / lint / format 错误 → 回阶段 3；契约不一致 → 回阶段 1 |

> **核心经验**：不要让自然语言来定义"CI 通过"。必须以命令的真实退出码为准，Agent 声称完成前要把退出码打印出来。

---

## 阶段 7 — 部署验证（Deploy Verify）

| 项 | 内容 |
|---|---|
| 入场 | 阶段 6 通过 |
| Skill | `deploy-verify` |
| 产出 | `deployment/preview_report.md`（前端截图 + console.error 数、后端 `/actuator/health` 结果、端到端一条 Run 的 SSE 事件序列）。`deployment/` 由 `scripts/lib/change-dir` 定位；并行多个 change 时必须显式 `SPARK_CHANGE=<change-id>` |
| 门禁 | 前端 bundle 未恶化（< +10%）；预览页面 console.error == 0；后端 health UP；示例 Run 走通至 `run.completed` |
| **HITL 确认点 ④** | 部署参数（环境、域名、灰度比例）由人工最终确认 |

---

## 阶段 8 — 用户确认（User Acceptance）

| 项 | 内容 |
|---|---|
| 入场 | 阶段 7 通过 |
| Skill | （无） |
| 产出 | `summary.md` 状态置 `DELIVERED`；`change` 目录归档 |
| 门禁 | 用户书面确认（chat 中的 "确认交付"） |
| **HITL 确认点 ⑤** | 最终交付确认 |

---

## 通用规则

- **循环上限**：需求评审 ≤ 3，编码评审 ≤ 2。超出立即上升到 Human-in-the-Loop，不允许 Agent 自我无限纠错。
- **回退路径**精确到阶段编号：避免"出问题就从头来"的低效。
- **summary.md 必须每阶段结束立即更新**，不允许批量补登（防"追加倾向"导致重复行）。
- 任何阶段**禁止**跳过；"小改动"也走完整流程，因为流程一致性是廉价保险。
