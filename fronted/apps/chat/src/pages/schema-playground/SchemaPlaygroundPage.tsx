import { useMemo } from 'react';
import { useSearchParams } from 'react-router';
import confirmExample from '@contracts/examples/ui-schema.example.json';
import resultExample from '@contracts/examples/ui-schema.result.example.json';
import type { UiSchema } from '@strato-ui/core';
import { SchemaRenderer, UiSchemaSchema, useDevice } from '@strato-ui/core';
import unknownFixture from './fixtures/unknown.json';
import styles from './SchemaPlaygroundPage.module.css';

/**
 * DEV 专用渲染宿主：/dev/schema?example={confirm|result|unknown}
 * confirm / result 来自契约示例并经 Zod 校验；unknown 为本地夹具，**跳过 Zod** 直接传给渲染器，
 * 用于验证 UnknownComponent 路径（契约校验会在此之前拒绝未知 type，所以这里必须绕过）。仅在 env.DEV 注册路由。
 */
type ExampleKey = 'confirm' | 'result' | 'unknown';

export function SchemaPlaygroundPage() {
  const [params] = useSearchParams();
  const key = (params.get('example') ?? 'confirm') as ExampleKey;
  const device = useDevice();

  const ui = useMemo<UiSchema | null>(() => {
    if (key === 'unknown') {
      // DEV-only：故意绕过 Zod，模拟后端下发了白名单外的 type
      return unknownFixture as unknown as UiSchema;
    }
    const raw = key === 'result' ? resultExample : confirmExample;
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
        {(['confirm', 'result', 'unknown'] as const).map((k) => (
          <a
            key={k}
            href={`/dev/schema?example=${k}`}
            className={k === key ? styles['active'] : undefined}
          >
            {k}
          </a>
        ))}
        <span className={styles['device']}>device={device}</span>
      </nav>
      {ui ? <SchemaRenderer ui={ui} /> : <p role="alert">示例无法通过契约校验</p>}
    </section>
  );
}
