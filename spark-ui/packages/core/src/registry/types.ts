import type { UiAction } from '../schema/uiSchema';

/**
 * 组件 props 类型全部来自 schema/uiSchema.ts（契约级 Zod），这里只 re-export 并定义渲染签名。
 * 桌面（antd）与移动（antd-mobile）实现必须接受同一份 props 类型（05-styling-spec）。
 */
export { PROPS_SCHEMAS } from '../schema/uiSchema';
export type {
  FormProps,
  CardProps,
  TableProps,
  TableRow,
  ResultProps,
  TimelineProps,
  LabelValue,
  InlineAction,
} from '../schema/uiSchema';

/** Form 组件回传：只有 formData，不发请求（05-styling-spec）。 */
export type FormValues = Record<string, string | number | boolean>;

/** 组件回调：Form 值变化；Table 行内指令 / Card.actions 点击（只回调纯文本，core 不发请求、不解释文本）。 */
export interface ComponentHandlers {
  onChange?: (values: FormValues) => void;
  onIntent?: (intent: string) => void;
  /** 历史回合只读：Form 禁用；行内指令仍可点（自然语言，无状态）。 */
  readOnly?: boolean;
}

/** 每个封装组件的统一签名：props 已校验；handlers 可选。 */
export interface RenderedComponentProps<P> {
  id: string;
  props: P;
  handlers?: ComponentHandlers;
}

/** RunStatus 展示的一步工具进度（来自 SSE tool.* 事件的投影；宿主负责归约）。 */
export interface ToolStep {
  displayName: string;
  status: 'selected' | 'running' | 'succeeded' | 'failed';
}

/** 一次 Run 的可见状态。 */
export type RunStatusKind = 'streaming' | 'waiting_confirmation' | 'completed' | 'failed';

/** RunStatus：SSE 阶段的一行状态条（Spin + 当前步骤文案 / 完成 / 失败），进度明细 <ol aria-label="工具进度">。 */
export interface RunStatusProps {
  status: RunStatusKind;
  tools: ToolStep[];
  /** failed 时的用户文案；其它状态忽略。 */
  text?: string;
}

/** SchemaSkeleton：屏到达前的骨架；variant 只影响形状。 */
export interface SchemaSkeletonProps {
  variant: 'table' | 'card' | 'form' | 'generic';
}

/** actions[] 渲染实现的统一 props（renderer 与 desktop / mobile 实现共用，避免类型环）。 */
export interface ActionBarProps {
  actions: UiAction[];
  disabled?: boolean;
  onAction: (action: UiAction) => void;
}
