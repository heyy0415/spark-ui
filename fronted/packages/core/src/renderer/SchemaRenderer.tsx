import { Suspense, useMemo } from 'react';
import type { UiSchema } from '../schema/uiSchema';
import { useDevice } from '../device/DeviceContext';
import { desktopRegistry, mobileRegistry } from '../registry/componentRegistry';
import type { RegisteredComponent } from '../registry/componentRegistry';
import { PROPS_SCHEMAS } from '../registry/types';
import type { FormComponentHandlers, FormValues } from '../registry/types';
import { UnknownComponent } from './UnknownComponent';
import styles from './SchemaRenderer.module.css';

/**
 * 声明式 UI 渲染器（agent-safety §4）：只查注册表；未知 type → UnknownComponent + console.error；
 * 每个组件 props 先经其 Zod 校验；不含 eval / new Function / dangerouslySetInnerHTML / 任意路径 import。
 * 接收已由调用方按 ui-schema 契约校验过的对象；type 在查找层按 string 处理，让 playground 的未知夹具可达占位路径。
 */
export interface SchemaRendererProps {
  ui: UiSchema;
  /** Form 组件的值变化回调（收集 formData）。 */
  onFormChange?: (values: FormValues) => void;
}

export function SchemaRenderer({ ui, onFormChange }: SchemaRendererProps) {
  const device = useDevice();
  const registry = device === 'mobile' ? mobileRegistry : desktopRegistry;
  const handlers = useMemo<FormComponentHandlers>(
    () => (onFormChange ? { onChange: onFormChange } : {}),
    [onFormChange],
  );

  return (
    <div className={styles['screen']} data-screen-id={ui.screenId} data-device={device}>
      {ui.title ? <h2 className={styles['title']}>{ui.title}</h2> : null}
      <div className={styles['components']}>
        {ui.components.map((c) => {
          const type: string = c.type;
          const Comp: RegisteredComponent | undefined = registry[type];
          if (!Comp) {
            console.error('[strato-ui] unknown component type', type, 'id=', c.id);
            return (
              <UnknownComponent key={c.id} id={c.id} type={type} reason="组件未在白名单注册表中" />
            );
          }
          const schema = PROPS_SCHEMAS[type as keyof typeof PROPS_SCHEMAS];
          const parsed = schema.safeParse(c.props);
          if (!parsed.success) {
            console.error('[strato-ui] invalid props for', type, c.id, parsed.error.issues);
            return (
              <UnknownComponent key={c.id} id={c.id} type={type} reason="组件属性不符合约定" />
            );
          }
          return (
            <Suspense key={c.id} fallback={<div className={styles['loading']}>加载中…</div>}>
              <Comp id={c.id} props={parsed.data as never} handlers={handlers} />
            </Suspense>
          );
        })}
      </div>
    </div>
  );
}
