import { createBrowserRouter } from 'react-router';
import { ChatPage } from '@pages/chat';
import { NotFoundPage } from '@pages/not-found';
import { SchemaPlaygroundPage } from '@pages/schema-playground';
import { env } from '@shared/config';
import { RootLayout } from './RootLayout';
import { RouteErrorBoundary } from './RouteErrorBoundary';

const children = [
  // 唯一业务页面：chat 即首页
  { index: true, Component: ChatPage },
  // DEV 专用：Strato UI 渲染宿主，生产构建不注册
  ...(env.DEV ? [{ path: 'dev/schema', Component: SchemaPlaygroundPage }] : []),
  { path: '*', Component: NotFoundPage },
];

export const routes = [
  { path: '/', Component: RootLayout, ErrorBoundary: RouteErrorBoundary, children },
];

export const router = createBrowserRouter(routes);
