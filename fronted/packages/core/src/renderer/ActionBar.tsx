import type { UiAction } from '@entities/agent-run';
import { useDevice } from '../device/DeviceContext';
import { ActionBarDesktop } from './desktop/ActionBar';
import { ActionBarMobile } from './mobile/ActionBar';

export interface ActionBarProps {
  actions: UiAction[];
  disabled?: boolean;
  onAction: (action: UiAction) => void;
}

/** 按端型选择 actions[] 渲染实现。 */
export function ActionBar(props: ActionBarProps) {
  const device = useDevice();
  return device === 'mobile' ? <ActionBarMobile {...props} /> : <ActionBarDesktop {...props} />;
}
