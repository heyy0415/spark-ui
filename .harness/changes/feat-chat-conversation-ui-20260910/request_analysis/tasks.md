# Tasks: feat-chat-conversation-ui-20260910

> v1。所属端 spark-ui / harness / docs。契约与后端不动。

### T01 core：RunStatus + SchemaSkeleton + readOnly
- 目标：`components/{desktop,mobile}/RunStatus.tsx`、`SchemaSkeleton.tsx`；`renderer/RunStatus.tsx`、`renderer/SchemaSkeleton.tsx` 按端型分发；`registry/types.ts` 增 `RunStatusProps` / `SchemaSkeletonProps` / `ToolStep`，`ComponentHandlers.readOnly?`；`Form`（两端）在 readOnly 下禁用；`index.ts` 导出；`check-registry` 白名单 +2；`verify-pack` 导出清单 +2 / +2、基线重写；README 组件表补两行。
- 验收：`pnpm -C spark-ui run ci` 0；植入业务命名组件文件 → 红。

### T02 spark-chat：回合模型 + 聊天布局
- 目标：`runView.ts` 改 turns；`useAgentRun` 归约到最后回合、确认在同回合；`AgentChatPanel` 重写为消息流 + 底部输入栏 + 自动滚底；`ChatPage` 标题栏；CSS 气泡样式。
- 验收：typecheck / lint / format 0；手工核对 spec §4 四个场景。

### T03 e2e-frontend 改造
- 目标：步骤 5 / 7 按回合断言；新增骨架屏、两屏共存、历史回合无 ActionBar 断言；用例数 41。
- 验收：`SPARK_FRONT_BASE=http://localhost:5199 node e2e-frontend.mjs` 41/41。

### T04 README 改写 + doctor 词表门禁
- 目标：根 `README.md` 重写；`harness-doctor` 增「README AI 味词表」检查（`一句话 / 赋能 / 全面 / 极致 / 赋予`）。
- 验收：doctor 0；植入「赋能」→ 红。

### T05 验收与冻结
- `pnpm -C .harness run ci` 0；deploy-verify 12/12；e2e-frontend 41/41；`coding_report_v1.md`。

依赖：T01 → T02 → T03；T04 独立；T05 最后。
