import { Outlet } from 'react-router';
import styles from './RootLayout.module.css';

export function RootLayout() {
  return (
    <div className={styles['shell']}>
      <header className={styles['header']}>
        <span className={styles['brand']}>Spark UI</span>
      </header>
      <main className={styles['main']}>
        <Outlet />
      </main>
    </div>
  );
}
