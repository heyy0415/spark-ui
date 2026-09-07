import { StratoDeviceProvider, StratoThemeProvider } from '@strato-ui/core';
import type { QueryClient } from '@tanstack/react-query';
import { QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useState } from 'react';
import { STRATO_TOKENS } from '../styles/tokens';
import { createQueryClient } from './queryClient';

interface AppProvidersProps {
  children: ReactNode;
  queryClient?: QueryClient;
}

/** QueryClient → Strato 端型（挂载一次判定）→ Strato 主题（antd / antd-mobile / --strato-* 同一套令牌）。 */
export function AppProviders({ children, queryClient }: AppProvidersProps) {
  const [client] = useState(() => queryClient ?? createQueryClient());
  return (
    <QueryClientProvider client={client}>
      <StratoDeviceProvider>
        <StratoThemeProvider tokens={STRATO_TOKENS}>{children}</StratoThemeProvider>
      </StratoDeviceProvider>
    </QueryClientProvider>
  );
}
