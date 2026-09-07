import type { ReactNode } from 'react';
import { useState } from 'react';
import { DeviceContext, MOBILE_MAX_WIDTH } from '@shared/ui';
import type { DeviceKind } from '@shared/ui';

/** 挂载时一次性判定端型并下发；不监听 resize（spec §2.3：一次性决定）。 */
export function DeviceProvider({ children }: { children: ReactNode }) {
  const [device] = useState<DeviceKind>(() =>
    typeof window !== 'undefined' && window.innerWidth < MOBILE_MAX_WIDTH ? 'mobile' : 'desktop',
  );
  return <DeviceContext.Provider value={device}>{children}</DeviceContext.Provider>;
}
