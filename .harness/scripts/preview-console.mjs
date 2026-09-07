#!/usr/bin/env node
// node .harness/scripts/preview-console.mjs <deployDir>：用本机 Chrome 打开预览（4173）的 chat 主页面与一个 NotFound 路径，
// 统计 console.error / pageerror 并截图到 deployDir。stdout 独立一行 `agent-input=1|0` 表示主页面首屏是否渲染出输入框
// （deploy-verify 用 grep 解析）；退出码 = error 总数（0 即通过）。
import { join } from 'node:path';
import puppeteer from 'puppeteer-core';
const deploy = process.argv[2];
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox'] });
let total = 0;
try {
  for (const [name, url] of [
    ['preview-chat', 'http://localhost:4173/?page=order-detail&entityType=order&entityId=10001'],
    ['preview-notfound', 'http://localhost:4173/does-not-exist'],
  ]) {
    const page = await browser.newPage();
    await page.setViewport({ width: 1280, height: 900 });
    const errors = [];
    page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    await page.goto(url, { waitUntil: 'networkidle0' });
    await page.screenshot({ path: join(deploy, `${name}.png`), fullPage: true });
    if (name === 'preview-chat') {
      const hasInput = (await page.$('#agent-input')) !== null;
      console.log(`agent-input=${hasInput ? 1 : 0}`);
    }
    console.log(`  ${name}: console.error=${errors.length}${errors.length ? ' ' + JSON.stringify(errors.slice(0, 2)) : ''}`);
    total += errors.length;
    await page.close();
  }
} finally {
  await browser.close();
}
process.exit(total);
