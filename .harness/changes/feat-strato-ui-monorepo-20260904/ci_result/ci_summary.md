# CI Summary — feat-strato-ui-monorepo-20260904（阶段 6）

日期：2026-09-08 · 命令：`rm -rf fronted/*/dist && pnpm -C .harness run ci`（本地执行，仓库尚无远端 CI）· 提交：`8c815e1`（本 change 共 10 commit）

| 步骤 | 退出码 | 摘要 |
|---|---|---|
| check-contracts | 0 | 9 schema / 20 example（`ui-schema.schema.json` 仅 description 注释） |
| check-module-deps | 0 | 后端零改动 |
| fronted ci | 0 | build:core → typecheck → lint（oxlint + check-deps + check-registry）→ format:check → verify-examples 16 OK → build:chat → verify-pack 14 ✓ |
| backed `mvnw verify` | 0 | app.jar 36,026,813 B |
| **合计** | **0** | `ci: all steps passed (exit 0)` |

## 前端 bundle 与首期基线对比（raw bytes）
| chunk | 首期 | 本次 | 变化 |
|---|---|---|---|
| chat `index-*.js` | 254,794 | 252,164 | -1.0% |
| chat `useSize-*.js`（antd） | 252,069 | 252,069 | 0 |
| chat `Table-*.js`（antd，lazy） | 226,258 | 226,258 | 0 |
| chat `Input-*.js` | 129,473 | 129,473 | 0 |
| chat `Form-*.js` | 107,405 | 107,175 | -0.2% |
| `@strato-ui/core` dist（新） | — | 40 KB 打包 / 180 KB 含 d.ts | 基线 `scripts/verify-pack.baseline.json` |

bundle 未恶化（门禁 < +10%）。core 自身 `index.js` 10.2 KB + 14 个组件 chunk ≤ 1 KB，antd / antd-mobile / zod 全部 external。

## 补充验收
- `bash .harness/scripts/e2e-backend.sh` → 47 / 47（== T01 前基线）
- `node .harness/scripts/e2e-frontend.mjs` → 21 / 21
- `pnpm -C .harness run doctor` → 0 errors
