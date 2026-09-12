import { describe, expect, it, vi } from 'vitest';
import { createRunStore } from './runStore';
import { emptyView } from './runView';

describe('createRunStore', () => {
  it('starts from an empty view for the given conversation', () => {
    const store = createRunStore('conv-1');
    expect(store.getSnapshot()).toEqual(emptyView('conv-1'));
  });

  it('notifies subscribers on change and stops after unsubscribe', () => {
    const store = createRunStore('conv-1');
    const listener = vi.fn();
    const unsubscribe = store.subscribe(listener);

    store.dispatch(emptyView('conv-2'));
    expect(listener).toHaveBeenCalledTimes(1);

    unsubscribe();
    store.dispatch(emptyView('conv-3'));
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('supports the updater form like setQueryData did', () => {
    const store = createRunStore('conv-1');
    store.dispatch((prev) => ({ ...prev, conversationId: 'conv-updated' }));
    expect(store.getSnapshot().conversationId).toBe('conv-updated');
  });

  /** 引用未变时不通知：reduceEvent 对无关事件会返回同一对象，此时不该触发重渲染。 */
  it('skips notification when the next value is reference-equal', () => {
    const store = createRunStore('conv-1');
    const listener = vi.fn();
    store.subscribe(listener);

    store.dispatch((prev) => prev);
    expect(listener).not.toHaveBeenCalled();
  });

  it('delivers to every subscriber', () => {
    const store = createRunStore('conv-1');
    const a = vi.fn();
    const b = vi.fn();
    store.subscribe(a);
    store.subscribe(b);

    store.dispatch(emptyView('conv-2'));
    expect(a).toHaveBeenCalledTimes(1);
    expect(b).toHaveBeenCalledTimes(1);
  });

  /** 监听器在回调里取消订阅不应打断本轮通知（遍历前已复制一份）。 */
  it('tolerates unsubscribing from within a listener', () => {
    const store = createRunStore('conv-1');
    const second = vi.fn();
    const unsubscribeFirst = store.subscribe(() => unsubscribeFirst());
    store.subscribe(second);

    expect(() => store.dispatch(emptyView('conv-2'))).not.toThrow();
    expect(second).toHaveBeenCalledTimes(1);
  });
});
