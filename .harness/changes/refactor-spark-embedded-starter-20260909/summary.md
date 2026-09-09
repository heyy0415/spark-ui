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
| 1 | 需求分析 | DONE（v2） | — | spec.md（8 章）, tasks.md（16 task）；用户决策：Starter 嵌入、方法级 @SparkTool、仅 embedded、全量改名 spark-ui / spark-rooter、内核不管身份 / 权限 / 页面上下文、前端只发自然语言、不拆 agent 包、会话记忆 + 澄清屏 | 2026-09-09 |
| 2 | 需求评审 | DONE | 2/3 | v1 RR（4 MUST：反射调用绕过 AOP 的边界与宿主 ThreadLocal 跨线程为空、默认 SessionIdResolver 无隔离、T07/T09 超粒度、验收空洞；8 SHOULD）→ v2 APPROVED（1 SHOULD N-2 viewer 破坏 parity → 改用 demo.whoami；2 LOW 编码期吸收）；HITL ② 用户已授权不阻断 | 2026-09-10 |
| 3 | 编码实现 | IN PROGRESS | — | T01 起 | 2026-09-10 |
| 4 | 编码评审 | TODO | 0/2 | — | — |
| 5 | 代码推送 | TODO | — | — | — |
| 6 | CI 验证 | TODO | — | — | — |
| 7 | 部署验证 | TODO | — | — | — |
| 8 | 用户确认 | TODO | — | — | — |

## 契约变更
- （列出本 change 新增 / 修改的 `.harness/contracts/*.schema.json`；无则写 NONE）

## 经验沉淀
- （留空，每发现一个 Agent 错误后**先**在此追加一行，再决定是否升级到 Skill / Rule）
