# Spec: 样式与组件库（Styling）

## 总则
- 组件库：桌面 **antd 6**，移动 **antd-mobile 5**。它们只出现在 `shared/ui/**`；其他层通过 `@shared/ui` 使用封装。
- 自定义样式用 **CSS Modules**；全局只有 `app/styles/global.css`（CSS 变量 + reset）。**不**引入额外运行时 CSS-in-JS（antd 自带的 cssinjs 除外）。
- 主题：`shared/ui/theme/AppThemeProvider.tsx` 封装 antd `ConfigProvider theme.token` 与 antd-mobile CSS 变量（`--adm-color-primary` 等），注入**同一套**色值，真源是 `global.css` 的 `--color-*` 变量；`app/providers/` 只 import `@shared/ui` 的该封装。**禁止**硬编码颜色 / 间距。

## Generate UI 封装层

```
shared/ui/generate/
├── componentRegistry.ts     # desktopRegistry / mobileRegistry，键 = UI Schema type
├── SchemaRenderer.tsx       # 只查注册表；未知 type → UnknownComponent
├── types.ts                 # 各组件 props 的 Zod 投影（与 ui-schema.schema.json 一致）
├── desktop/{Type}.tsx       # antd 实现
└── mobile/{Type}.tsx        # antd-mobile 实现
```

- 同一 `Type` 的桌面与移动实现必须接受**相同 props 类型**（来自 `types.ts`）。
- 封装组件不暴露 antd / antd-mobile 的 props 类型到外部；只暴露契约 props。
- `Form` 封装：字段定义来自契约 `props.fields[]`，提交只回传 `formData`，不自行发请求。
- `actions[].style ∈ {default, primary, danger}` 映射为 antd `Button` 的 `type/danger` 与 antd-mobile `Button` 的 `color`。

## 命名
- CSS Modules 类名 camelCase（`styles['cardTitle']`）；不允许深嵌套选择器（> 3 层）。

## 可访问性
- 颜色对比度 ≥ WCAG AA。
- 不允许仅靠颜色传达状态（错误必须有图标 / 文字）。
- 保留 antd 默认 focus ring；`outline: none` 必须配替代样式。

## 反模式
- ❌ 在 `features/` 里 `import { Table } from 'antd'`——应从 `@shared/ui` 拿封装。
- ❌ `.ant-btn { ... }` 覆盖内部类名——用 `theme.token`。
- ❌ 桌面与移动实现各自定义一套 props 类型。
- ❌ inline style 写主题色。
