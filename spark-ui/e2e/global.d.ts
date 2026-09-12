/**
 * `page.evaluate` 在浏览器上下文里挂的临时观测量。
 *
 * 本机后端几十毫秒就回屏，骨架屏与 streaming 状态只存在一瞬，直接断言必然抓不到。
 * 做法是发送消息前先装一个 MutationObserver 把「曾出现过」记在 window 上，事后再读。
 */
interface SparkSeen {
  skeleton: boolean;
  streaming: boolean;
}

interface Window {
  /**
   * 双下划线前缀是刻意的：标明这是测试注入的临时量，与业务全局量区分。
   * `.oxlintrc.json` 的 `e2e/**` override 为此关闭了 `no-underscore-dangle`。
   */
  __sparkSeen?: SparkSeen;
}
