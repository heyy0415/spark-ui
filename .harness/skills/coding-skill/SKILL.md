---
name: coding-skill
description: 阶段 3 — 编码实现。触发场景："写代码"、"实现 feature"、"开发组件"、"新增端点"、"实现工具"。基于 spec/tasks 按分层 Spec 完成代码变更，顺序 contracts → backed → fronted，产出 coding_report 并通过编译门禁。
---

# Skill: coding-skill

## 何时触发
阶段 2 评审通过 + 用户确认进入实现。

## 输入
- `spec.md` + `tasks.md`
- L1 Rules（已加载）：`project-structure` / `coding-standard` / `backend-standard` / `contracts` / `agent-safety`
- 分层规范（按 task 所属端加载，**不要全部加载**）：
  - 契约：`./specs/00-contract-spec.md`
  - 后端：`./specs/06-backend-module-spec.md`
  - 前端：`./specs/01-page-spec.md`、`02-feature-spec.md`、`03-entity-spec.md`、`04-shared-spec.md`、`05-styling-spec.md`

## 工作流（每个 task 重复）

```
按 task 所属端 load 对应 spec
read existing code in target module / slice
plan minimal diff
write code following spec
run 门禁：
  contracts → pnpm -C .harness run check-contracts
  backed    → node .harness/scripts/mvn.mjs -q -B compile
  fronted   → pnpm -C fronted typecheck
write coding_report entry
```

编码顺序固定：**contracts → backed → fronted**。前端不得在契约未落地时先写 Zod 投影。

## 输出
- 代码变更（最小必要 diff）
- `coding/coding_report_v{n}.md`，含：
  - 改动文件列表（路径 + 所属端 + 行数）
  - 新增 / 删除的公共出口、端点、契约文件
  - 关键决策（为什么这样做、放弃了什么方案）
  - `agent-safety.md` 六条边界自查结果
  - 已知限制 / 后续工作

## 硬性约束（重申，由 code-review 校验）

- 金额、ID 用 `string`（金额单位"元"、两位小数）；时间 ISO-8601。
- 所有跨边界数据按 `.harness/contracts/` Schema 校验。
- Agent Runtime 不直连领域服务；Registry 不转发调用。
- 前端只渲染注册表内组件；服务端状态走 TanStack Query。
- 后端 `domain/` 包不依赖 Spring。

## 反模式
- ❌ 先写前端再"回头补契约"。
- ❌ 在 Runtime 里 `new RestTemplate().postForObject(domainServiceUrl, ...)`。
- ❌ 前端组件按 `props.componentPath` 动态 import。
- ❌ 顺手把 spec 之外的代码也改了——另开 change。

## Checklist
- [ ] 三端门禁命令退出码均为 0
- [ ] coding_report 列出全部改动文件与所属端
- [ ] 改动未触碰 spec 之外的模块
- [ ] 新增跨端结构均有 `.harness/contracts/` Schema 与示例
