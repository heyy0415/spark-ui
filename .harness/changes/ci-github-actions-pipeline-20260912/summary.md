# Change Summary: ci-github-actions-pipeline-20260912

| 字段 | 值 |
|---|---|
| Change ID | ci-github-actions-pipeline-20260912 |
| 类型 | ci |
| 状态 | PUSHED |
| 负责人 | Platform Owner Agent |
| 涉及端 | harness（契约无变更；不改两端业务代码） |
| 起止时间 | 2026-09-12 ~ — |
| Spec | [request_analysis/spec.md](request_analysis/spec.md) |
| Tasks | [request_analysis/tasks.md](request_analysis/tasks.md) |

## 阶段进度表

| # | 阶段 | 状态 | 评审轮次 | 产出 | 时间 |
|---|---|---|---|---|---|
| 1 | 需求分析 | DONE | — | spec.md, tasks.md（含 3 轮原型实测校准） | 2026-09-12 |
| 2 | 需求评审 | DONE | 1/3 | spec_review_v1.md（**APPROVED**，0 MUST FIX / 3 SHOULD）；独立性不满足（subagent 通道不可用），局限见文件 §4 | 2026-09-12 |
| 3 | 编码实现 | DONE | — | coding_report_v1.md（T01–T05 完成，9 步门禁 exit 0） | 2026-09-12 |
| 4 | 编码评审 | DONE | 1/2 | code_review_v1.md（**APPROVED**，0 MUST FIX / 2 SHOULD）；独立性不满足，局限见文件 §5 | 2026-09-12 |
| 5 | 代码推送 | DONE | — | 本地 commit（push 待用户执行） | 2026-09-12 |
| 6 | CI 验证 | PENDING | — | 本 change 即是配置 CI；**首次运行需 push 后才发生**，结果将回填此处 | — |
| 7 | 部署验证 | DONE | — | deploy-verify 12 passed（`planner=fake-e2e`）+ e2e-backend 161 passed + e2e-frontend 7 passed，三套脚本在 java-home 改造后全部实跑通过 | 2026-09-12 |
| 8 | 用户确认 | TODO | — | — | — |

## 契约变更
- NONE

## 经验沉淀
- （留空，每发现一个 Agent 错误后**先**在此追加一行，再决定是否升级到 Skill / Rule）

## 关键成果

| 项 | 状态 |
|---|---|
| GitHub Actions 三 job（gates → e2e ∥ deploy-verify） | 已配置，待首跑 |
| 脚本摆脱本机 JDK 路径（8 处） | 完成，三套脚本实跑验证 |
| shellcheck 入门禁 | 完成，5 个脚本零告警 |
| `check-log-assertions` 门禁 | 完成，自证有效（插入失实断言即红） |
| CONTRIBUTING + PR 模板 | 新增 |

阶段 6 自四个 change 以来首次不再是 SKIP —— 它现在有了可运行的定义。

## 遗留债务

- **预热步骤在空缓存下的有效性未验证**（评审 S-1）。`mvn -o` 需要 host-demo 的外部 parent 与 boot 插件在本地仓，本机有缓存所以验不出来。若 CI 首跑失败，备选：去掉 `-o`，或改用 `dependency:go-offline`。
- **e2e 与 deploy-verify 两 job 的固定开销重复**（评审 S-2）。各自 checkout、装依赖、下 chromium。待首跑拿到真实耗时后决定是否合并。
- **actionlint 未在本机运行**（未安装）。工作流的静态校验只做了 YAML 解析，以 CI 首跑为准。

## 经验沉淀

- **spec 里的技术方案必须在阶段 1 用原型实测，不能靠推理**。`check-log-assertions` 的匹配逻辑在阶段 1 就迭代了三轮（单行 log 调用 → 全部字面量、整段匹配 → `{}` 通配、剩余误报 → 白名单），每轮都由实测驱动。若把这三轮留到编码阶段，spec 的设计描述会有两处是错的。这是上一 change「spec 事实断言必须附命令」教训的正向延伸：**不只是事实要核实，方案也要先跑通**。
- **「本机能跑」不等于「CI 能跑」，差异集中在三类**：写死的工具路径（8 处 JDK 路径）、已有的缓存（`mvn -o` 依赖 `~/.m2`）、独占的资源（端口）。前两类本 change 已解，第三类靠脚本既有的端口检查。**Hashimoto 候选**：`harness-doctor` 可加一条「脚本不得出现 `$HOME/` 开头的工具路径」的检查，让这类假设不能再悄悄引入。
- **静态徽章会骗人**。README 原有的 CI 徽章是 `shields.io` 静态图，看起来像状态实则恒绿，掩盖了「根本没有 CI」这个事实四个 change。换成真实 workflow 徽章后，没配 CI 就会显示红或 no status。
