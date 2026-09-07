import { List, Result } from 'antd-mobile';
import type { RenderedComponentProps, ResultCardProps } from '../../registry/types';

export function ResultCardMobile({ id, props }: RenderedComponentProps<ResultCardProps>) {
  return (
    <div data-component-id={id}>
      <Result status={props.status} title={props.title} description={props.description} />
      {props.details && props.details.length > 0 ? (
        <List>
          {props.details.map((d, i) => (
            <List.Item key={i} extra={d.value}>
              {d.label}
            </List.Item>
          ))}
        </List>
      ) : null}
    </div>
  );
}
