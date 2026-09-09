# CI Summary — feat-commerce-domains-20260908

日期：2026-09-09 · 命令：`rm -rf fronted/*/dist && pnpm -C .harness run ci`（HEAD `dc5aef5`）

| 段 | 结果 |
|---|---|
| check-contracts | 9 schemas / 26 examples OK |
| check-module-deps | OK（含本 change 新增：spi 不依赖 com.strato；contracts-java 只依赖 spi；domains 互不 import） |
| check-seed | 7 项不变量全部成立（30 单 / 20 品 / 4 售后 / 3 退款） |
| fronted ci | build:core → typecheck → lint（oxlint + check-deps + check-registry 5 类型 + 官方组件映射红线）→ format → verify-examples → build:chat → verify-pack 全过；dist 38 KB（基线 38） |
| backed verify | 11 模块 BUILD SUCCESS（spotless / -Werror / enforcer） |
| **ci 总退出码** | **0** |

补充门禁：`pnpm -C .harness run doctor` 0 errors / 0 warnings（新增 changes/** 密钥形态扫描）；全树 grep LLM 网关主机名 / 密钥前缀 / 模型名 0 命中。

体积：见 `deployment/bundle_size.txt`（app.jar ≈ 36 MB，chat 主 chunk ≈ 257 KB，core index.js ≈ 9.4 KB）。相对上一 change：core dist 40 → 38 KB（删 4 个业务组件）；jar 增两个领域模块。
