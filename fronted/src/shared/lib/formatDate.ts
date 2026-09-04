/** 时间统一以 ISO-8601 字符串传输，只在 UI 渲染时格式化（coding-standard.md §3）。 */
export function formatDateTime(iso: string, locale = 'zh-CN'): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) {
    throw new Error(`[formatDateTime] invalid ISO-8601 string: ${iso}`);
  }
  return d.toLocaleString(locale);
}
