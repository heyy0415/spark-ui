import { SpinLoading } from 'antd-mobile';
import type { RunStatusProps } from '../../registry/types';
import { runStatusText, TOOL_STATUS_TEXT } from '../../registry/runStatusText';

/** 移动端 RunStatus：SpinLoading + 文案；进度明细同桌面结构。 */
export function RunStatusMobile(props: RunStatusProps) {
  const { status, tools } = props;
  const color =
    status === 'failed'
      ? 'var(--adm-color-danger)'
      : status === 'completed'
        ? 'var(--adm-color-weak)'
        : undefined;
  return (
    <div data-run-status={status} style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          fontSize: 13,
          ...(color ? { color } : {}),
        }}
      >
        {status === 'streaming' ? <SpinLoading style={{ '--size': '16px' }} /> : null}
        <span>{runStatusText(props)}</span>
      </div>
      {tools.length > 0 ? (
        <ol
          aria-label="工具进度"
          style={{ margin: 0, paddingLeft: 18, fontSize: 12, color: 'var(--adm-color-weak)' }}
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
