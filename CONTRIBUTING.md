# 参与贡献

欢迎 issue 和 PR。这个仓库有一套自己的工作流，提交前花两分钟看一下能省不少来回。

## 跑起来

需要 JDK 21、Node 20、pnpm 10、Maven 3.9+（或用仓内 `spark-rooter/mvnw`）。Redis 可选——没有它 Redis 相关测试会跳过，不会失败。

```bash
pnpm -C .harness install
pnpm -C spark-ui install
pnpm -C .harness run ci        # 全部门禁，退出码 0 才算过
```

## 提交前必须过的门禁

`pnpm -C .harness run ci` 一条命令跑完 9 步：改名一致性、契约校验、模块依赖方向、种子数据、日志断言、shellcheck、前端 ci（typecheck / 单测 / lint / format / 构建 / 打包校验）、后端 install（含单测 / spotless）、host-demo 离线打包。

PR 上 GitHub Actions 会跑同一条命令。本地过了 CI 基本就过。

端到端脚本不在 CI 里，改了编排 / 网关 / 前端交互请本地跑对应的：

```bash
SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh          # 后端全链路
SPARK_PORT=8091 bash .harness/scripts/e2e-frontend.sh         # Playwright
SPARK_PORT=8091 bash .harness/scripts/deploy-verify.sh        # 部署验证
bash .harness/scripts/e2e-provider.sh                          # hub + provider 双进程
bash .harness/scripts/e2e-multi-instance.sh                    # 两个 hub 共享 Redis
```

## 几条红线

改动碰到这些会被门禁直接拒掉，提前知道省事：

- 跨端数据结构必须先有 `.harness/contracts/*.schema.json`，前端 Zod 和后端 record 都是它的投影
- 平台模块（spi / contracts / runtime / registry / gateway / web-mvc）不能出现业务词汇（`order` / `refund` 这类只在 `examples/`）、不能出现 `userId` / `tenantId` / `Principal`、不能用 `@Component` 等扫描注解
- 后端 `domain/` 包不依赖 Spring 和 Jackson
- 前端 `apps/chat` 不直接 import antd；`@spark-ui/core/client` 不 import react
- 工具实现不能自己重试
- 金额、ID、Token 一律字符串

全文在 [`.harness/rules/`](.harness/rules/)。

## 改动的记录方式

每个需求走 8 个阶段（需求分析 → 需求评审 → 编码 → 编码评审 → 推送 → CI → 部署验证 → 用户确认），产物在 `.harness/changes/<change-id>/`。外部贡献者不必走全套，但 PR 描述里请写清：改了什么、为什么、怎么验证的。改契约的 PR 请附上契约变更记录（`contracts.md` 有格式）。

## 发现 Agent 或工具链的错误

如果你发现某个门禁本该拦住却没拦住，或者文档和代码不一致，这类问题比功能 bug 更值得报——修一次能防一类。开 issue 时标一下「harness」。

## 许可

提交即表示你同意以 [MIT](LICENSE) 许可贡献。
