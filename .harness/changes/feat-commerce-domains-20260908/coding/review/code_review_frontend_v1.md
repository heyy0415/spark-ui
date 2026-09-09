# Code Review（前端 + 文档）— feat-commerce-domains-20260908 v1

- **mode**: execution
- **评审范围**: `git diff d988063..HEAD -- fronted .harness/wiki .harness/rules .harness/scripts/e2e-frontend.mjs backed/README.md`
- **独立性**: 未读 `coding_report_v1.md` 与 summary「经验沉淀」；只看产出物 + spec v3.2（§2.3 / §2.5 / §6.3）+ tasks.md
- **加载规则**: coding-standard / contracts / agent-safety §4 / project-structure（FSD、红线 10）/ expert-reviewer execution checklist
- **用户关键决策**: core 组件 = antd / antd-mobile 官方组件直接映射；无业务命名；仅 5 个 type；props 契约级

## 0. 机械校验实测（只读命令，真实退出码）

| 命令 | 结果 |
|---|---|
| `pnpm -C fronted run typecheck` | exit 0 |
| `pnpm -C fronted run lint`（oxlint --deny-warnings + check-deps + check-registry） | exit 0 |
| `node fronted/scripts/check-registry.mjs` | exit 0，「5 component types consistent … official-component mappings」 |
| `pnpm -C .harness run check-contracts` | 9 schemas OK（含 8 个 ui-schema 示例） |
| `ls components/desktop` / `mobile` | 均为 `ActionBar Card Form Result Table Timeline` |
| `grep -rn "Order\|Refund\|Product\|Logistics" fronted/packages/core/src/components` | 0 |
| `grep -rn "http\|href=" fronted/packages/core/src` | 0 |
| `grep -rn "from 'antd\|@ant-design" fronted/apps/chat/src` | 0 |
| `grep -rn ": any\|as any" fronted/apps/chat/src fronted/packages/core/src` | 0 |
| `grep -n "@ant-design/icons" fronted/packages/core/package.json` | **2 处命中（spec §6.3 要求 0）** → 见 F-01 |

## 1. 契约逐字段比对：`uiSchema.ts` vs `ui-schema.schema.json`

| 字段 | 契约 | Zod | 结论 |
|---|---|---|---|
| componentType enum | 5 | `COMPONENT_TYPES` 5 | 一致 |
| component.id / screenId / action.id | `^[a-z0-9-]+$` 1..64 | `idPattern` 1..64 | 一致 |
| component.props | `type: object` + if/then | `z.record` + `superRefine` 按 type 调 `PROPS_SCHEMAS` | 一致 |
| formProps / formField / options | 不变 | `.strict()` 全覆盖 | 一致 |
| labelValue | label 1..80、value ≤200、tone enum 4 | 同 | 一致 |
| inlineAction | label 1..32、intent 1..200、`^(?!.*(://|<)).*$` | 同 | 一致 |
| cardProps | title ≤80、description ≤500、items ≤32 | 同，`.strict()` | 一致 |
| tableRow.id | 1..64 | 同 | 一致 |
| tableRow.cells | `maxProperties: 16`、值 ≤200 | `z.record(z.string(), z.string().max(200))` **无 maxProperties** | **偏差**（F-05） |
| tableRow.actions | ≤6 | ≤6 | 一致 |
| tableProps.columns | 1..16、key `^[a-zA-Z][a-zA-Z0-9_]*$`、title 1..80 | 同 | 一致 |
| tableProps.rows / total / emptyText | ≤50 / int ≥0 / ≤120 | 同 | 一致 |
| resultProps | status enum 4、title 1..80、description ≤500、details ≤32 | 同 | 一致 |
| timelineProps.items | ≤32；time `format: date-time`；label 1..80；description ≤200 | ≤32；`z.string().datetime({ offset: true })` | 语义一致，边界略严（F-13） |
| timelineProps.emptyText | ≤120 | ≤120 | 一致 |
| action | type/style enum、label 1..32、token 16..256 | 同 | 一致 |
| UiSchemaSchema | version const、components ≤32、actions ≤8、title 1..80 | 同、`.strict()` | 一致 |

`additionalProperties: false` 的对象在 Zod 侧均 `.strict()`；`props` 本体不 strict（契约也未加），符合 contracts §3。

## 2. Findings

| # | 位置 | 问题 | 建议 | 分级 |
|---|---|---|---|---|
| F-01 | `fronted/packages/core/package.json:38,46`；`fronted/scripts/verify-pack.mjs:77`（`PEERS` 含 `@ant-design/icons`）；`fronted/packages/core/README.md:10,18` | spec §2.3 / §3 / §6.3 三处明确「不引入 `@ant-design/icons`」且验收项为 `grep "@ant-design/icons" packages/core/package.json` 0；实测 peerDependencies 与 devDependencies 各 1 处命中，core `src/` 无任何使用（`grep -rn "@ant-design/icons" packages/core/src` 0），是一个无消费者的 peer。verify-pack `PEERS` 与 README peer 表也仍把它列为 6 个 peer 之一，三处与 spec 矛盾。 | 从 core `package.json` peer / dev 中移除；`verify-pack.mjs` `PEERS` 改为 5 项并同步注释「(b) 6 peer」；README peer 表删行并把安装命令里的 `@ant-design/icons` 去掉。若确有保留理由，须在 coding_report 明确标注偏差并改 spec §6.3 验收项。 | **MUST FIX** |
| F-02 | `fronted/scripts/check-registry.mjs:115` | import 白名单只匹配 `^import … from 'x'`。实测可绕过：`export * from 'lodash'` / `export { pick } from 'lodash'`（re-export）不被捕获；行中 `await import('lodash')` / `require('lodash')` 不被捕获。`import x = require('lodash')` 与副作用 `import 'x'` 能捕获。另外 `components/desktop/` 下若出现子目录，`readFileSync` 抛 EISDIR 直接崩溃（虽也是非 0 退出，但报错不可读）。 | 追加两条正则：`/^export\s+(\*|\{[^}]*\})\s+from\s+['"]([^'"]+)['"]/gm` 与 `/\b(import|require)\s*\(\s*['"]([^'"]+)['"]/g`，同走 `ALLOWED_IMPORT`；遍历时 `statSync(...).isFile()` 过滤并对目录直接 `fail`。 | SHOULD |
| F-03 | `fronted/apps/chat/src/features/agent-chat/model/runView.ts:51-60,65-67`；`ui/AgentChatPanel.tsx:141-155` | `beginTurn` / `run.started` 保留上一屏 `ui`，但新 Run 若以「无 ui.replace」结束（后端 `RunOrchestrator.java:173,192` 的缺实体 / 无能力路径只发 `message.delta` + `run.completed`；或 `run.failed`），旧屏永久停留：若旧屏是确认屏（`Card + Form`），用户看到的是一张已失效的确认表单（ActionBar 因 phase≠waiting_confirmation 隐藏，但 Form 仍可填），且 `phase === 'completed' && !view.ui` 的「已完成」永远不显示。列表屏（Table）保留是合理的（可继续点行内指令），确认屏保留则误导。 | 在 `beginTurn` 中：若 `view.phase === 'waiting_confirmation'` 则 `ui: null`（放弃旧确认屏；其 token 在后端过期即可）；其余情况保留。或给 `AgentRunView` 加 `uiStale: boolean`，新 Run 终态且未收到 `ui.replace` 时置灰并禁用旧 Form。补一条 e2e：确认屏下发送「有什么商品」→ 旧 Form 不再可见。 | SHOULD |
| F-04 | `fronted/packages/core/src/components/desktop/Table.tsx:25`；`mobile/Table.tsx:23` | 行内按钮 `key={a.intent}`。契约不约束同一行两个 action 的 intent 唯一；后端若下发重复 intent（或未来同一动词两种 label）会触发 React duplicate-key `console.error`，进而让 e2e「console errors 0」误红。 | `key={`${i}-${a.intent}`}` 或直接用索引。 | SHOULD |
| F-05 | `fronted/packages/core/src/schema/uiSchema.ts:81` | `tableRow.cells` 契约有 `maxProperties: 16`，Zod 未投影（实测 17 键通过）。contracts §1「字段与约束必须与 Schema 一致」。 | `z.record(...).refine((o) => Object.keys(o).length <= 16, 'cells ≤ 16 keys')`。 | SHOULD |
| F-06 | `.harness/scripts/e2e-frontend.mjs:147-165`（step 6） | 断言 `rendered > 0` 是对 8 个示例求和后的总数，任一示例渲染 1 个组件即通过；某示例 props 校验失败会渲染 `UnknownComponent` 占位（同时 console.error），只靠「errors 0」间接兜底。 | 用示例文件已知的组件数逐个断言（confirm 3 / result 1 / order-table 1 / product-table 1 / order-detail 3 / logistics 2 / aftersale-confirm 2 / delete-confirm 1），并断言 `[role="alert"][data-component-id]` 为 0。 | SHOULD |
| F-07 | `.harness/scripts/e2e-frontend.mjs:122` | `['OrderCard', 'RefundConfirmCard', 'Form'].length` 仍引用已删除的业务组件名（数值 3 恰好与 `[Card, Card, Form]` 相等，所以没红）。tasks T? 的 stale-name grep 未把本脚本列入路径，因此漏网。 | 改为 `['Card', 'Card', 'Form'].length` 或直接 `3` + 注释；把 `.harness/scripts` 加入 stale-name grep 路径。 | SHOULD |
| F-08 | `backed/README.md:20-22` | 模块表被一段正文（「每个领域模块还提供 …」）截断，`| app |` 行落在表外，Markdown 渲染为普通文本。 | 把说明段移到 `| app |` 行之后。 | SHOULD |
| F-09 | `.harness/rules/project-structure.md:34,36`；`fronted/scripts/verify-pack.mjs:11` | 「17 运行时 + 10 类型导出」已过时（index.ts / verify-pack `TYPE_EXPORTS` / README 均为 19）；`registry/ # … + 各组件 props Zod` 已过时（props Zod 真源移到 `schema/uiSchema.ts`，registry/types.ts 只 re-export）。 | 改为「17 运行时 + 19 类型」；registry 行改为「componentRegistry（desktop / mobile）+ 渲染签名类型；props Zod 见 schema/」。 | SHOULD |
| F-10 | `fronted/apps/chat/src/features/agent-chat/ui/AgentChatPanel.tsx:34,143` | `send` 每次渲染都是新函数，`SchemaRenderer` 的 `handlers = useMemo(…, [onFormChange, onIntent])` 因此每帧失效，向下导致 `Form` 的 `useCallback([handlers])` 与 antd `onValuesChange` 每帧重建。功能无误，只是 memo 形同虚设。 | `send` 用 `useCallback`（依赖 `busy, start, pageContext`）。 | LOW |
| F-11 | `fronted/apps/chat/src/features/agent-chat/ui/AgentChatPanel.tsx:91` | `<div aria-label="示例问题">` 无 role，generic div 上的 aria-label 不会被读屏播报（coding-standard §7）。 | `role="group"` 或改用 `<nav>` / `<ul>`。 | LOW |
| F-12 | `fronted/packages/core/src/components/desktop/Table.tsx:24-31`；`mobile/Table.tsx:22-29` | 行内按钮在宿主 `busy` 时不禁用（`send` 内静默 return），点击无反馈；chip 与输入框都 disabled，唯独 Table 按钮不一致。 | core 不感知 busy 是对的；可在 `SchemaRenderer` 加 `disabled?: boolean` 传到 handlers（或宿主用 `<fieldset disabled>` 包裹 UI 区），本期可接受。 | LOW |
| F-13 | `fronted/packages/core/src/schema/uiSchema.ts:128` | `z.string().datetime({ offset: true })` 比 ajv `date-time` 严：拒绝小写 `t/z`、空格分隔、`+0800`（无冒号）。契约合法载荷可能被前端拒绝；当前后端用 `Instant.toString()`（大写 Z），实测 4 个示例全通过。 | 记录为已知差异即可；若后端将来输出 `OffsetDateTime` 带 `+08:00` 也能过。无需改动。 | INFO |
| F-14 | `fronted/apps/chat/src/pages/schema-playground/SchemaPlaygroundPage.tsx:37,46` | URL 参数 `as ExampleKey` 是对外部输入的 `as` 强转（coding-standard §2）；`EXAMPLES[key as keyof typeof EXAMPLES] ?? confirmExample` 在类型上是「不必要」的 fallback，靠运行期 undefined 兜底。基线 d988063 已如此，本次只是扩到 8 个键。DEV-only 路由。 | 写 `isExampleKey(v: string): v is ExampleKey` 守卫替代两处 cast。 | LOW |
| F-15 | `fronted/packages/core/src/schema/uiSchema.ts:159-164` | `superRefine` 只报 `${type}.props invalid`，丢弃内层 issues；`parseUiSchema` 抛出的 ZodError 无法定位到具体 props 字段（渲染器路径另有逐组件校验并 `console.error(issues)`，但整体校验路径没有）。 | `r.error.issues.forEach(i => ctx.addIssue({ ...i, path: ['props', ...i.path] }))`。 | LOW |
| F-16 | `fronted/packages/core/src/components/mobile/Table.tsx:10` | rows 为空时 `<List.Item>` 直接渲染在 `<List>` 外（antd-mobile `List.Item` 无 Context 依赖，可用，但缺 List 边框样式）。`columns[0]?.key ?? ''` 在 minItems=1 校验后不会走到 `''` 分支，属防御写法。 | 包一层 `<List>`；其余保持。 | LOW |
| F-17 | `fronted/packages/core/src/components/desktop/Timeline.tsx:22`；`mobile/Timeline.tsx:20` | `new Date(it.time).toLocaleString()` 按浏览器时区 / locale 渲染，与 coding-standard §3「UI 渲染时再 toLocaleString」一致；与 Table 里后端已格式化的 `createdAt`（字符串「2026-09-07 10:12」）在同一页面可能出现两种时间格式。 | 记录；若产品要求统一，后续让后端 Timeline `time` 也走格式化文本或前端固定 `zh-CN` + `Asia/Shanghai`。 | INFO |
| F-18 | `.harness/scripts/e2e-frontend.mjs:177,191` | 硬编码 20 行 / `10030` / `P-1003` / 「无线耳机 Pro」与种子数据强耦合；种子改动即红。`.ant-timeline-item` 类名已在 antd 6.6.2 源码中确认存在。`STRATO_FRONT_BASE` 处理正确；文件头「前置：后端 8080」与 vite proxy `STRATO_BACKEND` 可覆盖一致。 | 可接受（验收脚本本就绑定 fixtures）；建议注释指向 `gen-seed.mjs` 的对应夹具。 | INFO |
| F-19 | `fronted/packages/core/README.md:65`；spec §2.3 表 | spec 写移动 Form 用 `Picker`，实现与 README 均为 `Selector`（基线已是 Selector，本次未改）。文档内部一致，与 spec 表不一致。 | 在 coding_report 标注偏差或改 spec 表为 `Selector`。 | INFO |
| F-20 | 安全边界核对（agent-safety §4、project-structure 红线 2 / 10） | 组件文件只 import antd / antd-mobile / react / `../../registry/types`；无 URL / href / dangerouslySetInnerHTML；`data-component-id` 在 5×2 组件根节点齐全（Form / Card 借 antd / antd-mobile 透传 data-*，已确认 antd Card 透传 rest、antd-mobile `withNativeProps` 透传 `data-*`）；`data-intent` 在两端按钮上；`onIntent` 仅以 `a.intent` 原文调用；宿主 `send(message)` 不拼接、不改写；`apps/chat` 无 antd import、无深路径；FSD 方向 `check-deps` 通过；`Object.freeze` 注册表与 PROPS_SCHEMAS；`index.ts` 17 运行时 / 19 类型与 `verify-pack` 清单、README 三方一致。 | 无 | INFO |

## 3. spec §6.3 验收项对照

| 验收项 | 状态 |
|---|---|
| `pnpm -C fronted run ci` 0；check-registry 5 + 两条新规则 | typecheck / lint / check-registry 实测 0（本次未跑 build / verify-pack 以免触碰 dist；基线 40→38 KB 已重写） |
| `ls components/desktop` == `ActionBar Card Form Result Table Timeline` | 通过 |
| `grep Order\|Refund\|Product\|Logistics components` 0 | 通过 |
| `grep @ant-design/icons packages/core/package.json` 0 | **未通过**（F-01） |
| `grep http\|href= packages/core/src` 0 | 通过 |
| e2e 原 21 项 + 新增 ≥ 8 项 | 新增 13 项断言（step 6：4，step 7：9）；未在本次评审中运行（8080 / 5173 属用户） |
| 8 示例 1280 / 375 渲染、console.error 0 | 由 step 6 覆盖；断言强度见 F-06 |

## 4. verdict

**REVISION REQUIRED**（1 条 MUST FIX：F-01 `@ant-design/icons` 与 spec §6.3 验收项矛盾，且 verify-pack / README 三处仍以 6 peer 为准）。

其余 8 条 SHOULD 建议随 F-01 一并修复后出 v2；LOW / INFO 可延后或记录。
