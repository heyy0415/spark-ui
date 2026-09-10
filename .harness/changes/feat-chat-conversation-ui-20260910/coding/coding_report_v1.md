# Coding Report v1 — feat-chat-conversation-ui-20260910

日期：2026-09-10 · 基线：change 5 末态（e2e-frontend 37/37、verify-pack 39 KB、运行时导出 17）

## 做了什么

| task | 内容 |
|---|---|
| T01 core | `RunStatus`（antd `Spin` / antd-mobile `SpinLoading` + 状态文案；进度明细 `<ol aria-label="工具进度">`）、`SchemaSkeleton`（`Skeleton`，variant table / card / form / generic，根节点 `data-testid="spark-skeleton"`）；`ComponentHandlers.readOnly` → Form 禁用；`index.ts` 运行时导出 17 → 19、类型 +4；`check-registry` 白名单 +2；`verify-pack` 基线 39 → 47 KB（`--write-baseline` 重写） |
| T02 spark-chat | `runView.ts` 改为回合列表 `turns[]`（用户原话 + 该 Run 的进度 / 文本 / 屏 / 终态）；SSE 只归约到最后回合；确认在同回合内继续；`AgentChatPanel` 重写为消息流（右侧用户气泡 / 左侧助手气泡：`RunStatus` → 文本 → 骨架或屏 → 仅最后回合的 `ActionBar`）+ 底部输入栏 + ResizeObserver 跟随到底（用户上翻超一屏停止，新回合恢复）；历史回合 `readOnly`；`RootLayout` / `ChatPage` 改为视口高、只消息流滚动 |
| T03 e2e | 步骤 5 / 7 按「最后一个助手气泡」断言；新增：骨架屏与 streaming 状态曾出现（MutationObserver 记录，本机后端几十毫秒就回屏）、确认后同回合 / 确认屏被替换 / 无 ActionBar / completed、两回合两屏共存、五回合计数；37 → 50 |
| T04 README | 根 README 重写；doctor 增「口号式词表」门禁（一句话 / 赋能 / 全面 / 极致 / 开箱即用 / 无缝 …），植入「赋能」→ 红 |

## 验收（真实输出）

```
pnpm -C spark-ui run ci                      exit 0（verify-pack 19 运行时 / 23 类型 / 47 KB）
pnpm -C .harness run ci                      exit 0
SPARK_CHANGE=… e2e-frontend（5199）          50 passed / 0 failed（对新起的 host-demo 8091；对已退过 10001 的旧实例会在确认步失败——种子状态，非本 change）
pnpm -C .harness run doctor                  0 errors 0 warnings（含 README 词表）
```

## 决策与偏差

1. **`RunStatus` / `SchemaSkeleton` 进 core 而不进契约**：它们是 SSE 阶段的宿主 UI，不是后端下发的 `componentType`；与 `ActionBar` 同类。`check-registry` 文件名白名单显式列出三者，仍禁止业务命名组件。
2. **确认不新开回合**：确认屏 → 结果屏是同一个 Run，放同一气泡里替换更符合直觉；e2e 断言「确认后仍只有 1 个用户气泡」。
3. **历史回合行内按钮仍可点**：行内指令是自然语言、无状态，点了就是新回合；只有 Form 与 ActionBar 随回合失效。
4. **骨架 variant 靠 toolId 粗猜**（`.list.` → table，`detail / eligibility / status / preview / logistics` → card），猜错只影响形状。
5. **跟随滚动用 ResizeObserver + ref 回调**：按事件猜时机（rAF / effect deps）在表格渲染完成前就滚，量到 `scrollTop=0`；观察内容容器高度变化后稳定在底部（实测 813 / 1377 ≈ scrollHeight - clientHeight）。
6. **e2e 用例数 50 而非 spec 写的 41**：把确认阶段拆成 4 条独立断言、步骤 7 多回合 3 条、五回合计数 1 条。
7. 前端 e2e 对「已跑过一次退款」的后端实例会在确认步失败（10001 已退，重校验拒绝）——这是种子数据状态，脚本内已有说明；`deploy-verify` 自起实例不受影响。
