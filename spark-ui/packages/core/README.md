# @spark-ui/core

Spark UI 渲染引擎：把后端（Agent Runtime）下发的 **UI Schema** 渲染为白名单组件。桌面端用 antd 6，移动端用 antd-mobile 5，按视口一次性选端。包本身**不发请求、不持有会话状态、不执行任何模型生成的代码**。

## 安装

```bash
pnpm add @spark-ui/core
# peer（由宿主安装，版本范围见下表）
pnpm add react react-dom antd antd-mobile zod
```

| peer              | 范围 |
| ----------------- | ---- |
| react / react-dom | ^19  |
| antd              | ^6   |
| antd-mobile       | ^5   |
| zod               | ^4   |

peer 而非 dependencies：避免宿主与本包各持一份 antd（主题 token 不共享、体积翻倍）与 zod（`UiSchemaSchema` 组合进宿主 schema 时实例不一致）。

## 使用

```tsx
import {
  SchemaRenderer,
  ActionBar,
  SparkDeviceProvider,
  SparkThemeProvider,
  parseUiSchema,
} from '@spark-ui/core';
import '@spark-ui/core/style.css';

const ui = parseUiSchema(payloadFromBackend); // 不要写 payload as UiSchema

<SparkDeviceProvider>
  <SparkThemeProvider tokens={{ colorPrimary: '#3370ff' }}>
    <SchemaRenderer ui={ui} onFormChange={setFormValues} />
    <ActionBar
      actions={ui.actions}
      onAction={(a) => submit(a.id, a.confirmationToken, formValues)}
    />
  </SparkThemeProvider>
</SparkDeviceProvider>;
```

- `SparkDeviceProvider`：挂载时按 `window.innerWidth < 768` 一次性判定端型；不监听 resize。
- `SparkThemeProvider`：同一套令牌同时下发 antd `ConfigProvider`、antd-mobile `--adm-*` 变量与包内 `--spark-*` 变量。令牌全部可选，默认值见下。
- `SchemaRenderer`：只渲染注册表内的组件；`onFormChange` 收集 `Form` 组件的值，由宿主在确认动作时回传；`onIntent` 接收 `Table` 行内指令与 `Card.actions` 的文本，宿主把它当作用户输入原样发送（新一轮对话）。
- `ActionBar`：渲染 `ui.actions`，点击回调整个 action 对象；`confirmationToken` 只回传，不解析。

## 公共 API

运行时：`SchemaRenderer`、`ActionBar`、`UnknownComponent`、`desktopRegistry`、`mobileRegistry`、`REGISTRY_KEYS`、`PROPS_SCHEMAS`、`UiSchemaSchema`、`UiComponentSchema`、`UiActionSchema`、`FormPropsSchema`、`COMPONENT_TYPES`、`parseUiSchema`、`SparkThemeProvider`、`SparkDeviceProvider`、`useDevice`、`MOBILE_MAX_WIDTH`。

类型：`UiSchema`、`UiComponent`、`UiAction`、`ComponentType`、`FormProps`、`CardProps`、`TableProps`、`TableRow`、`ResultProps`、`TimelineProps`、`LabelValue`、`InlineAction`、`FormValues`、`ComponentHandlers`、`DeviceKind`、`SchemaRendererProps`、`ActionBarProps`、`RunStatusProps`、`RunStatusKind`、`ToolStep`、`SchemaSkeletonProps`、`SparkThemeTokens`、`SparkThemeProviderProps`。

运行时另有两个与 SSE 阶段配套的组件（不在契约 `componentType` 里，是 antd `Spin` / `Skeleton` 的映射）：

- `RunStatus({ status, tools, text? })`：一行状态条。`streaming` 时 Spin + 当前步骤文案（「正在搜索订单…」，取最后一个未完成工具；没有工具时「正在理解你的问题…」），`completed` 显示「完成 · N 步」，`failed` 显示 `text`。进度明细在 `<ol aria-label="工具进度">`。宿主把 SSE 事件归约成 `status` + `tools` 交给它即可。
- `SchemaSkeleton({ variant })`：屏到达前的骨架，`variant ∈ table / card / form / generic` 只影响形状。根节点 `data-testid="spark-skeleton"`。
- `SchemaRenderer` 增可选 `readOnly`：历史回合的 Form 禁用（令牌已失效）。

`SparkThemeTokens`（默认值）：`colorPrimary #3370ff`、`colorText #1f2329`、`colorTextSecondary #646a73`、`colorBorder #dee0e3`、`colorBgLayout #f7f8fa`、`colorBgContainer #ffffff`、`borderRadius 6`。

白名单组件（5）——每个都是官方组件的直接映射，core 内**没有**业务命名组件（`scripts/check-registry.mjs` 守护文件名与 import 白名单）：

| type       | 桌面（antd 6）                                                                                                                        | 移动（antd-mobile 5）                     | 用途                                                              |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------- | ----------------------------------------------------------------- |
| `Form`     | `Form` + `Input` / `Select` / `InputNumber`                                                                                           | `Form` + `Input` / `Selector` / `Stepper` | 确认屏表单                                                        |
| `Card`     | `Card` + `Descriptions`（`items[].tone` → `Typography.Text type`）；`actions[]` → 底部 `Button`（`data-intent`，点击回调 `onIntent`） | `Card` + `List` + `Button`                | 订单 / 商品 / 摘要 / 警示 / 详情屏二级入口（返回列表 / 查看物流） |
| `Table`    | `Table`（末列 `Button` 为行内指令，`data-intent`）                                                                                    | 每行一个 `List` 分组 + `Button`           | 订单 / 商品 / 售后列表                                            |
| `Result`   | `Result` + `Descriptions`                                                                                                             | `Result` + `List`                         | 写操作结果                                                        |
| `Timeline` | `Timeline`                                                                                                                            | `Steps direction="vertical"`              | 物流轨迹                                                          |

五个组件的 props 都是契约级约束（`ui-schema.schema.json` if/then；Zod `.strict()` 同源）。

## 安全边界

| 随包走（接入方无法关闭）                                                              | 留在宿主（必须由你做）                                                                |
| ------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| 组件只按注册表白名单查表，`type` 以字符串查找                                         | 用 `parseUiSchema()` 整体校验后端载荷（screenId / title / actions 形态 / token 长度） |
| 每个组件的 props 先经 Zod 校验再渲染，失败渲染占位                                    | 不把模型输出直接当 UI Schema 构造                                                     |
| 未知 `type` → `UnknownComponent` 占位 + `console.error('[spark-ui] …')`，其余组件照常 | `confirmationToken` 只回传给后端，不解析、不落日志                                    |
| 无 eval / new Function / dangerouslySetInnerHTML / 任意路径 import                    | 页面上下文视为不可信，鉴权在后端                                                      |
| `onIntent` 只回调纯文本；core 不发请求、不解释文本                                    | 把 `onIntent` 文本当用户输入原样提交，不拼接、不改写                                  |
| UI Schema 中不存在可被渲染为链接或富文本的 URL / HTML 字段                            | 不用 `as UiSchema` 绕过校验                                                           |

### 不要这样做

```ts
// ✗ 绕过整体校验：actions / token 形态不再被检查
<SchemaRenderer ui={payload as UiSchema} />

// ✗ 把 UnknownComponent 换成渲染任意 HTML 的组件：白名单失去意义
// ✗ 在宿主里解析 confirmationToken：它是后端签发的不透明字符串
```

## 版本

`0.1.0`。契约真源在平台仓库 `.harness/contracts/ui-schema.schema.json`，本包的 Zod 投影与之逐字段一致。
