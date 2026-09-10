import type { ReactNode } from 'react';
import { useState } from 'react';
import type { DeviceKind } from './DeviceContext';
import { DeviceContext, MOBILE_MAX_WIDTH } from './DeviceContext';

/** 宿主挂载一次：挂载时按视口一次性判定端型并下发；不监听 resize；组件内不得各自判断。 */
export function SparkDeviceProvider({ children }: { children: ReactNode }) {
  const [device] = useState<DeviceKind>(() =>
    typeof window !== 'undefined' && window.innerWidth < MOBILE_MAX_WIDTH ? 'mobile' : 'desktop',
  );
  return <DeviceContext.Provider value={device}>{children}</DeviceContext.Provider>;
}
