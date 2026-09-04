import { Card as AntCard, Descriptions, Tag } from 'antd';
import type { OrderCardProps, RenderedComponentProps } from '../types';

export function OrderCardDesktop({ id, props }: RenderedComponentProps<OrderCardProps>) {
  return (
    <AntCard
      size="small"
      data-component-id={id}
      title={`订单 ${props.orderId}`}
      extra={<Tag color={props.status === 'PAID' ? 'blue' : 'default'}>{props.status}</Tag>}
    >
      <Descriptions
        size="small"
        column={1}
        items={[
          { key: 'product', label: '商品', children: props.productName },
          { key: 'amount', label: '金额', children: `${props.amount} ${props.currency}` },
        ]}
      />
    </AntCard>
  );
}
