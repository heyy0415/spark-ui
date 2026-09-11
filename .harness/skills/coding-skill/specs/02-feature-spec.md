# Spec: features/ 层

## 职责
- 闭合一个**业务能力**（"用户管理"、"订单导出"），含：UI 组件 + 该能力私有 Hook + API 调用编排。
- 可以引用 entities / shared；**不能**引用 pages / 其他 features。

## 标准结构

```
features/{name}/
├── api/
│   └── queries.ts        # TanStack Query Hook 定义、queryKeys
├── model/
│   ├── types.ts          # 仅本 feature 用的类型（不属于实体）
│   └── runView.ts        # 纯函数归约（如 SSE 事件 → 视图状态）；无全局客户端状态库
├── ui/
│   └── *.tsx             # 不通用、专属于此 feature 的组件
└── index.ts              # 公共出口
```

## queryKeys 命名

```ts
export const userQueryKeys = {
  all: ['users'] as const,
  list: (params: ListParams) => [...userQueryKeys.all, 'list', params] as const,
  detail: (id: string) => [...userQueryKeys.all, 'detail', id] as const,
};
```

变更类操作完成后必须 `queryClient.invalidateQueries({ queryKey: userQueryKeys.all })`。

## 必备
- 每个 feature 必须有 `index.ts` 公共出口；外部只能 `import { ... } from '@features/{name}'`。
- Mutation 必须有错误反馈（toast / inline UI）。

## 反模式
- ❌ 在 feature 里直接 `fetch('/api/...')`，应走 entity 的 api 模块。
- ❌ feature A 直接 import `feature B/internal/foo`。
