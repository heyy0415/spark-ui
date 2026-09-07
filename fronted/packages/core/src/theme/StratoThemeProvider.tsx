import { ConfigProvider, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import type { ReactNode } from 'react';
import { useMemo } from 'react';

/**
 * 主题封装：antd ConfigProvider token 与 antd-mobile CSS 变量同一套色值，真源为 global.css 的 --color-* 变量。
 * antd / antd-mobile 只允许在 shared/ui/** 内 import（coding-standard §4）；app 层只 import 本组件。
 */
interface AppThemeProviderProps {
  children: ReactNode;
}

function cssVar(name: string, fallback: string): string {
  if (typeof window === 'undefined') {
    return fallback;
  }
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v.length > 0 ? v : fallback;
}

export function AppThemeProvider({ children }: AppThemeProviderProps) {
  const token = useMemo(
    () => ({
      colorPrimary: cssVar('--color-primary', '#3370ff'),
      colorText: cssVar('--color-text', '#1f2329'),
      colorTextSecondary: cssVar('--color-text-muted', '#646a73'),
      colorBorder: cssVar('--color-border', '#dee0e3'),
      colorBgLayout: cssVar('--color-bg', '#f7f8fa'),
      colorBgContainer: cssVar('--color-surface', '#ffffff'),
      borderRadius: 6,
    }),
    [],
  );

  // antd-mobile 通过 CSS 变量取色；在根节点上同步同一套值
  const mobileVars = useMemo(
    () =>
      ({
        '--adm-color-primary': token.colorPrimary,
        '--adm-color-text': token.colorText,
        '--adm-color-text-secondary': token.colorTextSecondary,
        '--adm-color-border': token.colorBorder,
        '--adm-color-background': token.colorBgContainer,
        '--adm-color-box': token.colorBgLayout,
      }) as Record<string, string>,
    [token],
  );

  return (
    <ConfigProvider locale={zhCN} theme={{ algorithm: antdTheme.defaultAlgorithm, token }}>
      <div style={mobileVars}>{children}</div>
    </ConfigProvider>
  );
}
