# Change Summary: feat-strato-ui-monorepo-20260904

| 字段 | 值 |
|---|---|
| Change ID | feat-strato-ui-monorepo-20260904 |
| 类型 | feat |
| 状态 | DONE |
| 负责人 | Platform Owner Agent |
| 涉及端 | fronted / harness |
| 起止时间 | 2026-09-04 ~ 2026-09-08 |
| Spec | [request_analysis/spec.md](request_analysis/spec.md) |
| Tasks | [request_analysis/tasks.md](request_analysis/tasks.md) |

## 阶段进度表

| # | 阶段 | 状态 | 评审轮次 | 产出 | 时间 |
|---|---|---|---|---|---|
| 1 | 需求分析 | DONE（v3.1） | — | spec.md（8 章）, tasks.md（8 task：T01 / T02a / T02b / T03 / T04 / T05 / T06 / T07）；用户澄清 4 项：包名 `@strato-ui/core`、只含渲染引擎、antd 为 peer、/dev/schema 留在 chat 应用 | 2026-09-04 |
| 2 | 需求评审 | HITL | 3/3 | v1 RR（4 MUST）→ v2 RR（2 MUST）→ v3 RR（2 MUST，均一行级：根 workspace 依赖、verify-pack 目录）→ 已按 v3 建议补为 spec v3.1。3 轮上限；HITL ② 用户「继续」确认 v3.1 进入编码 | 2026-09-04 |
| 3 | 编码实现 | DONE | — | T01–T07 共 7 个 commit（移动与逻辑分开）；`fronted ci` 清 dist 后 0；verify-examples 16 OK；verify-pack 13 ✓（dist 40 KB 基线）；e2e-backend 47/47（== 基线）；e2e-frontend 21/21；deploy-verify 12/12；全仓 `run ci` 四段 0；doctor 0（新增 4 项检查）。`coding/coding_report_v1.md` | 2026-09-04 |
| 4 | 编码评审 | DONE | 2/2 | `code_review_v1.md` 机械项全绿 → `code_review_v2.md` **REVISION REQUIRED**（1 MUST FIX：L1 三文件 antd 措辞陈旧；7 SHOULD）→ 回修 → `code_review_v3.md` **APPROVED**（0 MUST FIX，2 SHOULD 已顺手修：catalog 解析、check-deps 抓 export/动态 import）。全部门禁复验 0。HITL ③ 用户「继续」 | 2026-09-04 |
| 5 | 代码推送 | DONE | — | 10 个 commit（`f146764`…`8c815e1`），移动与逻辑分开；工作区干净；无远端 | 2026-09-08 |
| 6 | CI 验证 | DONE | — | 清 dist 后 `run ci` 四段 0 → `ci_result/ci_summary.md`；chat bundle -1.0%，core dist 40 KB 新基线 | 2026-09-08 |
| 7 | 部署验证 | DONE | — | `deploy-verify` 12/12（预览走 core dist，`agent-input=1`，console.error 0）→ `deployment/preview_report.md`；不部署、不发包 | 2026-09-08 |
| 8 | 用户确认 | DONE | — | 用户「继续」确认。本地联调时发现：无 `entityId` 上下文直接发起退款 → 规则规划器产出空参数 → Gateway INPUT_INVALID → `TOOL_EXECUTION_FAILED`；不是本 change 回归，记为下一 change 的「无实体上下文的优雅拒绝」需求。不部署、不发包、无远端 | 2026-09-08 |

## 契约变更
- 1 处 description 注释路径（`ui-schema.schema.json` props.description：实现路径 → `@strato-ui/core`），字段与约束零变化，示例校验不变。ui-schema 的前端 Zod 投影从 apps/chat 迁到 packages/core。

## 经验沉淀
- （留空，每发现一个 Agent 错误后**先**在此追加一行，再决定是否升级到 Skill / Rule）
- 阶段 2：三轮共 8 条 MUST FIX，全部是「验收命令在目标工具链下跑不通」而非方向错误（Node 无法 import antd-mobile ESM、Vite alias 前缀匹配、tsc exclude 破坏 d.ts、pnpm hoist 语义、node_modules 解析位置）。教训：spec 里每条含命令的验收，写之前先在 /tmp 最小夹具上跑一遍；评审方也这么做才抓到。建议 request-analysis Skill 增加「验收命令须附首次实测记录」。
- 阶段 2：第 2 轮评审自己给的建议（exclude vite-env.d.ts）在第 3 轮被证伪。评审建议同样要实测，不能因为来自评审就免检。
- 阶段 3 T04：check-deps 的 import 正则只匹配 `import x from`，漏掉副作用裸导入 `import '@features/x'`——门禁自首期存在至今从未被负例证明过。植入反例第一次就抓到。教训再次印证：每个 check-* 脚本必须附带会红的负例（首期经验沉淀已提，本次真正落地到 coding_report 表格）。
- 阶段 3 T03a：`strict-peer-dependencies=true` 让 `pnpm install` 直接失败在 antd-mobile 传递依赖的 react ≤18 声明上，spec 三轮评审都没预见。教训：涉及包管理器严格模式的决策，spec 阶段应在真实依赖树上跑一次 install。
- 阶段 4：唯一 MUST FIX 是 CLAUDE.md / AGENTS.md / platform-owner.md 三份 L1 文件里「antd 只在 shared/ui/**」这句陈旧硬约束——spec §6.1 的 grep 关键字和 doctor 路径检查都抓不到它，因为它引用的是包名片段而非 `fronted/` 路径。教训：L1 文件里凡引用目录 / 包路径的硬约束，doctor 应把它们与 rules 的对应条目做一致性比对；本次先记入待办，下一 change 落地。
- 阶段 4：评审用 `--resolution-only` 证明 `peerDependencyRules` 三条精确放行是最小充分集，并指出日常 `pnpm install` 因 lock 已最新会跳过解析、不会暴露此类错误。教训：涉及 lock 的校验要用 `--frozen-lockfile` 或 `--resolution-only` 才有区分度。
- 阶段 4：check-deps 正则第二次被负例打穿（`export … from` 与动态 import）。同一门禁同一 change 内两次修补，说明「写门禁时先列全要抓的语法形态」比事后补更划算。
