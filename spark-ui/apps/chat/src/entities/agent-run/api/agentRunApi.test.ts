import { describe, expect, it } from 'vitest';
import { ZodError } from 'zod';
import { AGENT_RUNS_PATH, actionPath, buildActionRequest, buildIntentRequest } from './agentRunApi';

/** 出站请求体在发送前过契约投影；路径段做 URL 编码。 */
describe('buildIntentRequest', () => {
  const ok = {
    conversationId: 'conv-1',
    message: '看看条目',
    clientCapabilities: { uiSchemaVersion: '1.0' as const, components: ['Card', 'Table'] },
  };

  it('returns the validated request unchanged', () => {
    expect(buildIntentRequest(ok)).toEqual(ok);
  });

  it('rejects a message over 2000 chars', () => {
    expect(() => buildIntentRequest({ ...ok, message: 'x'.repeat(2001) })).toThrow(ZodError);
  });

  it('rejects an empty message and duplicate components', () => {
    expect(() => buildIntentRequest({ ...ok, message: '' })).toThrow(ZodError);
    expect(() =>
      buildIntentRequest({
        ...ok,
        clientCapabilities: { uiSchemaVersion: '1.0', components: ['Card', 'Card'] },
      }),
    ).toThrow(ZodError);
  });
});

describe('buildActionRequest', () => {
  it('accepts a 16+ char token with small formData', () => {
    const req = { confirmationToken: 'ct_' + 'a'.repeat(20), formData: { reason: 'DAMAGED' } };
    expect(buildActionRequest(req)).toEqual(req);
  });

  it('rejects a short token', () => {
    expect(() => buildActionRequest({ confirmationToken: 'short', formData: {} })).toThrow(
      ZodError,
    );
  });

  it('rejects formData with more than 16 keys or bad key names', () => {
    const many: Record<string, string> = {};
    for (let i = 0; i < 17; i += 1) {
      many[`k${i}`] = 'v';
    }
    const token = 'ct_' + 'a'.repeat(20);
    expect(() => buildActionRequest({ confirmationToken: token, formData: many })).toThrow(
      ZodError,
    );
    expect(() =>
      buildActionRequest({ confirmationToken: token, formData: { '1bad': 'v' } }),
    ).toThrow(ZodError);
  });
});

describe('paths', () => {
  it('actionPath URL-encodes both segments', () => {
    expect(actionPath('run_1', 'confirm-close')).toBe('/agent/runs/run_1/actions/confirm-close');
    expect(actionPath('a/b', 'c d')).toBe('/agent/runs/a%2Fb/actions/c%20d');
    expect(AGENT_RUNS_PATH).toBe('/agent/runs');
  });
});
