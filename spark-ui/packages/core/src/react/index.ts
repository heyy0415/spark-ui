/**
 * `@spark-ui/core/react` —— headless 层的 React 绑定。
 *
 * 只有一层薄封装：把 `client/runStore` 接到 `useSyncExternalStore`。
 * 允许 import react（它就是 React 绑定），但不得 import antd——UI 库只在 `components/**` 与 `theme/**`。
 */
export { FormIncompleteError, missingRequiredFields, useSparkRun } from './useSparkRun';
export type { RunAction, UseSparkRunOptions } from './useSparkRun';
