import { UiComponentSchema, UiSchemaSchema } from '../schema/uiSchema';
import { z } from 'zod';

/**
 * 契约投影（真源：.harness/contracts/*.schema.json）。字段名、enum 值、约束逐一对应；ui-schema 的投影真源在 @spark-ui/core（本文件组合引用），
 * 其余 8 个契约的前端真源在本文件；其他模块只 import。金额 / ID / Token 一律 string（coding-standard §3）。
 */

// ---- 共用 -------------------------------------------------------------------

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
    clientCapabilities: z
      .object({
        uiSchemaVersion: z.literal('1.0'),
        components: z
          .array(z.string().min(1).max(64))
          .min(1)
          .max(64)
          .refine((a) => new Set(a).size === a.length, { message: 'components: uniqueItems' }),
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
