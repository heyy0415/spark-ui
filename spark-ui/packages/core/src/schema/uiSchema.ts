import { z } from 'zod';

/**
 * ui-schema.schema.json 的 Zod 投影（真源：.harness/contracts/ui-schema.schema.json）。
 * 本文件是前端 ui-schema 投影的唯一真源，chat 应用的 run-summary / sse-events 投影从 @spark-ui/core 组合它。
 * 字段名、enum 值、约束与契约逐一对应；ID / Token 一律 string；五个组件的 props 都是契约级约束（.strict()）。
 */

const idPattern = /^[a-z0-9-]+$/;

/** 白名单组件：每个都是 antd / antd-mobile 官方组件的直接映射（check-registry 校验此列表 == 契约 enum == 注册表键 == PROPS_SCHEMAS 键）。 */
export const COMPONENT_TYPES = ['Form', 'Card', 'Table', 'Result', 'Timeline'] as const;
export const ComponentTypeSchema = z.enum(COMPONENT_TYPES);
export type ComponentType = z.infer<typeof ComponentTypeSchema>;

export const MoneySchema = z.string().regex(/^-?\d+(\.\d{1,2})?$/);

// ---- Form
export const FormFieldSchema = z
  .object({
    name: z
      .string()
      .min(1)
      .max(64)
      .regex(/^[a-zA-Z][a-zA-Z0-9_]*$/),
    type: z.enum(['text', 'select', 'number']),
    label: z.string().min(1).max(80),
    required: z.boolean().optional(),
    options: z
      .array(
        z.object({ label: z.string().min(1).max(80), value: z.string().min(1).max(64) }).strict(),
      )
      .min(1)
      .max(64)
      .optional(),
  })
  .strict();
export type FormField = z.infer<typeof FormFieldSchema>;
export const FormPropsSchema = z
  .object({ fields: z.array(FormFieldSchema).min(1).max(16) })
  .strict();
export type FormProps = z.infer<typeof FormPropsSchema>;

// ---- 共用：label/value 项（Card.items / Result.details）
export const LabelValueSchema = z
  .object({
    label: z.string().min(1).max(80),
    value: z.string().max(200),
    tone: z.enum(['default', 'success', 'warning', 'danger']).optional(),
  })
  .strict();
export type LabelValue = z.infer<typeof LabelValueSchema>;

// ---- 共用：行内自然语言指令（前端点击后原样作为新消息发送；不是 URL、不带 token）
export const InlineActionSchema = z
  .object({
    label: z.string().min(1).max(32),
    intent: z
      .string()
      .min(1)
      .max(200)
      .regex(/^(?!.*(:\/\/|<)).*$/),
  })
  .strict();
export type InlineAction = z.infer<typeof InlineActionSchema>;

// ---- Card
export const CardPropsSchema = z
  .object({
    title: z.string().max(80).optional(),
    description: z.string().max(500).optional(),
    items: z.array(LabelValueSchema).max(32).optional(),
  })
  .strict();
export type CardProps = z.infer<typeof CardPropsSchema>;

// ---- Table
export const TableRowSchema = z
  .object({
    id: z.string().min(1).max(64),
    cells: z
      .record(z.string(), z.string().max(200))
      .refine((c) => Object.keys(c).length <= 16, { message: 'cells: at most 16 keys' }),
    actions: z.array(InlineActionSchema).max(6).optional(),
  })
  .strict();
export type TableRow = z.infer<typeof TableRowSchema>;
export const TablePropsSchema = z
  .object({
    columns: z
      .array(
        z
          .object({
            key: z
              .string()
              .min(1)
              .max(64)
              .regex(/^[a-zA-Z][a-zA-Z0-9_]*$/),
            title: z.string().min(1).max(80),
          })
          .strict(),
      )
      .min(1)
      .max(16),
    rows: z.array(TableRowSchema).max(50),
    total: z.number().int().min(0).optional(),
    emptyText: z.string().max(120).optional(),
  })
  .strict();
export type TableProps = z.infer<typeof TablePropsSchema>;

// ---- Result
export const ResultPropsSchema = z
  .object({
    status: z.enum(['success', 'error', 'info', 'warning']),
    title: z.string().min(1).max(80),
    description: z.string().max(500).optional(),
    details: z.array(LabelValueSchema).max(32).optional(),
  })
  .strict();
export type ResultProps = z.infer<typeof ResultPropsSchema>;

// ---- Timeline
export const TimelinePropsSchema = z
  .object({
    items: z
      .array(
        z
          .object({
            time: z.string().datetime({ offset: true }),
            label: z.string().min(1).max(80),
            description: z.string().max(200).optional(),
          })
          .strict(),
      )
      .max(32),
    emptyText: z.string().max(120).optional(),
  })
  .strict();
export type TimelineProps = z.infer<typeof TimelinePropsSchema>;

/** 组件 → props schema 的映射；SchemaRenderer 渲染前校验，UiComponentSchema 整体校验也用它。 */
// Object.freeze：宿主运行期不得改写 props 约定（与注册表同级保证）
export const PROPS_SCHEMAS = Object.freeze({
  Form: FormPropsSchema,
  Card: CardPropsSchema,
  Table: TablePropsSchema,
  Result: ResultPropsSchema,
  Timeline: TimelinePropsSchema,
} as const);

const ComponentBaseSchema = z
  .object({
    id: z.string().min(1).max(64).regex(idPattern),
    type: ComponentTypeSchema,
    props: z.record(z.string(), z.unknown()),
  })
  .strict();

/** 五个组件的 props 都受契约 if/then 约束：整体校验时逐个按 type 校验 props。 */
export const UiComponentSchema = ComponentBaseSchema.superRefine((c, ctx) => {
  const r = PROPS_SCHEMAS[c.type].safeParse(c.props);
  if (!r.success) {
    ctx.addIssue({ code: 'custom', message: `${c.type}.props invalid`, path: ['props'] });
  }
});
export type UiComponent = z.infer<typeof UiComponentSchema>;

export const UiActionSchema = z
  .object({
    id: z.string().min(1).max(64).regex(idPattern),
    type: z.enum(['submit', 'cancel']),
    label: z.string().min(1).max(32),
    style: z.enum(['default', 'primary', 'danger']),
    confirmationToken: z.string().min(16).max(256).optional(),
  })
  .strict();
export type UiAction = z.infer<typeof UiActionSchema>;

export const UiSchemaSchema = z
  .object({
    schemaVersion: z.literal('1.0'),
    screenId: z.string().min(1).max(64).regex(idPattern),
    title: z.string().min(1).max(80),
    components: z.array(UiComponentSchema).max(32),
    actions: z.array(UiActionSchema).max(8),
  })
  .strict();
export type UiSchema = z.infer<typeof UiSchemaSchema>;

/**
 * 接入方应对后端载荷调用本函数而不是 `payload as UiSchema`：整体校验（screenId / title / actions 形态 / token 长度）
 * 是"留在宿主"的那一半安全边界，渲染器内部只做组件级 props 校验。校验失败抛 ZodError。
 */
export function parseUiSchema(input: unknown): UiSchema {
  return UiSchemaSchema.parse(input);
}
