import { Card as AdmCard, List } from 'antd-mobile';
import type { CardProps, LabelValue, RenderedComponentProps } from '../../registry/types';

/** tone → antd-mobile 颜色变量；default 不上色。 */
const TONE_COLOR: Record<NonNullable<LabelValue['tone']>, string | undefined> = {
  default: undefined,
  success: 'var(--adm-color-success)',
  warning: 'var(--adm-color-warning)',
  danger: 'var(--adm-color-danger)',
};

export function CardMobile({ id, props }: RenderedComponentProps<CardProps>) {
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
    </AdmCard>
  );
}
