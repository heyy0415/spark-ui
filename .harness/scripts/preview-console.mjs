#!/usr/bin/env node
// node .harness/scripts/preview-console.mjs <deployDir>：用 Playwright 自带 chromium 打开预览（4173）的 chat 主页面
// 与一个 NotFound 路径，统计 console.error / pageerror 并截图到 deployDir。stdout 独立一行 `agent-input=1|0`
// 表示主页面首屏是否渲染出输入框（deploy-verify 用 grep 解析）；退出码 = error 总数（0 即通过）。
//
// 浏览器来自 .harness 自己的 devDep `playwright`，版本必须与 spark-ui 的 @playwright/test 精确一致，
// 否则两者各下一份 chromium（harness-doctor 有一致性检查）。
import { join } from 'node:path';
import { chromium } from 'playwright';

const deploy = process.argv[2];
if (!deploy) {
  console.error('usage: node preview-console.mjs <deployDir>');
  process.exit(2);
}

const browser = await chromium.launch();
let total = 0;
try {
  for (const [name, url] of [
    ['preview-chat', 'http://localhost:4173/'],
    ['preview-notfound', 'http://localhost:4173/does-not-exist'],
  ]) {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errors = [];
    page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    await page.goto(url, { waitUntil: 'networkidle' });
    await page.screenshot({ path: join(deploy, `${name}.png`), fullPage: true });
    if (name === 'preview-chat') {
      const hasInput = (await page.locator('#agent-input').count()) > 0;
      console.log(`agent-input=${hasInput ? 1 : 0}`);
    }
    console.log(
      `  ${name}: console.error=${errors.length}${errors.length ? ' ' + JSON.stringify(errors.slice(0, 2)) : ''}`,
    );
    total += errors.length;
    await page.close();
  }
} finally {
  await browser.close();
}
process.exit(total);
