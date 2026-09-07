import { Descriptions, Result } from 'antd';
import type { RenderedComponentProps, ResultCardProps } from '../../registry/types';

export function ResultCardDesktop({ id, props }: RenderedComponentProps<ResultCardProps>) {
  return (
    <div data-component-id={id}>
      <Result status={props.status} title={props.title} subTitle={props.description}>
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
      </Result>
    </div>
  );
}
