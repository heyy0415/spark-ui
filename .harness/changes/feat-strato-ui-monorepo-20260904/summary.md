# Change Summary: feat-strato-ui-monorepo-20260904

| 字段 | 值 |
|---|---|
| Change ID | feat-strato-ui-monorepo-20260904 |
| 类型 | feat |
| 状态 | DRAFT |
| 负责人 | Platform Owner Agent |
| 涉及端 | fronted / harness |
| 起止时间 | 2026-09-04 ~ — |
| Spec | [request_analysis/spec.md](request_analysis/spec.md) |
| Tasks | [request_analysis/tasks.md](request_analysis/tasks.md) |

## 阶段进度表

| # | 阶段 | 状态 | 评审轮次 | 产出 | 时间 |
|---|---|---|---|---|---|
| 1 | 需求分析 | DONE（v3.1） | — | spec.md（8 章）, tasks.md（8 task：T01 / T02a / T02b / T03 / T04 / T05 / T06 / T07）；用户澄清 4 项：包名 `@strato-ui/core`、只含渲染引擎、antd 为 peer、/dev/schema 留在 chat 应用 | 2026-09-04 |
| 2 | 需求评审 | HITL | 3/3 | v1 RR（4 MUST）→ v2 RR（2 MUST）→ v3 RR（2 MUST，均一行级：根 workspace 依赖、verify-pack 目录）→ 已按 v3 建议补为 spec v3.1。3 轮上限；HITL ② 用户「继续」确认 v3.1 进入编码 | 2026-09-04 |
| 3 | 编码实现 | IN PROGRESS | — | T01 起 | 2026-09-04 |
| 4 | 编码评审 | TODO | 0/2 | — | — |
| 5 | 代码推送 | TODO | — | — | — |
| 6 | CI 验证 | TODO | — | — | — |
| 7 | 部署验证 | TODO | — | — | — |
| 8 | 用户确认 | TODO | — | — | — |

## 契约变更
- NONE（ui-schema 的前端 Zod 投影从 apps/chat 迁到 packages/core，契约文件不变）

## 经验沉淀
- （留空，每发现一个 Agent 错误后**先**在此追加一行，再决定是否升级到 Skill / Rule）
- 阶段 2：三轮共 8 条 MUST FIX，全部是「验收命令在目标工具链下跑不通」而非方向错误（Node 无法 import antd-mobile ESM、Vite alias 前缀匹配、tsc exclude 破坏 d.ts、pnpm hoist 语义、node_modules 解析位置）。教训：spec 里每条含命令的验收，写之前先在 /tmp 最小夹具上跑一遍；评审方也这么做才抓到。建议 request-analysis Skill 增加「验收命令须附首次实测记录」。
- 阶段 2：第 2 轮评审自己给的建议（exclude vite-env.d.ts）在第 3 轮被证伪。评审建议同样要实测，不能因为来自评审就免检。
