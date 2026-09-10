import { List, Result as AdmResult } from 'antd-mobile';
import type { RenderedComponentProps, ResultProps } from '../../registry/types';

export function ResultMobile({ id, props }: RenderedComponentProps<ResultProps>) {
  return (
    <div data-component-id={id}>
      <AdmResult status={props.status} title={props.title} description={props.description} />
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
