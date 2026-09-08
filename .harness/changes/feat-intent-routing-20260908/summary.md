# Change Summary: feat-intent-routing-20260908

| 字段 | 值 |
|---|---|
| Change ID | feat-intent-routing-20260908 |
| 类型 | feat |
| 状态 | DRAFT |
| 负责人 | Platform Owner Agent |
| 涉及端 | backed / fronted / harness |
| 起止时间 | 2026-09-08 ~ — |
| Spec | [request_analysis/spec.md](request_analysis/spec.md) |
| Tasks | [request_analysis/tasks.md](request_analysis/tasks.md) |

## 阶段进度表

| # | 阶段 | 状态 | 评审轮次 | 产出 | 时间 |
|---|---|---|---|---|---|
| 1 | 需求分析 | DONE（v2.1） | — | spec.md（8 章）, tasks.md（9 task）；用户决策：规则优先 + 模型补位、规划前拦截缺实体、并入前后端评审遗留 8 项 | 2026-09-08 |
| 2 | 需求评审 | DONE | 2/3 | v1 RR（5 MUST：order 领域拦截规则失效、domains() 无 principal、幂等自检不可注入、e2e ④ 不可通过、freeze 破坏 check-registry；8 SHOULD）→ v2 → v2 **APPROVED**（0 MUST，6 SHOULD 已吸收为 v2.1）。HITL ② 用户「继续」 | 2026-09-08 |
| 3 | 编码实现 | DONE | — | T01–T09；e2e-backend 规则 62/62、LIVE 62/62（`source=model` 实证）；deploy-verify 12/12；e2e-frontend 21/21；全仓 ci 0；doctor 0 | 2026-09-08 |
| 4 | 编码评审 | IN PROGRESS | 1/2 | 机械项全绿；expert-reviewer execution 进行中 | 2026-09-08 |
| 5 | 代码推送 | TODO | — | — | — |
| 6 | CI 验证 | TODO | — | — | — |
| 7 | 部署验证 | TODO | — | — | — |
| 8 | 用户确认 | TODO | — | — | — |

## 契约变更
- NONE

## 经验沉淀
- （留空，每发现一个 Agent 错误后**先**在此追加一行，再决定是否升级到 Skill / Rule）
- 阶段 2：第 1 轮 5 条 MUST FIX 里有 3 条是「验收在现有数据 / 脚本形态下不可能通过」（种子订单都已退款、审计重放也记 succeeded、`Object.freeze` 破坏 check-registry 正则）——与前两个 change 同类。这次评审方在 /tmp 实测了 freeze 正则与 `order.list.search {}` 通过 inputSchema，一轮就收敛到 APPROVED。教训不变：spec 里每条含命令的验收先跑一遍。
- 阶段 2：「全部候选都要求实体才拦截」这条判定在 refund 领域成立、在 order 领域失效（`order.list.search` 无 required），是只看一个领域写出来的规则。教训：涉及「按候选集合属性判定」的规则，spec 要对每个已注册领域各举一例。
- 阶段 3：新增自检忘了打 `selfcheck: <name> OK` 日志行，e2e 首次即红。约定只存在于 e2e 脚本里、不在 `SelfCheck` 接口上。教训：跨模块的隐性约定要写进接口 Javadoc，或让 Runner 统一打日志、e2e 只认 Runner 行。
