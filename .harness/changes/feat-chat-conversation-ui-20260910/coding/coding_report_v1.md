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

---

# 阶段 4 回修记录 v1（响应 `coding/review/code_review_frontend_v1.md`：2 MUST / 9 SHOULD / 10 LOW）

| # | 意见 | 处理 |
|---|---|---|
| M1 | `stream()` 抛错（HTTP 非 2xx / 网络）时回合永远 streaming | `consumeSse(...).finally(finalize)`：任何结束都收口；rejection 仍抛给 useMutation 展示 |
| M2 | 等确认时发新消息，旧回合状态卡在 waiting_confirmation（无 ActionBar 却显示「请确认后继续」） | `beginTurn` 先把上一回合的 waiting_confirmation 收口为 completed；e2e 新增「确认屏挂起 → 发新消息 → 无 waiting 状态 / 无 ActionBar / 历史 Form 禁用」 |
| S1 | RootLayout 固定视口高后 `/dev/schema` 长示例被裁切 | playground `.wrap` 自己滚 |
| S2 | 规则文档仍写「ActionBar 例外」「17 / 19 导出」 | coding-standard / project-structure 同步为三例外、19 / 23 |
| S3 | 文案工具错放 `registry/` | 移到 `lib/runStatusText.ts`，`check-registry` import 白名单增 `lib/` |
| S4 | `scrollIntoView` 卷走宿主页面；贴底看不到表头；阈值过宽 | 只滚 `.stream` 自身；新回合把最后一条用户气泡滚到顶部（`scrollTop = min(userTop, max)`）；停止跟随阈值改半屏。实测两回合后 scrollTop 1392 = 用户气泡顶 |
| S5 | 无「历史回合无 ActionBar / Form 禁用」断言 | 步骤 5 末尾 +4 条、步骤 7 +1 条（历史行内按钮仍可点）；e2e 50 → 54 |
| S6 | 基线 39 → 47 KB 未解释 | 两端 RunStatus / SchemaSkeleton 与 ActionBar 一样 eager 进 index.js（≈ 6 KB）+ 7 个 d.ts（≈ 2 KB）；lazy 分包与 ActionBar 一并留待下一 change |
| S7 | `TurnView` 未 memo，每帧重渲全部历史表格 | `memo(TurnView)`；`send` 改读 `busyRef`（effect 同步）不再依赖 `busy` |
| S8 | 归约不校验 runId | `reduceTurn` 对 run.started 之后的事件比对 `ev.data.runId === t.runId` |
| S9 | 页面 `<h1>` 用了被 doctor 封禁的「一句话」且被样式化成副标题 | `<h1>` 视觉隐藏保留 aria；副标题改 `<p>`「查订单、看物流、办售后、退款，直接说就行」 |

另：根 README 按用户要求再次重写为标准开源项目结构（徽章 / 目录 / 功能 / 架构 / 快速开始 / 接入 / 部署 Demo / 安全 / 门禁 / 限制 / 许可）；新增根 `Dockerfile` + `.dockerignore`（前端 dist 进 host-demo `classpath:/static`，单镜像 8080）与 `DemoStaticConfig`（`/` → index.html）。本机无 Docker daemon，用等价步骤验证：dist 拷入 static → `mvn package` → jar 内 43 个静态文件 → `GET /` 200 且 title 正确、assets 200、`POST /agent/runs` SSE 6 帧。

复验：spark-ui ci 0（47 KB）；e2e-frontend 54/54（新起 host-demo）；doctor 0（README 词表）；check-rename 0。
