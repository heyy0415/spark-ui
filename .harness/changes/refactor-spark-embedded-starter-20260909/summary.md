# Change Summary: refactor-spark-embedded-starter-20260909

| 字段 | 值 |
|---|---|
| Change ID | refactor-spark-embedded-starter-20260909 |
| 类型 | refactor |
| 状态 | DRAFT |
| 负责人 | Platform Owner Agent |
| 涉及端 | contracts / backed（→ spark-rooter）/ fronted（→ spark-ui）/ harness |
| 起止时间 | 2026-09-09 ~ — |
| Spec | [request_analysis/spec.md](request_analysis/spec.md) |
| Tasks | [request_analysis/tasks.md](request_analysis/tasks.md) |

## 阶段进度表

| # | 阶段 | 状态 | 评审轮次 | 产出 | 时间 |
|---|---|---|---|---|---|
| 1 | 需求分析 | DONE（v1） | — | spec.md（8 章）, tasks.md（16 task）；用户决策：Starter 嵌入、方法级 @SparkTool、仅 embedded、全量改名 spark-ui / spark-rooter、内核不管身份 / 权限 / 页面上下文、前端只发自然语言、不拆 agent 包、会话记忆 + 澄清屏 | 2026-09-09 |
| 2 | 需求评审 | IN PROGRESS | 0/3 | — | 2026-09-09 |
| 3 | 编码实现 | TODO | — | — | — |
| 4 | 编码评审 | TODO | 0/2 | — | — |
| 5 | 代码推送 | TODO | — | — | — |
| 6 | CI 验证 | TODO | — | — | — |
| 7 | 部署验证 | TODO | — | — | — |
| 8 | 用户确认 | TODO | — | — | — |

## 契约变更
- （列出本 change 新增 / 修改的 `.harness/contracts/*.schema.json`；无则写 NONE）

## 经验沉淀
- （留空，每发现一个 Agent 错误后**先**在此追加一行，再决定是否升级到 Skill / Rule）
