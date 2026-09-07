import { ConfigProvider, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import type { CSSProperties, ReactNode } from 'react';
import { useMemo } from 'react';

/**
 * 主题令牌：键集合 = 首期实际使用的 7 个，全部可选，默认值即首期 chat 应用 global.css 的色值。
 * 色值只来自 props，不读宿主 CSS 变量（包可在任何宿主里独立成立）。
 */
export interface StratoThemeTokens {
  colorPrimary?: string;
  colorText?: string;
  colorTextSecondary?: string;
  colorBorder?: string;
  colorBgLayout?: string;
  colorBgContainer?: string;
  borderRadius?: number;
}

export interface StratoThemeProviderProps {
  tokens?: StratoThemeTokens;
  children: ReactNode;
}

const DEFAULT_TOKENS: Required<StratoThemeTokens> = {
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
 * 以及包内 CSS Modules 使用的 --strato-* 变量（渲染器 / 占位组件）。三者色值一致。
 */
export function StratoThemeProvider({ tokens, children }: StratoThemeProviderProps) {
  const token = useMemo<Required<StratoThemeTokens>>(
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
        '--strato-color-text': token.colorText,
        '--strato-color-text-muted': token.colorTextSecondary,
        '--strato-color-border': token.colorBorder,
        '--strato-color-surface-hover': token.colorBgLayout,
        '--strato-radius-md': `${token.borderRadius}px`,
      }) as CSSProperties,
    [token],
  );

  return (
    <ConfigProvider locale={zhCN} theme={{ algorithm: antdTheme.defaultAlgorithm, token }}>
      <div style={cssVars}>{children}</div>
    </ConfigProvider>
  );
}
