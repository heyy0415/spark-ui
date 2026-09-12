import { describe, expect, it } from 'vitest';
import { HttpError } from './http';
import { consumeSse, parseFrame } from './sse';
import type { SseFrame } from './sse';

/** SSE 客户端：分帧解析与跨 chunk 流消费。假 fetch 用 ReadableStream 按脚本推 chunk，不依赖计时。 */

function streamResponse(chunks: string[], init: ResponseInit = { status: 200 }): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const c of chunks) {
        controller.enqueue(encoder.encode(c));
      }
      controller.close();
    },
  });
  return new Response(body, init);
}

interface Captured {
  url: string;
  init: RequestInit | undefined;
}

function fakeFetch(response: Response, captured: Captured[] = []): typeof fetch {
  return async (input, init) => {
    captured.push({ url: String(input), init });
    return response;
  };
}

async function collect(chunks: string[]): Promise<SseFrame[]> {
  const frames: SseFrame[] = [];
  await consumeSse(
    { path: '/agent/runs', body: {}, fetch: fakeFetch(streamResponse(chunks)), baseUrl: '' },
    (f) => frames.push(f),
  );
  return frames;
}

describe('parseFrame', () => {
  it('parses event and JSON data', () => {
    expect(parseFrame('event: run.started\ndata: {"runId":"run_1"}')).toEqual({
      event: 'run.started',
      data: { runId: 'run_1' },
    });
  });

  it('accepts no space after the colon (SseEmitter format)', () => {
    expect(parseFrame('event:run.completed\ndata:{}')).toEqual({
      event: 'run.completed',
      data: {},
    });
  });

  it('joins multi-line data with newlines before JSON parsing', () => {
    const frame = parseFrame('event: message.delta\ndata: {"text":\ndata: "a"}');
    expect(frame?.data).toEqual({ text: 'a' });
  });

  it('keeps id when present and omits the key otherwise', () => {
    expect(parseFrame('id: 7\nevent: e\ndata: {}')).toEqual({ event: 'e', data: {}, id: '7' });
    expect(parseFrame('event: e\ndata: {}')).not.toHaveProperty('id');
  });

  it('returns null for comment-only or event-less frames', () => {
    expect(parseFrame(': ping')).toBeNull();
    expect(parseFrame('')).toBeNull();
    expect(parseFrame('data: {"x":1}')).toBeNull();
  });

  it('keeps non-JSON data as raw text and empty data as null', () => {
    expect(parseFrame('event: e\ndata: not json')).toEqual({ event: 'e', data: 'not json' });
    expect(parseFrame('event: e')).toEqual({ event: 'e', data: null });
  });

  it('ignores unknown fields and lines without a colon', () => {
    expect(parseFrame('retry: 1000\nweird\nevent: e\ndata: 1')).toEqual({ event: 'e', data: 1 });
  });
});

describe('consumeSse', () => {
  it('reassembles a frame split across chunks', async () => {
    const frames = await collect(['event: run.st', 'arted\ndata: {"runId":', '"run_1"}\n\n']);
    expect(frames).toEqual([{ event: 'run.started', data: { runId: 'run_1' } }]);
  });

  it('handles several frames in one chunk and CRLF separators', async () => {
    const frames = await collect(['event: a\r\ndata: 1\r\n\r\nevent: b\r\ndata: 2\r\n\r\n']);
    expect(frames.map((f) => f.event)).toEqual(['a', 'b']);
    expect(frames.map((f) => f.data)).toEqual([1, 2]);
  });

  it('skips ping comment frames', async () => {
    const frames = await collect([': ping\n\nevent: a\ndata: 1\n\n: ping\n\n']);
    expect(frames).toEqual([{ event: 'a', data: 1 }]);
  });

  it('delivers a trailing frame without a final blank line', async () => {
    const frames = await collect(['event: a\ndata: 1\n\nevent: tail\ndata: 2']);
    expect(frames.map((f) => f.event)).toEqual(['a', 'tail']);
  });

  it('delivers nothing for an empty stream', async () => {
    expect(await collect([])).toEqual([]);
  });

  it('posts JSON with SSE accept header to baseUrl + path and forwards the signal', async () => {
    const captured: Captured[] = [];
    const ac = new AbortController();
    await consumeSse(
      {
        path: '/agent/runs',
        body: { message: 'hi' },
        fetch: fakeFetch(streamResponse([]), captured),
        baseUrl: 'https://gw.example.com',
        headers: { 'X-Trace-Id': 't1' },
        signal: ac.signal,
      },
      () => {},
    );
    expect(captured).toHaveLength(1);
    const call = captured[0];
    if (!call) {
      throw new Error('fetch not called');
    }
    expect(call.url).toBe('https://gw.example.com/agent/runs');
    expect(call.init?.method).toBe('POST');
    expect(call.init?.body).toBe(JSON.stringify({ message: 'hi' }));
    expect(call.init?.signal).toBe(ac.signal);
    const headers = call.init?.headers as Record<string, string>;
    expect(headers['Accept']).toBe('text/event-stream');
    expect(headers['Content-Type']).toBe('application/json');
    expect(headers['X-Trace-Id']).toBe('t1');
  });

  it('throws HttpError with parsed JSON body on non-2xx', async () => {
    const res = new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'x', traceId: 't' }), {
      status: 404,
    });
    await expect(
      consumeSse({ path: '/agent/runs/x', body: {}, fetch: fakeFetch(res), baseUrl: '' }, () => {}),
    ).rejects.toSatisfy((e: unknown) => {
      expect(e).toBeInstanceOf(HttpError);
      const err = e as HttpError;
      expect(err.status).toBe(404);
      expect(err.body).toEqual({ code: 'NOT_FOUND', message: 'x', traceId: 't' });
      return true;
    });
  });

  it('keeps a non-JSON error body as text', async () => {
    const res = new Response('<html>502</html>', { status: 502 });
    await expect(
      consumeSse({ path: '/agent/runs', body: {}, fetch: fakeFetch(res), baseUrl: '' }, () => {}),
    ).rejects.toSatisfy((e: unknown) => {
      expect(e).toBeInstanceOf(HttpError);
      const err = e as HttpError;
      expect(err.status).toBe(502);
      expect(err.body).toBe('<html>502</html>');
      return true;
    });
  });

  it('throws HttpError when a 2xx response has no body', async () => {
    const res = new Response(null, { status: 204 });
    await expect(
      consumeSse({ path: '/agent/runs', body: {}, fetch: fakeFetch(res), baseUrl: '' }, () => {}),
    ).rejects.toBeInstanceOf(HttpError);
  });
});
