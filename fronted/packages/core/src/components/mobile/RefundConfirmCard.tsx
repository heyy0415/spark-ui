import { Card as AdmCard, List, Tag } from 'antd-mobile';
import type { RefundConfirmCardProps, RenderedComponentProps } from '../../registry/types';

export function RefundConfirmCardMobile({
  id,
  props,
}: RenderedComponentProps<RefundConfirmCardProps>) {
  return (
    <AdmCard
      data-component-id={id}
      title="退款摘要"
      extra={
        props.eligible ? <Tag color="success">可退款</Tag> : <Tag color="danger">不可退款</Tag>
      }
    >
      <List>
        <List.Item extra={props.orderId}>订单号</List.Item>
        <List.Item extra={`${props.amount} ${props.currency}`}>退款金额</List.Item>
        {props.estimatedDays !== undefined ? (
          <List.Item extra={`${props.estimatedDays} 个工作日`}>预计到账</List.Item>
        ) : null}
        {props.reason ? <List.Item extra={props.reason}>说明</List.Item> : null}
      </List>
    </AdmCard>
  );
}
