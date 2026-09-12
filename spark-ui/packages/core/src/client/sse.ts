import { HttpError } from './http';

/**
 * SSE 客户端（fetch + ReadableStream）。原生 EventSource 不支持 POST body，故自实现：
 * 按 "\n\n" 分帧；支持 event: / data:（多行拼接）/ id: 行；兼容冒号后有无空格；忽略 ": ping" 注释帧。
 * 每帧回调 {event, data: unknown}，由调用方做 Zod 校验（coding-standard §2）。
 */
export interface SseFrame {
  event: string;
  data: unknown;
  id?: string;
}

export interface SseRequest {
  path: string;
  body: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  /** 宿主注入的 fetch（带登录态）；缺省 window.fetch。 */
  fetch?: typeof fetch;
  /** 端点前缀，同源传 `''`。**必填**，理由同 `http.ts` 的 RequestOptions.baseUrl。 */
  baseUrl: string;
}

export type SseFrameHandler = (frame: SseFrame) => void;

/** 消费完整流直到服务端关闭；非 2xx 直接抛 HttpError（body 为 error-response）。 */
export async function consumeSse(req: SseRequest, onFrame: SseFrameHandler): Promise<void> {
  const init: RequestInit = {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...req.headers },
    body: JSON.stringify(req.body),
  };
  if (req.signal) {
    init.signal = req.signal;
  }
  const doFetch = req.fetch ?? fetch;
  const res = await doFetch(`${req.baseUrl}${req.path}`, init);
  if (!res.ok) {
    const text = await res.text();
    let json: unknown = null;
    try {
      json = text.length > 0 ? JSON.parse(text) : null;
    } catch {
      json = text;
    }
    throw new HttpError(res.status, `POST ${req.path} failed with ${res.status}`, json);
  }
  if (!res.body) {
    throw new HttpError(res.status, `POST ${req.path}: empty SSE body`);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    // 兼容 \r\n\r\n 与 \n\n
    buffer = buffer.replace(/\r\n/g, '\n');
    let sep = buffer.indexOf('\n\n');
    while (sep >= 0) {
      const raw = buffer.slice(0, sep);
      buffer = buffer.slice(sep + 2);
      const frame = parseFrame(raw);
      if (frame) {
        onFrame(frame);
      }
      sep = buffer.indexOf('\n\n');
    }
  }
  const tail = parseFrame(buffer);
  if (tail) {
    onFrame(tail);
  }
}

/** 解析单帧文本；纯注释帧或无 event 返回 null。data 为 JSON 时解析，否则保留原文。 */
export function parseFrame(raw: string): SseFrame | null {
  let event: string | null = null;
  let id: string | undefined;
  const dataLines: string[] = [];
  for (const line of raw.split('\n')) {
    if (line === '' || line.startsWith(':')) {
      continue;
    }
    const idx = line.indexOf(':');
    const field = idx >= 0 ? line.slice(0, idx) : line;
    let value = idx >= 0 ? line.slice(idx + 1) : '';
    if (value.startsWith(' ')) {
      value = value.slice(1);
    }
    if (field === 'event') {
      event = value;
    } else if (field === 'data') {
      dataLines.push(value);
    } else if (field === 'id') {
      id = value;
    }
  }
  if (event === null) {
    return null;
  }
  const text = dataLines.join('\n');
  let data: unknown = text;
  try {
    data = text.length > 0 ? JSON.parse(text) : null;
  } catch {
    data = text;
  }
  return id === undefined ? { event, data } : { event, data, id };
}
