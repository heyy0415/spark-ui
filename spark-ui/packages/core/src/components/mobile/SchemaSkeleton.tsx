import { Skeleton } from 'antd-mobile';
import type { SchemaSkeletonProps } from '../../registry/types';

/** 移动端骨架：同桌面语义。 */
export function SchemaSkeletonMobile({ variant }: SchemaSkeletonProps) {
  const rows = variant === 'table' ? 4 : variant === 'form' ? 3 : variant === 'card' ? 4 : 2;
  return (
    <div data-testid="spark-skeleton" data-variant={variant} aria-busy="true">
      {variant !== 'table' && variant !== 'form' ? <Skeleton.Title animated /> : null}
      <Skeleton.Paragraph lineCount={rows} animated />
    </div>
  );
}
