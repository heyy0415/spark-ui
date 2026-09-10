import type { SparkThemeTokens } from '@spark-ui/core';

/**
 * 交给 @spark-ui/core 的主题令牌。与 global.css 的 --color-* 变量同值（两处手工保持一致，
 * 不在渲染期 getComputedStyle 读取，见 coding-standard §4）。
 */
export const SPARK_TOKENS: SparkThemeTokens = {
  colorPrimary: '#3370ff',
  colorText: '#1f2329',
  colorTextSecondary: '#646a73',
  colorBorder: '#dee0e3',
  colorBgLayout: '#f7f8fa',
  colorBgContainer: '#ffffff',
  borderRadius: 6,
};
