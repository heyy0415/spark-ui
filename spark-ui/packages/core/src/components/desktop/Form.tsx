import { Form as AntForm, Input, InputNumber, Select } from 'antd';
import { useCallback } from 'react';
import type { FormProps, FormValues, RenderedComponentProps } from '../../registry/types';

/**
 * Form 封装：字段定义来自契约 props.fields[]；只回传 formData，不自行发请求（05-styling-spec）。
 * 每次值变化把当前全部值交给 handlers.onChange，由 feature 层收集。
 */
export function FormDesktop({ id, props, handlers }: RenderedComponentProps<FormProps>) {
  const [form] = AntForm.useForm<FormValues>();
  const onValuesChange = useCallback(
    (_changed: Partial<FormValues>, all: FormValues) => {
      handlers?.onChange?.(all);
    },
    [handlers],
  );
  return (
    <AntForm form={form} layout="vertical" data-component-id={id} onValuesChange={onValuesChange}>
      {props.fields.map((f) => (
        <AntForm.Item
          key={f.name}
          name={f.name}
          label={f.label}
          {...(f.required ? { rules: [{ required: true, message: `请填写${f.label}` }] } : {})}
        >
          {f.type === 'select' ? (
            <Select
              placeholder={`请选择${f.label}`}
              options={(f.options ?? []).map((o) => ({ label: o.label, value: o.value }))}
            />
          ) : f.type === 'number' ? (
            <InputNumber style={{ width: '100%' }} />
          ) : (
            <Input maxLength={512} />
          )}
        </AntForm.Item>
      ))}
    </AntForm>
  );
}
