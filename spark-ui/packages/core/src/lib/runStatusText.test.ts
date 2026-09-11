import { describe, expect, it } from 'vitest';
import type { ToolStep } from '../registry/types';
import { TOOL_STATUS_TEXT, runStatusText } from './runStatusText';

/** 两端共用的状态文案：streaming 取最后一个 status !== 'succeeded' 的工具（含 failed），无则「理解问题」。 */
const step = (displayName: string, status: ToolStep['status']): ToolStep => ({
  displayName,
  status,
});

describe('runStatusText', () => {
  it('streaming with no tools is the understanding phase', () => {
    expect(runStatusText({ status: 'streaming', tools: [] })).toBe('正在理解你的问题…');
  });

  it('streaming names the last unfinished tool', () => {
    const tools = [step('查看条目', 'succeeded'), step('关闭条目', 'running')];
    expect(runStatusText({ status: 'streaming', tools })).toBe('正在关闭条目…');
  });

  it('streaming picks the last non-succeeded tool even if an earlier one is running', () => {
    const tools = [step('A', 'running'), step('B', 'succeeded'), step('C', 'selected')];
    expect(runStatusText({ status: 'streaming', tools })).toBe('正在C…');
  });

  it('streaming treats a failed tool as unfinished', () => {
    const tools = [step('A', 'succeeded'), step('B', 'failed')];
    expect(runStatusText({ status: 'streaming', tools })).toBe('正在B…');
  });

  it('streaming with all tools succeeded falls back to understanding', () => {
    const tools = [step('A', 'succeeded'), step('B', 'succeeded')];
    expect(runStatusText({ status: 'streaming', tools })).toBe('正在理解你的问题…');
  });

  it('waiting_confirmation asks to confirm', () => {
    expect(runStatusText({ status: 'waiting_confirmation', tools: [] })).toBe('请确认后继续');
  });

  it('completed reports the step count when there are tools', () => {
    expect(runStatusText({ status: 'completed', tools: [step('A', 'succeeded')] })).toBe(
      '完成 · 1 步',
    );
    expect(runStatusText({ status: 'completed', tools: [] })).toBe('完成');
  });

  it('failed shows the given text or a default', () => {
    expect(runStatusText({ status: 'failed', tools: [], text: '连接中断' })).toBe('连接中断');
    expect(runStatusText({ status: 'failed', tools: [] })).toBe('处理失败');
  });

  it('TOOL_STATUS_TEXT covers all four tool states', () => {
    const keys = Object.keys(TOOL_STATUS_TEXT);
    expect(keys).toHaveLength(4);
    expect(keys).toEqual(expect.arrayContaining(['failed', 'running', 'selected', 'succeeded']));
  });
});
