import type { AgentRunView } from './runView';
import { emptyView } from './runView';

/** 订阅回调；store 变更后被逐个调用。 */
export type RunStoreListener = () => void;

/**
 * 可订阅的运行视图容器。
 *
 * <p>替代原先的 TanStack Query：那边只用到 `setQueryData` / `getQueryData`（状态容器），
 * 缓存失效、重试、后台刷新一个都没用上——SSE 是推送模型，视图状态完全由事件序列决定，
 * 不存在「数据过期需要重新获取」。为这点功能让 headless 层背一个 React 专属依赖不值得。
 *
 * <p>接口形状对齐 React 的 `useSyncExternalStore`（`subscribe` / `getSnapshot`），
 * 但本身不依赖 React，任何框架都能订阅。
 */
export interface RunStore {
  /** 当前快照。引用相等即状态未变，可直接用于 `useSyncExternalStore` 的重渲染判定。 */
  getSnapshot: () => AgentRunView;
  /** 注册监听，返回取消函数。 */
  subscribe: (listener: RunStoreListener) => () => void;
  /** 写入新状态。传函数时基于当前快照计算，与 `setQueryData` 的 updater 形式一致。 */
  dispatch: (next: AgentRunView | ((prev: AgentRunView) => AgentRunView)) => void;
}

export function createRunStore(conversationId: string): RunStore {
  let snapshot = emptyView(conversationId);
  const listeners = new Set<RunStoreListener>();

  return {
    getSnapshot: () => snapshot,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    dispatch: (next) => {
      const value = typeof next === 'function' ? next(snapshot) : next;
      // 引用未变就不通知：避免 reduceEvent 返回同一对象时触发无意义的重渲染
      if (value === snapshot) {
        return;
      }
      snapshot = value;
      // 先快照再遍历：监听器在回调里取消订阅不应影响本轮通知
      for (const listener of Array.from(listeners)) {
        listener();
      }
    },
  };
}
