#!/usr/bin/env node
/**
 * node .harness/scripts/sse-parse.mjs <file> [--events|--data <event-name> [--index n]|--json <event-name>]
 *
 * 解析 SSE 帧文件（兼容 "event:xxx" 与 "event: xxx"，data 多行拼接）。
 *   --events                 输出事件名序列（空格分隔）
 *   --data <event> [--index] 输出第 n 个（默认 0）该事件的 data 原文
 *   --frames                 输出 JSON 数组 [{event,data}]
 */
import { readFileSync } from 'node:fs';

const [file, mode, arg, idxFlag, idxVal] = process.argv.slice(2);
if (!file) {
  console.error('usage: sse-parse.mjs <file> --events | --data <event> [--index n] | --frames');
  process.exit(2);
}
const text = readFileSync(file, 'utf-8');
const frames = [];
let ev = null;
let data = [];
for (const raw of text.split(/\r?\n/)) {
  if (raw === '') {
    if (ev !== null) frames.push({ event: ev, data: data.join('\n') });
    ev = null;
    data = [];
    continue;
  }
  if (raw.startsWith(':')) continue; // comment / ping
  const m = raw.match(/^([a-z]+):\s?(.*)$/);
  if (!m) continue;
  if (m[1] === 'event') ev = m[2];
  else if (m[1] === 'data') data.push(m[2]);
}
if (ev !== null) frames.push({ event: ev, data: data.join('\n') });

if (mode === '--events') {
  console.log(frames.map((f) => f.event).join(' '));
} else if (mode === '--data') {
  const idx = idxFlag === '--index' ? Number(idxVal) : 0;
  const hit = frames.filter((f) => f.event === arg)[idx];
  if (!hit) process.exit(1);
  console.log(hit.data);
} else if (mode === '--frames') {
  console.log(JSON.stringify(frames.map((f) => ({ event: f.event, data: JSON.parse(f.data) }))));
} else {
  console.error('unknown mode');
  process.exit(2);
}
