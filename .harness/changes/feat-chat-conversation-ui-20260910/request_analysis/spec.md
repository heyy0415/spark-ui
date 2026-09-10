# Spec: feat-chat-conversation-ui-20260910

> v1。用户原话：「优化一下 chat 页面的效果，使之类似一个聊天页面，有用户问题和生成式 UI 回答。不需要持久化存储用户信息。并且 SSE 的状态要内嵌在 spark-ui 引擎中，加载过程中就是骨架屏 + 状态文案 loading；并且优化一下当前工程根目录的 README.md，语言不要 AI 味道太浓。」

## 1. 背景

现状 `AgentChatPanel` 把「输入框 / chips / 消息列表 / 工具进度列表 / 当前屏」堆成一列：只有一块 `ui` 区域，每轮 `ui.replace` 覆盖上一屏；进度是独立的 `<ol>`；加载期没有任何视觉占位。不像聊天，也看不出「哪个问题对应哪个回答」。

## 2. 范围（In Scope）

### 2.1 会话模型（spark-chat，仅内存）

`AgentRunView` 改为**回合（turn）列表**：

```ts
interface ChatTurn {
  id: string;                 // 客户端生成
  runId: string | null;
  user: string;               // 用户原话（输入框 / chip / 行内指令都原样）
  status: 'streaming' | 'waiting_confirmation' | 'completed' | 'failed';
  tools: ToolProgress[];      // 该回合的工具进度（驻留在回合内，供 RunStatus 展示）
  texts: string[];            // message.delta
  ui: UiSchema | null;        // 该回合最终屏（ui.replace / ui.patch）
  pendingActionId: string | null;
  failure: { code; message } | null;
}
interface AgentRunView { conversationId; turns: ChatTurn[]; }
```

- 每次 `send()` 追加一个新回合；SSE 事件只归约到**最后一个**回合。
- 确认动作（`submitAction`）不新开回合：在同一回合内继续（status → streaming → completed），结果屏替换该回合的 `ui`。
- 历史回合的屏保留展示但**只读**：Form 禁用、行内按钮仍可点（是自然语言，无状态）、ActionBar 不渲染（令牌已失效）。只有最后一个回合可交互。
- 不持久化：刷新即空。`conversationId` 仍每次挂载生成。

### 2.2 SSE 状态内嵌到引擎（`@spark-ui/core`）

新增两个公共组件（进 `RUNTIME_EXPORTS`，`verify-pack` 基线重写）：

| 导出 | 说明 |
|---|---|
| `RunStatus` | 一行状态条：`status` ∈ `streaming / waiting_confirmation / completed / failed` + `tools: ToolStep[]`（`{displayName, status}`）+ 可选 `text`。streaming 时显示 `Spin`（antd）/ `SpinLoading`（antd-mobile）+ 当前步骤文案「正在查询订单…」（取最后一个非 succeeded 工具的 displayName；没有工具时「正在理解你的问题…」）；completed 显示「完成 · N 步」；failed 显示失败文案。对外 props 契约级，不暴露 antd 类型。 |
| `SchemaSkeleton` | 骨架屏：`variant` ∈ `table / card / form / generic`，用 antd `Skeleton` / antd-mobile `Skeleton`。回合 streaming 且尚无 `ui` 时渲染；`variant` 由 spark-chat 按最后一个工具的 toolId 粗猜（`*.list.*` → table，`*.detail.*` / `*.eligibility.*` / `*.preview` → card，其余 generic）——猜错只是骨架形状不同，不影响正确性。 |

- 文件放 `components/desktop/RunStatus.tsx` / `SchemaSkeleton.tsx` 与 `mobile/` 同名；`renderer/RunStatus.tsx` / `renderer/SchemaSkeleton.tsx` 按端型分发（同 `ActionBar` 模式）。
- `check-registry.mjs` 的文件名白名单增 `RunStatus`、`SchemaSkeleton`（都是官方组件 `Spin` / `Skeleton` 的映射，不是业务组件）。
- `ComponentHandlers` / `SchemaRenderer` 增可选 `readOnly?: boolean`：Form 组件在 readOnly 下 `disabled`；其余组件忽略。

### 2.3 聊天页布局（spark-chat）

- 页面 = 顶部标题栏（「Spark 助手」+ 一句副标题）+ 中间可滚动消息流 + 底部固定输入栏（输入框 + 发送 + 示例 chips 只在没有回合时显示）。
- 消息流每个回合两条气泡：右侧用户气泡（`data-role="user"`）；左侧助手气泡（`data-role="assistant"`）内依次：`RunStatus` → `texts`（`message.delta`）→ 骨架屏（streaming 且无 ui）或 `SchemaRenderer`（有 ui）→ `ActionBar`（仅最后回合且 waiting_confirmation）→ 失败文案。
- 新回合追加后自动滚到底部（`scrollIntoView`，只在用户没有手动上滚时）。
- 保留 e2e 依赖的 hook：`#agent-input`、`[data-chip]`、`[aria-label="对话消息"] li[data-role=user]`、`[data-screen-id]`、`[data-component-id]`、`[data-action-id]`、`[data-intent]`。「工具进度」`<ol aria-label="工具进度">` 改为 `RunStatus` 内部的 `<ol aria-label="工具进度">`（每个回合一份；e2e 步骤 5 的计数改为「最后一个助手气泡内」）。

### 2.4 README.md（仓库根）

按用户要求改写：去掉口号式排比与「一句话」体，改成工程师对工程师的说明；结构：这是什么 / 目录 / 跑起来 / 接到自己的服务 / 边界与限制 / 门禁 / 开发流程。数字与命令保持真实（e2e 159 / 37 / 12）。

## 3. 非目标

- 不做会话持久化（localStorage / 后端）；不做多会话切换；不做消息编辑 / 重发。
- 不改契约、不改后端。
- 不做流式文本打字机效果（`message.delta` 一次一条）。
- 不做移动端专门布局（沿用 `SparkDeviceProvider` 的组件级切换，页面布局用同一套 CSS 自适应）。

## 4. 核心场景

1. 空页：标题 + chips + 输入框。点「看看我的订单」→ 右侧用户气泡；左侧助手气泡先出 `RunStatus`（Spin +「正在理解你的问题…」）与 table 骨架 → `tool.selected` 后文案变「正在搜索订单…」→ `ui.replace` 骨架换成 Table，状态变「完成 · 1 步」。
2. 点 Table 行「查看物流」→ 新回合追加在下方，页面滚到底；上一回合的 Table 仍可见。
3. 「删除订单 10010」→ 助手气泡内 Card + ActionBar；确认 → 同一气泡内状态回到 loading → Result 替换 Card，ActionBar 消失。
4. 失败：气泡内 `RunStatus` 显示失败文案（红），无骨架。

## 5. 契约影响

NONE。

## 6. 验收

- `pnpm -C spark-ui run ci` 0；`verify-pack` 运行时导出 17 → 19（`RunStatus`、`SchemaSkeleton`），类型 +2（`RunStatusProps`、`SchemaSkeletonProps`），基线重写并记录。
- `check-registry`：白名单 ∪ {ActionBar, RunStatus, SchemaSkeleton}；植入 `components/desktop/ChatBubble.tsx` → 红。
- e2e-frontend：步骤 5 / 7 改为按回合断言（最后一个 `[data-role="assistant"]` 内的组件数、工具进度数）；新增：发送后 300ms 内存在 `[data-testid="spark-skeleton"]`；完成后不存在；两轮后 `[data-role="user"]` 计 2 且两个 `[data-screen-id]` 同时在 DOM；历史回合内无 `[data-action-id]`。用例数写死为 41。
- README：`grep -c "一句话\|赋能\|全面\|极致" README.md` 0（AI 味词表，机械守）。

## 7. 风险

- 骨架 variant 猜错：只影响形状，接受。
- 历史回合 Form 只读靠 `readOnly` 传给 Form 组件，其余组件本就无写入。
- verify-pack 体积基线会涨（Skeleton / Spin 是 antd peer，不打进包，涨幅应 < 2 KB）。
