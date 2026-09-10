import { SparkDeviceProvider, SparkThemeProvider } from '@spark-ui/core';
import type { QueryClient } from '@tanstack/react-query';
import { QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useState } from 'react';
import { SPARK_TOKENS } from '../styles/tokens';
import { createQueryClient } from './queryClient';

interface AppProvidersProps {
  children: ReactNode;
  queryClient?: QueryClient;
}

/** QueryClient → Spark 端型（挂载一次判定）→ Spark 主题（antd / antd-mobile / --spark-* 同一套令牌）。 */
export function AppProviders({ children, queryClient }: AppProvidersProps) {
  const [client] = useState(() => queryClient ?? createQueryClient());
  return (
    <QueryClientProvider client={client}>
      <SparkDeviceProvider>
        <SparkThemeProvider tokens={SPARK_TOKENS}>{children}</SparkThemeProvider>
      </SparkDeviceProvider>
    </QueryClientProvider>
  );
}
