import { List, NoticeBar } from 'antd-mobile';
import type { ConfirmationCardProps, RenderedComponentProps } from '../types';

export function ConfirmationCardMobile({
  id,
  props,
}: RenderedComponentProps<ConfirmationCardProps>) {
  return (
    <div data-component-id={id}>
      <NoticeBar color="alert" content={`${props.title}：${props.message}`} wrap />
      {props.items && props.items.length > 0 ? (
        <List>
          {props.items.map((it, i) => (
            <List.Item key={i} extra={it.value}>
              {it.label}
            </List.Item>
          ))}
        </List>
      ) : null}
    </div>
  );
}
