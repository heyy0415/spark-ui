import type { RunStatusProps } from '../registry/types';
import { useDevice } from '../device/DeviceContext';
import { RunStatusDesktop } from '../components/desktop/RunStatus';
import { RunStatusMobile } from '../components/mobile/RunStatus';

/** SSE 运行状态条，按端型选择实现。宿主把 SSE 事件归约成 status + tools 交给它，不用自己拼 loading 文案。 */
export function RunStatus(props: RunStatusProps) {
  const device = useDevice();
  return device === 'mobile' ? <RunStatusMobile {...props} /> : <RunStatusDesktop {...props} />;
}
