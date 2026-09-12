import { SparkDeviceProvider, SparkThemeProvider } from '@spark-ui/core';
import type { ReactNode } from 'react';
import { SPARK_TOKENS } from '../styles/tokens';

interface AppProvidersProps {
  children: ReactNode;
}

/**
 * Spark 端型（挂载一次判定）→ Spark 主题（antd / antd-mobile / --spark-* 同一套令牌）。
 *
 * 原先这里还有 QueryClientProvider：运行视图的状态容器改为 `@spark-ui/core/client` 的 runStore
 * （零框架依赖，经 useSyncExternalStore 接入 React）后，react-query 不再需要。
 */
export function AppProviders({ children }: AppProvidersProps) {
  return (
    <SparkDeviceProvider>
      <SparkThemeProvider tokens={SPARK_TOKENS}>{children}</SparkThemeProvider>
    </SparkDeviceProvider>
  );
}
