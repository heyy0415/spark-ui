import { Descriptions, Result as AntResult } from 'antd';
import type { RenderedComponentProps, ResultProps } from '../../registry/types';

export function ResultDesktop({ id, props }: RenderedComponentProps<ResultProps>) {
  return (
    <div data-component-id={id}>
      <AntResult status={props.status} title={props.title} subTitle={props.description}>
        {props.details && props.details.length > 0 ? (
          <Descriptions
            size="small"
            column={1}
            items={props.details.map((d, i) => ({
              key: String(i),
              label: d.label,
              children: d.value,
            }))}
          />
        ) : null}
      </AntResult>
    </div>
  );
}
