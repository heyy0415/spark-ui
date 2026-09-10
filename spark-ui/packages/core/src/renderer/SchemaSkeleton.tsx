import type { SchemaSkeletonProps } from '../registry/types';
import { useDevice } from '../device/DeviceContext';
import { SchemaSkeletonDesktop } from '../components/desktop/SchemaSkeleton';
import { SchemaSkeletonMobile } from '../components/mobile/SchemaSkeleton';

/** 屏到达前的骨架，按端型选择实现。 */
export function SchemaSkeleton(props: SchemaSkeletonProps) {
  const device = useDevice();
  return device === 'mobile' ? (
    <SchemaSkeletonMobile {...props} />
  ) : (
    <SchemaSkeletonDesktop {...props} />
  );
}
