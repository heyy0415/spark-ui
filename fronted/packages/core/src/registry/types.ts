import { z } from 'zod';
import type { UiAction } from '../schema/uiSchema';
import { FormPropsSchema } from '../schema/uiSchema';

/**
 * 各白名单组件 props 的前端约束。契约中 props 为自由 JSON（Form 除外），这里是前端内部约束；
 * 各封装组件渲染时 parse 自己的 props，失败则渲染错误占位而不抛出。
 * 桌面（antd）与移动（antd-mobile）实现必须接受同一份 props 类型（05-styling-spec）。
 */

const MoneySchema = z.string().regex(/^-?\d+(\.\d{1,2})?$/);

/** Form 的 props 约束是契约级（ui-schema if/then），真源在 schema/uiSchema.ts，这里只复用不重定义。 */
export type FormComponentProps = z.infer<typeof FormPropsSchema>;

export const CardPropsSchema = z.object({
  title: z.string().max(80).optional(),
  description: z.string().max(500).optional(),
  items: z
    .array(z.object({ label: z.string().max(80), value: z.string().max(200) }))
    .max(32)
    .optional(),
});
export type CardProps = z.infer<typeof CardPropsSchema>;

export const TablePropsSchema = z.object({
  columns: z
    .array(z.object({ key: z.string().min(1).max(64), title: z.string().min(1).max(80) }))
    .min(1)
    .max(16),
  rows: z.array(z.record(z.string(), z.union([z.string(), z.number(), z.boolean()]))).max(200),
});
export type TableProps = z.infer<typeof TablePropsSchema>;

export const ResultCardPropsSchema = z.object({
  status: z.enum(['success', 'error', 'info', 'warning']),
  title: z.string().min(1).max(80),
  description: z.string().max(500).optional(),
  details: z
    .array(z.object({ label: z.string().max(80), value: z.string().max(200) }))
    .max(32)
    .optional(),
});
export type ResultCardProps = z.infer<typeof ResultCardPropsSchema>;

export const ConfirmationCardPropsSchema = z.object({
  title: z.string().min(1).max(80),
  message: z.string().min(1).max(500),
  items: z
    .array(z.object({ label: z.string().max(80), value: z.string().max(200) }))
    .max(32)
    .optional(),
});
export type ConfirmationCardProps = z.infer<typeof ConfirmationCardPropsSchema>;

export const OrderCardPropsSchema = z.object({
  orderId: z.string().min(1).max(64),
  productName: z.string().min(1).max(200),
  amount: MoneySchema,
  currency: z.string().length(3),
  status: z.string().min(1).max(32),
});
export type OrderCardProps = z.infer<typeof OrderCardPropsSchema>;

export const RefundConfirmCardPropsSchema = z.object({
  orderId: z.string().min(1).max(64),
  amount: MoneySchema,
  currency: z.string().length(3),
  eligible: z.boolean(),
  estimatedDays: z.number().int().min(0).optional(),
  reason: z.string().max(200).optional(),
});
export type RefundConfirmCardProps = z.infer<typeof RefundConfirmCardPropsSchema>;

/** 组件 → props schema 的映射；SchemaRenderer 用它在渲染前校验。 */
// Object.freeze：宿主运行期不得改写 props 约定（与注册表同级保证）
export const PROPS_SCHEMAS = Object.freeze({
  Form: FormPropsSchema,
  Card: CardPropsSchema,
  Table: TablePropsSchema,
  ResultCard: ResultCardPropsSchema,
  ConfirmationCard: ConfirmationCardPropsSchema,
  OrderCard: OrderCardPropsSchema,
  RefundConfirmCard: RefundConfirmCardPropsSchema,
} as const);

export type ComponentTypeName = keyof typeof PROPS_SCHEMAS;

/** Form 组件回传：只有 formData，不发请求（05-styling-spec）。 */
export type FormValues = Record<string, string | number | boolean>;

export interface FormComponentHandlers {
  onChange?: (values: FormValues) => void;
}

/** 每个封装组件的统一签名：props 已校验；Form 额外接收 handlers。 */
export interface RenderedComponentProps<P> {
  id: string;
  props: P;
  handlers?: FormComponentHandlers;
}

/** actions[] 渲染实现的统一 props（renderer 与 desktop / mobile 实现共用，避免类型环）。 */
export interface ActionBarProps {
  actions: UiAction[];
  disabled?: boolean;
  onAction: (action: UiAction) => void;
}
