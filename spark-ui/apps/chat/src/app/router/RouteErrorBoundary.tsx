import { isRouteErrorResponse, Link, useRouteError } from 'react-router';

/**
 * 路由级错误边界（S10）：任何页面渲染异常都落到这里，而不是白屏。
 * 不展示堆栈与内部信息，只给用户可理解的文案与返回入口。
 */
export function RouteErrorBoundary() {
  const error = useRouteError();
  // statusText 可能为空串（部分服务端 / 浏览器不返回），避免渲染出 "404 · " 这样的空尾标题
  const title =
    isRouteErrorResponse(error) && error.statusText
      ? `${error.status} · ${error.statusText}`
      : '页面出错了';
  console.error('[route-error]', error);
  return (
    <section role="alert" aria-labelledby="route-error-title">
      <h1 id="route-error-title">{title}</h1>
      <p>页面渲染时发生了未预期的错误，请刷新重试。</p>
      <p>
        <Link to="/">返回首页</Link>
      </p>
    </section>
  );
}
