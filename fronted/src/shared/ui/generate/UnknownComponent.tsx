import styles from './UnknownComponent.module.css';

/** 未知或 props 非法的组件占位；console.error 一次并保留页面其余部分可用。 */
export function UnknownComponent({
  id,
  type,
  reason,
}: {
  id: string;
  type: string;
  reason: string;
}) {
  return (
    <div className={styles['box']} role="alert" data-component-id={id}>
      <strong>无法渲染组件</strong>
      <span className={styles['meta']}>
        type={type} id={id}
      </span>
      <span className={styles['reason']}>{reason}</span>
    </div>
  );
}
