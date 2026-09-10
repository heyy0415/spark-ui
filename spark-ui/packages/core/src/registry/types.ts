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

/** 组件回调：Form 值变化；Table 行内指令点击（只回调纯文本，core 不发请求、不解释文本）。 */
export interface ComponentHandlers {
  onChange?: (values: FormValues) => void;
  onIntent?: (intent: string) => void;
}

/** 每个封装组件的统一签名：props 已校验；handlers 可选。 */
export interface RenderedComponentProps<P> {
  id: string;
  props: P;
  handlers?: ComponentHandlers;
}

/** actions[] 渲染实现的统一 props（renderer 与 desktop / mobile 实现共用，避免类型环）。 */
export interface ActionBarProps {
  actions: UiAction[];
  disabled?: boolean;
  onAction: (action: UiAction) => void;
}
