import { Card as AdmCard, List, Tag } from 'antd-mobile';
import type { OrderCardProps, RenderedComponentProps } from '../../registry/types';

export function OrderCardMobile({ id, props }: RenderedComponentProps<OrderCardProps>) {
  return (
    <AdmCard
      data-component-id={id}
      title={`订单 ${props.orderId}`}
      extra={<Tag color={props.status === 'PAID' ? 'primary' : 'default'}>{props.status}</Tag>}
    >
      <List>
        <List.Item extra={props.productName}>商品</List.Item>
        <List.Item extra={`${props.amount} ${props.currency}`}>金额</List.Item>
      </List>
    </AdmCard>
  );
}
