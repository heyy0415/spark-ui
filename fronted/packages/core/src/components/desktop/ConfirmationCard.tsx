import { Alert, Descriptions } from 'antd';
import type { ConfirmationCardProps, RenderedComponentProps } from '../../registry/types';

export function ConfirmationCardDesktop({
  id,
  props,
}: RenderedComponentProps<ConfirmationCardProps>) {
  return (
    <div data-component-id={id}>
      <Alert
        type="warning"
        showIcon
        message={props.title}
        description={
          <>
            <p style={{ margin: 0 }}>{props.message}</p>
            {props.items && props.items.length > 0 ? (
              <Descriptions
                size="small"
                column={1}
                items={props.items.map((it, i) => ({
                  key: String(i),
                  label: it.label,
                  children: it.value,
                }))}
              />
            ) : null}
          </>
        }
      />
    </div>
  );
}
