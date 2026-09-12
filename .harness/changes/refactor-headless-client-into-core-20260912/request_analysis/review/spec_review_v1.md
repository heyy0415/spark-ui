# Spec Review v1 — refactor-headless-client-into-core-20260912

- **mode**: plan
- **评审对象**: `request_analysis/spec.md`、`request_analysis/tasks.md`
- **依据**: `expert-reviewer/SKILL.md`（plan 必查项）、`rules/{contracts,project-structure,coding-standard,dev-workflow}.md`、公司 TypeScript 规范
- **verdict**: **APPROVED**（0 条 MUST FIX，3 条 SHOULD）

> **独立性声明**：subagent 通道在本会话不可用，本文由 spec 作者撰写，独立性不满足。补偿：spec 的每处技术断言在阶段 1 实测（并据此修正了两处设计）；本文只复核「实测是否支撑结论」。§4 列建议他人复核项。

---

## 0. 事实断言核对

| spec 断言 | 实测 | 证据 |
|---|---|---|
| 六个文件共 793 行待迁 | ✓ | `wc -l` 逐个确认 |
| `runView.ts` 零框架依赖 | ✓ | 只 import `UiSchema` 类型与 `@entities/agent-run` 类型，无 react |
| react-query 仅服务 `useAgentRun` | ✓ | 全仓 grep 得 5 处：1 处真实使用 + 3 处 Provider 脚手架 + 1 处 package.json |
| `Transport` 抽象已存在 | ✓ | `agentRunApi.ts:9`，`{ baseUrl: string; fetch: typeof fetch }` |
| env 有两层兜底 | ✓ | 应用层 `AgentChatPanel:43`（正确）+ 传输层 `sseClient:39` / `httpClient:47`（死代码） |
| 6 个测试文件需迁移 | ✓ | `find` 确认；spec §1.3 与 tasks 头部已列全 |
| core 当前 `exports` 只有 `.` 与 `./style.css` | ✓ | `package.json` 确认，需新增两个子入口 |

**阶段 1 的核实修正了 spec 的两处设计**（见 §1.3）：原本写「改为必须由调用方传入 baseUrl」，实际 `Transport` 已经是必填抽象，真正要做的只是删掉传输层永不生效的 `??` 兜底。这个修正让 T02 的范围明确缩小。

## 1. 逐条意见

### S-1 ｜ SHOULD ｜ `client/` 禁 react 的规则需覆盖 `react/` 目录的反向约束

- **位置**: spec §2.5、tasks T05
- **核对**: 新规则是「`client/**` 不得 import react / antd」。但 `react/**` 只说了「允许 react、不得 antd」，**没有机械守护**。
- **问题**: `react/useSparkRun.ts` 若不小心 import antd（比如为了个 `message.error` 提示），会破坏「渲染层与绑定层分离」——antd 只应出现在 `components/**` 与 `theme/**`（`project-structure.md` §1 红线 10）。现有 `check-deps.mjs` 对 core 的规则是按 `packages/core/src/**` 整体判的吗？需确认它是否已覆盖 `react/`。
- **建议**: T05 顺带确认现有 oxlint 的 `no-restricted-imports`（`.oxlintrc.json` 的 `packages/core/src/**` override）是否已覆盖新目录。若已覆盖则记录结论，否则补规则。成本很低。
- **分级**: SHOULD

### S-2 ｜ SHOULD ｜「行为零变化」缺少一个比 e2e 更细的判据

- **位置**: spec §4、§6 验收 3、tasks T08
- **核对**: 唯一判据是三套端到端全过。这能证明「主链路没坏」，但 e2e 覆盖的是**用户可见路径**——`runView` 的 10 种事件归约里，`ui.patch`、`message.delta` 的边界分支（如 `screenId` 不匹配时丢弃）在 e2e 里未必走到。
- **问题**: 纯重构最怕的是「主链路对了，边界悄悄变了」。`runView.test.ts` 随迁移带过去能守护，但 spec 没把它列为**独立判据**，只在风险表里提了一句。
- **建议**: 验收 7 改为明确的数字断言：「`runView.test.ts` 的 N 个用例全过」（N 取迁移前实测值），而不是笼统的「单测仍全过」。这样若有人在迁移时顺手删掉一个"看起来多余"的用例，验收会红。
- **分级**: SHOULD

### S-3 ｜ SHOULD ｜ T01 的「临时 re-export」是个易留尾巴的过渡态

- **位置**: tasks T01 输出第 3 条
- **核对**: T01 把契约投影迁到 core，但子入口在 T05 才建立，所以 T01 写「先用相对路径或临时 re-export 保证可编译」。
- **问题**: 「临时」措施若 T05 忘了清理就变成永久的。而且 `apps/chat` 用相对路径 import core 的 `src/`（如 `../../../packages/core/src/client/contracts`）会违反红线——`apps/chat` 只能用 `@spark-ui/core` 包入口，禁深路径（`project-structure.md` §1 红线 2）。
- **建议**: 调整顺序，**把 T05 的子入口建立提前到 T01 之前**（改为 T00）。`exports` 里加两个子入口指向尚不存在的文件会让 typecheck 报错，所以更稳的做法是：T00 只建空的 `client/index.ts` 与 `react/index.ts` 加 `exports` 配置，后续 task 往里填内容。这样每个 task 结束时 chat 都能用正式的 `@spark-ui/core/client` 路径，不需要任何临时措施。
- **分级**: SHOULD（不阻塞，但建议采纳——它消除了一类返工）

### I-1 ｜ INFO ｜ 契约投影真源统一是本 change 的实质收益

`contracts.md` §1 现在的分工（ui-schema 在 core、其余 8 个在 chat）确实是权宜之计，spec §1.1 的判断成立：8 个契约里只有 ui-schema 被 core 用到，其余放 chat 的唯一理由是「chat 恰好是唯一消费者」。迁移后真源统一在 core，这比「让别人能 npm 装」更实在——它消除了一个需要解释的例外。

### I-2 ｜ INFO ｜ 不用 react-query 的论证充分

现有代码只用 `setQueryData` / `getQueryData`，没用缓存失效、重试、后台刷新任何一项。SSE 推送模型下「数据过期需重新获取」这个概念不存在。用 `useSyncExternalStore` 是 React 18+ 的官方解法，且让 core 不新增 peer 依赖——这一点对「不发包但保持结构干净」的目标是对的。

### L-1 ｜ LOW ｜ FSD 规则的同步范围

spec §7 提到「chat 不再有 entities 层」可能与 FSD 规则冲突，T07 会改 `project-structure.md`。补充一点：`.oxlintrc.json` 或 `check-deps.mjs` 里若有「FSD 依赖方向」的硬编码层级列表（`pages → features → entities → shared`），entities 层消失后需确认它不会因「找不到该层」而报错或静默失效。T06 的 lint 验收会暴露。

## 2. plan 必查项

| 项 | 结果 |
|---|---|
| 「非目标」存在且非空 | ✓ 7 条，明确排除了第 16/17/18/19 项与 UI 改动 |
| 每条验收可被命令 / 断言校验 | ✓ 8 条全部可命令化；验收 7 偏笼统（S-2） |
| 风险章节 ≥1 失败模式 + 缓解 | ✓ 6 条 |
| task 标注所属端；contracts task 前置 | ✓ 契约文件零改动（只改投影位置），无 contracts task |
| 跨端结构列契约文件 | N/A |
| 每个 task ≤ 0.5 天 | ✓ T01–T03 各为 1–2 文件搬迁 + 单测同迁；T04 约 25 行 store + hook 改写；T05 配置与守护；T06 删除与改引用；T07 文档；T08 只跑验收 |

## 3. 回退

`APPROVED` → 进阶段 3。三条 SHOULD 建议在编码时处理：**S-3 调整 task 顺序（建议采纳，消除返工）**、S-1 确认 oxlint 覆盖范围、S-2 把验收 7 改为数字断言。

## 4. 建议他人复核的条目

1. **`useSyncExternalStore` 是否真能等价替代**。我判断可以（视图状态由事件序列决定，无需缓存语义），但没有实测证据——只有编码后的 e2e 能证明。
2. **契约投影全部迁入 core 是否合适**。反方观点：`intent-request` / `action-request` 是「前端发给后端」的，某种意义上属于应用关心的事。我认为它们同属 spark 协议，但这个边界可以讨论。
3. **S-3 的 task 顺序调整**。
