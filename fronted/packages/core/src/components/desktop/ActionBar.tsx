import { Button as AntButton, Space } from 'antd';
import type { UiAction } from '../../schema/uiSchema';

/**
 * UI Schema actions[] 的桌面渲染。前端只用 actionId 提交（agent-safety §4），confirmationToken 由调用方原样回传。
 * style ∈ {default, primary, danger} → antd type/danger。
 */
export interface ActionBarProps {
  actions: UiAction[];
  disabled?: boolean;
  onAction: (action: UiAction) => void;
}

export function ActionBarDesktop({ actions, disabled, onAction }: ActionBarProps) {
  if (actions.length === 0) {
    return null;
  }
  return (
    <Space wrap>
      {actions.map((a) => (
        <AntButton
          key={a.id}
          type={a.style === 'default' ? 'default' : 'primary'}
          danger={a.style === 'danger'}
          {...(disabled === undefined ? {} : { disabled })}
          onClick={() => onAction(a)}
          data-action-id={a.id}
        >
          {a.label}
        </AntButton>
      ))}
    </Space>
  );
}
