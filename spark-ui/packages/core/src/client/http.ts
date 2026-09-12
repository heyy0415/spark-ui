import type { ZodType } from 'zod';

/** 网络层统一错误（coding-standard.md §6）。 */
export class HttpError extends Error {
  override readonly name = 'HttpError';
  readonly status: number;
  readonly body: unknown;

  constructor(status: number, message: string, body?: unknown) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

interface RequestOptions<T> {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  schema: ZodType<T>;
  signal?: AbortSignal;
  headers?: Record<string, string>;
  /** 宿主注入的 fetch（带登录态）；缺省 window.fetch。 */
  fetch?: typeof fetch;
  /**
   * 端点前缀，同源传 `''`。**必填**：headless 层不读环境变量（core 里不读构建期注入的环境变量），
   * 缺省值属于应用层决策——`apps/chat` 在构造 `Transport` 时用 `env.VITE_API_BASE_URL` 兜底。
   */
  baseUrl: string;
}

/**
 * 唯一的 HTTP 出口。所有响应都必须带 Zod schema 校验后才能进入应用。
 */
export async function request<T>(path: string, options: RequestOptions<T>): Promise<T> {
  const { method = 'GET', body, schema, signal, headers = {}, baseUrl, fetch: doFetch } = options;

  const init: RequestInit = {
    method,
    headers: { Accept: 'application/json', ...headers },
  };
  if (body !== undefined) {
    init.headers = { ...init.headers, 'Content-Type': 'application/json' };
    init.body = JSON.stringify(body);
  }
  if (signal) {
    init.signal = signal;
  }

  const res = await (doFetch ?? fetch)(`${baseUrl}${path}`, init);

  const text = await res.text();
  let json: unknown = null;
  try {
    json = text.length > 0 ? JSON.parse(text) : null;
  } catch {
    // 网关 / 反代返回 HTML 错误页：仍按 HttpError 抛，不让 SyntaxError 逃出网络层
    json = null;
  }

  if (!res.ok) {
    throw new HttpError(res.status, `${method} ${path} failed with ${res.status}`, json);
  }

  const parsed = schema.safeParse(json);
  if (!parsed.success) {
    console.error('[http-client] response schema mismatch', path, parsed.error.issues);
    throw new HttpError(res.status, `${method} ${path}: response did not match schema`, json);
  }
  return parsed.data;
}
