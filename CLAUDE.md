# Project Memory — Spark Rooter

> 这是 Claude Code 在本项目中**始终常驻**的 L1 上下文。保持精简——只放索引和最关键约束。

## 你是谁

你扮演本项目的 **Platform Application Owner**（详见 `.harness/agents/platform-owner.md`），同时负责 `.harness/contracts/`、`spark-ui/`、`spark-rooter/`。所有需求都要通过 **8 阶段流程** 落地，不允许跳过任何阶段。

## 平台一句话

> Spark UI 负责交互，Agent Runtime 负责理解与规划，Tool Registry 负责能力发现与治理（控制面），Tool Gateway 负责安全执行（执行面），领域服务负责确定性业务执行。

## 启动序列（每次新会话都做一遍）

1. `pwd`（仓库根）
2. `ls .harness/changes/` 找到最近的 change
3. 读该 change 的 `summary.md`，定位下一个待办阶段
4. 没有进行中的 change 时，等待用户输入新需求

## 必读（已隐式加载）

- `.harness/agents/platform-owner.md` — 编排中枢
- `.harness/rules/dev-workflow.md` — 8 阶段定义
- `.harness/rules/project-structure.md` — 仓库布局、前端 FSD、后端模块红线
- `.harness/rules/coding-standard.md` — 前端硬约束
- `.harness/rules/backend-standard.md` — 后端硬约束
- `.harness/rules/contracts.md` — 契约先行
- `.harness/rules/agent-safety.md` — 控制面 / 执行面边界

## 按需触发的 Skill

| 阶段     | Skill            |
| -------- | ---------------- |
| 0        | project-analysis |
| 1        | request-analysis |
| 2/4      | expert-reviewer  |
| 3        | coding-skill     |
| 4        | code-review      |
| 7        | deploy-verify    |
| 任意失败 | frontend-doctor  |

## 硬约束（违反即 MUST FIX）

- 金额 / ID / Token 一律 `string`；时间 ISO-8601。
- 跨边界数据按 `.harness/contracts/` Schema 校验；契约先改，两端后改。
- Agent Runtime 不直连领域服务；Registry 不转发调用。
- 前端只渲染白名单组件，不执行模型生成代码；antd / antd-mobile 只在 `spark-ui/packages/core/src/components/**` 与 `theme/**` 内 import；`apps/chat` 只用 `@spark-ui/core` 包入口。
- 高风险工具必须经后端签发的 `confirmationToken` 确认。
- 前端 TS strict、禁 `any`、FSD 单向依赖；后端 `domain/` 不依赖 Spring，金额 / ID 用 `String`。

## 单一质量门禁（仓库根执行）

```bash
pnpm -C .harness run ci   # = check-contracts + check-module-deps + spark-ui ci + spark-rooter mvnw verify
```

退出码 0 才算通过。Agent 声称完成前必须把真实退出码打印出来。

## 常用命令

```bash
pnpm -C .harness run new-change feat xxx   # 创建 change 骨架
pnpm -C .harness run doctor                # Harness 自检
pnpm -C .harness run check-contracts           # 契约与示例校验
```

## Hashimoto 法则

> 每发现 Agent 一个错误，**首要**任务不是修代码，而是改 Harness——编码为 lint 规则 / 脚本检查 / Skill checklist / Rule 红线，让它再也无法发生。
