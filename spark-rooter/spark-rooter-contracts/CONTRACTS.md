# 契约副本说明

`src/main/resources/contracts/` 下的 `*.schema.json`、`examples/*.example.json` 与 `examples/INDEX` **不是真源**，是
`.harness/contracts/` 的机械副本，由 `pnpm -C .harness run sync-contracts` 生成并提交进 git。

- 改契约：只改 `.harness/contracts/`，再跑 `pnpm -C .harness run sync-contracts`。
- 一致性：`pnpm -C .harness run check-contracts` 末尾会执行 `sync-contracts --check`，副本过期即门禁失败。
- 为什么有副本：让本模块能脱离仓库目录独立 `mvn package` / 发布，不再用 pom 相对路径 `../../.harness/contracts`。

勿手改副本目录下的任何文件。
