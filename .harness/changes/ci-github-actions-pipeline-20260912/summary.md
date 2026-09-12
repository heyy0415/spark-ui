# Change Summary: ci-github-actions-pipeline-20260912

| 字段 | 值 |
|---|---|
| Change ID | ci-github-actions-pipeline-20260912 |
| 类型 | ci（**工作流部分已按用户决定回退**，见下） |
| 状态 | DELIVERED |
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
| 6 | CI 验证 | N/A | — | 工作流已回退（见「范围调整」），阶段 6 门禁回到 `pnpm -C .harness run ci` | 2026-09-12 |
| 7 | 部署验证 | DONE | — | deploy-verify 12 passed（`planner=fake-e2e`）+ e2e-backend 161 passed + e2e-frontend 7 passed，三套脚本在 java-home 改造后全部实跑通过 | 2026-09-12 |
| 8 | 用户确认 | DONE | — | 用户确认：单人仓库不需要 GitHub Actions，删工作流保留门禁改进；不做发包 | 2026-09-12 |

## 契约变更
- NONE

## 经验沉淀
- （留空，每发现一个 Agent 错误后**先**在此追加一行，再决定是否升级到 Skill / Rule）

## 范围调整（阶段 8 用户决定）

用户明确本仓库是**单人开发**、**不发包**（「本工程仅提供代码，需要发包的可以自己把源码拿下来去发」）。据此回退：

| 删除 | 原因 |
|---|---|
| `.github/workflows/ci.yml` | 单人仓库没有「别人提 PR 需要自动验证」的场景，CI 的价值只剩「防自己忘跑门禁」，不值这个复杂度 |
| `.github/pull_request_template.md` | 不会给自己提 PR |
| `CONTRIBUTING.md` | 没有外部贡献者；README 已有快速开始与门禁命令，规则在 `.harness/rules/` |
| `.harness/changes/ci-run/` | 仅为 CI 的 `SPARK_CHANGE` 占位而存在 |
| README 的 CI 徽章、`dev-workflow.md` 阶段 6 的 GitHub Actions 描述 | 随工作流一并回退 |

**保留**（与 CI 无关的独立价值，回退后 `pnpm -C .harness run ci` 仍 9 步全绿）：

| 保留 | 独立价值 |
|---|---|
| `scripts/lib/java-home.sh` + 8 处路径改造 | 换机器、换 JDK 安装方式（jenv / sdkman / 系统包）都不用改脚本 |
| `check-shell`（shellcheck 入门禁） | 拦住 `bash -n` 查不出的问题，如上一 change 踩到的「变量名被全角括号吞掉」 |
| `check-log-assertions` | 拦住「断言依赖已删除的日志」——上一 change 里这类问题潜伏了三个 change |
| `ci.mjs` 的 host-demo 步骤注入 JAVA_HOME | 与上面同理 |

## 关键成果

| 项 | 状态 |
|---|---|
| 脚本摆脱本机 JDK 路径（8 处） | 完成，三套脚本实跑验证 |
| shellcheck 入门禁 | 完成，5 个脚本零告警 |
| `check-log-assertions` 门禁 | 完成，自证有效（插入失实断言即红） |

阶段 6 的门禁定义从「未配置 / SKIP」改为明确的「`pnpm -C .harness run ci` 9 步退出 0」。

## 遗留债务

工作流回退后，评审 S-1（预热步骤未在空缓存验证）、S-2（两 job 开销重复）、actionlint 未运行三条**随之作废**——它们都只在 CI 语境下成立。

保留的一条：

- **`check-log-assertions` 只校验 `key=` 侧**（评审 §5 第 4 条）。日志把 `status=succeeded` 改成 `state=succeeded` 能被抓到，但 `succeeded` 改成 `ok` 抓不到。这是 spec §2.4 基于实测的取舍（value 侧来源含枚举 `.name()` 与运行时拼接），本次要防的那类问题都是 key 侧消失。

## 经验沉淀

- **spec 里的技术方案必须在阶段 1 用原型实测，不能靠推理**。`check-log-assertions` 的匹配逻辑在阶段 1 就迭代了三轮（单行 log 调用 → 全部字面量、整段匹配 → `{}` 通配、剩余误报 → 白名单），每轮都由实测驱动。若把这三轮留到编码阶段，spec 的设计描述会有两处是错的。这是上一 change「spec 事实断言必须附命令」教训的正向延伸：**不只是事实要核实，方案也要先跑通**。
- **「本机能跑」不等于「CI 能跑」，差异集中在三类**：写死的工具路径（8 处 JDK 路径）、已有的缓存（`mvn -o` 依赖 `~/.m2`）、独占的资源（端口）。前两类本 change 已解，第三类靠脚本既有的端口检查。**Hashimoto 候选**：`harness-doctor` 可加一条「脚本不得出现 `$HOME/` 开头的工具路径」的检查，让这类假设不能再悄悄引入。
- **静态徽章会骗人**。README 原有的 CI 徽章是 `shields.io` 静态图，看起来像状态实则恒绿，掩盖了「根本没有 CI」这个事实四个 change。换成真实 workflow 徽章后，没配 CI 就会显示红或 no status。
