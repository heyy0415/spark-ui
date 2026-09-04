# Harness 详细使用指南

> 本文档是日常参考手册（Manual）。
> - 想知道**谁写什么、新需求 22 步实战** → [AUTHORING-GUIDE.md](./AUTHORING-GUIDE.md)

## 目录

- [一、心智模型](#一心智模型)
- [二、日常开发循环](#二日常开发循环)
- [三、什么时候该调哪个 Skill](#三什么时候该调哪个-skill)
- [四、改了 X 应该改 Harness 的哪里](#四改了-x-应该改-harness-的哪里)
- [五、出错怎么办（按现象索引）](#五出错怎么办按现象索引)
- [六、新人入场（首次接手项目）](#六新人入场首次接手项目)
- [七、演化 Harness：Hashimoto 法则的实操路径](#七演化-harnesshashimoto-法则的实操路径)
- [八、与 Claude Code 的高效协作模式](#八与-claude-code-的高效协作模式)
- [九、反模式清单（一定不要做）](#九反模式清单一定不要做)
- [十、迁移到现有项目](#十迁移到现有项目)

---

## 一、心智模型

### 三层抽象，一句话讲清楚

```
Prompt Engineering：写好"一封邮件"     —— 优化单次对话
Context Engineering：附上"所有附件"     —— 优化单个上下文窗口
Harness Engineering：搭一个"工厂流水线" —— 设计跨会话、跨 Agent、跨阶段的工程系统
```

**Harness 不是技巧，是基础设施。**

### Harness 的 ROI 公式

```
ROI = (返工减少 × 团队规模 × 时间) - 一次性建设成本
```

- 一次性建设成本：`.harness/` 约 20 个文件，约 1 周。
- 复利收益：每个后续 change 节省 30-60%（评审循环减少、跨会话冷启动消失）。

按经验，**第 5-8 个 change 之后就回本**。

### 四要素 + 8 阶段 + 5 HITL = 一张工作流

```
┌─ 四要素（物理载体在 .harness/）────────────┐
│ Rules    告诉 Agent "标准是什么"            │
│ Skills   告诉 Agent "应该怎么做"            │
│ Wiki     告诉 Agent "系统是什么样的"        │
│ Changes  记录 Agent "做了什么"              │
└─────────────────────────────────────────────┘
              │
              │ 在 8 阶段流程中被调度
              ▼
┌─ 8 阶段（详见 .harness/rules/dev-workflow.md）─────┐
│ 1需求 →2评审 →3编码 →4评审 →5推送               │
│                                  │                │
│   8用户确认 ←─ 7部署 ←─ 6 CI ←──┘                │
└────────────────────────────────────────────────────┘
              │
              │ 每个 HITL 确认点暂停等用户
              ▼
   5 个 Human-in-the-Loop 确认点
   ① 需求待决议  ② 计划评审通过  ③ 编码评审通过
   ④ 部署参数    ⑤ 最终交付
```

---

## 二、日常开发循环

### 一个标准 change 的最小操作序列

```bash
# 0. 启动会话（Claude Code 自动加载 CLAUDE.md）
cd ~/projects/your-app

# 1. 创建 change 骨架
pnpm -C .harness run new-change feat dashboard-export

# 2. 在 Claude Code 中提需求（不要超过 3 段）
"我需要给 /dashboard 加导出 PDF 的能力。
点击右上角"导出"按钮，弹出对话框选时间范围，确认后下载。
不需要在线预览。"

# 3. Owner Agent 自动调度 8 阶段
#    - Stage 1 → 你看 spec.md / tasks.md
#    - HITL ② → 你回 "确认进入编码"
#    - Stage 3-4 → 你看产出物
#    - HITL ③ → 你回 "进入推送"
#    - Stage 5 → 你执行 git commit（或让 Claude 执行）
#    - Stage 6 → pnpm -C .harness run ci
#    - HITL ④ → 你确认部署参数
#    - Stage 8 → 你回 "确认交付"

# 4. summary.md 状态置 DELIVERED，change 归档
```

### 每天首次会话的启动序列

每次新会话或 `/clear` 之后，**强制**走启动序列（CLAUDE.md 已写明）：

```
1. pwd
2. ls .harness/changes/                    # 找未交付的 change
3. cat .harness/changes/{...}/summary.md   # 读进度
4. 定位下一个待办阶段
5. 触发对应 Skill
```

**不**在不知道当前进度的情况下接需求。

### 一周的节奏推荐

| 周日/周一 | 跑 `pnpm -C .harness run doctor`，看 `.harness/` 健康度 |
| 工作日 | 1-3 个 change 并行（不同 worktree）|
| 周五前 | 把停留 > 7 天的 change 关掉或重启 |
| 月末 | review 本月的 `summary.md.经验沉淀` 段，把发现升级到 Skill / Rule |

---

## 三、什么时候该调哪个 Skill

### 决策表

| 我现在的状态 | 下一步触发哪个 Skill | 原因 |
|---|---|---|
| 第一次进入这个项目 / `/clear` 后第一次启动 | `project-analysis` | 快速建立项目地图，避免冷启动 |
| 用户给了需求，但我还没写任何文件 | `request-analysis` | 先把需求结构化（spec + tasks）|
| 写完 spec.md 了 | `expert-reviewer` (plan) | 评审计划，不要直接动手 |
| 评审通过了，要开始写代码 | `coding-skill` + 加载对应分层 spec | 按 FSD 分层 + 硬约束写 |
| 代码写完，准备进 PR | `code-review` 先 + `expert-reviewer` (execution) | 机器化检查在前，语义评审在后 |
| 准备部署到预览环境 | `deploy-verify` | 烟囱测试 + bundle 比对 |
| 任意阶段卡住 / CI 反复红 | `frontend-doctor` | 系统化诊断，禁止"再试一次" |

### 反模式：什么时候**不**调 Skill

- 用户问"`useState` 和 `useReducer` 怎么选" → 不调 Skill，直接回答（这是知识问题，不是开发任务）。
- 用户问"我们项目用什么路由库" → 不调 Skill，读 `package.json` 即可。
- 用户改个错别字 → 不调 Skill，直接 Edit。

> **判别原则**：「会产生 commit 的工作」走 Skill；「不会产生 commit 的工作」直接回答。

---

## 四、改了 X 应该改 Harness 的哪里

### 决策表

| 你改了什么 | 同步修改 .harness/ 的哪里 |
|---|---|
| 在 `entities/{x}/model/types.ts` 改 schema | [`wiki/api-contracts.md`](../.harness/wiki/api-contracts.md) + [`wiki/domain-model.md`](../.harness/wiki/domain-model.md) |
| 新增 / 删除一个 feature 切片 | [`wiki/architecture.md`](../.harness/wiki/architecture.md) 切片清单 |
| 引入新依赖（如换 TanStack Router） | [`agents/platform-owner.md`](../.harness/agents/platform-owner.md) 项目背景 + [`rules/coding-standard.md`](../.harness/rules/coding-standard.md) 状态管理段 |
| 发现一类编码错误反复出现 | 升级 [`rules/coding-standard.md`](../.harness/rules/coding-standard.md) 红线 + oxlint 规则（**优先**自动化） |
| Skill 输出不稳定 | 修该 Skill 的 SKILL.md，明确「输出格式」段落（fewer prose, more checklist） |
| 加了新的 CI 步骤 | [`rules/dev-workflow.md`](../.harness/rules/dev-workflow.md) 阶段 6 门禁段落 |
| 加了新的 MCP server | [`mcp/servers.json`](../.harness/mcp/servers.json) + [`agents/platform-owner.md`](../.harness/agents/platform-owner.md) MCP 索引 |

**核心原则**：代码与 `.harness/` 的同步**必须在同一个 PR 里**，否则 Harness 会漂移失效。

### Hashimoto 法则的具体落地

```
每发现 Agent 一个错误：
  1. 复盘根因（用 frontend-doctor 5 步法）
  2. 决定升级到哪一层：
     - 能写成 oxlint 规则? → 写 rule（最高优先级，机械化执行）
     - 能写成 CI 检查命令? → 加到 pnpm -C .harness run ci
     - 只能写成文字约束? → 写到对应 Skill 的 Checklist 末尾
     - 仅一次性教训? → 写到 change 的 summary.md「经验沉淀」段
  3. 在下一个 change 的 Stage 0 自检
```

---

## 五、出错怎么办（按现象索引）

### 现象 → 触发 Skill / 定位

| 现象 | 第一步 | 第二步 |
|---|---|---|
| `pnpm typecheck` 报 schema 不一致 | 看 `entities/{x}/model/types.ts` 是不是改了 | 用 `frontend-doctor` 二分查找首个引入失败的 commit |
| 预览页面出现 console.error | `frontend-doctor`：复现 ≥3/5 | 看浏览器 console + source map 解出的栈 |
| 本地能跑 CI 跑不过 | 检查 `pnpm-lock.yaml` 是否提交 | 检查 Node 版本一致 |
| Agent 写出来的代码不符合 FSD | 立刻打断，调 `code-review` 跑 `check-deps.mjs` | 让 Agent 加载对应 spec 重写（不要让它"理解一下重新写"，必须显式 load spec） |
| Agent 跳过评审直接说"完成" | 在 `summary.md` 阶段进度表里看到 Stage 4 标 TODO | **永远**不接受 Stage 6 跳过 → 强制回 Stage 4 |
| Agent 在评审循环 ≥ 3 轮 | 立刻升级 HITL | 不要让 Agent 自我无限纠错（Harness 核心原则） |
| 跨切片导入被 oxlint 拦截 | 不要解禁 oxlint，去看 spec | 多半是没通过 `index.ts` 公共出口 |

### 通用诊断法（5 步）

详见 [`.harness/skills/frontend-doctor/SKILL.md`](../.harness/skills/frontend-doctor/SKILL.md)：

1. 复现到最小用例
2. 二分查找
3. 收集证据（trace、log、screenshot）
4. 形成假设 + 反证条件
5. 修 Harness 而不仅是修代码

---

## 六、新人入场（首次接手项目）

### Day 1：建立项目地图

```
> 第一句 prompt：
请用 project-analysis Skill 给我一份项目摘要，120 行以内。

预期输出：项目摘要 / .harness 索引 / src 切片清单 / 文档漂移 / 推荐阅读路径
```

### Day 1-2：阅读路径（按重要性）

1. [README.md](../README.md) — 5 分钟
2. [.harness/agents/platform-owner.md](../.harness/agents/platform-owner.md) — 10 分钟，**地图**
3. [.harness/rules/dev-workflow.md](../.harness/rules/dev-workflow.md) — 15 分钟，**8 阶段定义**
4. [.harness/rules/project-structure.md](../.harness/rules/project-structure.md) — 10 分钟，**FSD 红线**
5. [.harness/rules/coding-standard.md](../.harness/rules/coding-standard.md) — 15 分钟，**硬约束**
6. `.harness/changes/` 下最近一个已交付 change 的 `summary.md` — 10 分钟，**真实范例**

总计 ~60 分钟。读完你应该知道：
- 来一个新需求该按什么顺序产出什么文件
- 评审循环的红线在哪
- 出错时第一步是 `frontend-doctor` 不是"再试一次"

### Day 3：跟一次评审

不要直接接需求。先在已交付的 change 里挑一个，**重新跑一遍 Stage 2 评审**：

```
> 请用 expert-reviewer Skill 重新评审 .harness/changes/{某个已交付 change}/request_analysis/spec.md，告诉我你会给出哪些不同意见。
```

这是最低成本的训练——你能立刻看到自己的 review 关注点和 Skill 期望的关注点之间的差距。

### Week 1：跑一个 Dry Run change

挑一个**真实但不紧急**的小需求，按 8 阶段完整跑。重点不是产出，是**走流程**。

任何想"省一步"的冲动都立刻警觉——那是 Anthropic 提到的 Premature Victory 心理。

---

## 七、演化 Harness：Hashimoto 法则的实操路径

### 一次完整的演化 cycle

```
                      Day N+1
   Day N                 │
     │   发现问题         │
     ▼                    ▼
  ┌─────────────┐    ┌────────────────┐    ┌─────────────────┐
  │ Agent 出错  │ ─→ │ 写到 change 的 │ ─→ │ 月度 review 时 │
  │ 或漏需求    │    │ summary.md     │    │ 升级到 Skill /  │
  └─────────────┘    │ 经验沉淀段     │    │ Rule / oxlint  │
                     └────────────────┘    └─────────────────┘
```

### 升级路径决策

```
能机械化校验? ──是──> oxlint 规则 / CI step                 [最高优先级]
      │
      否
      ▼
能在 Skill Checklist 中明确? ──是──> Skill 的 Checklist 段落
      │
      否
      ▼
是稳定的硬约束? ──是──> Rule（rules/*.md）红线
      │
      否
      ▼
是当前 change 特有? ──是──> 留在 change 的 summary.md，不上升
```

**关键原则**：上升要谨慎。每条 Rule 都是一份"未来的认知负担"，写多了 Agent 反而抓不住重点。**OpenAI 的 100 行 AGENTS.md 经验**：超过那个长度，"全部重要 = 没有重要"。

### 已知的演化案例（本项目历史）

| 发现 | 升级到 | 文件 |
|---|---|---|
| 跨层只需要类型时被 check-deps 拦截 | 允许 type-only import 跨层 | [project-structure.md §2](../.harness/rules/project-structure.md) |
| httpClient 反向依赖 features/auth | 依赖注入模板（Pending）| 待写入 [04-shared-spec.md](../.harness/skills/coding-skill/specs/04-shared-spec.md) |
| MUST FIX 应包含微小问题还是只大问题 | "可一笔修订完成"的微小问题归 SHOULD | 待写入 [expert-reviewer/SKILL.md](../.harness/skills/expert-reviewer/SKILL.md) |

---

## 八、与 Claude Code 的高效协作模式

### 提需求的好范式

```
好示例 ✓
"给 /orders 加批量导出 CSV：
- 列表勾选行后右上角按钮可导出
- 导出列：订单号 / 创建时间 / 金额（分）/ 状态
- 不超过 10000 行
- 不做导出 Excel / PDF"

坏示例 ✗
"做个导出"
"参考 XX 网站的样式"
"先写个 demo"
```

差异：**好示例已经替 Owner Agent 写完了 spec 的 80%——非目标尤其关键**。

### 让 Agent 进入"严格模式"

如果 Agent 反复跳步骤或想偷懒：

```
"请严格按 dev-workflow.md 走 8 阶段。当前你在哪一阶段？请把 summary.md 阶段进度表更新后再说话。"
```

这一句通常能把 Agent 拉回流程。

### 让 Agent 自我评估其产出（**禁用**）

不要说 `你看自己的代码有没有问题`。Anthropic 已证实"Agent 无法准确评估自身产出"。

正确做法：

```
"请用 expert-reviewer Skill (execution 模式) 评审你刚才的实现。"
```

让另一个独立 prompt context 走评审 Skill——**分离执行与评判**。

### 当 Agent 想跳过 HITL

```
你（错）："好的你看着办，跳过确认就行"
你（对）："请在每个 HITL 确认点暂停等我回复。这是流程红线。"
```

HITL 是 5 个最便宜的安全网。绝不该被绕过。

---

## 九、反模式清单（一定不要做）

| # | 反模式 | 后果 | 替代 |
|---|---|---|---|
| 1 | 直接写代码不写 spec | 范围漂移、过度重构 | `request-analysis` Skill 先 |
| 2 | 评审让 Agent 自评 | 失去独立视角 | 单独 prompt 跑 `expert-reviewer` |
| 3 | "小改动跳流程" | 流程一致性破坏 | 流程一致性 > 流程效率 |
| 4 | Agent 口头说 "CI 过了" 就放行 | 无证据的假完成 | 让 Agent 打印 `pnpm -C .harness run ci` 的真实退出码 |
| 5 | "再试一次" 修构建失败 | 根因不明 | `frontend-doctor` 5 步法 |
| 6 | 顺手优化非范围内的代码 | PR 巨大、评审无效 | 另开 `chore-XXX` change |
| 7 | 把所有规则塞 AGENTS.md | "全部重要 = 没有重要" | 100 行索引 + 分层加载 |
| 8 | 自然语言定义质量门禁 | Agent 会偏离 | 程序化命令（退出码、文件存在）|
| 9 | 评审循环 > 3 轮硬撑 | 浪费时间 + 不收敛 | 立即升级 HITL |
| 10 | 不在 commit footer 写 Change ID | Audit Trail 断裂 | `Change: feat-xxx-YYYYMMDD` |
| 11 | 跨切片直接 import 内部模块 | FSD 破坏 | 通过 `index.ts` 公共出口 |
| 12 | 让 Agent 改 Harness 时也跑 Harness | 自指悖论 | 改 Harness 时人工主导，跑完再用 |

---

## 十、迁移到现有项目

如果你想把这套 Harness 引入一个**已有的大型前端项目**（10w+ 行 / 多年历史），按 5 步走：

### Step 1：同构性评估（1 天）

跑一遍：

```bash
ls -la                        # 是否已有 .harness/ ?
cat package.json | grep -E "react|vue|angular"  # 框架
cat tsconfig.json | grep -E "strict|noUncheckedIndexedAccess"  # 严格度
ls src/                       # 是否符合分层模式
```

**输出**一份「项目当前状态 vs Harness 假设」差异表。

### Step 2：最小可用 Harness（1-2 天）

不要一次性引入全部 7 个 Skill。**先**：

- `.harness/agents/platform-owner.md`（精简版，~150 行）
- `.harness/rules/project-structure.md`（按你项目实际分层写）
- `.harness/rules/coding-standard.md`（精炼至 50 行）
- `.harness/skills/{request-analysis,coding-skill,code-review}/SKILL.md`（3 个核心 Skill）
- `CLAUDE.md` 入口

不要 `wiki/`、不要 8 阶段，先跑 4 阶段：分析 → 编码 → 评审 → 推送。

### Step 3：跑 3 个 Dry Run change（1 周）

挑 3 个**已经做过的**老需求，让 Agent 在新 Harness 下"重做一遍"，每次记录 summary.md「经验沉淀」段。
3 次之后，Harness 已经被现实世界打磨过了。

### Step 4：扩展 Skills 与 Rules（持续）

按照 §七 的演化路径，每次发现问题就升级 Harness。
**不**在没有真实痛点驱动的情况下增加 Rule。

### Step 5：团队推广（2-4 周）

- Week 1：你自己跑流畅 5+ 个 change。
- Week 2：拉 1 个搭子配合，互相做 expert-reviewer。
- Week 3：开内部分享，展示 AI 代码采纳率数据。
- Week 4：团队全员入场，统一约定。

> **关键忠告**：不要把 Harness 作为 KPI 强推。先用结果说服自己，再用结果说服别人。

---

## 附录 A：每天的 5 分钟自检脚本

把这段贴到你的 `~/.zshrc`：

```bash
function harness_morning() {
  cd ~/projects/your-app || return
  echo "=== Harness Doctor ==="
  pnpm -C .harness run doctor
  echo ""
  echo "=== 进行中的 change ==="
  for d in .harness/changes/*/; do
    if grep -q "^| 状态 | DRAFT" "$d/summary.md" 2>/dev/null \
       || grep -q "TODO" "$d/summary.md" 2>/dev/null; then
      echo "  - $(basename $d)"
    fi
  done
}
```

---

## 附录 B：术语速查

| 术语 | 含义 |
|---|---|
| Harness | 围绕 Agent 设计的约束 / 反馈 / 编排系统总称 |
| Owner Agent | 编排中枢 Agent，串联 8 阶段 |
| Skill | 一份结构化 SOP，对应特定阶段的工作流程 |
| Rule | 不随需求变化的硬约束 |
| Wiki | 业务上下文知识库（按需加载）|
| Change | 一次需求从分析到交付的完整目录 |
| FSD | Feature-Sliced Design：pages → features → entities → shared |
| HITL | Human-in-the-Loop，人工确认点 |
| Audit Trail | 完整可追溯链 |
| Dry Run | 用虚拟需求空跑全流程 |
| MUST FIX / SHOULD / LOW / INFO | 评审意见 4 级分级 |

---

## 进一步阅读

- [Anthropic: Effective harnesses for long-running agents](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)
- [Anthropic: Harness design for long-running application development](https://www.anthropic.com/engineering/harness-design-long-running-apps)
- [OpenAI: Harness engineering: leveraging Codex in an agent-first world](https://openai.com/index/harness-engineering/)
- Mitchell Hashimoto: *"Every time you discover an agent has made a mistake, you take the time to engineer a solution so that it can never make that mistake again."*
