import type { ComponentType as ReactComponentType } from 'react';
import { lazy } from 'react';
import type { RenderedComponentProps } from './types';

/**
 * 白名单组件注册表（project-structure §1「Spark UI 专项」）。
 * 键集合必须与 ui-schema.schema.json 的 componentType enum 完全一致，scripts/check-registry.mjs 机械校验。
 * 每个 type 是 antd / antd-mobile 官方组件的直接映射，文件名 == type，按端型 React.lazy 固定路径加载；
 * 禁止按模型输出拼路径动态 import。
 */

// eslint 不允许 any；注册表条目统一为接收已校验 props 的组件
export type RegisteredComponent = ReactComponentType<RenderedComponentProps<never>>;

type Registry = Readonly<Record<string, RegisteredComponent>>;

const asRegistered = <P>(c: ReactComponentType<RenderedComponentProps<P>>): RegisteredComponent =>
  c as unknown as RegisteredComponent;

// Object.freeze：宿主运行期不得向白名单写入（agent-safety §4「随包走」的保证之一）
export const desktopRegistry: Registry = Object.freeze({
  Form: asRegistered(
    lazy(() => import('../components/desktop/Form').then((m) => ({ default: m.FormDesktop }))),
  ),
  Card: asRegistered(
    lazy(() => import('../components/desktop/Card').then((m) => ({ default: m.CardDesktop }))),
  ),
  Table: asRegistered(
    lazy(() => import('../components/desktop/Table').then((m) => ({ default: m.TableDesktop }))),
  ),
  Result: asRegistered(
    lazy(() => import('../components/desktop/Result').then((m) => ({ default: m.ResultDesktop }))),
  ),
  Timeline: asRegistered(
    lazy(() =>
      import('../components/desktop/Timeline').then((m) => ({ default: m.TimelineDesktop })),
    ),
  ),
});

export const mobileRegistry: Registry = Object.freeze({
  Form: asRegistered(
    lazy(() => import('../components/mobile/Form').then((m) => ({ default: m.FormMobile }))),
  ),
  Card: asRegistered(
    lazy(() => import('../components/mobile/Card').then((m) => ({ default: m.CardMobile }))),
  ),
  Table: asRegistered(
    lazy(() => import('../components/mobile/Table').then((m) => ({ default: m.TableMobile }))),
  ),
  Result: asRegistered(
    lazy(() => import('../components/mobile/Result').then((m) => ({ default: m.ResultMobile }))),
  ),
  Timeline: asRegistered(
    lazy(() =>
      import('../components/mobile/Timeline').then((m) => ({ default: m.TimelineMobile })),
    ),
  ),
});

export const REGISTRY_KEYS: readonly string[] = Object.keys(desktopRegistry);
