# Change Summary: test-frontend-unit-tests-20260911

| 字段 | 值 |
|---|---|
| Change ID | test-frontend-unit-tests-20260911 |
| 类型 | test |
| 状态 | PUSHED |
| 负责人 | Platform Owner Agent |
| 涉及端 | spark-ui / harness（契约无变更） |
| 起止时间 | 2026-09-11 ~ 2026-09-11 |
| Spec | [request_analysis/spec.md](request_analysis/spec.md) |
| Tasks | [request_analysis/tasks.md](request_analysis/tasks.md) |

## 阶段进度表

| # | 阶段 | 状态 | 评审轮次 | 产出 | 时间 |
|---|---|---|---|---|---|
| 1 | 需求分析 | DONE | — | spec.md, tasks.md | 2026-09-11 |
| 2 | 需求评审 | DONE | 2/3 | spec_review_v1.md（REVISION REQUIRED：vitest 5 不兼容 Node 20、node:fs 不可用）→ v2（APPROVED） | 2026-09-11 |
| 3 | 编码实现 | DONE | — | coding_report_v1.md（9 文件 / 99 用例，spark-ui ci 0） | 2026-09-11 |
| 4 | 编码评审 | DONE | 1/2 | code_review_v1.md（APPROVED，0 MUST FIX；3 SHOULD 已吸收）；ci_result/ci_stage4.txt | 2026-09-11 |
| 5 | 代码推送 | DONE | — | eef46d5 | 2026-09-11 |
| 6 | CI 验证 | SKIP | — | 未配置 GitHub Actions（改造第 4 项）；本地 ci 退出 0 见 ci_result/ci_stage4.txt | 2026-09-11 |
| 7 | 部署验证 | SKIP | — | 本 change 不改运行时产物（build:chat 同 hash 同体积，评审 INFO 已核对），沿用上一 change 的 deploy-verify 结论 | 2026-09-11 |
| 8 | 用户确认 | PENDING | — | 待用户书面「确认交付」 | — |

## 契约变更
- NONE

## 经验沉淀
- spec 起草时按「最新版」选了 vitest 5，未核对 `engines.node` / optional peer `@types/node` 与本仓库 Node 20 + `strict-peer-dependencies=true` 的关系 → 评审 v1 M-1。教训：引入新 devDependency 前先 `npm view <pkg> engines peerDependencies peerDependenciesMeta`，对照 `.npmrc` 与根 `engines`。
- spec 写「测试用 node:fs 读契约示例」，但两个包的 tsconfig 都没有 `@types/node` → 评审 v1 M-2。教训：为测试选夹具加载方式前先看目标包 tsconfig 的 `types`。
- 相对路径层级数错一级（4 级 vs 5 级）连犯两次（spec 与测试文件）→ 评审 v2 SHOULD、首次跑测失败。教训：跨包相对路径先 `ls` 验证再写进文档。
- chat 测试经 `@spark-ui/core` 入口连带加载 antd-mobile，其 CJS 入口顶层 `require("./global.css")` 在 Node 下崩；`server.deps.inline` / `ssr.noExternal` / alias 到 es 均无效，只有 `deps.optimizer.ssr.include` + esbuild `.css` → `empty` loader 可行。`esbuildOptions` 在 Vite 8 已标弃用（每次跑测告警一次），是本方案的已知风险；后续若失效改为 `test.alias` 把 antd-mobile 指向空 stub。已写进 vite.config 注释。
- oxlint `unicorn/no-array-sort` 禁 `sort()`，而 core 的 lib 目标无 `toSorted` 类型 → 集合相等断言改用 `toHaveLength` + `expect.arrayContaining`。
- 顺带发现（非本 change 范围，待办）：`runView.ts` `skeletonVariant` 返回类型含 `'form'` 但无分支产生。
