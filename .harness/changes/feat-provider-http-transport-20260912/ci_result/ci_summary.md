# CI 验证 — feat-provider-http-transport-20260912

**本仓未配置 GitHub Actions**（用户决策：单人仓库，改造第 4 项已删除工作流并保留三项门禁改进）。故阶段 6 记为 **SKIP**，以**本地实跑**的全量门禁替代，结果如下。

## 门禁：`pnpm -C .harness run ci`

退出码 **0**。九步逐项：

| 步骤 | 退出码 | 守什么 |
|---|---|---|
| check-rename | 0 | 旧项目名残留 |
| check-contracts | 0 | 9 个 Schema + 示例 + 后端副本一致性；invalid 示例必须被拒 |
| check-module-deps | 0 | 后端模块依赖方向、身份红线、领域词汇、provider 薄依赖、产物字节码 ≤ 61、端点路径一致、不得自行重试 |
| check-seed | 0 | 种子数据一致性 |
| check-log-assertions | 0 | e2e 脚本断言的日志串在源码里真实存在 |
| check-shell | 0 | shell 脚本静态检查 |
| spark-ui | 0 | 前端 ci（build / typecheck / test 106 / lint / format / verify-examples / verify-transport / verify-pack） |
| spark-rooter | 0 | 后端 `mvnw install`（含全部单测） |
| host-demo | 0 | 示例宿主离线 `mvn -o package` |

## 产物大小

| 产物 | 大小 | 说明 |
|---|---|---|
| `@spark-ui/core` dist | 248 KB | 三入口（`.` / `./client` / `./react`），`verify-pack` 断言 ≤ baseline × 1.1 |
| `spark-chat` dist | 1.5 MB | — |
| `host-demo.jar` | 37 MB | hub（含 Spring AI、Agent Runtime、Registry、Gateway、四个示例领域） |
| `provider-demo.jar` | **23 MB** | provider（**不含** Spring AI 与任何 hub 模块）——薄依赖的体现，比 hub 小 14 MB |

## 测试与端到端

| 套件 | 结果 |
|---|---|
| 后端单测（`mvnw install` 内） | 全过 |
| 前端单测 | **106 passed** |
| `e2e-backend.sh`（单体形态） | **161 passed, 0 failed** |
| `e2e-provider.sh`（跨服务双进程） | **15 passed, 0 failed** |
| `e2e-frontend.sh` | **7 passed** |
| `deploy-verify.sh` | **12 passed, 0 failed** |

## 规划器口径

未设 `SPARK_LLM_*`，e2e 与 deploy-verify 走示例宿主的假规划器（`planner=fake-e2e`，仅存在于 `e2e` profile）。其产出的计划仍要过 `PlanValidator` 全部校验，故验的是**校验边界 / 编排 / 网关 / 领域 / 前端渲染 / 跨进程传输**。

**模型的意图理解质量不在本轮覆盖范围**，需带真 key 单独验（`dev-workflow.md` 阶段 6 的既有口径）。

## JDK 基线的实证

`provider-demo` 真以 **JDK 17** 编译打包并跑通 15 条跨服务 e2e——不是靠 pom 里的版本号推断。产物字节码实测：

| 模块 | class major | 含义 |
|---|---|---|
| spi / contracts / provider-starter | **61** | JDK 17，provider 闭包可加载 |
| runtime / registry / gateway / web-mvc / hub starter | **65** | JDK 21 |

该性质已加进 `check-module-deps` 的产物级门禁（双向自证：注入一个真的 JDK 21 class 会变红）。
