import { Button, Card as AdmCard, List, Space } from 'antd-mobile';
import type { CardProps, LabelValue, RenderedComponentProps } from '../../registry/types';

/** tone → antd-mobile 颜色变量；default 不上色。 */
const TONE_COLOR: Record<NonNullable<LabelValue['tone']>, string | undefined> = {
  default: undefined,
  success: 'var(--adm-color-success)',
  warning: 'var(--adm-color-warning)',
  danger: 'var(--adm-color-danger)',
};

/** 移动端 Card：底部 actions 与桌面同语义（intent 原文回调 handlers.onIntent）。 */
export function CardMobile({ id, props, handlers }: RenderedComponentProps<CardProps>) {
  const actions = props.actions ?? [];
  return (
    <AdmCard title={props.title} data-component-id={id}>
      {props.description ? <p style={{ margin: 0 }}>{props.description}</p> : null}
      {props.items && props.items.length > 0 ? (
        <List>
          {props.items.map((it, i) => {
            const color = TONE_COLOR[it.tone ?? 'default'];
            return (
              <List.Item
                key={i}
                extra={color ? <span style={{ color }}>{it.value}</span> : it.value}
              >
                {it.label}
              </List.Item>
            );
          })}
        </List>
      ) : null}
      {actions.length > 0 ? (
        <Space wrap style={{ marginTop: 8 }}>
          {actions.map((a, i) => (
            <Button
              key={i}
              size="small"
              data-intent={a.intent}
              disabled={handlers?.onIntent === undefined}
              onClick={() => handlers?.onIntent?.(a.intent)}
            >
              {a.label}
            </Button>
          ))}
        </Space>
      ) : null}
    </AdmCard>
  );
}
