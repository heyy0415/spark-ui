# CI Summary — feat-agent-tool-platform-20260903（阶段 6）

日期：2026-09-04 · 命令：`pnpm -C .harness run ci`（本地执行，仓库尚无远端 CI）· 提交：`a6c7a03`

| 步骤 | 退出码 | 摘要 |
|---|---|---|
| check-contracts | 0 | 9 schema / 20 example，Ajv strict |
| check-module-deps | 0 | pom 方向 + domain 包无框架 + 三模块互不依赖对方 infra/domain |
| fronted ci（typecheck / oxlint / check-deps / check-registry / prettier / build） | 0 | 见下 bundle |
| backed `mvnw verify`（spotless / enforcer / `-Xlint:all -Werror`） | 0 | app.jar 36,026,316 B（34.4 MB） |
| **合计** | **0** | `ci: all steps passed (exit 0)` |

## 前端 bundle（gzip，前 5 个 chunk）
| chunk | raw | gzip |
|---|---|---|
| index（应用入口 + react + antd 基础） | 254.79 kB | 79.13 kB |
| useSize（antd 公共） | 252.06 kB | 84.46 kB |
| Table（antd Table，按需 lazy） | 226.25 kB | 68.63 kB |
| Input | 129.47 kB | 41.15 kB |
| Form（antd） | 107.40 kB | 35.40 kB |

本 change 为首个版本，无 baseline；此表作为后续 change 的 baseline。全部 chunk gzip ≤ 85 kB，单 chunk < 250 kB gzip 门禁满足。7 个白名单组件按端型 `React.lazy` 拆分，首屏不加载 Table / Form。

## 补充验收（阶段 4 已记录，此处引用）
- `bash .harness/scripts/e2e-backend.sh` → 47 passed / 0 failed
- `node .harness/scripts/e2e-frontend.mjs` → 21 passed / 0 failed
- `pnpm -C .harness run doctor` → 0 errors
