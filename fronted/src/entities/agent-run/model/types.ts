import { z } from 'zod';

/**
 * 契约投影（真源：.harness/contracts/*.schema.json）。字段名、enum 值、约束逐一对应；本文件是前端唯一真源，
 * 其他模块只 import。金额 / ID / Token 一律 string（coding-standard §3）。
 */

// ---- 共用 -------------------------------------------------------------------

const idPattern = /^[a-z0-9-]+$/;
export const RunIdSchema = z.string().regex(/^run_[A-Za-z0-9_-]{1,60}$/);
export const ToolCallIdSchema = z.string().regex(/^tc_[A-Za-z0-9_-]{1,60}$/);
export const ToolIdSchema = z.string().regex(/^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*){1,3}$/);
export const SemverSchema = z.string().regex(/^\d+\.\d+\.\d+$/);
export const IsoDateTimeSchema = z.string().datetime({ offset: true });

export const RunStateSchema = z.enum([
  'CREATED',
  'PLANNING',
  'EXECUTING',
  'WAITING_CONFIRMATION',
  'COMPLETED',
  'FAILED',
]);
export type RunState = z.infer<typeof RunStateSchema>;

export const RunFailureCodeSchema = z.enum([
  'CONFIRMATION_REJECTED',
  'TOOL_SELECTION_INVALID',
  'TOOL_OUTPUT_INVALID',
  'TOOL_EXECUTION_FAILED',
  'INTERNAL_ERROR',
]);
export type RunFailureCode = z.infer<typeof RunFailureCodeSchema>;

// ---- error-response --------------------------------------------------------

export const ErrorResponseSchema = z
  .object({
    code: z.enum([
      'REQUEST_INVALID',
      'UNAUTHENTICATED',
      'FORBIDDEN',
      'NOT_FOUND',
      'TOOL_VERSION_CONFLICT',
      'INTERNAL_ERROR',
    ]),
    message: z.string().min(1).max(512),
    traceId: z.string().min(1).max(128),
    details: z
      .array(z.object({ path: z.string().max(256), message: z.string().max(256) }).strict())
      .max(64)
      .optional(),
  })
  .strict();
export type ErrorResponse = z.infer<typeof ErrorResponseSchema>;

// ---- intent-request ---------------------------------------------------------

export const IntentRequestSchema = z
  .object({
    conversationId: z.string().min(1).max(64),
    message: z.string().min(1).max(2000),
    pageContext: z
      .object({
        page: z.string().min(1).max(64).regex(idPattern),
        selectedEntity: z
          .object({
            type: z
              .string()
              .min(1)
              .max(32)
              .regex(/^[a-z]+$/),
            id: z.string().min(1).max(64),
          })
          .strict()
          .optional(),
      })
      .strict()
      .optional(),
    clientCapabilities: z
      .object({
        uiSchemaVersion: z.literal('1.0'),
        components: z.array(z.string().min(1).max(64)).min(1).max(64),
      })
      .strict(),
  })
  .strict();
export type IntentRequest = z.infer<typeof IntentRequestSchema>;

// ---- action-request ---------------------------------------------------------

export const FormDataSchema = z
  .record(
    z.string().regex(/^[a-zA-Z][a-zA-Z0-9_]*$/),
    z.union([z.string().max(512), z.number(), z.boolean()]),
  )
  .refine((o) => Object.keys(o).length <= 16, { message: 'formData has more than 16 keys' });

export const ActionRequestSchema = z
  .object({
    confirmationToken: z.string().min(16).max(256),
    formData: FormDataSchema,
  })
  .strict();
export type ActionRequest = z.infer<typeof ActionRequestSchema>;

// ---- ui-schema ----------------------------------------------------------------

/** 白名单组件（与 ui-schema.schema.json componentType enum 一致；check-registry 校验注册表键集合等于此列表）。 */
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

/** Form 组件的 props 受契约 if/then 约束；其他组件 props 为自由 JSON，由各封装组件自行 parse。 */
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

// ---- run-summary --------------------------------------------------------------

export const RunSummarySchema = z
  .object({
    runId: RunIdSchema,
    conversationId: z.string().min(1).max(64),
    state: RunStateSchema,
    currentUi: UiSchemaSchema.optional(),
    failureCode: RunFailureCodeSchema.optional(),
    createdAt: IsoDateTimeSchema,
    updatedAt: IsoDateTimeSchema,
  })
  .strict()
  .superRefine((r, ctx) => {
    if (r.state === 'FAILED' && r.failureCode === undefined) {
      ctx.addIssue({
        code: 'custom',
        message: 'FAILED requires failureCode',
        path: ['failureCode'],
      });
    }
    if (r.state === 'WAITING_CONFIRMATION' && r.currentUi === undefined) {
      ctx.addIssue({
        code: 'custom',
        message: 'WAITING_CONFIRMATION requires currentUi',
        path: ['currentUi'],
      });
    }
  });
export type RunSummary = z.infer<typeof RunSummarySchema>;

// ---- sse-events ---------------------------------------------------------------

const frame = <E extends string, D extends z.ZodTypeAny>(event: E, data: D) =>
  z.object({ event: z.literal(event), data }).strict();

export const SseEventSchema = z.discriminatedUnion('event', [
  frame(
    'run.started',
    z
      .object({
        runId: RunIdSchema,
        conversationId: z.string().min(1).max(64),
        at: IsoDateTimeSchema,
      })
      .strict(),
  ),
  frame('message.delta', z.object({ runId: RunIdSchema, text: z.string().max(4000) }).strict()),
  frame(
    'tool.selected',
    z
      .object({
        runId: RunIdSchema,
        toolCallId: ToolCallIdSchema,
        toolId: ToolIdSchema,
        version: SemverSchema,
        displayName: z.string().min(1).max(80),
      })
      .strict(),
  ),
  frame(
    'tool.started',
    z.object({ runId: RunIdSchema, toolCallId: ToolCallIdSchema, at: IsoDateTimeSchema }).strict(),
  ),
  frame(
    'tool.completed',
    z
      .object({
        runId: RunIdSchema,
        toolCallId: ToolCallIdSchema,
        status: z.enum(['succeeded', 'failed']),
        durationMs: z.number().int().min(0),
        summary: z.string().max(200).optional(),
      })
      .strict(),
  ),
  frame('ui.replace', z.object({ runId: RunIdSchema, ui: UiSchemaSchema }).strict()),
  frame(
    'ui.patch',
    z
      .object({
        runId: RunIdSchema,
        screenId: z.string().min(1).max(64),
        components: z.array(UiComponentSchema).min(1).max(32),
      })
      .strict(),
  ),
  frame(
    'confirmation.required',
    z
      .object({
        runId: RunIdSchema,
        actionId: z.string().min(1).max(64),
        expiresAt: IsoDateTimeSchema,
      })
      .strict(),
  ),
  frame('run.completed', z.object({ runId: RunIdSchema, at: IsoDateTimeSchema }).strict()),
  frame(
    'run.failed',
    z
      .object({
        runId: RunIdSchema,
        code: RunFailureCodeSchema,
        message: z.string().min(1).max(200),
        at: IsoDateTimeSchema,
      })
      .strict(),
  ),
]);
export type SseEvent = z.infer<typeof SseEventSchema>;
export type SseEventName = SseEvent['event'];
