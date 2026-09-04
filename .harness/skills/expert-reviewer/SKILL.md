---
name: expert-reviewer
description: 阶段 2 / 4 评审循环。触发场景："评审 spec"、"评审代码"、"plan review"、"code review"。基于不同模式（plan/execution）输出分级评审意见，给出 APPROVED 或 REVISION REQUIRED 决议。
---

# Skill: expert-reviewer

## 模式

| 模式 | 评审对象 | 关注点 |
|---|---|---|
| `plan` | spec.md + tasks.md | 完整性、范围合理、验收可校验、风险识别、契约先行、task 标注所属端 |
| `execution` | 代码变更 + coding_report | 契约一致、安全边界、错误处理、可访问性、是否符合 spec |

## 输入
- 评审对象路径
- 对应 Rules（plan: project-structure + contracts；execution: coding-standard + backend-standard + contracts + agent-safety + project-structure）

## 步骤
1. **独立性原则**：评审 Agent 不得阅读编码 Agent 的"自我评估"；只看产出物本身。
2. 按 mode 加载对应检查清单。
3. 逐条产出意见，每条必须含：
   - **位置**：文件:行号 或章节名
   - **问题**：具体观察到的现象（非感受）
   - **建议**：可操作的修改方向
   - **分级**：`MUST FIX` / `SHOULD` / `LOW` / `INFO`
4. 给出最终 verdict：
   - 0 条 MUST FIX → `APPROVED`
   - ≥1 条 MUST FIX → `REVISION REQUIRED`

## 输出
- 文件名约定：`{stage}_review_v{n}.md`，`n` 单调递增，**旧版本不删**（Audit Trail）。

## 必查项（按模式）

### plan
- [ ] 「非目标」章节存在且非空
- [ ] 每条验收标准都可被命令或断言校验
- [ ] 风险章节列出 ≥1 个失败模式与缓解措施
- [ ] 每个 task 标注所属端（contracts / backed / fronted），且 contracts task 排在依赖它的 task 之前
- [ ] 涉及跨端结构的 task 列出对应契约文件
- [ ] 每个 task 工作量 ≤ 0.5 天

### execution
- [ ] 契约：前端 Zod、后端 record 与 `.harness/contracts/` Schema 字段逐一一致
- [ ] 安全：`agent-safety.md` §1 四面职责、§2 发现、§3 确认、§4 前端边界、§5 Gateway、§6 流式 六条逐项核对
- [ ] 前端：FSD 红线、Zod 校验、无 `any` / `console.log`、金额 `string`、错误状态可观测
- [ ] 后端：`domain` 无框架依赖、Controller 无业务逻辑、无空 catch、日志无敏感信息、幂等键存在
- [ ] 改动 ≠ spec 时显式标注偏差并解释

## Checklist（产出文件本身）
- [ ] 含 verdict（APPROVED / REVISION REQUIRED）
- [ ] 含 mode 字段
- [ ] 每条意见有分级
- [ ] 文件名带 v{n} 版本号
