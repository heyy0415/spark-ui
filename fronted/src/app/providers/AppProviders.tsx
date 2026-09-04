import type { QueryClient } from '@tanstack/react-query';
import { QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useState } from 'react';
import { AppThemeProvider } from '@shared/ui';
import { DeviceProvider } from './DeviceProvider';
import { createQueryClient } from './queryClient';

interface AppProvidersProps {
  children: ReactNode;
  queryClient?: QueryClient;
}

export function AppProviders({ children, queryClient }: AppProvidersProps) {
  const [client] = useState(() => queryClient ?? createQueryClient());
  return (
    <QueryClientProvider client={client}>
      <DeviceProvider>
        <AppThemeProvider>{children}</AppThemeProvider>
      </DeviceProvider>
    </QueryClientProvider>
  );
}
