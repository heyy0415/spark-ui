import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

/**
 * 聊天主链路（原 e2e-frontend.mjs 的 step 5，共 19 条断言）：
 * 输入自然语言 → 两条工具进度 + 确认卡片 → 选原因 → 确认 → 结果卡片 → 历史回合只读。
 *
 * 需要后端在跑（e2e profile，由 .harness/scripts/e2e-frontend.sh 负责启动），
 * 后端地址经 SPARK_BACKEND 传给 dev server 的 proxy。
 */

/** 聊天流保留每个回合，断言只看最后一个助手气泡。 */
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

/**
 * 在浏览器里装一个 MutationObserver 记录骨架屏与 streaming 状态是否「曾出现」。
 *
 * 本机后端几十毫秒就回屏，这两个瞬态直接断言必然抓不到，只能提前埋观察者、事后读结果。
 */
async function watchTransientStates(page: Page): Promise<void> {
  await page.evaluate(() => {
    const seen = { skeleton: false, streaming: false };
    window.__sparkSeen = seen;
    new MutationObserver(() => {
      if (document.querySelector('[data-testid="spark-skeleton"]')) {
        seen.skeleton = true;
      }
      if (document.querySelector('[data-run-status="streaming"]')) {
        seen.streaming = true;
      }
    }).observe(document.body, { subtree: true, childList: true, attributes: true });
  });
}

async function send(page: Page, message: string): Promise<void> {
  await page.fill('#agent-input', message);
  await page.press('#agent-input', 'Enter');
}

test('step 5 — 退款主链路：确认屏 → 选原因 → 确认 → 结果屏 → 历史只读', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto('/');
  await expect(page.locator('#agent-input')).toBeVisible();
  await watchTransientStates(page);

  // 前端只发自然语言，订单号在消息里，不带任何页面上下文
  await send(page, '帮我把订单 10001 退款');
  await expect(page.locator('[data-screen-id="refund-confirmation"]')).toBeVisible({
    timeout: 20_000,
  });

  const seen = await page.evaluate(() => window.__sparkSeen);
  expect(seen?.skeleton).toBe(true);
  expect(seen?.streaming).toBe(true);
  await expect(page.locator(USER_BUBBLES)).toHaveCount(1);
  await expect(page.locator('[data-testid="spark-skeleton"]')).toHaveCount(0);
  await expect(page.locator(inLast('[data-run-status="waiting_confirmation"]'))).toHaveCount(1);
  await expect(page.locator(inLast('[aria-label="工具进度"] li'))).toHaveCount(2);
  await expect(
    page.locator(inLast('[aria-label="工具进度"] li[data-status="succeeded"]')),
  ).toHaveCount(2);
  // 确认屏为 Card + Card + Form 三个组件
  await expect(
    page.locator('[data-screen-id="refund-confirmation"] [data-component-id]'),
  ).toHaveCount(3);
  await expect(page.locator('[data-action-id="confirm-refund"]')).toHaveCount(1);

  // antd 6 的内部类名不稳定，用契约字段名 #reason 定位后走键盘选第一项
  await page.click('#reason');
  await expect(page.locator('.ant-select-dropdown')).toBeVisible();
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await expect(page.locator('#reason')).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('.ant-select-placeholder')).toHaveCount(0);

  await page.click('[data-action-id="confirm-refund"]');
  await expect(page.locator('[data-screen-id="refund-result"]')).toBeVisible({ timeout: 20_000 });

  await expect(
    page.locator('[data-screen-id="refund-result"] [data-component-id="result"]'),
  ).toHaveCount(1);
  // 确认在同一回合内完成：用户气泡仍是 1 个，结果屏替换确认屏，ActionBar 消失
  await expect(page.locator(USER_BUBBLES)).toHaveCount(1);
  await expect(page.locator('[data-screen-id="refund-confirmation"]')).toHaveCount(0);
  await expect(page.locator('[data-action-id]')).toHaveCount(0);
  await expect(page.locator(inLast('[data-run-status="completed"]'))).toHaveCount(1);
  await expect(page.locator(inLast('[aria-label="工具进度"] li'))).toHaveCount(4);

  // 历史回合只读：先造一个「确认屏挂起时发新消息」的场景，旧回合应被收口为 completed
  await send(page, '帮我把订单 10011 退款');
  await expect(page.locator(inLast('[data-screen-id="refund-confirmation"]'))).toBeVisible({
    timeout: 20_000,
  });
  await send(page, '看看我的订单');
  await expect(page.locator(inLast('[data-component-id="orders"]'))).toBeVisible({
    timeout: 20_000,
  });

  await expect(page.locator('[data-action-id]')).toHaveCount(0);
  await expect(page.locator('[data-run-status="waiting_confirmation"]')).toHaveCount(0);
  const historySelects = page.locator(
    'li[data-role="assistant"]:not(:last-of-type) .ant-form .ant-select',
  );
  expect(await historySelects.count()).toBeGreaterThan(0);
  const allDisabled = await historySelects.evaluateAll((els) =>
    els.every((e) => e.className.includes('ant-select-disabled')),
  );
  expect(allDisabled).toBe(true);

  expect(errors).toEqual([]);
});
