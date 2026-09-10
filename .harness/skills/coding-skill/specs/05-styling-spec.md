# Spec: 样式与组件库（Styling）

## 总则
- 组件库：桌面 **antd 6**，移动 **antd-mobile 5**。它们只出现在 `spark-ui/packages/core/src/components/**` 与 `spark-ui/packages/core/src/theme/**`；`apps/chat` 只用 `@spark-ui/core` 导出。
- 自定义样式用 **CSS Modules**；chat 全局只有 `apps/chat/src/app/styles/global.css`（CSS 变量 + reset，首行 `@import '@spark-ui/core/style.css'`）。**不**引入额外运行时 CSS-in-JS（antd 自带的 cssinjs 除外）。
- 主题：`spark-ui/packages/core/src/theme/SparkThemeProvider.tsx` 接收 `tokens`（7 键可选）并同时下发 antd `ConfigProvider theme.token`、antd-mobile `--adm-*` 变量、包内 `--spark-*` 变量；chat 在 `apps/chat/src/app/styles/tokens.ts` 用与 `global.css` 同值的常量传入。**禁止**硬编码颜色 / 间距；core 内**禁止** `getComputedStyle` 与 `--color-*` / `--radius-*`（宿主变量）。

## Spark UI 封装层（`@spark-ui/core`）

```
spark-ui/packages/core/src/
├── schema/uiSchema.ts           # ui-schema 契约 Zod 投影 + parseUiSchema
├── registry/componentRegistry.ts # desktopRegistry / mobileRegistry，键 = UI Schema type
├── registry/types.ts            # 各组件 props 的 Zod 投影
├── renderer/SchemaRenderer.tsx  # 只查注册表；未知 type → UnknownComponent
├── components/desktop/{Type}.tsx # antd 实现
└── components/mobile/{Type}.tsx  # antd-mobile 实现
```

- 同一 `Type` 的桌面与移动实现必须接受**相同 props 类型**（来自 `registry/types.ts`）。
- 封装组件不暴露 antd / antd-mobile 的 props 类型到外部（公共 d.ts 不得 import antd）；只暴露契约 props。
- `Form` 封装：字段定义来自契约 `props.fields[]`，提交只回传 `formData`，不自行发请求。
- `actions[].style ∈ {default, primary, danger}` 映射为 antd `Button` 的 `type/danger` 与 antd-mobile `Button` 的 `color`。
- 包内 `.module.css` 只引用 `--spark-*` 变量且必须带 fallback（第三方宿主无 chat 的 global.css）。

## 命名
- CSS Modules 类名 camelCase（`styles['cardTitle']`）；不允许深嵌套选择器（> 3 层）。

## 可访问性
- 颜色对比度 ≥ WCAG AA。
- 不允许仅靠颜色传达状态（错误必须有图标 / 文字）。
- 保留 antd 默认 focus ring；`outline: none` 必须配替代样式。

## 反模式
- ❌ 在 `apps/chat` 任何文件里 `import { Table } from 'antd'`——应从 `@spark-ui/core` 拿封装。
- ❌ `.ant-btn { ... }` 覆盖内部类名——用 `theme.token`。
- ❌ 桌面与移动实现各自定义一套 props 类型。
- ❌ inline style 写主题色。
