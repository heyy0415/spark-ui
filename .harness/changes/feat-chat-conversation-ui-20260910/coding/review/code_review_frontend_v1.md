# Code Review (frontend) v1 — feat-chat-conversation-ui-20260910

- 评审对象：commit `1ecc9b1`（`spark-ui/**`、`.harness/scripts/e2e-frontend.mjs`、`.harness/scripts/harness-doctor.mjs`、`README.md`）
- 依据：`request_analysis/spec.md`、`.harness/rules/coding-standard.md`、`project-structure.md` §1、`agent-safety.md` §4、`coding/coding_report_v1.md`
- 模式：execution，有罪推定；只读评审，未改代码
- 环境事实：react 19.2.8、antd 6.6.2、antd-mobile 5.42.3；`dist/` 实测 46.87 KB（基线 47）；dist `.d.ts` grep 无 `antd` import；后端 `RunOrchestrator.confirm()` 路径不发 `run.started`，`waitForConfirmation` 发 `confirmation.required` 后 `sink.close()`

## 1. 必查项

| # | 必查项 | 结论 | 证据 |
|---|---|---|---|
| 1 | SSE 事件只归约到最后回合，有无竞态 | 基本成立；缺 runId 防御 | `runView.ts` `updateLast`；`useAgentRun.ts:99` abort 与 `beginTurn` 在同一同步段；`busy` 门禁 `send`。但 `reduceEvent` 不比对 `ev.data.runId` 与 `turn.runId` |
| 2 | `beginConfirm` 后 `run.started` 会否清 `failure` / `ui` | 不会清 `ui`；会清 `failure`（合理）。且后端确认路径根本不发 `run.started` | `runView.ts` `case 'run.started'`；`RunOrchestrator.java:263-335` 无 `RUN_STARTED` emit |
| 3 | 连接中断 `failIfStillStreaming` | **非 2xx / fetch 抛错时不会执行**，回合永久 `streaming`（骨架 + Spin 常亮） | `useAgentRun.ts:102-116`：`await consumeSse` 抛 `HttpError` 后跳过 `if (!ac.signal.aborted)` 块 |
| 4 | `ui.patch` 对非最后回合忽略 | 合理：事件只能属于当前 Run；`screenId` 不匹配即丢弃 | `runView.ts` `case 'ui.patch'` |
| 5 | React 19 ref 回调返回 cleanup | 成立（19.2.8；`@types/react` 19 `RefCallback` 允许返回 cleanup）。`if (!list) return` 在 19 的 cleanup 语义下是死分支 | `AgentChatPanel.tsx:105-116` |
| 6 | `stickRef < clientHeight` 长表格误判 | 有：阈值一屏过宽；且贴底策略让高于视口的新屏只露出**底部** | 截图 `deployment/ui-order-table.png`：表头与 RunStatus 被卷出视口，只见「共 30 单」页脚 |
| 7 | 历史回合 `readOnly` 是否真禁用 Form | 成立。antd 6 `Form disabled` 经 DisabledContext 到 Input / Select / InputNumber；antd-mobile 5 `Form disabled` → `FormItem` 读 `context.disabled` 并 `cloneElement(children, {disabled})` | `antd-mobile/es/components/form/form-item.js:44,195,292`；`Selector` 读 `props.disabled` |
| 8 | 历史回合行内按钮仍可点的安全性 | 无安全问题：`intent` 为自然语言、无令牌、走 `send()` 新回合，符合 `agent-safety` §4。`busy` 期间点击被静默丢弃（既有行为） | `Table.tsx:28-29`；`AgentChatPanel.tsx:57-69` |
| 9 | `RunStatus` / `SchemaSkeleton` 与「组件文件 = 官方组件映射」精神 | 勉强成立（Spin / Skeleton 组合），但**规则文档未同步**：`coding-standard.md` §4 仍写「`ActionBar` 例外」唯一；`project-structure.md` 仍写「17 运行时 + 19 类型」「renderer/ SchemaRenderer / UnknownComponent / ActionBar」 | `coding-standard.md:35`、`project-structure.md:34,37` |
| 10 | `runStatusText.ts` 放 `registry/` 是否合适 | 不合适：它是文案工具，放 `registry/` 只是为了过 `check-registry` 的 import 白名单 `../../registry/*` | `check-registry.mjs:103-104` |
| 11 | d.ts 是否泄露 antd 类型 | 未泄露。`RunStatusProps` / `SchemaSkeletonProps` 纯字面量；verify-pack (g) 通过；实测 dist grep 为空 | `registry/types.ts:37-57` |
| 12 | verify-pack 基线 47 KB 合理性 | 数字真实（46.87 KB），但 spec §7 预估「< 2 KB」，实涨 8 KB（+20%），coding_report 未解释偏差；新组件两端实现全部 eager 打进 `index.js`（13.9 KB），非 `lazy` chunk | `verify-pack.baseline.json`；`dist/index.js` 13957 B |
| 13 | e2e MutationObserver「曾出现」断言是否空洞 | 不空洞。骨架在 `beginTurn` commit 后立即存在，消失依赖网络帧（至少一个宏任务后），MO 回调是微任务，必然观察到；配合「skeleton gone after ui.replace」形成闭环。`attributes: true` 多余 | `e2e-frontend.mjs:121-129` |
| 14 | `:last-of-type` 在 user / assistant 交替 `<li>` 下是否选对 | 选对：每回合固定 user li 后接 assistant li，最后一个 li 总是 assistant。但若将来在 `<ol>` 末尾加任何 li（如输入指示器）即失效 | `e2e-frontend.mjs:111`；`AgentChatPanel.tsx:200-232` |
| 15 | README 语气 | 达到工程说明口吻；数字（159 / 12 / 50）可追溯到既有 report。个别陈述已过时（见 LOW） | `README.md` |
| 16 | doctor 词表误伤 | 范围仅根 README，误伤面小；但 `全面` / `赋予` 是常用中性词（「全面校验」「赋予权限」），子串匹配会误红。且 `ChatPage` 的 `<h1>` 仍用「用一句话查订单…」，与 README 禁词自相矛盾 | `harness-doctor.mjs:90`；`ChatPage.tsx:13` |
| 17 | `apps/chat` 不 import antd；只用 `@spark-ui/core` 包入口 | 成立 | `AgentChatPanel.tsx:3-10` |
| 18 | TS strict / 无 `any` / `import type` | 成立 | 全部新增文件 |

## 2. 发现表

### MUST

| # | file:line | 问题 | 建议 |
|---|---|---|---|
| M1 | `spark-ui/apps/chat/src/features/agent-chat/api/useAgentRun.ts:102-116` | `stream()` 中 `await consumeSse(...)` 在非 2xx（`HttpError`）或网络异常（fetch `TypeError`）时抛出，`failIfStillStreaming` 被跳过。回合永久停在 `streaming`：气泡内骨架 + Spin 常亮，`busy` 归 false 后用户再发新消息，旧回合也永远不会被收尾（`failIfStillStreaming` 只碰最后回合）。spec §4 场景 4「失败：气泡内 RunStatus 显示失败文案（红），无骨架」在 HTTP 级失败下不成立。结构与改前相同，但改前没有骨架 / Spin，视觉上现在是「永久加载」 | `try { await consumeSse(...) } finally { if (!ac.signal.aborted) setQueryData(failIfStillStreaming) }`；或 catch 后 `reduceEvent`-等价地写入 `{ status: 'failed', failure: { code: 'INTERNAL_ERROR', message } }` 再 rethrow 让 mutation 仍报错 |
| M2 | `spark-ui/apps/chat/src/features/agent-chat/model/runView.ts` `beginTurn`（约 L52-66）+ `AgentChatPanel.tsx:198` | 用户在确认屏未处理时直接发新消息：上一回合 `status` 仍为 `waiting_confirmation`，`canConfirm` 因 `!isLast` 为 false → ActionBar 消失，但 `RunStatus` 在历史气泡里永久显示「请确认后继续」，无任何可确认的东西。改前 `beginTurn` 会撤掉含 submit 的旧屏，本次删掉了这段却没有给回合定终态。spec §2.1 定义历史回合「ActionBar 不渲染（令牌已失效）」，状态文案应与之一致 | `beginTurn` 追加新回合前对 `lastTurn` 做 `status === 'waiting_confirmation' ? { ...t, status: 'completed', pendingActionId: null } : t`（或引入 `'cancelled'` 文案「已跳过确认」）。e2e 增加：确认屏未处理 → 发新消息 → 历史气泡 `[data-run-status]` 不为 `waiting_confirmation` 且 `[data-action-id]` = 0 |

### SHOULD

| # | file:line | 问题 | 建议 |
|---|---|---|---|
| S1 | `spark-ui/apps/chat/src/app/router/RootLayout.module.css:1-12,23-29` + `pages/schema-playground/SchemaPlaygroundPage.module.css:1-7` | `.shell` 改为 `100dvh; overflow:hidden`，`.main` 也 `overflow:hidden`，只有 chat 的 `.stream` 自己滚。`/dev/schema` 的 `.wrap` 没有 `flex:1; min-height:0; overflow:auto`，`order-table`（20 行）在 375×900 下被裁切且无处滚动。e2e 步骤 6 只数 DOM 节点，不会发现 | `.wrap` 加 `flex: 1; min-height: 0; overflow-y: auto;`；或把 `overflow:hidden` 下沉到 `ChatPage`，`RootLayout` 保持可滚 |
| S2 | `.harness/rules/coding-standard.md:35`、`.harness/rules/project-structure.md:34,37` | 规则与脚本分叉：`check-registry.mjs` 白名单已是 `{ActionBar, RunStatus, SchemaSkeleton}`，规则文档仍写「`ActionBar` 例外」；`project-structure.md` 的「17 运行时 + 19 类型」与 renderer 清单过时。Hashimoto 法则要求规则先改 | 更新两处；把三者收敛为 `check-registry.mjs` 顶部一个命名常量 `RUNTIME_ONLY_COMPONENTS` 并在规则里引用同名 |
| S3 | `spark-ui/packages/core/src/registry/runStatusText.ts` | 文案工具放 `registry/`，唯一原因是 `check-registry` 只放行 `../../registry/*` / `../../schema/*`。`registry/` 的职责是注册表与渲染签名类型；这是为了过门禁而错放 | 移到 `packages/core/src/lib/runStatusText.ts` 并在 `ALLOWED_IMPORT` 增加 `\.\.\/\.\.\/lib\/[a-zA-Z]+`；或直接内联进 `renderer/RunStatus.tsx` 由 renderer 计算文案后当 prop 传给两端实现 |
| S4 | `spark-ui/apps/chat/src/features/agent-chat/ui/AgentChatPanel.tsx:98-116` | 跟随滚动三点：(a) `bottomRef.scrollIntoView` 会滚动**所有**可滚祖先，`AgentChatPanel` 作为可嵌入宿主页面的组件（README 明示）会把宿主页面一起卷走；(b) 新屏高于视口时贴底导致用户只看到表格底部，表头 / 状态条不可见（截图 `ui-order-table.png`）；(c) 「上翻不足一屏仍跟随」阈值过宽，用户在读一张 1400px 表格中部时，任何 RO 触发（窗口 resize、lazy chunk 到达）都会把人拽回底部 | 用 `streamRef.current.scrollTop = scrollHeight` 替代 `scrollIntoView`；新回合追加时优先滚到该回合**用户气泡顶部**（`li[data-role=user]:last` 的 `offsetTop`），仅当回合内容不足一屏时贴底；阈值改为固定像素（如 80px） |
| S5 | `.harness/scripts/e2e-frontend.mjs` 步骤 5 / 7 | spec §6 验收「历史回合内无 `[data-action-id]`」与 §2.1「历史回合 Form 禁用」都没有对应断言：步骤 5 的 `no action bar after confirm` 是同回合替换，不是历史回合；全脚本没有一处检查 `readOnly` 生效（如 `li[data-role=assistant]:not(:last-of-type) .ant-form input[disabled]`）。M2 的缺陷正是因此漏网 | 步骤 5 结尾追加：再发「看看我的订单」→ 断言历史气泡 `[data-action-id]` = 0、历史 Form 控件 `disabled`、历史 `[data-run-status]` ≠ `waiting_confirmation` |
| S6 | `spark-ui/scripts/verify-pack.baseline.json` + `coding_report_v1.md` | spec §7 预估「涨幅 < 2 KB」，实际 39 → 47 KB（+8 KB，+20%）。report 只写「基线重写」，未解释为何是预估 4 倍。构成：7 个新 `.d.ts` ≈ 2 KB，其余 ≈ 6 KB 是两端 RunStatus / SchemaSkeleton 全部 eager 进 `index.js`（契约组件走 `lazy` chunk，这两个和 ActionBar 一样不走） | report 补一句偏差原因；评估把 mobile / desktop 实现改为与契约组件相同的 `lazy` 分包（同时也顺手治 ActionBar），否则桌面宿主会加载 antd-mobile 的 RunStatus 代码 |
| S7 | `spark-ui/apps/chat/src/features/agent-chat/ui/AgentChatPanel.tsx:141-151,196` | `TurnView` 未 `memo`。每个 SSE 帧都 `setQueryData` → `view.turns` 新数组 → 所有历史回合（含 20 行 antd Table）整体重渲。历史 `turn` 对象引用是稳定的（`updateLast` 只换最后一个），`memo` 会直接生效；但 `send` 依赖 `busy`，`onIntent` 引用随 `busy` 变化——可接受（每回合两次） | `const TurnView = memo(function TurnView(...) {...})`；`send` 改为读 `busyRef` 去掉对 `busy` 的依赖 |
| S8 | `spark-ui/apps/chat/src/features/agent-chat/model/runView.ts` `reduceTurn` | 事件不校验 `ev.data.runId === t.runId`（`run.started` 之后）。当前靠 `abort()` 与 `beginTurn` 同步、`busy` 门禁保证安全，但契约每帧都带 runId，加一行比对可把「旧流残帧写进新回合」从「靠时序」变成「靠数据」 | `if (t.runId && ev.event !== 'run.started' && ev.data.runId !== t.runId) return t;` |
| S9 | `spark-ui/apps/chat/src/pages/chat/ChatPage.tsx:13` + `ChatPage.module.css:11-18` | 页面 `<h1>` 文案「用一句话查订单、看物流、办售后、退款」用了 doctor 刚为 README 封禁的「一句话」；同一 change 一边禁一边用。且 `<h1>` 被样式化成 13px 灰色副标题，语义与呈现错位（`aria-labelledby` 指向它） | 文案改为陈述式（如「查订单、看物流、办售后、退款」）；若要副标题就用 `<p>`，`<h1>` 保持真正的标题（可视觉隐藏） |

### LOW

| # | file:line | 问题 | 建议 |
|---|---|---|---|
| L1 | `.harness/scripts/harness-doctor.mjs:90` | 词表 `全面` / `赋予` / `无缝` 为常用中性词，子串匹配会误伤「全面校验」「赋予权限」等正当表达；只扫 README 所以影响可控 | 改为带上下文的短语（「全面赋能」「赋能业务」）或允许行内 `<!-- doctor:allow -->` 标记 |
| L2 | `.harness/scripts/e2e-frontend.mjs:111` | `li[data-role="assistant"]:last-of-type` 依赖「最后一个 li 恰是 assistant」这一 DOM 巧合；任何尾部 li 都会让选择器选空 | 改为 `page.$$` 取最后一个 `li[data-role="assistant"]` 的 handle，或给最后回合加 `data-last="true"` |
| L3 | `.harness/scripts/e2e-frontend.mjs:127` | MO `attributes: true` 多余（骨架出现 / 消失是 childList 变化），只增加回调次数 | 去掉 |
| L4 | `spark-ui/packages/core/src/components/mobile/RunStatus.tsx:8-13,31` | 直接用 `var(--adm-color-danger)` / `var(--adm-color-weak)` 无 fallback；桌面侧用了 `var(--spark-color-text-muted, #646a73)` 带 fallback。`mobile/Card.tsx` 有同样先例，规则「包内 CSS 只允许 `--spark-*` 且带 fallback」对 `--adm-*` 未明说 | 与桌面一致改 `--spark-*` + fallback，或在规则里明文放行 antd-mobile 自身变量 |
| L5 | `spark-ui/packages/core/src/registry/types.ts:55-57` + `runView.ts skeletonVariant` | `SchemaSkeletonProps.variant` 含 `form`，spark-chat 永不产出 `form`（无对应 toolId 规则）；公共 API 里留着一个没有生产者的值 | 要么 `skeletonVariant` 对 `*.confirm*` / `*.refund.*` 之类给 `form`，要么删掉 `form` |
| L6 | `spark-ui/packages/core/src/registry/runStatusText.ts:15` | 「正在{displayName}…」假设 displayName 是动宾短语；契约只约束 1–80 字符，`displayName: "订单详情"` 会渲染「正在订单详情…」 | 文案改为「{displayName} 进行中…」或「正在执行：{displayName}」 |
| L7 | `README.md`（门禁段、开发流程段）；`request_analysis/tasks.md` T03/T05 | README 说「组件文件只能是官方组件的映射」已有 3 个例外；「最近一次是 refactor-spark-embedded-starter」在本 change 合入后过时；tasks.md 仍写 41 条 e2e，report 已是 50 | 顺手同步 |
| L8 | `spark-ui/apps/chat/src/features/agent-chat/ui/AgentChatPanel.tsx:106-108` | React 19 对返回 cleanup 的 ref 回调不再以 `null` 调用，`if (!list) return` 是死分支（无害） | 删除或加注释说明为 18 兼容 |
| L9 | `spark-ui/packages/core/src/registry/runStatusText.ts:26` | `Record<RunStatusProps['tools'][number]['status'], string>` 可直接写 `Record<ToolStep['status'], string>` | 简化 |
| L10 | `spark-ui/apps/chat/src/features/agent-chat/ui/AgentChatPanel.tsx:57-61` | `busy` 期间点历史回合行内按钮被 `send` 静默丢弃，无反馈（既有行为，本次把历史屏永久留在页面上后更容易触发） | 行内按钮在 `busy` 时置灰：`SchemaRenderer` 已有 `readOnly`，可再传 `disabled` 或 `onIntent` 传 `undefined` |

## 3. Verdict

**REJECT（需回修）** — MUST 2 / SHOULD 9 / LOW 10。

M1 是「失败即永久加载」的正确性缺陷，M2 是历史回合状态文案与 spec 定义直接矛盾；两者都能在本地无后端（M1：关掉 8080 再发消息）或有后端（M2：触发确认屏后直接发新消息）一分钟内复现，且 e2e 当前都没覆盖（S5）。

## 4. 最小补丁清单

1. `useAgentRun.ts` `stream()`：`try / finally` 包住 `consumeSse`，`finally` 内 `if (!ac.signal.aborted) setQueryData(failIfStillStreaming)`；失败文案可按 `HttpError` 带状态码。（M1）
2. `runView.ts` `beginTurn`：追加新回合前把 `lastTurn` 若为 `waiting_confirmation` 收敛为 `completed`（`pendingActionId: null`）。（M2）
3. `e2e-frontend.mjs` 步骤 5 末尾：再发一条消息，断言历史气泡 `[data-action-id]` = 0、历史 `[data-run-status]` ≠ `waiting_confirmation`、历史 Form 控件 `disabled`。（S5，守 M2）
4. `SchemaPlaygroundPage.module.css` `.wrap` 加 `flex:1; min-height:0; overflow-y:auto`。（S1）
5. `coding-standard.md` §4、`project-structure.md` §1 同步 `RunStatus` / `SchemaSkeleton` 例外与 19 / 23 导出计数。（S2）
6. `runStatusText.ts` 移出 `registry/`（`lib/` + 白名单），或内联进 `renderer/RunStatus.tsx`。（S3）
7. `AgentChatPanel.tsx`：`scrollIntoView` → 容器 `scrollTop` 赋值；新回合优先滚到用户气泡顶部；`TurnView` 加 `memo`。（S4、S7）
8. `coding_report_v1.md` 补 verify-pack +8 KB 与 spec §7 预估偏差的原因。（S6）
9. `ChatPage.tsx` 标题文案去掉「一句话」。（S9）

以上 1–3 为回修 v1 的最低门槛；4–9 建议同批处理。
