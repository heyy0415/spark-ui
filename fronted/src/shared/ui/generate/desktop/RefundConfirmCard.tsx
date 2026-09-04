import { Card as AntCard, Descriptions, Tag } from 'antd';
import type { RefundConfirmCardProps, RenderedComponentProps } from '../types';

export function RefundConfirmCardDesktop({
  id,
  props,
}: RenderedComponentProps<RefundConfirmCardProps>) {
  const items = [
    { key: 'order', label: '订单号', children: props.orderId },
    { key: 'amount', label: '退款金额', children: `${props.amount} ${props.currency}` },
  ];
  if (props.estimatedDays !== undefined) {
    items.push({ key: 'eta', label: '预计到账', children: `${props.estimatedDays} 个工作日` });
  }
  if (props.reason) {
    items.push({ key: 'reason', label: '说明', children: props.reason });
  }
  return (
    <AntCard
      size="small"
      data-component-id={id}
      title="退款摘要"
      extra={props.eligible ? <Tag color="green">可退款</Tag> : <Tag color="red">不可退款</Tag>}
    >
      <Descriptions size="small" column={1} items={items} />
    </AntCard>
  );
}
