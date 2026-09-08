import { Empty, Timeline as AntTimeline } from 'antd';
import type { RenderedComponentProps, TimelineProps } from '../../registry/types';

/** 桌面 Timeline：items 按给定顺序渲染；空数组显示 emptyText。 */
export function TimelineDesktop({ id, props }: RenderedComponentProps<TimelineProps>) {
  if (props.items.length === 0) {
    return (
      <div data-component-id={id}>
        <Empty description={props.emptyText ?? '暂无记录'} />
      </div>
    );
  }
  return (
    <div data-component-id={id}>
      <AntTimeline
        items={props.items.map((it, i) => ({
          key: String(i),
          content: (
            <>
              <div>{it.label}</div>
              <div style={{ opacity: 0.65 }}>
                {new Date(it.time).toLocaleString()}
                {it.description ? ` · ${it.description}` : ''}
              </div>
            </>
          ),
        }))}
      />
    </div>
  );
}
