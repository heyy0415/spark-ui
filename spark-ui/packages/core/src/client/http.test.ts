import { afterEach, describe, expect, it, vi } from 'vitest';
import { z } from 'zod';
import { HttpError, request } from './http';

/** 唯一 HTTP 出口：响应必须过 Zod；非 2xx 与 schema 不符都以 HttpError 抛出，HTML 错误页不逃出 SyntaxError。 */

interface Captured {
  url: string;
  init: RequestInit | undefined;
}

function fakeFetch(body: string | null, status: number, captured: Captured[] = []): typeof fetch {
  return async (input, init) => {
    captured.push({ url: String(input), init });
    return new Response(body, { status });
  };
}

const Item = z.object({ id: z.string() }).strict();

describe('request', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns schema-validated data on 2xx', async () => {
    const captured: Captured[] = [];
    const data = await request('/agent/runs/run_1', {
      schema: Item,
      fetch: fakeFetch(JSON.stringify({ id: 'run_1' }), 200, captured),
      baseUrl: 'https://gw.example.com',
    });
    expect(data).toEqual({ id: 'run_1' });
    expect(captured[0]?.url).toBe('https://gw.example.com/agent/runs/run_1');
    expect(captured[0]?.init?.method).toBe('GET');
    const headers = captured[0]?.init?.headers as Record<string, string>;
    expect(headers['Accept']).toBe('application/json');
    expect(headers['Content-Type']).toBeUndefined();
  });

  it('adds Content-Type and serialises the body when a body is given', async () => {
    const captured: Captured[] = [];
    await request('/x', {
      method: 'POST',
      body: { a: 1 },
      schema: Item,
      fetch: fakeFetch(JSON.stringify({ id: 'x' }), 200, captured),
      baseUrl: '',
    });
    const headers = captured[0]?.init?.headers as Record<string, string>;
    expect(headers['Content-Type']).toBe('application/json');
    expect(captured[0]?.init?.body).toBe('{"a":1}');
    expect(captured[0]?.init?.method).toBe('POST');
  });

  it('throws HttpError with status and parsed body on non-2xx', async () => {
    const body = { code: 'NOT_FOUND', message: 'x', traceId: 't' };
    await expect(
      request('/x', { schema: Item, fetch: fakeFetch(JSON.stringify(body), 404), baseUrl: '' }),
    ).rejects.toSatisfy((e: unknown) => {
      expect(e).toBeInstanceOf(HttpError);
      expect((e as HttpError).status).toBe(404);
      expect((e as HttpError).body).toEqual(body);
      return true;
    });
  });

  it('turns an HTML error page into HttpError with null body (no SyntaxError)', async () => {
    await expect(
      request('/x', {
        schema: Item,
        fetch: fakeFetch('<html>bad gateway</html>', 502),
        baseUrl: '',
      }),
    ).rejects.toSatisfy((e: unknown) => {
      expect(e).toBeInstanceOf(HttpError);
      expect((e as HttpError).status).toBe(502);
      expect((e as HttpError).body).toBeNull();
      return true;
    });
  });

  it('rejects a 2xx response that does not match the schema and logs once', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    await expect(
      request('/x', {
        schema: Item,
        fetch: fakeFetch(JSON.stringify({ id: 1 }), 200),
        baseUrl: '',
      }),
    ).rejects.toSatisfy((e: unknown) => {
      expect(e).toBeInstanceOf(HttpError);
      expect((e as HttpError).status).toBe(200);
      expect((e as HttpError).message).toContain('did not match schema');
      return true;
    });
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('forwards the abort signal and extra headers', async () => {
    const captured: Captured[] = [];
    const ac = new AbortController();
    await request('/x', {
      schema: Item,
      fetch: fakeFetch(JSON.stringify({ id: 'x' }), 200, captured),
      baseUrl: '',
      signal: ac.signal,
      headers: { 'X-Trace-Id': 't1' },
    });
    const call = captured[0];
    if (!call) {
      throw new Error('fetch not called');
    }
    expect(call.init?.signal).toBe(ac.signal);
    const headers = (call.init?.headers ?? {}) as Record<string, string>;
    expect(headers['X-Trace-Id']).toBe('t1');
  });

  it('HttpError carries name, status and body', () => {
    const e = new HttpError(418, 'teapot', { a: 1 });
    expect(e.name).toBe('HttpError');
    expect(e.status).toBe(418);
    expect(e.body).toEqual({ a: 1 });
    expect(e).toBeInstanceOf(Error);
  });
});
