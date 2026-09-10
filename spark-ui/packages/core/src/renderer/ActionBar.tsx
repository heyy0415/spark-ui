import type { ActionBarProps } from '../registry/types';
import { useDevice } from '../device/DeviceContext';
import { ActionBarDesktop } from '../components/desktop/ActionBar';
import { ActionBarMobile } from '../components/mobile/ActionBar';

/** 按端型选择 actions[] 渲染实现。 */
export function ActionBar(props: ActionBarProps) {
  const device = useDevice();
  return device === 'mobile' ? <ActionBarMobile {...props} /> : <ActionBarDesktop {...props} />;
}
