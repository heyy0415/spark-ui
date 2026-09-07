#!/usr/bin/env node
/**
 * 统一定位当前 change 的目录（spec feat-strato-ui-monorepo §2.5）。
 *   - 设置了 STRATO_CHANGE=<change-id> → .harness/changes/<id>
 *   - 未设置 → 在 .harness/changes/ 中按 summary.md 的 `| 状态 | X |` 行读状态，选状态 ∉ {DONE} 的目录；
 *     恰 1 个则用之；0 或 >1 个 → 退出码 2 并列出候选（并行多个 change 时必须显式指定，这是预期用法）。
 * 作为模块：import { changeDir, deploymentDir }；作为 CLI：打印 deployment 目录绝对路径。
 */
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const changesRoot = join(root, '.harness', 'changes');
const TERMINAL = new Set(['DONE']);

function statusOf(dir) {
  const summary = join(dir, 'summary.md');
  if (!existsSync(summary)) return null;
  const m = readFileSync(summary, 'utf-8').match(/^\| 状态 \| (\S+)/m);
  return m ? m[1] : null;
}

export function changeDir() {
  const explicit = process.env.STRATO_CHANGE;
  if (explicit) {
    const dir = join(changesRoot, explicit);
    if (!existsSync(dir)) {
      console.error(`[change-dir] STRATO_CHANGE=${explicit} not found under .harness/changes/`);
      process.exit(2);
    }
    return dir;
  }
  const candidates = readdirSync(changesRoot, { withFileTypes: true })
    .filter((e) => e.isDirectory())
    .map((e) => e.name)
    .filter((name) => !TERMINAL.has(statusOf(join(changesRoot, name)) ?? 'DONE'));
  if (candidates.length !== 1) {
    console.error(
      `[change-dir] expected exactly one non-DONE change, found ${candidates.length}: ${candidates.join(', ') || '(none)'}. Set STRATO_CHANGE=<id>.`,
    );
    process.exit(2);
  }
  return join(changesRoot, candidates[0]);
}

export function deploymentDir() {
  return join(changeDir(), 'deployment');
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  console.log(deploymentDir());
}
