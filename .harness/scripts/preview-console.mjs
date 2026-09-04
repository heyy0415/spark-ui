#!/usr/bin/env node
// node .harness/scripts/preview-console.mjs <deployDir>：用本机 Chrome 打开预览（4173）首页与 /agent，统计 console.error / pageerror，
// 截图到 deployDir。退出码 = error 总数（0 即通过）。
import { join } from 'node:path';
import puppeteer from 'puppeteer-core';
const deploy = process.argv[2];
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox'] });
let total = 0;
try {
  for (const [name, url] of [
    ['preview-home', 'http://localhost:4173/'],
    ['preview-agent', 'http://localhost:4173/agent?page=order-detail&entityType=order&entityId=10001'],
  ]) {
    const page = await browser.newPage();
    await page.setViewport({ width: 1280, height: 900 });
    const errors = [];
    page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    await page.goto(url, { waitUntil: 'networkidle0' });
    await page.screenshot({ path: join(deploy, `${name}.png`), fullPage: true });
    console.log(`  ${name}: console.error=${errors.length}${errors.length ? ' ' + JSON.stringify(errors.slice(0, 2)) : ''}`);
    total += errors.length;
    await page.close();
  }
} finally {
  await browser.close();
}
process.exit(total);
