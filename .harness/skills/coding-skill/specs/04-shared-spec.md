# Spec: shared/ 层

## 职责
- 与具体业务**无关**的通用资产。任何引用了具体业务实体的代码都不属于 shared。
- 子目录：`ui` / `lib` / `hooks` / `api` / `config`。

## 决策树（如何判断"是否进 shared"）

```
模块是否引用了某个具体业务实体（User、Order 等）？
├── 是 → 不要进 shared，进 entities/{x} 或 features/{x}
└── 否 → 是否有 ≥2 个不同切片在使用？
        ├── 是 → 进 shared
        └── 否 → 暂时留在使用方目录，等有第二个使用者再上提
```

## 通用 UI 约定

- 必须导出 props 类型（命名 `XxxProps`）。
- forwardRef 必须正确传递 ref（输入 / 按钮组件）。
- 所有交互组件必须可键盘操作（Tab / Enter / Esc）。

## 反模式
- ❌ 把"用户卡片"放进 `shared/ui`——它依赖 User 类型，应进 `entities/user/ui`。
- ❌ 把只有一处使用的 hook 上提到 shared——等有第二处再说。
