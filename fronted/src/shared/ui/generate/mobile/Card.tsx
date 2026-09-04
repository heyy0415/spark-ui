import { Card as AdmCard, List } from 'antd-mobile';
import type { CardProps, RenderedComponentProps } from '../types';

export function CardMobile({ id, props }: RenderedComponentProps<CardProps>) {
  return (
    <AdmCard title={props.title} data-component-id={id}>
      {props.description ? <p style={{ margin: 0 }}>{props.description}</p> : null}
      {props.items && props.items.length > 0 ? (
        <List>
          {props.items.map((it, i) => (
            <List.Item key={i} extra={it.value}>
              {it.label}
            </List.Item>
          ))}
        </List>
      ) : null}
    </AdmCard>
  );
}
