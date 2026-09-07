import { Card as AntCard, Descriptions } from 'antd';
import type { CardProps, RenderedComponentProps } from '../../registry/types';

export function CardDesktop({ id, props }: RenderedComponentProps<CardProps>) {
  return (
    <AntCard title={props.title} data-component-id={id} size="small">
      {props.description ? <p style={{ margin: 0 }}>{props.description}</p> : null}
      {props.items && props.items.length > 0 ? (
        <Descriptions
          size="small"
          column={1}
          items={props.items.map((it, i) => ({
            key: String(i),
            label: it.label,
            children: it.value,
          }))}
        />
      ) : null}
    </AntCard>
  );
}
