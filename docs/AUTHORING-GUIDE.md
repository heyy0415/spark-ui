# Authoring Guide：人 vs Agent 职责分工与新需求实战

> 这份文档回答两件事：
> 1. **`.harness/` 下每份文件由谁创建、谁修改、什么时候改**？
> 2. **来一个新需求时，人和 Agent 分别做什么、按什么顺序做**？
>
> 它是 [HARNESS-USAGE-GUIDE.md](./HARNESS-USAGE-GUIDE.md)（操作手册）的互补——那份回答了"做什么 / 怎么做"，本文回答**"谁来做"**。

---

## 目录

- [一、核心原则](#一核心原则)
- [二、文件作者矩阵（一张大表）](#二文件作者矩阵一张大表)
- [三、六大类文件的具体修改方式](#三六大类文件的具体修改方式)
- [四、新需求 0 → DELIVERED 的 22 步实战](#四新需求-0--delivered-的-22-步实战)
- [五、首次为新项目搭建 Harness 的人工清单](#五首次为新项目搭建-harness-的人工清单)
- [六、修改 .harness/ 时的硬约束](#六修改-harness-时的硬约束)
- [七、FAQ](#七faq)

---

## 一、核心原则

### 1.1 范式转移

Harness Engineering 的核心理念是——开发者的核心工作正在从 **写代码** 转向 **设计 Agent 的工作环境**：

```
传统开发                       Harness 开发
─────────────                ─────────────────
代码 ← 人写                   代码 ← Agent 写
文档 ← 辅助参考                文档 ← Agent 的输入（=人写）
评审 ← 人对人                  评审 ← 独立 Agent 对 Agent
```

**人和 Agent 的分工大致是 90/10**——但分工方向是**反过来的**：

| 工作 | 人占比 | Agent 占比 |
|---|---|---|
| 代码（src/） | ~10% | **~90%** |
| 常驻基础设施（rules/, agents/, skills/） | **~95%** | ~5%（提建议）|
| 知识库（wiki/） | ~70% | ~30%（自动同步契约）|
| 变更产出（changes/{id}/）| ~5%（HITL 确认）| **~95%** |

把这个比例颠倒过来理解，你就掌握了 Harness 的精髓。

### 1.2 五个 Human-in-the-Loop（HITL）确认点

Agent 跑 8 阶段时**只**在这 5 个点停下等你回复——其他 95% 的时间它在自己往前推：

| # | 触发时机 | 你看什么 | 你说什么 |
|---|---|---|---|
| ① | Stage 1 写完发现需求模糊 | Agent 列的澄清问题 | 回答澄清问题 |
| ② | Stage 2 评审通过后 | spec_review_v1.md 的决议段 | "确认进入编码" / "改一下 X" |
| ③ | Stage 4 编码评审通过后 | code_review_v1.md 的决议段 | "进入推送" / "改一下 Y" |
| ④ | Stage 7 部署验证通过后 | preview_report.md 的部署参数 | "确认部署到 staging" |
| ⑤ | Stage 8 全部跑完 | summary.md 的 DELIVERED 摘要 | "确认交付" |

**HITL 是流程红线**——绝不让 Agent 自己跳过这 5 个点。

---

## 二、文件作者矩阵（一张大表）

> 标签说明：
> - **[H-FOUND]** = 人工奠基（首次创建）
> - **[H-EDIT]** = 人工日常编辑
> - **[A-DRAFT]** = Agent 起草（人工 HITL 确认）
> - **[A-AUTO]** = Agent 全自动产出（人工偶尔回看）
> - **[CI]** = CI 流水线产出

### 2.1 项目根

| 文件 | 首建 | 日常修改 | 频率 |
|---|---|---|---|
| `CLAUDE.md` | [H-FOUND] | [H-EDIT] | 月度（项目结构变化时）|
| `README.md` | [H-FOUND] | [H-EDIT] | 季度 |
| `package.json` | [H-FOUND] | [H-EDIT] | 加依赖时 |
| `tsconfig*.json`、`vite.config.ts`、`.oxlintrc.json` 等 | [H-FOUND] | [H-EDIT] | 几乎不动 |

### 2.2 .harness/ 常驻基础设施

| 文件 | 首建 | 日常修改 | 频率 |
|---|---|---|---|
| `agents/platform-owner.md` | [H-FOUND] | [H-EDIT] | 月度（加新 Skill / 改阶段定义）|
| `rules/project-structure.md` | [H-FOUND] | [H-EDIT] | 季度（加新红线）|
| `rules/coding-standard.md` | [H-FOUND] | [H-EDIT] | 月度（每次 Agent 出错升级）|
| `rules/dev-workflow.md` | [H-FOUND] | [H-EDIT] | 半年（流程稳定后很少改）|
| `skills/{name}/SKILL.md` (×7) | [H-FOUND] | [H-EDIT] | 月度（基于 change 的「经验沉淀」）|
| `skills/coding-skill/specs/*.md` (×5) | [H-FOUND] | [H-EDIT] | 月度（添新分层 / 改硬约束）|
| `templates/change-template/*` | [H-FOUND] | [H-EDIT] | 半年 |
| `mcp/servers.json` | [H-FOUND] | [H-EDIT] | 季度（接入新 MCP）|

### 2.3 .harness/wiki — 混合维护

| 文件 | 首建 | 日常修改 | 谁负责 |
|---|---|---|---|
| `wiki/architecture.md` | [H-FOUND] | [H-EDIT] | 人工，每次架构变动同步 |
| `wiki/domain-model.md` | [H-FOUND] | [A-DRAFT] + [H-EDIT] | Agent 起草字段表，人工补业务约束 |
| `wiki/api-contracts.md` | [H-FOUND] | [A-DRAFT] + [H-EDIT] | Agent 同步 schema，人工审核 |

### 2.4 .harness/changes/{change-id}/ — Agent 主导

每个 change 进来时**全是空白**（由 `pnpm -C .harness run new-change` 创建骨架），8 阶段跑完产出：

| 文件 | 阶段 | 作者 | 人工介入点 |
|---|---|---|---|
| `summary.md` | 全程 | [A-AUTO] | 看一眼最终 DELIVERED |
| `request_analysis/spec.md` | 1 | [A-DRAFT] | **HITL ②** 看完整内容 |
| `request_analysis/tasks.md` | 1 | [A-DRAFT] | **HITL ②** 看完整内容 |
| `request_analysis/review/spec_review_v{n}.md` | 2 | [A-AUTO] | **HITL ②** 看决议 |
| `coding/coding_report_v{n}.md` | 3 | [A-AUTO] | 看「关键决策」段 |
| `coding/review/code_review_v{n}.md` | 4 | [A-AUTO] | **HITL ③** 看决议 |
| `coding/commit_message.md` | 5 | [A-DRAFT] | 复制粘贴执行 commit |
| `ci_result/ci_summary.md` | 6 | [A-AUTO] + [CI] | 看退出码 |
| `deployment/preview_report.md` | 7 | [A-AUTO] | **HITL ④** 看截图 + console.error |

### 2.5 src/ — 代码

| 类型 | 作者 | 人工介入 |
|---|---|---|
| `src/{layer}/**/*.ts(x)` | [A-AUTO] | 任何 Agent 写完后**抽样**人工读 |

> 抽样原则：**新增**的 entity schema 和路由守卫这种**安全敏感**代码必读；纯 UI 组件 / 标准 CRUD 抽 30% 即可。

### 2.6 一句话总结

```
人工建一次，写少量；Agent 跑 N 次，写大量；
人工只在 5 个 HITL 点暂停，其余靠 Skill checklist 把质量门禁住。
```

---

## 三、六大类文件的具体修改方式

> 类别命名按"作者属性 + 修改频率"：A 高频人工 / B 月度人工 / C 半年人工 / D Agent 起草 / E Agent 自动 / F CI 自动

### 类 A：永远不动 / 半年才动一次（基础架构）

**文件**：
- `CLAUDE.md`
- `package.json` 关键脚本（`pnpm -C .harness run ci`、`pnpm -C .harness *`）
- `tsconfig*.json`、`vite.config.ts`、`.oxlintrc.json`
- `.harness/rules/dev-workflow.md`（8 阶段定义）
- `.harness/templates/change-template/*`
- `scripts/*.mjs`

**修改时机**：
- 引入新工具链（如换包管理器）
- 新增 CI 步骤
- 流程本身改动（如增删阶段或修改门禁）

**操作步骤**：
1. **必须** 在 `.harness/changes/chore-{name}-YYYYMMDD/` 下开 change 跟踪此次修改。
2. 修改时单独成 commit，不与业务 feat 混合。
3. 改完跑 `pnpm -C .harness run doctor` 自检。
4. 在团队群同步周知（影响所有人）。

**示例改动**：
```bash
# 想新增"依赖审计"作为 Stage 6 的一部分
git checkout -b chore/add-audit-step
pnpm -C .harness run new-change chore add-audit-step

# 改 .harness/rules/dev-workflow.md 阶段 6 的门禁段落
# 改 package.json 的 ci 脚本：
#   "ci": "pnpm typecheck && pnpm lint && ... && pnpm audit --audit-level=high"
# 改 .harness/skills/code-review/SKILL.md，加 "pnpm audit" 到步骤
# 跑 pnpm -C .harness run doctor 验证
```

### 类 B：每月演化一次（Skills / Rules）

**文件**：
- `.harness/agents/platform-owner.md`
- `.harness/rules/project-structure.md`
- `.harness/rules/coding-standard.md`
- `.harness/skills/{name}/SKILL.md` (×7)
- `.harness/skills/coding-skill/specs/*.md` (×5)

**修改时机**——**只在以下情况下修改**：
1. **每个 change 的 summary.md「经验沉淀」段** 累积 ≥ 3 条同类问题 → 升级为永久 rule。
2. 出现一次**机械化可校验**的反模式 → 写到 oxlint / CI / Skill Checklist。
3. 团队约定升级（如改 bundle 大小阈值）。

**操作步骤**（每月 1 次的 review-and-evolve cycle）：
1. 找一个固定时间（如每月最后一个周五下午 1h）。
2. 把所有 `changes/*/summary.md` 的「经验沉淀」段抓出来：
   ```bash
   grep -A 5 "经验沉淀" .harness/changes/*/summary.md
   ```
3. 按下面的"升级路径决策树"（来自 [HARNESS-USAGE-GUIDE.md §七](./HARNESS-USAGE-GUIDE.md#七演化-harnesshashimoto-法则的实操路径)）：
   - 能写成 oxlint 规则？→ 加规则
   - 能写成 CI 命令？→ 加到 `pnpm -C .harness run ci`
   - 只能写成文字？→ 加到 Skill Checklist
   - 当前 change 特有？→ 留在 summary.md，不升级
4. 修改后在 `chore-harness-evolve-YYYYMM/` change 提交。
5. 跑 `pnpm -C .harness run doctor` + `pnpm -C fronted lint` 自检。

**写作要点**：
- Rule 必须**指向一个真实失败案例**（"金额必须用 string 单位分" 背后是某次精度 bug）。
- Skill Checklist 必须**机械化可校验**——避免 "确保高质量" 这种话。
- 改 SKILL.md 必须保持 YAML frontmatter（`name` + `description`）；`description` 含触发关键词，否则 Claude Code 不会自动调用。
- 改 `agents/platform-owner.md` 必须保持 ≤ 250 行（OpenAI 经验：超过 100 行注意力就开始分散，250 是上限）。

**反模式**：
- ❌ 给所有 rule 加 "建议"、"推荐" —— 软约束 Agent 会无视。
- ❌ 把同一条规则写在 5 个 Skill 里 —— 真源应只有一处，其余引用。
- ❌ 用一段长 prose 描述检查项 —— 必须列成 Checklist。

### 类 C：每周可能动（Wiki）

**文件**：
- `.harness/wiki/architecture.md`
- `.harness/wiki/domain-model.md`
- `.harness/wiki/api-contracts.md`

**修改时机**：
1. **代码同步同 commit**：改 `entities/{x}/model/types.ts` 时，同 commit 必须改 `wiki/domain-model.md` 和 `wiki/api-contracts.md`。
2. 架构变动时（加 / 删切片、引入新数据流）改 `wiki/architecture.md`。
3. Agent 起草 → 人工补业务规则。

**操作步骤**：
1. 改 schema 的 PR 里**必须**带 wiki 改动；lint 无法强制，但 code-review Skill 的 Checklist 会查。
2. Agent 在 Stage 3 编码时若发现契约变化，应**先**问"这次契约变了，要更新 api-contracts.md 吗"——这是约定动作。
3. 半年做一次 wiki 健康度检查：
   ```bash
   # 简单的 staleness 检查：wiki 的 mtime vs entities 的 mtime
   ls -la .harness/wiki/
   ls -la src/entities/*/model/types.ts
   # 如 wiki 显著旧于 schema → 立刻同步
   ```

**Agent 协助**：
```
> 请用 project-analysis Skill 比对当前 src/entities 与 wiki/domain-model.md 的字段差异，列出 STALE 项。
```

### 类 D：每个 change 来时 Agent 起草（spec / tasks / summary 骨架）

**文件**：
- `.harness/changes/{id}/request_analysis/spec.md`
- `.harness/changes/{id}/request_analysis/tasks.md`
- `.harness/changes/{id}/summary.md`（只人工最终回看）

**修改时机**：
- Stage 1 阶段，Owner Agent 触发 `request-analysis` Skill 起草。
- 评审循环若有 REVISION REQUIRED，Agent 重写 → spec_v2.md（Agent 自己改）。

**人工介入步骤**：
1. **HITL ②** 看 `spec.md`：
   - 「非目标」段是否非空？（最关键）
   - 「验收标准」是否全部可程序化？
   - 风险章节有没有 ≥1 条具体的失败模式？
2. 如果不满意，**不**手动改 spec.md——而是说："请补充非目标 X、Y、Z" 让 Agent 重写。
   - **原因**：Agent 下次跑同类需求时不会从你的手改里学到——但会从评审意见里学到。手改 spec 是绕过 Skill 的副作用。

**反模式**：
- ❌ 你自己动手把 spec 写得很完整然后让 Agent 直接跳到 Stage 3 —— 评审循环没机会跑，错失质量护栏。
- ❌ "非目标"留空 —— Agent 一定会顺手扩 scope。

### 类 E：每个 change 来时 Agent 全自动产出（review / report / plan）

**文件**：
- `.harness/changes/{id}/request_analysis/review/spec_review_v{n}.md`
- `.harness/changes/{id}/coding/coding_report_v{n}.md`
- `.harness/changes/{id}/coding/review/code_review_v{n}.md`
- `.harness/changes/{id}/coding/commit_message.md`

**修改时机**：
- 全部由 Agent 在对应阶段产出。
- **人工绝不手改 review 文件**——评审记录是 Audit Trail，篡改即破坏。

**人工介入步骤**：
1. **HITL ② / ③** 时只看 `review/*.md` 的「决议」段（APPROVED / REVISION REQUIRED）。
2. 如果决议是 REVISION REQUIRED 且循环已 ≥ 上限（Plan 3 / Code 2）→ **HITL** 介入决断：
   - 拆 change？
   - 降低验收标准？
   - 找团队讨论？
3. commit_message.md：复制内容到 `git commit -m`，footer 含 `Change: {id}`。

**反模式**：
- ❌ "我看 v1 写得不错，但是再让 Agent 评审一遍" —— 浪费循环上限；评审决议已是 APPROVED 就推进。
- ❌ 删掉 v1 重写 v2 —— 旧版本必须保留。

### 类 F：CI / 部署自动产出

**文件**：
- `.harness/changes/{id}/ci_result/ci_summary.md`
- `.harness/changes/{id}/deployment/preview_report.md`

**修改时机**：
- Stage 6 / 7 由 Agent + CI 自动写入。

**人工介入步骤**：
1. **HITL ④** 看 `preview_report.md`：
   - 关键页面截图是否符合预期
   - console.error == 0
   - bundle size 增长 ≤ 阈值
2. CI 失败时，**不**直接读 ci_summary.md 的总结——而是去原始 CI 链接看完整 log（避免 Agent 误读）。

---

## 四、新需求 0 → DELIVERED 的 22 步实战

下面是从你接到一个新需求开始，每一步谁做什么、操作什么命令、产物在哪。

> 以"给应用加'修改密码'功能"为虚构例子。

### Phase 0 — 接需求（1 步）

**Step 1** [HUMAN]
你接到产品 / 自己产生的需求："登录用户可以修改密码"。
**操作**：打开 Claude Code，进入项目根目录。Claude 自动加载 `CLAUDE.md`（L1 常驻）。

### Phase 1 — 启动会话（2 步）

**Step 2** [HUMAN]
**第一句对话**（不要直接给需求！）：
```
今天好。请按 CLAUDE.md 的「启动序列」走一遍：pwd / git log / 找未交付 change / 读其 summary.md。
```
**为什么**：避免 Agent 在不知道你之前做到哪了的状态下接需求。Cold Start 防御。

**Step 3** [AGENT]
Owner Agent 输出当前进度。如果有未交付 change，会问你"先继续未完成的还是开新的"。如果都干净，会说"等待新需求"。

### Phase 2 — Stage 0：创建 change 骨架（2 步）

**Step 4** [HUMAN] 提需求
```
新需求：登录用户可以修改密码。
- 入口在用户头像下拉菜单"修改密码"
- 弹出 Modal，输入旧密码 + 新密码（两次）
- 调 PUT /api/auth/password
- 不做密码强度提示、不做修改后强制重登录
```
> **关键**：自己写"不做"的部分。Agent 不会主动给你画边界。

**Step 5** [AGENT] 创建 change
Agent 执行：
```bash
pnpm -C .harness run new-change feat change-password
```
得到 `.harness/changes/feat-change-password-20260512/` 骨架（10 个空目录 + 模板 summary.md）。

### Phase 3 — Stage 1：需求分析（3 步）

**Step 6** [AGENT] 触发 request-analysis
Agent 加载 `.harness/skills/request-analysis/SKILL.md`，按 SKILL 的 5 步：
- 复述需求
- 列澄清假设
- 写 spec.md（6 强制章节）
- 写 tasks.md（每 task 5 要素）
- 更新 summary.md

**Step 7** [AGENT] 提澄清问题（可能跳过）
如果 Agent 发现 BLOCKING 假设——比如"密码格式约束（长度、字符集）"——会问：

```
我有 3 个 BLOCKING 澄清问题：
1. 密码长度区间（如 8-128）？
2. 是否支持特殊字符 / Emoji？
3. 修改成功后是否清空所有其他设备的会话？
请明确，否则无法写 spec。
```

**Step 8** [HUMAN] 回答澄清问题
```
1. 8-128
2. 支持任何 Unicode 字符
3. 不在本期范围（在非目标里写明）
```

**【HITL ①】** — 这是第一个 Human-in-the-Loop 点，但它**不**总是触发——只在 Agent 真的发现模糊点时。

### Phase 4 — Stage 2：需求评审（2 步）

**Step 9** [AGENT] 触发 expert-reviewer (plan 模式)
Agent 加载 `expert-reviewer` Skill，独立评审 spec.md + tasks.md（**用不同 prompt context**——这是分离执行与评判的关键）。
产出 `request_analysis/review/spec_review_v1.md`，verdict 为 APPROVED 或 REVISION REQUIRED。

**Step 10** [HUMAN] **【HITL ②】**
你打开 `spec_review_v1.md`，**只**看：
- verdict 段（APPROVED 直接进 Step 11）
- MUST FIX 列表（如果 verdict 是 REVISION REQUIRED）

如果 verdict 是 REVISION REQUIRED：
- 循环 ≤ 3 → 让 Agent 改 spec.md 后回 Step 9
- 循环 > 3 → 你介入决策（拆 change / 改需求 / 降验收）

如果 APPROVED：
```
你回："计划摘要看过了，确认进入编码"
```

### Phase 5 — Stage 3：编码实现（4 步）

**Step 11** [AGENT] 触发 coding-skill
Agent 按 tasks 顺序写代码。每个 task 加载对应分层 spec：
- 改 entities → 加载 `03-entity-spec.md`
- 改 features → 加载 `02-feature-spec.md`
- 改 pages → 加载 `01-page-spec.md`

> **不要**让 Agent 一次性加载全部 5 份 spec——这是 Anthropic 强调的"上下文 Sweet Spot < 40%"。

**Step 12** [AGENT] 自检 typecheck
每写完 1-2 个 task，Agent 跑：
```bash
pnpm typecheck
node scripts/check-deps.mjs
```
任一失败就立即修。

**Step 13** [AGENT] 写 coding_report_v1.md
含改动文件清单 / 关键决策 / 已知限制。

**Step 14** [HUMAN] 抽样人工 review
你**不**需要逐行看代码——但**必须**抽样：
- 安全敏感代码（auth schema、httpClient 改动）：100% 看
- 标准 UI 组件：30% 抽样

### Phase 6 — Stage 4：编码评审（2 步）

**Step 15** [AGENT] 触发 code-review + expert-reviewer (execution)
两个 Skill 串行：
1. `code-review` 跑机器化检查（typecheck / lint / format / check-deps）。失败就停，不进语义评审。
2. `expert-reviewer` 跑语义评审。

产出 `coding/review/code_review_v1.md`。

**Step 16** [HUMAN] **【HITL ③】**
看 verdict + MUST FIX。SHOULD 级别可以"在同 commit 内修订"，**不**触发回退。
回："进入推送"。

### Phase 7 — Stage 5：代码推送（2 步）

**Step 17** [AGENT] 起草 commit message
写到 `coding/commit_message.md`。

**Step 18** [HUMAN] 执行 commit
```bash
git add -A
git status      # 确认改动文件清单与 coding_report 一致
git commit -F .harness/changes/feat-change-password-20260512/coding/commit_message.md
```

> **谁执行 git commit**：人工执行更安全——避免 Agent 误提交未审过的文件。但允许 Agent 在 staging 完成后由你说一句 "你帮我 commit"。

### Phase 8 — Stage 6：CI 验证（1 步）

**Step 19** [CI] 跑 `pnpm -C .harness run ci`
本地或推到远端 CI 都行。门禁只有一条：
```
exit_code == 0    # typecheck + lint + format:check + build 全部为 0
```

Agent 把结果（含真实退出码）写入 `ci_result/ci_summary.md`。

### Phase 9 — Stage 7：部署验证（2 步）

**Step 20** [AGENT] 触发 deploy-verify
```bash
pnpm build
pnpm preview &
# 烟囱测试关键页面，截图，统计 console.error
```
产出 `deployment/preview_report.md`。

**Step 21** [HUMAN] **【HITL ④】**
看 preview_report：
- 截图符合预期？
- console.error == 0？
- bundle 增长 ≤ 阈值？
回："确认部署到 staging"（生产环境会有更严格审批）。

### Phase 10 — Stage 8：交付（1 步）

**Step 22** [HUMAN] **【HITL ⑤】**
说："确认交付"。
Agent 把 `summary.md` 状态置 **DELIVERED**。

---

### 22 步关键人工介入点速查

```
Step  1 [H] 启动 Claude Code
Step  2 [H] 启动序列对话
Step  4 [H] 提需求（含非目标）
Step  8 [H] 答澄清问题（HITL ①，可能跳过）
Step 10 [H] HITL ② — 计划评审通过
Step 14 [H] 抽样人工 review 代码
Step 16 [H] HITL ③ — 编码评审通过
Step 18 [H] 执行 git commit
Step 21 [H] HITL ④ — 部署参数确认
Step 22 [H] HITL ⑤ — 最终交付
```

**人工总耗时**：~15-20 分钟（不含等 Agent 跑的时间）。
**总耗时**：~30-60 分钟（取决于需求复杂度）。

---

## 五、首次为新项目搭建 Harness 的人工清单

> 这是一次性工作，~1 周。完成后 Harness 自动运转。

### Day 1：复制脚手架 + 改项目元信息

```bash
cp -r harness-frontend-scaffold ~/projects/my-new-app
cd ~/projects/my-new-app
git init && git add . && git commit -m "chore: init from harness scaffold"
```

人工修改文件清单（**绝不让 Agent 改这一组**——Agent 没有项目背景，只会复读模板）：

1. **`CLAUDE.md`** — 改第 1 行项目名、`项目核心信息`段。
2. **`README.md`** — 改项目名、移除 demo 段、加你的项目背景。
3. **`package.json`** — 改 `name`、`description`。
4. **`.harness/agents/platform-owner.md`** — 改 §1「角色与项目背景」（技术栈 + 中间件 + 业务约束）。
5. **`.harness/wiki/architecture.md`** — 改分层关系图、状态管理表。
6. **`.harness/wiki/domain-model.md`** — 删 demo（User），换上你的真实领域实体。
7. **`.harness/wiki/api-contracts.md`** — 删 demo，换真实契约。

### Day 2：改 Rules（按团队约定）

只改 3 份 rule 中**与 demo 不一致**的部分：

1. **`.harness/rules/project-structure.md`**
   - 你团队若不用 FSD（用 modules + 共享层）→ 改分层定义 + 红线。
   - 但**强烈建议**保持单向依赖原则——这是 Agent 友好的根本。

2. **`.harness/rules/coding-standard.md`**
   - 改第 3 节"数值与单位约束"——按你业务的精度敏感字段填。
   - 改第 4 节"状态管理选型"——若用 Redux / Recoil 而非 Zustand。

3. **`.harness/rules/dev-workflow.md`**
   - 一般保持原样。
   - 若你团队需要额外阶段（如设计稿对齐），增 Stage X.5。

### Day 3：改 Skills 的项目特定段

1. **`.harness/skills/coding-skill/specs/01-page-spec.md` ~ `05-styling-spec.md`**
   - 改路径示例（`src/pages/{name}/` 这种路径）。
   - 改技术选型例（如换路由库）。

2. **`.harness/skills/deploy-verify/SKILL.md`**
   - 改 preview 命令、关键页面 URL。

> **不要改的 Skill**：`request-analysis`、`expert-reviewer`、`code-review`、`frontend-doctor`、`project-analysis`——这些都是项目无关的通用流程，原样可用。

### Day 4：跑第一次 Dry Run

挑一个**真实但不紧急**的小需求：
- 加一个 health check 页面、加一个 footer 版权信息、加一个 404 页面……

跑 22 步全流程。重点不是产出代码，是**走一遍**——你会发现：
- 哪些 rule 写得不够具体（Agent 走偏了）
- 哪些 spec 章节模板不合用
- 哪些 Checklist 项缺失

**立刻修**，记入这个 change 的 `summary.md` 经验沉淀。

### Day 5：跑第二次 Dry Run + 修 Harness

第二次跑应当顺畅很多。把第一次发现的问题修完，再跑一遍验证。

### Day 6-7：团队同步

- 把 Harness 体系做一次内部分享。
- 让另一位同事跑第三次 Dry Run（不是你跑）——这能暴露你已经默认知道但文档没写明的"隐性知识"。
- 第三次 Dry Run 后 Harness 即可进入日常使用。

### 验收标准

第一次正式接真实需求前必须满足：
- [ ] `pnpm -C .harness run doctor` 0 errors / 0 warnings
- [ ] `node scripts/check-deps.mjs` 0 violations
- [ ] 至少 2 次 Dry Run 跑完整 8 阶段无人工跳步骤
- [ ] 至少 1 次 Dry Run 由非创建者跑通
- [ ] `summary.md` 经验沉淀段累积 ≥ 5 条

---

## 六、修改 .harness/ 时的硬约束

### 6.1 SKILL.md 必须保持的格式

```yaml
---
name: skill-name-kebab-case          # 必填，与目录名一致
description: 何时触发的描述。包含触发关键词。Claude Code 据此自动建议调用。
---

# Skill: {Name}

## 何时触发
## 输入
## 步骤
## 输出
## Checklist     # ← 必须存在；每条机械化可校验
```

破坏 frontmatter → Claude Code 永远不会自动触发该 Skill。`pnpm -C .harness run doctor` 会校验。

### 6.2 Rule 文件必须满足

- 每条规则关联**一个真实的失败案例**（即便案例没写出来，作者心里要清楚）。
- 优先 oxlint / CI 校验；不能机械化的才写文字。
- 不写 "建议" / "推荐"——只写 "必须" / "禁止"。

### 6.3 Owner Agent 行数上限

`.harness/agents/platform-owner.md` ≤ **250 行**。
当前长度：~150 行。新增内容时优先压缩既有内容。

### 6.4 改 Harness 必须开 chore change

任何 `.harness/` 修改都必须在 `chore-{name}-YYYYMMDD/` change 里跟踪——这本身就是 Audit Trail 的一部分。

### 6.5 已交付的 change 不删

已交付的 change 目录是**永久 Audit Trail**，不要删。新人入场也需要它们作为范例。

---

## 七、FAQ

### Q1：我能直接手改 spec.md 而不让 Agent 重写吗？

**短答**：可以，但不要常做。
**长答**：手改一次 = 你绕过了 Skill 一次。Agent 下次跑同类需求时不会从你的手改里学到，只会从评审意见里学。所以正确做法是：手改 spec → **同时**把"Agent 没写到位的部分"提炼到 `request-analysis/SKILL.md` 的步骤里，这样下次自动避免。

### Q2：我能让 Agent 改 .harness/rules/ 吗？

**不建议**。Agent 没有"项目历史"和"团队约定"——它会复读训练数据里的通用约定。
**例外**：让 Agent 提**草案**（"你认为我们应该加哪条 rule"），然后你审核 + 落笔。

### Q3：我跑了 5 个 change 都没动 Skills，正常吗？

**正常**。Skills 通常季度才动一次。如果月月都改 Skills，反而说明项目还没稳定。

### Q4：summary.md 的「经验沉淀」段怎么填？

每个 HITL 点之后立刻写 1-2 行。例：
```
- ✅ 评审抓出 httpClient 反向依赖；下次 entity API 改造前先在 spec 显式写"是否涉及 shared 层"。
- ⚠️ Stage 4 编码评审循环 1 轮但发现 1 条 LOW（错误提示缺少语义前缀）；记入 coding-standard §8。
```

### Q5：我写完 spec.md 觉得不满意，能让 Agent 整个重写吗？

可以——直接说："spec.md 重写一次，重点强调 X"。这是 Stage 1 内的循环，不算回退。但**不要**改完 spec 后跳到 Stage 3——必须重跑 Stage 2 评审。

### Q6：Agent 反复说"完成"但其实没做完怎么办？

这是 Anthropic 提到的 **Premature Victory Declaration**。立刻：
1. 让 Agent 跑 `pnpm -C .harness run ci`，看真实退出码。
2. 看 `summary.md` 的阶段进度表——TODO 是不是还在 Stage 4？
3. 如果反复"假完成"，**改 Owner Agent 的「禁止做的」段落**加一条："声称完成前必须 cat 出 pnpm -C .harness run ci 的真实退出码"。

### Q7：可以多人并行开多个 change 吗？

可以。每个 change 一个独立目录 + 独立 git worktree。注意：
- 每个人在自己的 worktree 里跑 Claude Code 会话
- 合并时先 `pnpm -C .harness run doctor` 自检
- `wiki/` 的修改可能冲突，提前协调

### Q8：我能跳过 Stage 4 编码评审直接 commit 吗？

**绝对不能**。评审是唯一由独立视角检查语义正确性的环节；Stage 6 的 CI 只能拦编译、lint、格式和构建错误。
即使强行 push，评审缺失会在 `summary.md` 进度表上留下 TODO，团队会找你。

### Q9：HITL ② / ③ 我每次都直接说"确认"行吗？

**短期**可以——评审 Skill 已经把 80% 的问题挡住了。
**长期**有风险——你失去了"看一眼计划是否合理"的最后窗口。建议每次至少花 30 秒看：
- spec.md 的非目标段（看 Agent 有没有偷偷扩范围）
- review 的 verdict 和 MUST FIX 数量

### Q10：我接手了一个项目，`.harness/` 是 1 年前的，怎么办？

跑：
```bash
pnpm -C .harness run doctor                    # 看健康度
grep -A 3 "经验沉淀" .harness/changes/*/summary.md | head -50
```

把所有 change 的经验沉淀过一遍，按 [HARNESS-USAGE-GUIDE.md §七](./HARNESS-USAGE-GUIDE.md#七演化-harnesshashimoto-法则的实操路径) 的升级路径决策树重新整理 Skills / Rules。这通常需要 2-3 天。

---

## 进一步阅读

- [HARNESS-USAGE-GUIDE.md](./HARNESS-USAGE-GUIDE.md) — 日常使用 Manual
- [.harness/rules/dev-workflow.md](../.harness/rules/dev-workflow.md) — 8 阶段权威定义
- [.harness/agents/platform-owner.md](../.harness/agents/platform-owner.md) — Owner Agent 编排逻辑
