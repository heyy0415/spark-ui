import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import confirmExample from '@contracts/examples/ui-schema.example.json';
import resultExample from '@contracts/examples/ui-schema.result.example.json';
import orderTableExample from '@contracts/examples/ui-schema.order-table.example.json';
import productTableExample from '@contracts/examples/ui-schema.product-table.example.json';
import orderDetailExample from '@contracts/examples/ui-schema.order-detail.example.json';
import logisticsExample from '@contracts/examples/ui-schema.logistics.example.json';
import aftersaleConfirmExample from '@contracts/examples/ui-schema.aftersale-confirm.example.json';
import deleteConfirmExample from '@contracts/examples/ui-schema.delete-confirm.example.json';
import productDetailExample from '@contracts/examples/ui-schema.product-detail.example.json';
import type { UiSchema } from '@spark-ui/core';
import { SchemaRenderer, UiSchemaSchema, useDevice } from '@spark-ui/core';
import unknownFixture from './fixtures/unknown.json';
import styles from './SchemaPlaygroundPage.module.css';

/**
 * DEV 专用渲染宿主：/dev/schema?example={confirm|result|order-table|product-table|order-detail|logistics|aftersale-confirm|delete-confirm|product-detail|unknown}
 * 契约示例经 Zod 校验；unknown 为本地夹具，**跳过 Zod** 直接传给渲染器，
 * 用于验证 UnknownComponent 路径（契约校验会在此之前拒绝未知 type，所以这里必须绕过）。仅在 env.DEV 注册路由。
 */
/** 契约示例表：键 = URL 参数。 */
const EXAMPLES = {
  confirm: confirmExample,
  result: resultExample,
  'order-table': orderTableExample,
  'product-table': productTableExample,
  'order-detail': orderDetailExample,
  'product-detail': productDetailExample,
  logistics: logisticsExample,
  'aftersale-confirm': aftersaleConfirmExample,
  'delete-confirm': deleteConfirmExample,
} as const;
type ExampleKey = keyof typeof EXAMPLES | 'unknown';
const KEYS = [...(Object.keys(EXAMPLES) as (keyof typeof EXAMPLES)[]), 'unknown'] as const;

export function SchemaPlaygroundPage() {
  const [params] = useSearchParams();
  const key = (params.get('example') ?? 'confirm') as ExampleKey;
  const device = useDevice();
  const [lastIntent, setLastIntent] = useState<string | null>(null);

  const ui = useMemo<UiSchema | null>(() => {
    if (key === 'unknown') {
      // DEV-only：故意绕过 Zod，模拟后端下发了白名单外的 type
      return unknownFixture as unknown as UiSchema;
    }
    const raw = EXAMPLES[key as keyof typeof EXAMPLES] ?? confirmExample;
    const parsed = UiSchemaSchema.safeParse(raw);
    if (!parsed.success) {
      console.error('[playground] example failed ui-schema validation', parsed.error.issues);
      return null;
    }
    return parsed.data;
  }, [key]);

  return (
    <section className={styles['wrap']}>
      <nav className={styles['nav']} aria-label="示例切换">
        {KEYS.map((k) => (
          <a
            key={k}
            href={`/dev/schema?example=${k}`}
            className={k === key ? styles['active'] : undefined}
          >
            {k}
          </a>
        ))}
        <span className={styles['device']}>device={device}</span>
        {lastIntent ? <span data-last-intent={lastIntent}>intent: {lastIntent}</span> : null}
      </nav>
      {ui ? (
        // playground 无后端：行内指令只显示在页面上，验证回调链路
        <SchemaRenderer ui={ui} onIntent={setLastIntent} />
      ) : (
        <p role="alert">示例无法通过契约校验</p>
      )}
    </section>
  );
}
