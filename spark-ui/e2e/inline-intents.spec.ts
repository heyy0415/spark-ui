import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

/**
 * 行内指令驱动的多级界面（原 e2e-frontend.mjs 的 step 7，共 16 条断言）：
 * 订单表 → 点行内「查看物流」→ 物流屏 → 商品表 → 点「查看商品详情」→ 商品卡 → 点「返回列表」→ 回到商品表。
 *
 * 关键边界：表格行与卡片上的按钮点击后，只是把预写好的自然语言 `intent` 当作新消息发送，
 * 不带令牌、不拼参数、不调接口。前端全程只发自然语言。
 */

const LAST_ASSISTANT = '[aria-label="对话消息"] li[data-role="assistant"]:last-of-type';
const inLast = (selector: string): string => `${LAST_ASSISTANT} ${selector}`;
const USER_BUBBLES = '[aria-label="对话消息"] li[data-role="user"]';

function collectErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') {
      errors.push(m.text());
    }
  });
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  return errors;
}

async function send(page: Page, message: string): Promise<void> {
  await page.fill('#agent-input', message);
  await page.press('#agent-input', 'Enter');
}

test('step 7 — 订单表 → 物流 → 商品表 → 商品卡 → 返回列表', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto('/');
  await expect(page.locator('#agent-input')).toBeVisible();

  await send(page, '看看我的订单');
  await expect(page.locator('[data-component-id="orders"]')).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('[data-component-id="orders"] tbody tr')).toHaveCount(20);
  await expect(page.locator(USER_BUBBLES)).toHaveCount(1);

  // 行内按钮的 intent 是后端预写的自然语言，点击等于发一条新消息
  await page.click('[data-intent="查看订单 10030 的物流"]');
  await expect(page.locator('[data-component-id="logistics-events"]')).toBeVisible({
    timeout: 20_000,
  });

  const userTexts = await page.locator(USER_BUBBLES).allTextContents();
  expect(userTexts.some((t) => t.includes('查看订单 10030 的物流'))).toBe(true);
  await expect(page.locator('[data-component-id="logistics"]')).toHaveCount(1);
  // 历史回合保留：订单表与物流屏同时在 DOM
  await expect(page.locator(USER_BUBBLES)).toHaveCount(2);
  await expect(page.locator('[data-component-id="orders"]')).toHaveCount(1);
  await expect(page.locator('[data-screen-id]')).toHaveCount(2);

  // 历史回合的行内按钮仍可点（自然语言、无令牌，不像确认按钮那样一次性）
  const historyIntents = page.locator('li[data-role="assistant"]:not(:last-of-type) [data-intent]');
  expect(await historyIntents.count()).toBeGreaterThan(0);
  const noneDisabled = await historyIntents.evaluateAll((els) =>
    els.every((e) => !(e as HTMLButtonElement).disabled),
  );
  expect(noneDisabled).toBe(true);
  expect(
    await page.locator('[data-component-id="logistics-events"] .ant-timeline-item').count(),
  ).toBeGreaterThanOrEqual(3);

  await send(page, '有什么商品');
  await expect(page.locator(inLast('[data-component-id="products"]'))).toBeVisible({
    timeout: 20_000,
  });
  await expect(page.locator(inLast('[data-component-id="products"] tbody tr'))).toHaveCount(20);

  await page.click(inLast('[data-intent="查看商品 P-1003 的详情"]'));
  await expect(page.locator('[data-component-id="product"]')).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('[data-component-id="product"]')).toContainText('无线耳机 Pro');

  // Card.actions：详情屏的「返回列表」同样是预写自然语言，点了开启新回合
  await expect(
    page.locator('[data-component-id="product"] [data-intent="有什么商品"]'),
  ).toHaveCount(1);
  await page.click('[data-component-id="product"] [data-intent="有什么商品"]');
  await expect(page.locator(inLast('[data-component-id="products"]'))).toBeVisible({
    timeout: 20_000,
  });
  await expect(page.locator(inLast('[data-component-id="products"] tbody tr'))).toHaveCount(20);
  await expect(page.locator(USER_BUBBLES)).toHaveCount(5);

  expect(errors).toEqual([]);
});
