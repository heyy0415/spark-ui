import { Skeleton } from 'antd';
import type { SchemaSkeletonProps } from '../../registry/types';

/** 桌面骨架：table 用多行 Input 块模拟表格，card 是标题 + 段落，form 是 3 个输入块，generic 是一段。 */
export function SchemaSkeletonDesktop({ variant }: SchemaSkeletonProps) {
  return (
    <div data-testid="spark-skeleton" data-variant={variant} aria-busy="true">
      {variant === 'table' ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {[0, 1, 2, 3].map((i) => (
            <Skeleton.Input key={i} active block size="small" />
          ))}
        </div>
      ) : variant === 'form' ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {[0, 1, 2].map((i) => (
            <Skeleton.Input key={i} active block />
          ))}
        </div>
      ) : (
        <Skeleton active title paragraph={{ rows: variant === 'card' ? 4 : 2 }} />
      )}
    </div>
  );
}
