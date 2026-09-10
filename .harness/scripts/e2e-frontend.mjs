#!/usr/bin/env node
/**
 * node .harness/scripts/e2e-frontend.mjs
 *
 * spec §6.3.4 人工验收脚本的自动化版本：用本机 Google Chrome（headless，CDP）驱动前端。
 * 前置：后端 8080 与 vite 5173 已启动。产出截图与结果到 change 的 deployment/。
 *   2. 视口 1280 /dev/schema?example=confirm → ant-* > 0 且 adm-* = 0
 *   3. 视口 375  同 URL               → adm-* > 0 且 ant-* = 0
 *   4. /dev/schema?example=unknown     → UnknownComponent 占位且 console.error 恰 1
 *   5. 视口 1280 / 主链路（chat 即首页）：输入 → 两条工具进度 + 确认卡片 → 选原因 → 确认 → 结果卡片；console 无 error
 *   6. 9 个契约示例 × 1280 / 375 全部渲染，console.error 0（feat-commerce-domains）
 *   7. 视口 1280 / 自然语言驱动：「看看我的订单」→ Table → 点 data-intent="查看订单 10030 的物流" → 用户消息 + Timeline
 *      → 「有什么商品」→ Table → 点 data-intent="查看商品 P-1003 的详情" → Card
 * 环境变量 SPARK_FRONT_BASE 可覆盖前端地址（默认 http://localhost:5173）。
 */
import { mkdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';
import { deploymentDir } from './lib/change-dir.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, '..', '..');
const DEPLOY = deploymentDir();
mkdirSync(DEPLOY, { recursive: true });
const BASE = process.env.SPARK_FRONT_BASE ?? 'http://localhost:5173';
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

let pass = 0;
let fail = 0;
const check = (name, expected, actual) => {
  const ok = expected === actual;
  console.log(`  ${ok ? '✓' : '✗'} ${name}: ${JSON.stringify(actual)}${ok ? '' : `  (expected ${JSON.stringify(expected)})`}`);
  ok ? pass++ : fail++;
};
const checkTrue = (name, cond, detail) => check(name, true, Boolean(cond)) || detail;

if (!existsSync(CHROME)) {
  console.error('Google Chrome not found at', CHROME);
  process.exit(2);
}

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox'] });

async function newPage(width) {
  const page = await browser.newPage();
  await page.setViewport({ width, height: 900 });
  const errors = [];
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(m.text());
  });
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  return { page, errors };
}
// 排除 antd-mobile 挂在 body 上的内部测量元素 adm-px-tester（任何端型都会存在，不是业务渲染）
const count = (page, prefix) =>
  page.evaluate(
    (p) => [...document.querySelectorAll(`[class^="${p}"], [class*=" ${p}"]`)].filter((e) => !e.className.includes('adm-px-tester')).length,
    prefix,
  );
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

try {
  // ---- step 2: desktop playground
  console.log('--- §6.3.4 step 2: /dev/schema?example=confirm @1280');
  {
    const { page, errors } = await newPage(1280);
    await page.goto(`${BASE}/dev/schema?example=confirm`, { waitUntil: 'networkidle0' });
    await page.waitForSelector('[data-screen-id="refund-confirmation"]');
    await sleep(500);
    check('device attr', 'desktop', await page.$eval('[data-screen-id]', (e) => e.getAttribute('data-device')));
    checkTrue('ant-* elements > 0', (await count(page, 'ant-')) > 0);
    check('adm-* elements', 0, await count(page, 'adm-'));
    check('components rendered', 3, await page.$$eval('[data-component-id]', (els) => els.length));
    check('console errors', 0, errors.length);
    await page.screenshot({ path: join(DEPLOY, 'ui-playground-desktop.png'), fullPage: true });
    await page.close();
  }

  // ---- step 3: mobile playground
  console.log('--- §6.3.4 step 3: /dev/schema?example=confirm @375');
  {
    const { page, errors } = await newPage(375);
    await page.goto(`${BASE}/dev/schema?example=confirm`, { waitUntil: 'networkidle0' });
    await page.waitForSelector('[data-screen-id="refund-confirmation"]');
    await sleep(500);
    check('device attr', 'mobile', await page.$eval('[data-screen-id]', (e) => e.getAttribute('data-device')));
    checkTrue('adm-* elements > 0', (await count(page, 'adm-')) > 0);
    check('ant-* elements', 0, await count(page, 'ant-'));
    check('console errors', 0, errors.length);
    await page.screenshot({ path: join(DEPLOY, 'ui-playground-mobile.png'), fullPage: true });
    await page.close();
  }

  // ---- step 4: unknown component
  console.log('--- §6.3.4 step 4: /dev/schema?example=unknown');
  {
    const { page, errors } = await newPage(1280);
    await page.goto(`${BASE}/dev/schema?example=unknown`, { waitUntil: 'networkidle0' });
    await page.waitForSelector('[data-screen-id="playground-unknown"]');
    await sleep(500);
    check('UnknownComponent placeholder present', 1, await page.$$eval('[role="alert"][data-component-id="mystery"]', (e) => e.length));
    check('known Card still rendered', 1, await page.$$eval('[data-component-id="known"]', (e) => e.length));
    check('console.error for unknown type emitted', true, errors.some((e) => e.includes('unknown component type')));
    check('no other console errors', 0, errors.filter((e) => !e.includes('unknown component type')).length);
    await page.screenshot({ path: join(DEPLOY, 'ui-unknown.png'), fullPage: true });
    await page.close();
  }

  // ---- step 5: agent main flow
  console.log('--- §6.3.4 step 5: / (chat) main flow @1280');
  {
    const { page, errors } = await newPage(1280);
    // change 5：无 URL 参数，消息带订单号（前端只发自然语言）
    await page.goto(`${BASE}/`, { waitUntil: 'networkidle0' });
    await page.waitForSelector('#agent-input');
    await page.type('#agent-input', '帮我把订单 10001 退款');
    await page.keyboard.press('Enter');
    await page.waitForSelector('[data-screen-id="refund-confirmation"]', { timeout: 15000 });
    await sleep(800);
    check('tool progress items', 2, await page.$$eval('[aria-label="工具进度"] li', (e) => e.length));
    check('tools all succeeded', 2, await page.$$eval('[aria-label="工具进度"] li[data-status="succeeded"]', (e) => e.length));
    check('confirmation screen components', ['Card', 'Card', 'Form'].length, await page.$$eval('[data-screen-id="refund-confirmation"] [data-component-id]', (e) => e.length));
    check('confirm action button present', 1, await page.$$eval('[data-action-id="confirm-refund"]', (e) => e.length));
    await page.screenshot({ path: join(DEPLOY, 'ui-desktop-confirm.png'), fullPage: true });

    // choose reason via antd Select（antd 6 内部类名变了，用契约字段名 #reason + ARIA role 定位）
    await page.click('#reason');
    await page.waitForSelector('.ant-select-dropdown');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    await sleep(300);
    check('reason selected', true, (await page.$eval('#reason', (e) => e.getAttribute('aria-expanded'))) === 'false' && (await page.$$eval('.ant-select-placeholder', (e) => e.length)) === 0);
    await sleep(300);
    await page.click('[data-action-id="confirm-refund"]');
    await page.waitForSelector('[data-screen-id="refund-result"]', { timeout: 15000 });
    await sleep(800);
    check('result screen components', 1, await page.$$eval('[data-screen-id="refund-result"] [data-component-id="result"]', (e) => e.length));
    check('tool progress items after confirm', 4, await page.$$eval('[aria-label="工具进度"] li', (e) => e.length));
    check('console errors', 0, errors.length);
    if (errors.length) console.log('    errors:', errors.slice(0, 3));
    await page.screenshot({ path: join(DEPLOY, 'ui-desktop-flow.png'), fullPage: true });
    await page.close();
  }

  // ---- step 6: all contract examples render at both viewports
  console.log('--- step 6: 9 contract examples @1280 / @375');
  {
    // 每个示例的期望组件数（与契约示例文件一致）；渲染出的 data-component-id 数必须相等且无 UnknownComponent 占位
    const EXAMPLES = { confirm: 3, result: 1, 'order-table': 1, 'product-table': 1, 'order-detail': 3, logistics: 2, 'aftersale-confirm': 2, 'delete-confirm': 1, 'product-detail': 1 };
    for (const width of [1280, 375]) {
      let errs = 0;
      const mismatched = [];
      let unknown = 0;
      for (const [ex, expected] of Object.entries(EXAMPLES)) {
        const { page, errors } = await newPage(width);
        await page.goto(`${BASE}/dev/schema?example=${ex}`, { waitUntil: 'networkidle0' });
        await page.waitForSelector('[data-screen-id]');
        await sleep(300);
        const n = await page.$$eval('[data-component-id]', (e) => e.length);
        if (n !== expected) mismatched.push(`${ex}=${n}/${expected}`);
        unknown += await page.$$eval('[role="alert"][data-component-id]', (e) => e.length);
        errs += errors.length;
        if (errors.length) console.log(`    ${ex}@${width} errors:`, errors.slice(0, 2));
        await page.close();
      }
      check(`examples @${width} component counts match`, '[]', JSON.stringify(mismatched));
      check(`examples @${width} unknown placeholders`, 0, unknown);
      check(`examples @${width} console errors`, 0, errs);
    }
  }

  // ---- step 7: natural-language driven flow via inline intents
  console.log('--- step 7: order table → inline intent → logistics → product table → product card @1280');
  {
    const { page, errors } = await newPage(1280);
    await page.goto(`${BASE}/`, { waitUntil: 'networkidle0' });
    await page.waitForSelector('#agent-input');
    await page.type('#agent-input', '看看我的订单');
    await page.keyboard.press('Enter');
    await page.waitForSelector('[data-component-id="orders"]', { timeout: 15000 });
    await sleep(600);
    check('order table rows', 20, await page.$$eval('[data-component-id="orders"] tbody tr', (e) => e.length));
    check('user message shown', 1, await page.$$eval('[aria-label="对话消息"] li[data-role="user"]', (e) => e.length));
    await page.screenshot({ path: join(DEPLOY, 'ui-order-table.png'), fullPage: true });
    await page.click('[data-intent="查看订单 10030 的物流"]');
    await page.waitForSelector('[data-component-id="logistics-events"]', { timeout: 15000 });
    await sleep(600);
    check('intent text became user message', true, await page.$$eval('[aria-label="对话消息"] li[data-role="user"]', (e) => e.some((li) => li.textContent.includes('查看订单 10030 的物流'))));
    check('logistics card present', 1, await page.$$eval('[data-component-id="logistics"]', (e) => e.length));
    checkTrue('timeline items ≥ 3', (await page.$$eval('[data-component-id="logistics-events"] .ant-timeline-item', (e) => e.length)) >= 3);
    await page.screenshot({ path: join(DEPLOY, 'ui-logistics.png'), fullPage: true });
    await page.type('#agent-input', '有什么商品');
    await page.keyboard.press('Enter');
    await page.waitForSelector('[data-component-id="products"]', { timeout: 15000 });
    await sleep(600);
    check('product table rows', 20, await page.$$eval('[data-component-id="products"] tbody tr', (e) => e.length));
    await page.screenshot({ path: join(DEPLOY, 'ui-product-table.png'), fullPage: true });
    await page.click('[data-intent="查看商品 P-1003 的详情"]');
    await page.waitForSelector('[data-component-id="product"]', { timeout: 15000 });
    await sleep(600);
    check('product card title', true, await page.$eval('[data-component-id="product"]', (e) => e.textContent.includes('无线耳机 Pro')));
    // change 5：Card.actions —— 详情卡「返回列表」→ 发「有什么商品」→ 回到商品表
    check('product card has 返回列表 intent', 1, await page.$$eval('[data-component-id="product"] [data-intent="有什么商品"]', (e) => e.length));
    await page.click('[data-component-id="product"] [data-intent="有什么商品"]');
    await page.waitForSelector('[data-component-id="products"]', { timeout: 15000 });
    await sleep(600);
    check('返回列表 → product table again', 20, await page.$$eval('[data-component-id="products"] tbody tr', (e) => e.length));
    check('console errors', 0, errors.length);
    if (errors.length) console.log('    errors:', errors.slice(0, 3));
    await page.close();
  }
} finally {
  await browser.close();
}

console.log(`\ne2e-frontend: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
