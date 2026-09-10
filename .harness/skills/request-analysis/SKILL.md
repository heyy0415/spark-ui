---
name: request-analysis
description: 阶段 1 — 需求分析。触发场景："新需求"、"需求分析"、"PRD 拆解"、"产出 spec"、"产出 tasks"。把用户原始需求转成结构化的 spec.md + tasks.md，标注契约、后端、前端三端影响，保证后续阶段有清晰契约。
---

# Skill: request-analysis

## 何时触发
- 用户给出新需求；或评审循环要求修订。

## 输入
- 用户原始需求（自然语言）
- 该 change 目录路径
- L1 Rules（已常驻）；按需查 `.harness/wiki/` 与 `.harness/contracts/`

## 步骤
1. **复述确认**：1 段话复述需求，明确「目标用户 / 业务价值 / 关键场景」。
2. **澄清假设**：列出所有不明确点，**禁止**自行推断。如有 ≥1 个 BLOCKING 假设，立刻向用户提问，不要写 spec。
3. **写 spec.md**（强制章节）：
   - **背景**
   - **范围（In Scope）**
   - **非目标（Out of Scope）**——阻止顺手过度重构的关键
   - **核心场景**：用户故事 / 运行链路（前端 → Runtime → Registry → Gateway → 领域服务 → 前端）
   - **契约影响**：新增 / 修改的 `.harness/contracts/*.schema.json` 清单
   - **验收标准**：可测试的断言列表（命令、HTTP 状态、SSE 事件序列、文件存在）
   - **风险与权衡**：至少覆盖 `agent-safety.md` 相关的一项
4. **写 tasks.md**（每个 task 六要素）：
   - 目标（Goal）
   - 所属端（Side）：contracts / spark-rooter / spark-ui / harness
   - 输入（Inputs）
   - 输出（Outputs）— 文件路径或可观测变化
   - 验收（Acceptance）— 可程序化校验
   - 依赖（Depends-on）— 其他 task ID；contracts task 必须先于两端实现

## 输出
- `request_analysis/spec.md`
- `request_analysis/tasks.md`
- 更新 `summary.md` 阶段 1 状态为 `DONE`

## 反模式
- ❌ "先实现 MVP，详情后续讨论"。
- ❌ "请确保性能良好" 这类不可校验的验收。
- ❌ 一个 change 同时塞进多个不相关领域。
- ❌ 前端 task 不依赖任何 contracts task 却消费新结构。

## Checklist
- [ ] spec.md 含 7 个强制章节
- [ ] 每个 task 含六要素
- [ ] 验收标准全部可程序化校验
- [ ] 「非目标」非空
- [ ] 「契约影响」列出文件名
