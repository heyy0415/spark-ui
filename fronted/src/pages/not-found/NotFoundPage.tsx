import { Link } from 'react-router';

export function NotFoundPage() {
  return (
    <section aria-labelledby="nf-title">
      <h1 id="nf-title">404 · 页面不存在</h1>
      <p>
        <Link to="/">返回首页</Link>
      </p>
    </section>
  );
}
