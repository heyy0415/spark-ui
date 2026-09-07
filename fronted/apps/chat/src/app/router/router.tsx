import { createBrowserRouter } from 'react-router';
import { AgentPage } from '@pages/agent';
import { HomePage } from '@pages/home';
import { NotFoundPage } from '@pages/not-found';
import { SchemaPlaygroundPage } from '@pages/schema-playground';
import { env } from '@shared/config';
import { RootLayout } from './RootLayout';
import { RouteErrorBoundary } from './RouteErrorBoundary';

const children = [
  { index: true, Component: HomePage },
  { path: 'agent', Component: AgentPage },
  // DEV 专用：Generate UI 渲染宿主（spec §2.3），生产构建不注册
  ...(env.DEV ? [{ path: 'dev/schema', Component: SchemaPlaygroundPage }] : []),
  { path: '*', Component: NotFoundPage },
];

export const routes = [
  { path: '/', Component: RootLayout, ErrorBoundary: RouteErrorBoundary, children },
];

export const router = createBrowserRouter(routes);
