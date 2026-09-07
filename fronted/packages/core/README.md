# @strato-ui/core

Strato UI 渲染引擎：把后端（Agent Runtime）下发的 **UI Schema** 渲染为白名单组件。桌面端用 antd 6，移动端用 antd-mobile 5，按视口一次性选端。包本身**不发请求、不持有会话状态、不执行任何模型生成的代码**。

## 安装

```bash
pnpm add @strato-ui/core
# peer（由宿主安装，版本范围见下表）
pnpm add react react-dom antd antd-mobile @ant-design/icons zod
```

| peer              | 范围 |
| ----------------- | ---- |
| react / react-dom | ^19  |
| antd              | ^6   |
| antd-mobile       | ^5   |
| @ant-design/icons | ^6   |
| zod               | ^4   |

peer 而非 dependencies：避免宿主与本包各持一份 antd（主题 token 不共享、体积翻倍）与 zod（`UiSchemaSchema` 组合进宿主 schema 时实例不一致）。

## 使用

```tsx
import {
  SchemaRenderer,
  ActionBar,
  StratoDeviceProvider,
  StratoThemeProvider,
  parseUiSchema,
} from '@strato-ui/core';
import '@strato-ui/core/style.css';

const ui = parseUiSchema(payloadFromBackend); // 不要写 payload as UiSchema

<StratoDeviceProvider>
  <StratoThemeProvider tokens={{ colorPrimary: '#3370ff' }}>
    <SchemaRenderer ui={ui} onFormChange={setFormValues} />
    <ActionBar
      actions={ui.actions}
      onAction={(a) => submit(a.id, a.confirmationToken, formValues)}
    />
  </StratoThemeProvider>
</StratoDeviceProvider>;
```

- `StratoDeviceProvider`：挂载时按 `window.innerWidth < 768` 一次性判定端型；不监听 resize。
- `StratoThemeProvider`：同一套令牌同时下发 antd `ConfigProvider`、antd-mobile `--adm-*` 变量与包内 `--strato-*` 变量。令牌全部可选，默认值见下。
- `SchemaRenderer`：只渲染注册表内的组件；`onFormChange` 收集 `Form` 组件的值，由宿主在确认动作时回传。
- `ActionBar`：渲染 `ui.actions`，点击回调整个 action 对象；`confirmationToken` 只回传，不解析。

## 公共 API

运行时：`SchemaRenderer`、`ActionBar`、`UnknownComponent`、`desktopRegistry`、`mobileRegistry`、`REGISTRY_KEYS`、`PROPS_SCHEMAS`、`UiSchemaSchema`、`UiComponentSchema`、`UiActionSchema`、`FormPropsSchema`、`COMPONENT_TYPES`、`parseUiSchema`、`StratoThemeProvider`、`StratoDeviceProvider`、`useDevice`、`MOBILE_MAX_WIDTH`。

类型：`UiSchema`、`UiComponent`、`UiAction`、`ComponentType`、`FormValues`、`DeviceKind`、`SchemaRendererProps`、`ActionBarProps`、`StratoThemeTokens`、`StratoThemeProviderProps`。

`StratoThemeTokens`（默认值）：`colorPrimary #3370ff`、`colorText #1f2329`、`colorTextSecondary #646a73`、`colorBorder #dee0e3`、`colorBgLayout #f7f8fa`、`colorBgContainer #ffffff`、`borderRadius 6`。

白名单组件（7）：`Form`、`Card`、`Table`、`ResultCard`、`ConfirmationCard`、`OrderCard`、`RefundConfirmCard`。每个都有桌面与移动两套实现，接受同一份 props 类型。

## 安全边界

| 随包走（接入方无法关闭）                                                               | 留在宿主（必须由你做）                                                                |
| -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| 组件只按注册表白名单查表，`type` 以字符串查找                                          | 用 `parseUiSchema()` 整体校验后端载荷（screenId / title / actions 形态 / token 长度） |
| 每个组件的 props 先经 Zod 校验再渲染，失败渲染占位                                     | 不把模型输出直接当 UI Schema 构造                                                     |
| 未知 `type` → `UnknownComponent` 占位 + `console.error('[strato-ui] …')`，其余组件照常 | `confirmationToken` 只回传给后端，不解析、不落日志                                    |
| 无 eval / new Function / dangerouslySetInnerHTML / 任意路径 import                     | 页面上下文视为不可信，鉴权在后端                                                      |
| UI Schema 中不存在可被渲染为链接或富文本的 URL / HTML 字段                             | 不用 `as UiSchema` 绕过校验                                                           |

### 不要这样做

```ts
// ✗ 绕过整体校验：actions / token 形态不再被检查
<SchemaRenderer ui={payload as UiSchema} />

// ✗ 把 UnknownComponent 换成渲染任意 HTML 的组件：白名单失去意义
// ✗ 在宿主里解析 confirmationToken：它是后端签发的不透明字符串
```

## 版本

`0.1.0`。契约真源在平台仓库 `.harness/contracts/ui-schema.schema.json`，本包的 Zod 投影与之逐字段一致。
