import { describe, expect, it } from 'vitest';
import { RunSummarySchema, SseEventSchema } from './contracts';

/** 契约投影：入站 SSE 帧与 run-summary 的 strict / 枚举 / 条件必填与 .harness/contracts 一致。 */

const RUN = 'run_abc123';
const AT = '2026-09-11T00:00:00Z';

describe('SseEventSchema', () => {
  it('accepts each of the ten events', () => {
    const ui = { schemaVersion: '1.0', screenId: 's', title: 't', components: [], actions: [] };
    const frames = [
      { event: 'run.started', data: { runId: RUN, conversationId: 'c', at: AT } },
      { event: 'message.delta', data: { runId: RUN, text: 'hi' } },
      {
        event: 'tool.selected',
        data: {
          runId: RUN,
          toolCallId: 'tc_1',
          toolId: 'demo.item.get',
          version: '1.0.0',
          displayName: 'x',
        },
      },
      { event: 'tool.started', data: { runId: RUN, toolCallId: 'tc_1', at: AT } },
      {
        event: 'tool.completed',
        data: { runId: RUN, toolCallId: 'tc_1', status: 'succeeded', durationMs: 1 },
      },
      { event: 'ui.replace', data: { runId: RUN, ui } },
      {
        event: 'ui.patch',
        data: { runId: RUN, screenId: 's', components: [{ id: 'c', type: 'Card', props: {} }] },
      },
      { event: 'confirmation.required', data: { runId: RUN, actionId: 'confirm', expiresAt: AT } },
      { event: 'run.completed', data: { runId: RUN, at: AT } },
      { event: 'run.failed', data: { runId: RUN, code: 'INTERNAL_ERROR', message: 'x', at: AT } },
    ];
    for (const f of frames) {
      expect(SseEventSchema.safeParse(f).success, f.event).toBe(true);
    }
  });

  it('rejects an unknown event name', () => {
    expect(SseEventSchema.safeParse({ event: 'model.thought', data: { runId: RUN } }).success).toBe(
      false,
    );
  });

  it('rejects extra keys in data (strict)', () => {
    expect(
      SseEventSchema.safeParse({ event: 'run.completed', data: { runId: RUN, at: AT, stack: 'x' } })
        .success,
    ).toBe(false);
  });

  it('rejects run ids and tool ids that do not match the contract patterns', () => {
    expect(
      SseEventSchema.safeParse({ event: 'run.completed', data: { runId: 'abc', at: AT } }).success,
    ).toBe(false);
    expect(
      SseEventSchema.safeParse({
        event: 'tool.selected',
        data: {
          runId: RUN,
          toolCallId: 'tc_1',
          toolId: 'Demo.Item',
          version: '1.0.0',
          displayName: 'x',
        },
      }).success,
    ).toBe(false);
  });

  it('run.failed.code only accepts the five failure codes', () => {
    const ok = SseEventSchema.safeParse({
      event: 'run.failed',
      data: { runId: RUN, code: 'TOOL_SELECTION_INVALID', message: 'x', at: AT },
    });
    expect(ok.success).toBe(true);
    const bad = SseEventSchema.safeParse({
      event: 'run.failed',
      data: { runId: RUN, code: 'UNKNOWN', message: 'x', at: AT },
    });
    expect(bad.success).toBe(false);
  });
});

describe('RunSummarySchema', () => {
  const base = { runId: RUN, conversationId: 'c', createdAt: AT, updatedAt: AT };

  it('FAILED requires failureCode', () => {
    expect(RunSummarySchema.safeParse({ ...base, state: 'FAILED' }).success).toBe(false);
    expect(
      RunSummarySchema.safeParse({ ...base, state: 'FAILED', failureCode: 'INTERNAL_ERROR' })
        .success,
    ).toBe(true);
  });

  it('WAITING_CONFIRMATION requires currentUi', () => {
    expect(RunSummarySchema.safeParse({ ...base, state: 'WAITING_CONFIRMATION' }).success).toBe(
      false,
    );
    const ui = { schemaVersion: '1.0', screenId: 's', title: 't', components: [], actions: [] };
    expect(
      RunSummarySchema.safeParse({ ...base, state: 'WAITING_CONFIRMATION', currentUi: ui }).success,
    ).toBe(true);
  });

  it('COMPLETED needs neither', () => {
    expect(RunSummarySchema.safeParse({ ...base, state: 'COMPLETED' }).success).toBe(true);
  });
});
