import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

/**
 * /dev/schema 渲染宿主的端型与契约示例断言（原 e2e-frontend.mjs 的 step 2 / 3 / 4 / 6，共 19 条）。
 *
 * 这些用例只渲染契约示例 JSON，不发后端请求，因此无需后端在跑。
 * `/dev/schema` 路由由 `router.tsx` 的 `env.DEV` 门控，只在 dev server 下注册——playwright.config.ts 的
 * webServer 因此用 `pnpm run dev` 而非 `vite preview`。
 */

/** antd-mobile 挂在 body 上的内部测量元素，任何端型都存在，不是业务渲染，计数时排除。 */
const PX_TESTER = 'adm-px-tester';

/** 统计 class 以指定前缀开头的元素数（`ant-` / `adm-` 用于判断实际渲染的是桌面端还是移动端组件）。 */
const countByClassPrefix = (page: Page, prefix: string): Promise<number> =>
  page.evaluate(
    ({ p, tester }) =>
      [...document.querySelectorAll(`[class^="${p}"], [class*=" ${p}"]`)].filter(
        (e) => !e.className.includes(tester),
      ).length,
    { p: prefix, tester: PX_TESTER },
  );

/** 收集本页所有 console.error 与未捕获异常；断言前读取。 */
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

test.describe('step 2 — 桌面端渲染 antd', () => {
  test('/dev/schema?example=confirm @1280', async ({ page }) => {
    const errors = collectErrors(page);
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto('/dev/schema?example=confirm');
    await expect(page.locator('[data-screen-id="refund-confirmation"]')).toBeVisible();
    // 白名单组件走 React.lazy + Suspense，先等组件真正挂载再统计 class 前缀，否则数到的是 fallback
    await expect(page.locator('[data-component-id]')).toHaveCount(3);

    await expect(page.locator('[data-screen-id]').first()).toHaveAttribute(
      'data-device',
      'desktop',
    );
    expect(await countByClassPrefix(page, 'ant-')).toBeGreaterThan(0);
    expect(await countByClassPrefix(page, 'adm-')).toBe(0);
    expect(errors).toEqual([]);
  });
});

test.describe('step 3 — 移动端渲染 antd-mobile', () => {
  test('/dev/schema?example=confirm @375', async ({ page }) => {
    const errors = collectErrors(page);
    await page.setViewportSize({ width: 375, height: 900 });
    await page.goto('/dev/schema?example=confirm');
    await expect(page.locator('[data-screen-id="refund-confirmation"]')).toBeVisible();
    await expect(page.locator('[data-component-id]')).toHaveCount(3);

    await expect(page.locator('[data-screen-id]').first()).toHaveAttribute('data-device', 'mobile');
    expect(await countByClassPrefix(page, 'adm-')).toBeGreaterThan(0);
    expect(await countByClassPrefix(page, 'ant-')).toBe(0);
    expect(errors).toEqual([]);
  });
});

test.describe('step 4 — 未知组件类型降级为占位', () => {
  test('/dev/schema?example=unknown', async ({ page }) => {
    const errors = collectErrors(page);
    await page.goto('/dev/schema?example=unknown');
    await expect(page.locator('[data-screen-id="playground-unknown"]')).toBeVisible();

    // 白名单外的 type → UnknownComponent 占位 + 一条 console.error，同屏已知组件不受影响
    await expect(page.locator('[role="alert"][data-component-id="mystery"]')).toHaveCount(1);
    await expect(page.locator('[data-component-id="known"]')).toHaveCount(1);
    expect(errors.some((e) => e.includes('unknown component type'))).toBe(true);
    expect(errors.filter((e) => !e.includes('unknown component type'))).toEqual([]);
  });
});

test.describe('step 6 — 9 个契约示例在两种视口下均正确渲染', () => {
  /** 每个示例的期望组件数，与 .harness/contracts/examples/ui-schema.*.example.json 一致。 */
  const EXAMPLES: Record<string, number> = {
    confirm: 3,
    result: 1,
    'order-table': 1,
    'product-table': 1,
    'order-detail': 3,
    logistics: 2,
    'aftersale-confirm': 2,
    'delete-confirm': 1,
    'product-detail': 1,
  };

  for (const width of [1280, 375]) {
    test(`9 examples @${width}`, async ({ page }) => {
      const errors = collectErrors(page);
      await page.setViewportSize({ width, height: 900 });
      const mismatched: string[] = [];
      let unknownPlaceholders = 0;

      for (const [example, expectedComponents] of Object.entries(EXAMPLES)) {
        await page.goto(`/dev/schema?example=${example}`);
        await expect(page.locator('[data-screen-id]').first()).toBeVisible();
        // lazy 组件挂载前 count 恒为 0：先等到期望值（超时即视为不匹配），再取实测值汇总
        const components = page.locator('[data-component-id]');
        try {
          await expect(components).toHaveCount(expectedComponents, { timeout: 5_000 });
        } catch {
          mismatched.push(`${example}=${await components.count()}/${expectedComponents}`);
        }
        unknownPlaceholders += await page.locator('[role="alert"][data-component-id]').count();
      }

      expect(mismatched).toEqual([]);
      expect(unknownPlaceholders).toBe(0);
      expect(errors).toEqual([]);
    });
  }
});
