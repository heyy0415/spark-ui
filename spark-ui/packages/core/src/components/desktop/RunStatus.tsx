import { Space, Spin, Typography } from 'antd';
import type { RunStatusProps } from '../../registry/types';
import { runStatusText, TOOL_STATUS_TEXT } from '../../registry/runStatusText';

/** 桌面 RunStatus：Spin + 文案；进度明细 <ol aria-label="工具进度">（e2e 依赖该 hook）。 */
export function RunStatusDesktop(props: RunStatusProps) {
  const { status, tools } = props;
  const type = status === 'failed' ? 'danger' : status === 'completed' ? 'secondary' : undefined;
  return (
    <div data-run-status={status} style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <Space size="small">
        {status === 'streaming' ? <Spin size="small" /> : null}
        <Typography.Text {...(type ? { type } : {})} style={{ fontSize: 13 }}>
          {runStatusText(props)}
        </Typography.Text>
      </Space>
      {tools.length > 0 ? (
        <ol
          aria-label="工具进度"
          style={{
            margin: 0,
            paddingLeft: 18,
            fontSize: 12,
            color: 'var(--spark-color-text-muted, #646a73)',
          }}
        >
          {tools.map((t, i) => (
            <li key={i} data-status={t.status}>
              {t.displayName} · {TOOL_STATUS_TEXT[t.status]}
            </li>
          ))}
        </ol>
      ) : null}
    </div>
  );
}
