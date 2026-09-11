# Rule: 项目编码规范（Coding Standard）

> 每条规则背后都对应一个真实踩过的坑。觉得"啰嗦"时请先问"这能不能被机器校验"——能就写到 lint 里。

## 1. TypeScript

- 项目运行在 **strict 模式**，禁止关闭 `strict` / `noUncheckedIndexedAccess` / `exactOptionalPropertyTypes`。
- **禁止 `any`**。无法描述时用 `unknown` 然后窄化。
- 函数参数与返回值**必须**显式类型；`React` 组件除外（Props 已显式）。
- `import type { Foo }` 优先（已通过 `consistent-type-imports` 强制）。
- 不写 `enum`（用 union 字面量），避免运行时 enum 与 tree-shaking 摩擦。`tsconfig` 的 `erasableSyntaxOnly` 已在编译期拦截 enum / namespace / 构造器参数属性。

## 2. 数据校验

- 所有从外部进入应用的数据（API、URL 参数、localStorage、postMessage）**必须** Zod 校验。
- 校验失败应抛出 `HttpError` 或显式错误，**不允许** `as Foo` 强转外部数据。
- 契约投影真源分工：ui-schema 在 `@spark-ui/core`（`spark-ui/packages/core/src/schema/uiSchema.ts`），其余契约在 `apps/chat` 的 `entities/{x}/model/types.ts`；其他模块只 import，不重复定义。

## 3. 数值与单位约束

- **金额**：`string`，单位"元"，两位小数（形如 `"128.00"`，与 `contracts.md` 的 pattern 一致），运算前 parse 为专用 Money 类型或后端 `BigDecimal`。**禁止** `number` 表示金额。
- **ID**：`string`。即便后端是数字 ID 也作为字符串处理（避免 53 位精度损失）。
- **时间**：传输 ISO-8601 字符串，UI 渲染时再 `new Date(...).toLocaleString()`。

## 4. React 组件

- 组件必须是**纯函数组件**；class 组件除非有 ErrorBoundary 需求否则禁止。
- 状态管理选型：
  - **服务端状态** → TanStack Query（`useQuery` / `useMutation`），禁止用 useEffect 自行 fetch。
  - **跨页面客户端状态** → 当前无此类状态；需要时以 change 引入并在此登记选型。
  - **同页面 UI 状态** → `useState` / `useReducer`。
- **禁止**在组件渲染期间执行副作用（log、读 localStorage、router push）。
- 避免在依赖数组中放对象字面量；必要时用 `useMemo`。
- **组件库**：桌面端 antd 6，移动端 antd-mobile 5。只允许在 `spark-ui/packages/core/src/components/**` 与 `spark-ui/packages/core/src/theme/**` 内 import 这两个库；`apps/chat` 任何文件不得 import antd / antd-mobile / @ant-design，只能使用 `@spark-ui/core` 包入口导出（禁止 `@spark-ui/core/src/*` 深路径）。oxlint `no-restricted-imports` 守护。
- **core 组件 = 官方组件映射**：`spark-ui/packages/core/src/components/{desktop,mobile}/*.tsx` 文件名必须等于契约 `componentType`（例外只有 `ActionBar` / `RunStatus` / `SchemaSkeleton` 三个 SSE 阶段的宿主 UI，分别是官方 Button / Spin / Skeleton 的映射，白名单在 `check-registry.mjs`），即 antd / antd-mobile 官方组件名；禁止业务命名组件（`OrderCard` / `RefundConfirmCard` 之类）；这些文件只能 import `antd` / `antd-mobile` / `react` / 本包 `registry` / `schema` / `lib`。新增 type 前先回答「能否用现有五个组件的 props 表达」。`check-registry` 机械守护。
- antd 主题只通过 `ConfigProvider` 的 `theme.token` 配置，与 `app/styles/global.css` 的 CSS 变量保持同一套色值；禁止覆盖 antd 内部类名。

## 5. Hook 规则

- 自定义 Hook 必须以 `use` 开头。
- Hook 不允许在 if / loop / try-catch 中调用（已由 `react-hooks/rules-of-hooks` 强制）。
- 跨切片复用的 Hook 放 `shared/hooks`；只服务一个 feature 的放 `features/{name}/api` 或 `features/{name}/model`。

## 6. 错误处理

- 网络错误统一抛 `HttpError`（已在 `shared/api/httpClient.ts` 定义），UI 层用 `isError` 渲染状态。
- **禁止**吞掉错误：`catch(e) {}` 必须 `console.error(e)` 并向上传播或显式 toast/UI 反馈。
- React 渲染层保持错误可见——使用 `<ErrorBoundary>`（在路由级 / 关键 feature 顶层）。

## 7. 可访问性（A11y）

- 任何交互元素必须有可达的语义（`<button>` / `<a>` / `aria-label`）。
- 表单输入必须有关联 `<label>` 或 `aria-label`。
- 图片必须有 `alt`（装饰性图片用 `alt=""`）。

## 8. 日志与控制台

- **禁止** `console.log`（lint 报错）。允许 `console.warn` / `console.error`，且必须带语义前缀，如 `console.error('[user-api]', err)`。

## 9. 提交与变更

- Commit message 遵循 Conventional Commits：`feat(user): add list filter`。
- 每个 commit 关联一个 `.harness/changes/` 变更目录（在 message footer 标注 `Change: feat-user-list-20260507`）。
- 跨切片重构必须独立成 commit，不与 feat 混合。
