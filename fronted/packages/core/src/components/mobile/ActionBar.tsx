import { Button as AdmButton, Space } from 'antd-mobile';
import type { UiAction } from '@entities/agent-run';

export interface ActionBarProps {
  actions: UiAction[];
  disabled?: boolean;
  onAction: (action: UiAction) => void;
}

/** actions[] 的移动端渲染；style → antd-mobile color。 */
export function ActionBarMobile({ actions, disabled, onAction }: ActionBarProps) {
  if (actions.length === 0) {
    return null;
  }
  return (
    <Space direction="vertical" block>
      {actions.map((a) => (
        <AdmButton
          key={a.id}
          block
          color={a.style === 'danger' ? 'danger' : a.style === 'primary' ? 'primary' : 'default'}
          {...(disabled === undefined ? {} : { disabled })}
          onClick={() => onAction(a)}
          data-action-id={a.id}
        >
          {a.label}
        </AdmButton>
      ))}
    </Space>
  );
}
