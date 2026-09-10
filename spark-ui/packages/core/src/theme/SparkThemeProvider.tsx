import { ConfigProvider, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import type { CSSProperties, ReactNode } from 'react';
import { useMemo } from 'react';

/**
 * 主题令牌：键集合 = 首期实际使用的 7 个，全部可选，默认值即首期 chat 应用 global.css 的色值。
 * 色值只来自 props，不读宿主 CSS 变量（包可在任何宿主里独立成立）。
 */
export interface SparkThemeTokens {
  colorPrimary?: string;
  colorText?: string;
  colorTextSecondary?: string;
  colorBorder?: string;
  colorBgLayout?: string;
  colorBgContainer?: string;
  borderRadius?: number;
}

export interface SparkThemeProviderProps {
  tokens?: SparkThemeTokens;
  children: ReactNode;
}

const DEFAULT_TOKENS: Required<SparkThemeTokens> = {
  colorPrimary: '#3370ff',
  colorText: '#1f2329',
  colorTextSecondary: '#646a73',
  colorBorder: '#dee0e3',
  colorBgLayout: '#f7f8fa',
  colorBgContainer: '#ffffff',
  borderRadius: 6,
};

/**
 * 同一套令牌同时下发给：antd ConfigProvider（桌面组件）、antd-mobile 的 --adm-* CSS 变量（移动组件）、
 * 以及包内 CSS Modules 使用的 --spark-* 变量（渲染器 / 占位组件）。三者色值一致。
 */
export function SparkThemeProvider({ tokens, children }: SparkThemeProviderProps) {
  const token = useMemo<Required<SparkThemeTokens>>(
    () => ({ ...DEFAULT_TOKENS, ...tokens }),
    [tokens],
  );

  const cssVars = useMemo<CSSProperties>(
    () =>
      ({
        '--adm-color-primary': token.colorPrimary,
        '--adm-color-text': token.colorText,
        '--adm-color-text-secondary': token.colorTextSecondary,
        '--adm-color-border': token.colorBorder,
        '--adm-color-background': token.colorBgContainer,
        '--adm-color-box': token.colorBgLayout,
        '--spark-color-text': token.colorText,
        '--spark-color-text-muted': token.colorTextSecondary,
        '--spark-color-border': token.colorBorder,
        // 占位块背景：介于 layout 与 container 之间的一层，首期与 chat global.css 的 --color-surface-hover 同值
        '--spark-color-surface-hover': '#f0f2f5',
        '--spark-radius-md': `${token.borderRadius}px`,
      }) as CSSProperties,
    [token],
  );

  return (
    <ConfigProvider locale={zhCN} theme={{ algorithm: antdTheme.defaultAlgorithm, token }}>
      <div style={cssVars}>{children}</div>
    </ConfigProvider>
  );
}
