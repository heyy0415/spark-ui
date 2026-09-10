# Code Review (frontend + contracts) v1 — refactor-spark-embedded-starter-20260909

- mode: execution（有罪推定）
- 评审对象：`git diff 471f732..2dcf93e -- spark-ui .harness/contracts .harness/scripts/e2e-frontend.mjs .harness/scripts/check-rename.mjs`（前端实质变更以 `git diff 471f732:fronted 2dcf93e:spark-ui` 对照，排除纯改名噪音）
- 依据：spec §2.8 / §2.9 / §6.3、spec_review_v2、`coding-standard.md`、`contracts.md`、`project-structure.md` §1、`agent-safety.md` §4、`coding_report_v1.md`
- 本机复跑：`check-contracts` 9 schemas / 27 examples exit 0；`check-rename` exit 0；`packages/core/dist` 实测 39 KB（与基线一致）。e2e-frontend 未复跑（需后端 + vite），只做静态核对。

## 必查项

| # | 检查项 | 结果 | 证据 |
|---|---|---|---|
| C1 | `CardPropsSchema.actions` 与契约 `cardProps.actions` 逐项一致 | 通过 | `uiSchema.ts:74` `z.array(InlineActionSchema).max(6).optional()` ↔ `ui-schema.schema.json:384-391` `maxItems 6` + `$ref inlineAction`；`InlineActionSchema` label 1–32 / intent 1–200 / `^(?!.*(:\/\/\|<)).*$` 与 `$defs.inlineAction` 相同 |
| C2 | `CardDesktop` / `CardMobile` actions 渲染与 Table 语义一致 | 通过 | `data-intent` / `disabled={handlers?.onIntent === undefined}` / `onClick → handlers?.onIntent?.(a.intent)` 与 `desktop/Table.tsx:24-32`、`mobile/Table.tsx:22-30` 逐字相同；`key={i}` 亦与 Table 一致 |
| C3 | exactOptionalPropertyTypes 处理 | 通过 | antd `CardProps.actions?: React.ReactNode[]`（`es/card/Card.d.ts:58`），`desktop/Card.tsx:39` 条件展开而非传 `undefined`；verify-pack (e) EOPT 双开关 |
| C4 | 公共 API 清单不变（17 运行时 / 19 类型） | 通过 | `index.ts` 只改名；`verify-pack.mjs` RUNTIME_EXPORTS / TYPE_EXPORTS 仅 `Strato*→Spark*` |
| C5 | core 内无 pageContext / entityType / entityId / chips | 通过 | grep `spark-ui/packages` 0 命中 |
| C6 | `apps/chat` 无身份 / pageContext 残留、无 URL 参数解析 | 通过 | grep `pageContext\|principal\|tenantId\|userId\|X-User-Id\|X-Tenant-Id` 0；`ChatPage.tsx` 不再 `useSearchParams`；`PageContextQuerySchema` 及 barrel 导出已删 |
| C7 | Zod `IntentRequestSchema` 与契约一致 | 基本通过 | 三字段 / min-max / `.strict()` 一致；契约 `components.uniqueItems: true` 无 Zod 对应（改前即缺，见 L-5） |
| C8 | FSD 依赖方向 | 通过 | `features/agent-chat/ui` → `@shared/api`、`@shared/ui`、同切片相对路径；`entities/agent-run/api` 不再依赖 `@shared/api`；`check-deps` 规则无违反 |
| C9 | `apps/chat` 不 import antd / 深路径 | 通过 | oxlint `no-restricted-imports` 覆盖；grep 0 |
| C10 | `ChatPage.module.css` 死样式 | 通过 | `.context` 已删；余 `.wrap` / `.title` 均被引用 |
| C11 | 5 个 schema 改动 ↔ spec §2.9 | 通过 | intent-request 删 pageContext / tool-search 删 principal 且 `required:["domain"]` / tool-invoke `sessionId` 1–128 替 userId+tenantId / tool-manifest `permission` 可选 / ui-schema cardProps.actions；9 个 `$id` 前缀已换 |
| C12 | 27 示例通过 Ajv | 通过 | 本机 `check-contracts` exit 0；新增 `ui-schema.product-detail.example.json` Card 含 actions |
| C13 | `contracts.md` §5a 记录 | 部分 | 5 行契约变更齐全；未记 `$id` 前缀整体变更，未按 §3 说明「破坏性变更却未发新版本」的理由（见 S-2 / L-7） |
| C14 | verify-pack 基线 38 → 39 | 基本通过 | 实测 39 KB；`note` 字段说明原因；但 `recordedAt` 为手写 `2026-09-10T00:00:00.000Z`，非 `--write-baseline` 产出（L-4） |
| C15 | README 与实现一致 | 部分 | 组件表已加 Card.actions；但 `packages/core/README.md:49` 仍写 `onIntent` 只接收 `Table` 行内指令（L-1） |
| C16 | e2e-frontend 新断言非空洞 | 通过 | `e2e-frontend.mjs:204` 断言 `[data-component-id="product"] [data-intent="有什么商品"]` 恰 1；`:205-208` 点击后等待 `products` 出现并断言 20 行——前一屏是 `product` 卡，选择器不可能被旧屏满足；34 个 check 调用点，step 6 循环 ×2 → 37，与报告一致 |
| C17 | check-rename 排除项 | 有问题 | `.idea / .vscode / .claude` 均在 `.gitignore`，放行无害；但 `ROOT_DIR_NAME`（`strato_ui`）按子串全局放行，实际掩盖了受控文件里的本机绝对路径（S-3） |

## 发现

| # | 位置 | 问题 | 失败场景 | 建议 | 分级 |
|---|---|---|---|---|---|
| M-1 | `spark-ui/apps/chat/src/features/agent-chat/ui/AgentChatPanel.tsx:32` + `spark-ui/apps/chat/src/shared/api/sseClient.ts:39` | Panel 把 `baseUrl ?? ''` 塞进 transport，`consumeSse` 用 `req.baseUrl ?? env.VITE_API_BASE_URL`。`''` 不是 `undefined`，所以 **`VITE_API_BASE_URL` 被永久短路**；`spark-ui/README.md:68` 仍宣称「生产可指向网关地址」 | 生产构建设置 `VITE_API_BASE_URL=https://gw.example.com`，`POST /agent/runs` 仍打同源 → 404；本地 e2e 走 vite proxy 同源，门禁全绿，线上才炸。改前 `consumeSse` 直接读 `env.VITE_API_BASE_URL`，这是本 change 引入的回退 | 任选其一：(a) `AgentChatPanel` 默认 `baseUrl ?? env.VITE_API_BASE_URL`（features 可 import `@shared/config`）；(b) `Transport.baseUrl?: string`，只在宿主传值时下发，`sseClient` 保持回落。同时给 e2e 或 verify-examples 加一条「`VITE_API_BASE_URL` 非空时请求 URL 以之为前缀」的单测 / 断言（Hashimoto） | MUST FIX |
| S-1 | `spark-ui/apps/chat/src/entities/agent-run/api/agentRunApi.ts:14-23`；`spark-ui/apps/chat/src/shared/api/httpClient.ts:28` | `getRun` 绕开 `request()`：非 2xx 抛裸 `Error` 而非 `HttpError`（coding-standard §6「网络错误统一抛 HttpError」）；响应体丢弃、无 `[…]` 前缀日志；且不读 `env.VITE_API_BASE_URL`（与 M-1 同源）。副作用：`request()` 从此 0 调用方，成为死代码 | 未来任一调用方 `catch (e) { if (e instanceof HttpError) … }` 对 `getRun` 失效，错误直接落到「请求失败」兜底；`getRun` 与 SSE 路径的 baseUrl 语义不一致 | 给 `RequestOptions` 增 `fetch?` / `baseUrl?`，`getRun` 回到 `request(path, { schema, fetch: transport.fetch, baseUrl: transport.baseUrl })`；若确认 `getRun` 无人用，直接删掉它和 `request`（不要留两套 HTTP 出口） | SHOULD |
| S-2 | `.harness/contracts/intent-request.schema.json`、`tool-invoke.schema.json`、`tool-search.schema.json`（`$id` 仍 `/v1/`）；`.harness/rules/contracts.md` §5a | `contracts.md` §3：「破坏性变更发新版本文件，不改旧文件」。删 `pageContext`（`additionalProperties:false` 下老客户端带该字段即被拒）、`executionContext` 必填字段整体替换、删 `principal` 都是破坏性变更，却原地改 v1。§4 对上次同类操作有「当时无外部消费方，schemaVersion 仍 1.0」的显式说明，§5a 没有 | 规则与实践不一致；下一个 change 照抄「原地改 v1」时无据可查 | §5a 补一行：「以上均为破坏性变更；本 change 时无外部消费方（唯一前端 / 后端同仓同 commit 切换），沿用 v1 不发新文件；首个外部消费方接入后此豁免失效」 | SHOULD |
| S-3 | `.harness/scripts/check-rename.mjs:25,52` | `ROOT_DIR_NAME = basename(root)` 后按**子串**把每行里的 `strato_ui` 替换为占位再匹配。放行范围 > 需要：(1) 任何含 `strato_ui` 的 token（如 `@strato_ui/x`、`strato_ui_legacy`）都过；(2) 实际已掩盖 `.harness/mcp/servers.json:7` 受控文件中的 `"/Users/fangtao/strato_ui"` 本机绝对路径 | 门禁「全树无旧名」结论不成立：受控文件里既有旧名又有个人机器路径，却报 ✓ | 只替换完整绝对路径 `root`（已做），删掉单独的 `ROOT_DIR_NAME` 替换；对 `servers.json` 这类需要绝对路径的配置改为 `${workspaceFolder}` / 相对路径或移出版本控制，让门禁真正变红一次再修 | SHOULD |
| S-4 | `spark-ui/scripts/verify-examples.ts`、`.harness/scripts/check-contracts.mjs` | 两个门禁都只证明「合法示例通过」。Zod 投影**比契约更宽松**时（如 `actions.max(7)`、漏 `uniqueItems`、漏 `.strict()`）全部示例仍通过，无任何门禁能发现；报告的植入反例（Card.actions 7 项）只测了 Ajv 一侧 | 投影漂移只在收紧方向可见，放宽方向永远绿 | 增 `.harness/contracts/examples/invalid/*.json`（每条标注违反的约束），`check-contracts` 断言 Ajv 拒绝、`verify-examples` 断言 Zod 拒绝；首批至少覆盖本 change 的 5 处变更（pageContext 残留、Card.actions 7 项、principal 残留、executionContext 缺 sessionId） | SHOULD |
| L-1 | `spark-ui/packages/core/README.md:49`；`spark-ui/packages/core/src/renderer/SchemaRenderer.tsx:20`；`spark-ui/packages/core/src/registry/types.ts:22` | 三处仍写 `onIntent` 只来自 `Table` 行内指令；实现已含 Card | 接入方按 README 只在 Table 场景接 `onIntent`，Card 按钮渲染为 disabled | 改为「Table 行内 / Card 底部指令」 | LOW |
| L-2 | `.harness/scripts/e2e-frontend.mjs:11` | 头注释「8 个契约示例」未随 step 6 改 9 | 文档漂移 | 改 9 | LOW |
| L-3 | `spark-ui/scripts/verify-examples.ts:6-7` | 头注释「16 个示例 / 输出 16 examples OK」；实际前端投影示例数为 23（ui-schema 9 + intent 1 + action 1 + run-summary 1 + sse 10 + error 1），改前已是 22 | 数字型验收注释失真，无人能据此核对 | 改成动态描述或写 23；更好：让脚本断言示例数 == 目录里匹配到的数量，避免再写死 | LOW |
| L-4 | `spark-ui/scripts/verify-pack.baseline.json:3` | `recordedAt: 2026-09-10T00:00:00.000Z` 是手写值；脚本约定基线只由 `--write-baseline` 产出。另：39 ≤ ceil(38×1.1)=42，本不需重写；重写把上限抬到 43 | 基线来源不可追溯；额外放宽 1 KB 上限 | 跑 `pnpm -C spark-ui run verify-pack -- --write-baseline` 拿真实时间戳；若要保留 `note`，让脚本写入时透传已有 `note` | LOW |
| L-5 | `spark-ui/apps/chat/src/entities/agent-run/model/types.ts:67` | 契约 `clientCapabilities.components` 有 `uniqueItems: true`，Zod 无对应 refine（改前即缺，本 change 触碰了该 schema 却未补） | 目前调用方固定 `[...COMPONENT_TYPES]` 不会重复；若日后宿主可注入 components，重复项前端放行、后端 400 | `.refine((a) => new Set(a).size === a.length)` | LOW |
| L-6 | `spark-ui/apps/chat/src/features/agent-chat/ui/AgentChatPanel.tsx:88` | placeholder「例如：帮我把这个订单退款」——「这个订单」依赖已删除的页面上下文；契约描述已要求「实体 ID 必须在 message 内」，e2e 也改成了「帮我把订单 10001 退款」 | 用户照示例输入 → 后端只能走澄清屏 | 改为「例如：帮我把订单 10001 退款」 | LOW |
| L-7 | `.harness/rules/contracts.md` §5a | 表格未记 9 个 `$id` 前缀 `strato.local → spark-rooter.local` 及 `SchemaValidator.ID_PREFIX` 同步 | 后端 `$ref` 解析失败时无变更记录可查 | 补一行 | LOW |
| L-8 | `spark-ui/apps/chat/src/entities/agent-run/api/agentRunApi.ts:15` | `transport.fetch(...)` 是方法调用，`this === transport`。Panel 只对**默认** fetch 做了 `bind`；宿主若传未绑定的 `window.fetch`（`fetch={window.fetch}`），SSE 路径 `doFetch(...)` 正常，而 `getRun` 抛 `Illegal invocation` | 同一 Transport 在两条路径行为不同，问题只在 `getRun` 被启用后暴露 | `const doFetch = transport.fetch; await doFetch(...)`，或在 Panel 对 `hostFetch` 也统一 `.bind(globalThis)`（注释里「注入的 fetch 需绑定」与代码只绑默认值不符，二选一改齐） | LOW |
| I-1 | `spark-ui/packages/core/src/components/{desktop,mobile}/Card.tsx` | `key={i}`（索引 key）与 Table 一致；actions 为后端下发的静态列表、无重排，可接受 | — | 保持一致即可；若将来加动画 / 可变列表再改 `label+intent` | INFO |
| I-2 | `spark-ui/apps/chat/src/shared/api/sseClient.ts:18` | `SseRequest.headers` 自去身份头后无调用方传入 | — | 可留作宿主扩展点；若删则同步 README | INFO |
| I-3 | `spark-ui/apps/chat/src/pages/chat/ChatPage.tsx:12-14` | `<h1 id="chat-title">` 内容被注释掉为空，`aria-labelledby` 指向空标题（改前即如此） | 读屏器读到空区域名 | 恢复文案或删掉 `aria-labelledby` | INFO |
| I-4 | `coding_report_v1.md` 偏差 7 | e2e-frontend 37 而非 spec §6.3 的 36：脚本静态核对 34 个调用点 + step 6 循环 = 37，与报告一致，偏差解释成立 | — | — | INFO |
| I-5 | `spark-ui/apps/chat/src/features/agent-chat/ui/AgentChatPanel.tsx:31-34` | `useMemo([baseUrl, hostFetch])`：宿主若每次渲染传内联箭头 `fetch`，transport → `stream` 每次重建；TanStack `useMutation` 每次渲染取最新 options，功能正确，只是失去 memo | — | README 提示宿主用稳定引用（`useCallback` / 模块级函数）传 `fetch` | INFO |

## verdict: CHANGES REQUIRED

- MUST FIX 1（M-1：`VITE_API_BASE_URL` 被 `baseUrl ?? ''` 短路，生产网关地址配置失效，属于本 change 引入的功能回退且现有门禁不可见）
- SHOULD 4（S-1 `getRun` 绕过 `HttpError` 且 `request()` 成死码；S-2 破坏性契约变更沿用 v1 无记录；S-3 `check-rename` 根目录名子串放行掩盖 `servers.json` 本机路径；S-4 投影放宽方向无门禁）
- LOW 8 / INFO 5

M-1 修复后可复评为 APPROVED（S 项可随修复一并吸收或在 summary 登记为下一 change）。

## 最小补丁清单

1. **M-1** `AgentChatPanel.tsx:32`：`baseUrl: baseUrl ?? env.VITE_API_BASE_URL`（`import { env } from '@shared/config'`），或改 `Transport.baseUrl?` 并只在有值时下发；README:68 保持不变。加一条门禁：`verify-examples` 同进程或新增 `scripts/verify-transport.ts`，用假 fetch 断言 `consumeSse({path:'/agent/runs'})` 在 `VITE_API_BASE_URL='https://x'` 下请求 `https://x/agent/runs`。
2. **S-1** `httpClient.ts`：`RequestOptions` 增 `fetch?: typeof fetch; baseUrl?: string`，内部 `const doFetch = options.fetch ?? fetch`、`${options.baseUrl ?? env.VITE_API_BASE_URL}${path}`；`agentRunApi.getRun` 回到 `request(...)`。或删除 `getRun` + `request` + `shared/api/index.ts` 对应导出。
3. **S-2 / L-7** `contracts.md` §5a：补「`$id` 前缀整体迁移」一行；补「破坏性变更、无外部消费方、沿用 v1」的豁免说明。
4. **S-3** `check-rename.mjs:25,52`：删 `ROOT_DIR_NAME` 替换，仅保留 `split(root)`；处理 `.harness/mcp/servers.json:7` 的绝对路径（相对路径 / 环境变量 / 移出版本控制）。
5. **S-4** 新增 `.harness/contracts/examples/invalid/`（≥ 4 个反例），`check-contracts.mjs` 与 `verify-examples.ts` 各加「必须被拒绝」分支。
6. **L-1** 三处注释 / README:49 改「Table 行内 / Card 底部指令」。
7. **L-2 / L-3** 头注释数字：e2e 9、verify-examples 23（或改为动态）。
8. **L-4** 重跑 `verify-pack -- --write-baseline`；如需保留 `note`，脚本写入时读旧文件透传。
9. **L-5** `IntentRequestSchema.clientCapabilities.components` 加唯一性 refine。
10. **L-6** placeholder 改「例如：帮我把订单 10001 退款」。
11. **L-8** `getRun` 内先解构再调用，或 Panel 对 `hostFetch` 同样 `bind`；注释与行为对齐。
