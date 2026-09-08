import { Card as AntCard, Descriptions, Typography } from 'antd';
import type { CardProps, LabelValue, RenderedComponentProps } from '../../registry/types';

/** tone → antd Typography.Text type；default 不上色。 */
function toneType(tone: LabelValue['tone']): 'success' | 'warning' | 'danger' | undefined {
  return tone === undefined || tone === 'default' ? undefined : tone;
}

export function CardDesktop({ id, props }: RenderedComponentProps<CardProps>) {
  return (
    <AntCard title={props.title} data-component-id={id} size="small">
      {props.description ? <p style={{ margin: 0 }}>{props.description}</p> : null}
      {props.items && props.items.length > 0 ? (
        <Descriptions
          size="small"
          column={1}
          items={props.items.map((it, i) => {
            const t = toneType(it.tone);
            return {
              key: String(i),
              label: it.label,
              children: t ? <Typography.Text type={t}>{it.value}</Typography.Text> : it.value,
            };
          })}
        />
      ) : null}
    </AntCard>
  );
}
