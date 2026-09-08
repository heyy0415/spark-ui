# Code Review v1（机械检查）— feat-intent-routing-20260908

日期：2026-09-08

| # | 命令 | 退出码 | 摘要 |
|---|---|---|---|
| 0 | `pnpm -C .harness run check-contracts` | 0 | 契约零变化 |
| 1 | `rm -rf fronted/*/dist && pnpm -C fronted run ci` | 0 | verify-pack 14 ✓ |
| 2 | `node .harness/scripts/mvn.mjs -q -B verify` | 0 | sealed `Claim` + switch pattern 在 `-Xlint:all -Werror` 下通过 |
| 3 | `pnpm -C .harness run check-module-deps` | 0 | 新规则：三模块禁 import 对方 `application` |
| 4 | `pnpm -C .harness run ci` | **0** | 四段全 0 |
| 5 | `pnpm -C .harness run doctor` | 0 | 含 L1 一致性新检查 |
| 6 | `bash .harness/scripts/e2e-backend.sh` 规则 / LIVE | 0 / 0 | 62 / 62 两次 |
| 7 | `node .harness/scripts/e2e-frontend.mjs` | 0 | 21 / 21 |
| 8 | `bash .harness/scripts/deploy-verify.sh` | 0 | 12 / 12 |

## 红线
- [x] runtime 无 `registry|gateway`.`(infra|domain|application)` import（grep 0）
- [x] `domain/` 包无 Spring / Jackson；`IdempotencyStore` 用 JDK `CompletableFuture`
- [x] 无 `System.out` / 空 catch；`SpringAiIntentClassifier` 日志不含用户原文（grep `log.*message` 0）
- [x] 契约字段零变化；SSE 顺序零变化
- [x] 前端：无 antd 越界、无深路径、无 `any`；`interface ActionBarProps` 恰 1 处；`FormFieldPropsSchema` 0 处
- [x] 密钥 / 端点 / 模型名全树 0 命中

结论：机械项全部通过，进入 expert-reviewer（execution）。
