import { Form as AdmForm, Input, Selector, Stepper } from 'antd-mobile';
import { useCallback } from 'react';
import type { FormComponentProps, FormValues, RenderedComponentProps } from '../types';

/** 移动端 Form：字段来自契约；select 用 Selector 单选；只回传 formData。 */
export function FormMobile({ id, props, handlers }: RenderedComponentProps<FormComponentProps>) {
  const [form] = AdmForm.useForm<FormValues>();
  const onValuesChange = useCallback(
    (_changed: Partial<FormValues>, all: FormValues) => {
      // Selector 单选返回数组；归一为标量再交给 feature 层
      const flat: FormValues = {};
      for (const [k, v] of Object.entries(all)) {
        flat[k] = Array.isArray(v) ? (v[0] as string) : (v as string | number | boolean);
      }
      handlers?.onChange?.(flat);
    },
    [handlers],
  );
  return (
    <AdmForm form={form} layout="vertical" data-component-id={id} onValuesChange={onValuesChange}>
      {props.fields.map((f) => (
        <AdmForm.Item
          key={f.name}
          name={f.name}
          label={f.label}
          {...(f.required ? { rules: [{ required: true, message: `请填写${f.label}` }] } : {})}
        >
          {f.type === 'select' ? (
            <Selector
              options={(f.options ?? []).map((o) => ({ label: o.label, value: o.value }))}
            />
          ) : f.type === 'number' ? (
            <Stepper />
          ) : (
            <Input maxLength={512} />
          )}
        </AdmForm.Item>
      ))}
    </AdmForm>
  );
}
