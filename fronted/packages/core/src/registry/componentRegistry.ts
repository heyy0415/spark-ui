import type { ComponentType as ReactComponentType } from 'react';
import { lazy } from 'react';
import type { RenderedComponentProps } from './types';

/**
 * 白名单组件注册表（project-structure §1「Generate UI 专项」）。
 * 键集合必须与 ui-schema.schema.json 的 componentType enum 完全一致，scripts/check-registry.mjs 机械校验。
 * 每个 type 有桌面（antd）与移动（antd-mobile）两套实现，按端型 React.lazy 固定路径加载；
 * 禁止按模型输出拼路径动态 import。
 */

// eslint 不允许 any；注册表条目统一为接收已校验 props 的组件
export type RegisteredComponent = ReactComponentType<RenderedComponentProps<never>>;

type Registry = Readonly<Record<string, RegisteredComponent>>;

const asRegistered = <P>(c: ReactComponentType<RenderedComponentProps<P>>): RegisteredComponent =>
  c as unknown as RegisteredComponent;

export const desktopRegistry: Registry = {
  Form: asRegistered(
    lazy(() => import('./desktop/Form').then((m) => ({ default: m.FormDesktop }))),
  ),
  Card: asRegistered(
    lazy(() => import('./desktop/Card').then((m) => ({ default: m.CardDesktop }))),
  ),
  Table: asRegistered(
    lazy(() => import('./desktop/Table').then((m) => ({ default: m.TableDesktop }))),
  ),
  ResultCard: asRegistered(
    lazy(() => import('./desktop/ResultCard').then((m) => ({ default: m.ResultCardDesktop }))),
  ),
  ConfirmationCard: asRegistered(
    lazy(() =>
      import('./desktop/ConfirmationCard').then((m) => ({ default: m.ConfirmationCardDesktop })),
    ),
  ),
  OrderCard: asRegistered(
    lazy(() => import('./desktop/OrderCard').then((m) => ({ default: m.OrderCardDesktop }))),
  ),
  RefundConfirmCard: asRegistered(
    lazy(() =>
      import('./desktop/RefundConfirmCard').then((m) => ({ default: m.RefundConfirmCardDesktop })),
    ),
  ),
};

export const mobileRegistry: Registry = {
  Form: asRegistered(lazy(() => import('./mobile/Form').then((m) => ({ default: m.FormMobile })))),
  Card: asRegistered(lazy(() => import('./mobile/Card').then((m) => ({ default: m.CardMobile })))),
  Table: asRegistered(
    lazy(() => import('./mobile/Table').then((m) => ({ default: m.TableMobile }))),
  ),
  ResultCard: asRegistered(
    lazy(() => import('./mobile/ResultCard').then((m) => ({ default: m.ResultCardMobile }))),
  ),
  ConfirmationCard: asRegistered(
    lazy(() =>
      import('./mobile/ConfirmationCard').then((m) => ({ default: m.ConfirmationCardMobile })),
    ),
  ),
  OrderCard: asRegistered(
    lazy(() => import('./mobile/OrderCard').then((m) => ({ default: m.OrderCardMobile }))),
  ),
  RefundConfirmCard: asRegistered(
    lazy(() =>
      import('./mobile/RefundConfirmCard').then((m) => ({ default: m.RefundConfirmCardMobile })),
    ),
  ),
};

export const REGISTRY_KEYS: readonly string[] = Object.keys(desktopRegistry);
