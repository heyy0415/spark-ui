import { describe, expect, it } from 'vitest';
import confirmExample from '../../../../../.harness/contracts/examples/ui-schema.example.json';
import tableExample from '../../../../../.harness/contracts/examples/ui-schema.order-table.example.json';
import {
  COMPONENT_TYPES,
  InlineActionSchema,
  PROPS_SCHEMAS,
  UiActionSchema,
  UiSchemaSchema,
  parseUiSchema,
} from './uiSchema';

/**
 * ui-schema 契约投影：接受契约示例形态；对白名单、各组件 props、行内指令、令牌长度的越界逐一拒绝。
 * 夹具直接来自 .harness/contracts/examples（真源），越界用例在其上做最小改动。
 */

const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T;

describe('parseUiSchema', () => {
  it('accepts the confirmation example (Card + Card + Form)', () => {
    const ui = parseUiSchema(confirmExample);
    expect(ui.screenId).toBe('refund-confirmation');
    expect(ui.components.map((c) => c.type)).toEqual(['Card', 'Card', 'Form']);
    expect(ui.actions.map((a) => a.type)).toEqual(['submit', 'cancel']);
  });

  it('accepts the table example with inline row actions', () => {
    const ui = parseUiSchema(tableExample);
    const table = ui.components[0];
    expect(table?.type).toBe('Table');
    expect(PROPS_SCHEMAS.Table.parse(table?.props).rows.length).toBeGreaterThan(0);
  });

  it('rejects a component type outside the whitelist', () => {
    const bad = clone(confirmExample) as { components: { type: string }[] };
    const first = bad.components[0];
    if (!first) {
      throw new Error('example has no components');
    }
    first.type = 'OrderCard';
    expect(UiSchemaSchema.safeParse(bad).success).toBe(false);
  });

  it('rejects unknown top-level keys (strict)', () => {
    const bad = { ...clone(confirmExample), scriptUrl: 'https://x' };
    expect(UiSchemaSchema.safeParse(bad).success).toBe(false);
  });

  it('rejects a wrong schemaVersion', () => {
    const bad = { ...clone(confirmExample), schemaVersion: '2.0' };
    expect(UiSchemaSchema.safeParse(bad).success).toBe(false);
  });

  it('rejects a screenId with uppercase or spaces', () => {
    expect(
      UiSchemaSchema.safeParse({ ...clone(confirmExample), screenId: 'Refund X' }).success,
    ).toBe(false);
  });

  it('throws ZodError on invalid input', () => {
    expect(() => parseUiSchema({})).toThrowError();
  });
});

describe('component props', () => {
  it('Form requires at least one field', () => {
    expect(PROPS_SCHEMAS.Form.safeParse({ fields: [] }).success).toBe(false);
    expect(
      PROPS_SCHEMAS.Form.safeParse({ fields: [{ name: 'reason', type: 'text', label: '原因' }] })
        .success,
    ).toBe(true);
  });

  it('Form field name must be an identifier', () => {
    expect(
      PROPS_SCHEMAS.Form.safeParse({ fields: [{ name: '1bad', type: 'text', label: 'x' }] })
        .success,
    ).toBe(false);
  });

  it('Card items are capped at 32', () => {
    const items = Array.from({ length: 33 }, (_, i) => ({ label: `k${i}`, value: 'v' }));
    expect(PROPS_SCHEMAS.Card.safeParse({ items }).success).toBe(false);
    expect(PROPS_SCHEMAS.Card.safeParse({ items: items.slice(0, 32) }).success).toBe(true);
  });

  it('Card actions are capped at 6 and must be inline intents', () => {
    const actions = Array.from({ length: 7 }, (_, i) => ({ label: `a${i}`, intent: `看看 ${i}` }));
    expect(PROPS_SCHEMAS.Card.safeParse({ actions }).success).toBe(false);
    expect(PROPS_SCHEMAS.Card.safeParse({ actions: actions.slice(0, 6) }).success).toBe(true);
  });

  it('Table row cells are capped at 16 keys', () => {
    const cells: Record<string, string> = {};
    for (let i = 0; i < 17; i += 1) {
      cells[`c${i}`] = 'v';
    }
    const columns = [{ key: 'c0', title: 'C0' }];
    expect(PROPS_SCHEMAS.Table.safeParse({ columns, rows: [{ id: '1', cells }] }).success).toBe(
      false,
    );
  });

  it('Table needs at least one column and rows ≤ 50', () => {
    expect(PROPS_SCHEMAS.Table.safeParse({ columns: [], rows: [] }).success).toBe(false);
    const rows = Array.from({ length: 51 }, (_, i) => ({ id: String(i), cells: {} }));
    expect(
      PROPS_SCHEMAS.Table.safeParse({ columns: [{ key: 'a', title: 'A' }], rows }).success,
    ).toBe(false);
  });

  it('Result status is one of four values', () => {
    expect(PROPS_SCHEMAS.Result.safeParse({ status: 'ok', title: 't' }).success).toBe(false);
    expect(PROPS_SCHEMAS.Result.safeParse({ status: 'success', title: 't' }).success).toBe(true);
  });

  it('Timeline time must be ISO-8601 with offset', () => {
    expect(
      PROPS_SCHEMAS.Timeline.safeParse({ items: [{ time: '2026-09-11', label: 'x' }] }).success,
    ).toBe(false);
    expect(
      PROPS_SCHEMAS.Timeline.safeParse({ items: [{ time: '2026-09-11T00:00:00Z', label: 'x' }] })
        .success,
    ).toBe(true);
  });

  it('inline intent rejects URLs and angle brackets', () => {
    expect(InlineActionSchema.safeParse({ label: 'go', intent: 'open https://x' }).success).toBe(
      false,
    );
    expect(InlineActionSchema.safeParse({ label: 'go', intent: '<img>' }).success).toBe(false);
    expect(InlineActionSchema.safeParse({ label: 'go', intent: '查看条目 10001' }).success).toBe(
      true,
    );
  });
});

describe('actions', () => {
  it('confirmationToken must be 16..256 chars and opaque', () => {
    const base = { id: 'confirm', type: 'submit', label: '确认', style: 'danger' } as const;
    expect(UiActionSchema.safeParse({ ...base, confirmationToken: 'ct_short' }).success).toBe(
      false,
    );
    expect(
      UiActionSchema.safeParse({ ...base, confirmationToken: 'ct_' + 'a'.repeat(20) }).success,
    ).toBe(true);
    expect(UiActionSchema.safeParse({ ...base, confirmationToken: 'x'.repeat(257) }).success).toBe(
      false,
    );
  });

  it('action id must be kebab-case', () => {
    expect(
      UiActionSchema.safeParse({ id: 'Confirm Now', type: 'cancel', label: 'x', style: 'default' })
        .success,
    ).toBe(false);
  });
});

describe('registry parity', () => {
  it('PROPS_SCHEMAS keys equal COMPONENT_TYPES and the map is frozen', () => {
    expect(Object.keys(PROPS_SCHEMAS)).toHaveLength(COMPONENT_TYPES.length);
    expect(Object.keys(PROPS_SCHEMAS)).toEqual(expect.arrayContaining([...COMPONENT_TYPES]));
    expect(Object.isFrozen(PROPS_SCHEMAS)).toBe(true);
  });
});
