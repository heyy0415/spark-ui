/**
 * `@spark-ui/core/client` —— 零框架依赖的 headless 层。
 *
 * 这里放「怎么跟 spark 后端说话」：契约投影、SSE 分帧、事件归约、动作提交。
 * 不 import react / antd，任何 TS 工程都能用（`check-deps.mjs` 机械守护）。
 *
 * React 宿主通常不直接用本入口，而是用 `@spark-ui/core/react` 的 `useSparkRun`。
 */
export * from './contracts';
export { HttpError, request } from './http';
export { consumeSse, parseFrame } from './sse';
export type { SseFrame, SseFrameHandler, SseRequest } from './sse';
export { AGENT_RUNS_PATH, actionPath, buildActionRequest, buildIntentRequest, getRun } from './api';
export type { Transport } from './api';
export * from './runView';
export { createRunStore } from './runStore';
export type { RunStore, RunStoreListener } from './runStore';
