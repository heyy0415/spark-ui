import { Empty, Steps } from 'antd-mobile';
import type { RenderedComponentProps, TimelineProps } from '../../registry/types';

/** 移动端用 Steps 竖向呈现时间线；空数组显示 emptyText。 */
export function TimelineMobile({ id, props }: RenderedComponentProps<TimelineProps>) {
  if (props.items.length === 0) {
    return (
      <div data-component-id={id}>
        <Empty description={props.emptyText ?? '暂无记录'} />
      </div>
    );
  }
  return (
    <div data-component-id={id}>
      <Steps direction="vertical" current={props.items.length - 1}>
        {props.items.map((it, i) => (
          <Steps.Step
            key={i}
            title={it.label}
            description={`${new Date(it.time).toLocaleString()}${it.description ? ` · ${it.description}` : ''}`}
          />
        ))}
      </Steps>
    </div>
  );
}
