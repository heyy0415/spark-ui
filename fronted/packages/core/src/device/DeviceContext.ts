import { createContext, useContext } from 'react';

/** 端型：由 app/providers/DeviceProvider 一次性判定并下发；组件内不得各自判断（project-structure §1）。 */
export type DeviceKind = 'desktop' | 'mobile';

export const DeviceContext = createContext<DeviceKind>('desktop');

/** 视口宽度小于此值视为移动端。 */
export const MOBILE_MAX_WIDTH = 768;

export function useDevice(): DeviceKind {
  return useContext(DeviceContext);
}
