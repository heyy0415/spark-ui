import { describe, expect, it } from 'vitest';
import type { UiSchema } from '../schema/uiSchema';
import { FormIncompleteError, missingRequiredFields } from './useSparkRun';

/** 提交前本地必填校验：决定是否发请求、是否消耗一次性令牌，所以每个分支都要精确。 */

const form = (fields: UiSchema['components'][number]['props']): UiSchema => ({
  schemaVersion: '1.0',
  screenId: 'confirm',
  title: '请确认',
  components: [{ id: 'form', type: 'Form', props: fields }],
  actions: [],
});

describe('missingRequiredFields', () => {
  it('returns [] when there is no screen', () => {
    expect(missingRequiredFields(null, {})).toEqual([]);
  });

  it('ignores non-Form components', () => {
    const ui: UiSchema = {
      schemaVersion: '1.0',
      screenId: 's',
      title: 't',
      components: [{ id: 'c', type: 'Card', props: { title: 'x' } }],
      actions: [],
    };
    expect(missingRequiredFields(ui, {})).toEqual([]);
  });

  it('reports required fields that are undefined or empty by label', () => {
    const ui = form({
      fields: [
        { name: 'reason', type: 'select', label: '原因', required: true },
        { name: 'note', type: 'text', label: '备注', required: true },
        { name: 'extra', type: 'text', label: '补充' },
      ],
    });
    expect(missingRequiredFields(ui, {})).toEqual(['原因', '备注']);
    expect(missingRequiredFields(ui, { reason: 'DAMAGED', note: '' })).toEqual(['备注']);
  });

  it('accepts filled values including 0 and false', () => {
    const ui = form({
      fields: [
        { name: 'qty', type: 'number', label: '数量', required: true },
        { name: 'flag', type: 'text', label: '标记', required: true },
      ],
    });
    expect(missingRequiredFields(ui, { qty: 0, flag: false })).toEqual([]);
  });

  it('skips a Form whose props do not match the contract', () => {
    const ui = form({ fields: [] });
    expect(missingRequiredFields(ui, {})).toEqual([]);
  });

  it('collects across multiple Form components in order', () => {
    const ui: UiSchema = {
      schemaVersion: '1.0',
      screenId: 's',
      title: 't',
      components: [
        {
          id: 'f1',
          type: 'Form',
          props: { fields: [{ name: 'a', type: 'text', label: 'A', required: true }] },
        },
        {
          id: 'f2',
          type: 'Form',
          props: { fields: [{ name: 'b', type: 'text', label: 'B', required: true }] },
        },
      ],
      actions: [],
    };
    expect(missingRequiredFields(ui, { a: 'x' })).toEqual(['B']);
  });
});

describe('FormIncompleteError', () => {
  it('exposes the missing labels and a stable name', () => {
    const e = new FormIncompleteError(['原因', '备注']);
    expect(e.name).toBe('FormIncompleteError');
    expect(e.missing).toEqual(['原因', '备注']);
    expect(e.message).toContain('原因, 备注');
    expect(e).toBeInstanceOf(Error);
  });
});
