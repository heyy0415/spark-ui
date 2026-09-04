# Spec: pages/ 层

## 职责
- 一个路由 = 一个目录 = 一个容器组件。
- **只**做：路由参数解析、调用 feature 的 hook、组合 feature UI、渲染状态分支（loading / error / empty / ready）。
- **禁止**：写业务逻辑、写网络请求、写复杂 useState 状态机。

## 模板

```tsx
// pages/{name}/{Name}Page.tsx
import { useParams } from 'react-router';
import { useUserQuery, UserDetailCard } from '@features/user-management';

export function {Name}Page() {
  const { id = '' } = useParams<{ id: string }>();
  const { data, isLoading, isError, error } = useUserQuery(id);

  if (isLoading) return <p>加载中…</p>;
  if (isError) return <p role="alert">加载失败：{(error as Error).message}</p>;
  if (!data) return <p>无数据</p>;

  return <UserDetailCard user={data} />;
}
```

## 必备
- 错误状态用 `role="alert"` 暴露给 a11y。
- 路由参数解析后显式 default（`id = ''`），避免 undefined 漏到下游。
- 该 Page 必须有同目录的 `index.ts` 公共出口。

## 反模式
- ❌ 在 Page 里 `useEffect(() => fetch(...))`。
- ❌ 在 Page 里实例化 Zustand store。
