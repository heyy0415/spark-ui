# Spec: entities/ 层

## 职责
- **领域实体**的真源：类型定义 + Zod schema + 该实体相关的纯网络 API。
- 不依赖任何 feature / page；只依赖 shared。

## 结构

```
entities/{name}/
├── api/userApi.ts        # 纯函数 API（不含 react-query）
├── model/types.ts        # Zod + 类型
├── ui/                   # 该实体的纯展示组件（如 UserAvatar）
└── index.ts
```

## API 规范

```ts
import { httpClient } from '@shared/api/httpClient';
import { UserSchema, type User } from '../model/types';

export async function getUser(id: string): Promise<User> {
  const data = await httpClient.get(`/api/users/${encodeURIComponent(id)}`);
  return UserSchema.parse(data); // ← 进入应用前必须 Zod 校验
}
```

## 必备
- 每个 API 函数返回值必须经 Zod `parse`，返回**校验后的类型**。
- ID / 路径参数必须 `encodeURIComponent`。
- 类型只在 `model/types.ts` 定义一次，其他地方只 import。

## 反模式
- ❌ 在 entity 里调用 `useQuery`（属于 feature 层）。
- ❌ 把 UI 弹窗（Modal）放进 entity（属于 feature/shared）。
