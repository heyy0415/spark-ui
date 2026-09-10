import type { RunStatusProps, ToolStep } from './types';

/** 两端共用的状态文案：streaming 取最后一个未完成工具，没有工具时是「理解问题」阶段。 */
export function runStatusText({ status, tools, text }: RunStatusProps): string {
  switch (status) {
    case 'streaming': {
      // 从后往前找第一个未完成的工具（core 的 lib 目标不含 toReversed）
      let active: ToolStep | undefined;
      for (let i = tools.length - 1; i >= 0; i -= 1) {
        if (tools[i]?.status !== 'succeeded') {
          active = tools[i];
          break;
        }
      }
      return active ? `正在${active.displayName}…` : '正在理解你的问题…';
    }
    case 'waiting_confirmation':
      return '请确认后继续';
    case 'completed':
      return tools.length > 0 ? `完成 · ${tools.length} 步` : '完成';
    case 'failed':
      return text ?? '处理失败';
  }
}

export const TOOL_STATUS_TEXT: Record<RunStatusProps['tools'][number]['status'], string> = {
  selected: '已选择',
  running: '执行中',
  succeeded: '完成',
  failed: '失败',
};
