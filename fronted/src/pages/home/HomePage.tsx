import { Link } from 'react-router';
import { Button } from '@shared/ui';
import styles from './HomePage.module.css';

export function HomePage() {
  return (
    <section className={styles['hero']} aria-labelledby="home-title">
      <h1 id="home-title" className={styles['title']}>
        欢迎使用 Strato UI
      </h1>
      <p className={styles['subtitle']}>
        工程骨架已就绪：Vite 8 · React 19 · TypeScript 7 · TanStack Query · Zustand · React Router 7
        · Feature-Sliced Design。
      </p>

      <div className={styles['card']}>
        <p className={styles['cardTitle']}>下一步</p>
        <ol className={styles['steps']}>
          <li>
            运行 <code>pnpm harness:new-change feat &lt;name&gt;</code> 创建第一个变更。
          </li>
          <li>
            在 <code>src/entities</code> 与 <code>src/features</code> 下按 FSD 分层落地业务。
          </li>
          <li>
            提交前执行 <code>pnpm run ci</code> 通过质量门禁。
          </li>
        </ol>
      </div>

      <div className={styles['actions']}>
        <Link
          to="/agent?page=order-detail&entityType=order&entityId=10001"
          className={styles['link']}
        >
          打开智能助手（订单 10001）
        </Link>
        <Link to="/does-not-exist" className={styles['link']}>
          查看 404 页面
        </Link>
        <Button variant="secondary" onClick={() => window.location.reload()}>
          刷新页面
        </Button>
      </div>
    </section>
  );
}
