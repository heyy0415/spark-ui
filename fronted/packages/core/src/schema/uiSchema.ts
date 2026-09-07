import { z } from 'zod';

/**
 * ui-schema.schema.json 的 Zod 投影（真源：.harness/contracts/ui-schema.schema.json）。
 * 本文件是前端 ui-schema 投影的唯一真源，chat 应用的 run-summary / sse-events 投影从 @strato-ui/core 组合它。
 * 字段名、enum 值、约束与契约逐一对应；ID / Token 一律 string。
 */

const idPattern = /^[a-z0-9-]+$/;

/** 白名单组件（与契约 componentType enum 一致；check-registry 校验此列表 == 契约 enum == 注册表键集合 == PROPS_SCHEMAS 键）。 */
export const COMPONENT_TYPES = [
  'Form',
  'Card',
  'Table',
  'ResultCard',
  'ConfirmationCard',
  'OrderCard',
  'RefundConfirmCard',
] as const;
export const ComponentTypeSchema = z.enum(COMPONENT_TYPES);
export type ComponentType = z.infer<typeof ComponentTypeSchema>;

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

export const FormPropsSchema = z.object({ fields: z.array(FormFieldSchema).min(1).max(16) });

const ComponentBaseSchema = z
  .object({
    id: z.string().min(1).max(64).regex(idPattern),
    type: ComponentTypeSchema,
    props: z.record(z.string(), z.unknown()),
  })
  .strict();

/** Form 组件的 props 受契约 if/then 约束；其他组件 props 为自由 JSON，由各封装组件渲染前自行 parse。 */
export const UiComponentSchema = ComponentBaseSchema.superRefine((c, ctx) => {
  if (c.type === 'Form') {
    const r = FormPropsSchema.safeParse(c.props);
    if (!r.success) {
      ctx.addIssue({ code: 'custom', message: 'Form.props.fields invalid', path: ['props'] });
    }
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
