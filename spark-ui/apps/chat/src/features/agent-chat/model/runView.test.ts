import { describe, expect, it } from 'vitest';
import type { UiSchema } from '@spark-ui/core';
import type { SseEvent } from '@entities/agent-run';
import {
  beginConfirm,
  beginTurn,
  cancelConfirm,
  emptyView,
  failIfStillStreaming,
  lastTurn,
  reduceEvent,
  skeletonVariant,
} from './runView';
import type { AgentRunView, ChatTurn } from './runView';

/**
 * 回合状态机：由 SSE 事件流归约。夹具全部手写（chat 的 oxlint 禁 ../../ 与 @contracts/*），工具用中性名 demo.item.*。
 */

const RUN = 'run_abc123';
const AT = '2026-09-11T00:00:00Z';

const screen = (screenId: string, componentId = 'card'): UiSchema => ({
  schemaVersion: '1.0',
  screenId,
  title: '结果',
  components: [{ id: componentId, type: 'Card', props: { title: '结果', items: [] } }],
  actions: [],
});

const started = (runId = RUN): SseEvent => ({
  event: 'run.started',
  data: { runId, conversationId: 'conv-1', at: AT },
});

function oneTurn(): AgentRunView {
  return reduceEvent(beginTurn(emptyView('conv-1'), '看看条目'), started());
}

function last(view: AgentRunView): ChatTurn {
  const t = lastTurn(view);
  if (!t) {
    throw new Error('no turn');
  }
  return t;
}

describe('turn lifecycle', () => {
  it('emptyView has no turns and lastTurn is null', () => {
    const v = emptyView('conv-1');
    expect(v.turns).toEqual([]);
    expect(lastTurn(v)).toBeNull();
  });

  it('beginTurn appends a streaming turn with the user text', () => {
    const v = beginTurn(emptyView('conv-1'), '看看条目');
    expect(v.turns).toHaveLength(1);
    const t = last(v);
    expect(t.user).toBe('看看条目');
    expect(t.status).toBe('streaming');
    expect(t.runId).toBeNull();
    expect(t.tools).toEqual([]);
    expect(t.texts).toEqual([]);
    expect(t.ui).toBeNull();
  });

  it('beginTurn closes a previous waiting_confirmation turn (M2)', () => {
    let v = oneTurn();
    v = reduceEvent(v, {
      event: 'confirmation.required',
      data: { runId: RUN, actionId: 'confirm-close', expiresAt: AT },
    });
    expect(last(v).status).toBe('waiting_confirmation');
    v = beginTurn(v, '下一句');
    expect(v.turns).toHaveLength(2);
    expect(v.turns[0]?.status).toBe('completed');
    expect(v.turns[0]?.pendingActionId).toBeNull();
    expect(last(v).status).toBe('streaming');
  });

  it('beginTurn leaves a completed previous turn untouched', () => {
    let v = oneTurn();
    v = reduceEvent(v, { event: 'run.completed', data: { runId: RUN, at: AT } });
    v = beginTurn(v, '下一句');
    expect(v.turns[0]?.status).toBe('completed');
  });

  it('turn ids are unique across turns', () => {
    const v = beginTurn(beginTurn(emptyView('c'), 'a'), 'b');
    expect(v.turns[0]?.id).not.toBe(v.turns[1]?.id);
  });

  it('beginConfirm keeps the same turn and returns to streaming', () => {
    let v = oneTurn();
    v = reduceEvent(v, {
      event: 'confirmation.required',
      data: { runId: RUN, actionId: 'confirm-close', expiresAt: AT },
    });
    v = beginConfirm(v);
    expect(v.turns).toHaveLength(1);
    expect(last(v).status).toBe('streaming');
    expect(last(v).pendingActionId).toBeNull();
  });

  it('cancelConfirm completes the turn but keeps the screen', () => {
    let v = oneTurn();
    v = reduceEvent(v, { event: 'ui.replace', data: { runId: RUN, ui: screen('confirm') } });
    v = reduceEvent(v, {
      event: 'confirmation.required',
      data: { runId: RUN, actionId: 'confirm-close', expiresAt: AT },
    });
    v = cancelConfirm(v);
    expect(last(v).status).toBe('completed');
    expect(last(v).ui?.screenId).toBe('confirm');
    expect(last(v).pendingActionId).toBeNull();
  });

  it('failIfStillStreaming only fails a streaming turn (M1)', () => {
    const streaming = failIfStillStreaming(oneTurn());
    expect(last(streaming).status).toBe('failed');
    expect(last(streaming).failure).toEqual({
      code: 'INTERNAL_ERROR',
      message: '连接中断，请重试',
    });

    let done = oneTurn();
    done = reduceEvent(done, { event: 'run.completed', data: { runId: RUN, at: AT } });
    expect(last(failIfStillStreaming(done)).status).toBe('completed');
    expect(failIfStillStreaming(emptyView('c')).turns).toEqual([]);
  });

  it('failIfStillStreaming keeps a waiting_confirmation turn intact (stream closes after confirmation.required)', () => {
    let v = oneTurn();
    v = reduceEvent(v, {
      event: 'confirmation.required',
      data: { runId: RUN, actionId: 'confirm-close', expiresAt: AT },
    });
    const after = failIfStillStreaming(v);
    expect(last(after).status).toBe('waiting_confirmation');
    expect(last(after).pendingActionId).toBe('confirm-close');
    expect(last(after).failure).toBeNull();
  });
});

describe('reduceEvent', () => {
  it('is a no-op on an empty view', () => {
    const v = emptyView('conv-1');
    expect(reduceEvent(v, started())).toBe(v);
  });

  it('run.started binds the runId and resets failure', () => {
    const t = last(oneTurn());
    expect(t.runId).toBe(RUN);
    expect(t.status).toBe('streaming');
    expect(t.failure).toBeNull();
  });

  it('message.delta appends texts in order', () => {
    let v = oneTurn();
    v = reduceEvent(v, { event: 'message.delta', data: { runId: RUN, text: 'a' } });
    v = reduceEvent(v, { event: 'message.delta', data: { runId: RUN, text: 'b' } });
    expect(last(v).texts).toEqual(['a', 'b']);
  });

  it('tool.selected → started → completed drives one progress item', () => {
    let v = oneTurn();
    v = reduceEvent(v, {
      event: 'tool.selected',
      data: {
        runId: RUN,
        toolCallId: 'tc_1',
        toolId: 'demo.item.get',
        version: '1.0.0',
        displayName: '查看条目',
      },
    });
    expect(last(v).tools).toEqual([
      { toolCallId: 'tc_1', toolId: 'demo.item.get', displayName: '查看条目', status: 'selected' },
    ]);
    v = reduceEvent(v, { event: 'tool.started', data: { runId: RUN, toolCallId: 'tc_1', at: AT } });
    expect(last(v).tools[0]?.status).toBe('running');
    v = reduceEvent(v, {
      event: 'tool.completed',
      data: {
        runId: RUN,
        toolCallId: 'tc_1',
        status: 'succeeded',
        durationMs: 12,
        summary: '1 条',
      },
    });
    expect(last(v).tools[0]).toEqual({
      toolCallId: 'tc_1',
      toolId: 'demo.item.get',
      displayName: '查看条目',
      status: 'succeeded',
      durationMs: 12,
      summary: '1 条',
    });
  });

  it('tool.completed without summary does not add a summary key', () => {
    let v = oneTurn();
    v = reduceEvent(v, {
      event: 'tool.selected',
      data: {
        runId: RUN,
        toolCallId: 'tc_1',
        toolId: 'demo.item.get',
        version: '1.0.0',
        displayName: '查看条目',
      },
    });
    v = reduceEvent(v, {
      event: 'tool.completed',
      data: { runId: RUN, toolCallId: 'tc_1', status: 'failed', durationMs: 3 },
    });
    expect(last(v).tools[0]?.status).toBe('failed');
    expect('summary' in (last(v).tools[0] ?? {})).toBe(false);
  });

  it('tool.started for an unknown toolCallId changes nothing', () => {
    const v = oneTurn();
    const after = reduceEvent(v, {
      event: 'tool.started',
      data: { runId: RUN, toolCallId: 'tc_x', at: AT },
    });
    expect(last(after).tools).toEqual([]);
  });

  it('ui.replace overwrites the current screen', () => {
    let v = oneTurn();
    v = reduceEvent(v, { event: 'ui.replace', data: { runId: RUN, ui: screen('s1') } });
    v = reduceEvent(v, { event: 'ui.replace', data: { runId: RUN, ui: screen('s2') } });
    expect(last(v).ui?.screenId).toBe('s2');
  });

  it('ui.patch merges components by id on the same screen', () => {
    let v = oneTurn();
    v = reduceEvent(v, { event: 'ui.replace', data: { runId: RUN, ui: screen('s1', 'card') } });
    v = reduceEvent(v, {
      event: 'ui.patch',
      data: {
        runId: RUN,
        screenId: 's1',
        components: [{ id: 'card', type: 'Card', props: { title: '更新后', items: [] } }],
      },
    });
    expect(last(v).ui?.components[0]?.props).toEqual({ title: '更新后', items: [] });
    expect(last(v).ui?.components).toHaveLength(1);
  });

  it('ui.patch for another screenId or without a screen is ignored', () => {
    let v = oneTurn();
    const patch: SseEvent = {
      event: 'ui.patch',
      data: {
        runId: RUN,
        screenId: 'other',
        components: [{ id: 'card', type: 'Card', props: {} }],
      },
    };
    expect(last(reduceEvent(v, patch)).ui).toBeNull();
    v = reduceEvent(v, { event: 'ui.replace', data: { runId: RUN, ui: screen('s1') } });
    expect(last(reduceEvent(v, patch)).ui?.components[0]?.props).toEqual({
      title: '结果',
      items: [],
    });
  });

  it('confirmation.required sets waiting state and pending action', () => {
    const v = reduceEvent(oneTurn(), {
      event: 'confirmation.required',
      data: { runId: RUN, actionId: 'confirm-close', expiresAt: AT },
    });
    expect(last(v).status).toBe('waiting_confirmation');
    expect(last(v).pendingActionId).toBe('confirm-close');
  });

  it('run.completed and run.failed are terminal', () => {
    const done = reduceEvent(oneTurn(), { event: 'run.completed', data: { runId: RUN, at: AT } });
    expect(last(done).status).toBe('completed');
    expect(last(done).pendingActionId).toBeNull();

    const failed = reduceEvent(oneTurn(), {
      event: 'run.failed',
      data: { runId: RUN, code: 'CONFIRMATION_REJECTED', message: '确认已过期', at: AT },
    });
    expect(last(failed).status).toBe('failed');
    expect(last(failed).failure).toEqual({ code: 'CONFIRMATION_REJECTED', message: '确认已过期' });
  });

  it('drops stale frames whose runId differs from the bound run (S8)', () => {
    let v = oneTurn();
    v = reduceEvent(v, { event: 'message.delta', data: { runId: 'run_old', text: '旧流' } });
    expect(last(v).texts).toEqual([]);
    v = reduceEvent(v, {
      event: 'run.failed',
      data: { runId: 'run_old', code: 'INTERNAL_ERROR', message: 'x', at: AT },
    });
    expect(last(v).status).toBe('streaming');
  });

  it('a new run.started rebinds the turn to the new runId', () => {
    let v = oneTurn();
    v = reduceEvent(v, started('run_new'));
    expect(last(v).runId).toBe('run_new');
    v = reduceEvent(v, { event: 'message.delta', data: { runId: 'run_new', text: 'ok' } });
    expect(last(v).texts).toEqual(['ok']);
  });

  it('events before run.started apply to the unbound turn', () => {
    const v = reduceEvent(beginTurn(emptyView('c'), 'x'), {
      event: 'message.delta',
      data: { runId: RUN, text: 'early' },
    });
    expect(last(v).texts).toEqual(['early']);
  });
});

describe('skeletonVariant', () => {
  const turnWith = (toolId: string): ChatTurn => ({
    ...last(oneTurn()),
    tools: [{ toolCallId: 'tc_1', toolId, displayName: 'x', status: 'running' }],
  });

  it('guesses table for list tools, card for detail-like tools, generic otherwise', () => {
    expect(skeletonVariant(turnWith('demo.list.search'))).toBe('table');
    expect(skeletonVariant(turnWith('demo.item.detail.get'))).toBe('card');
    expect(skeletonVariant(turnWith('demo.item.preview'))).toBe('card');
    expect(skeletonVariant(turnWith('demo.item.close'))).toBe('generic');
    expect(skeletonVariant(last(oneTurn()))).toBe('generic');
  });
});
