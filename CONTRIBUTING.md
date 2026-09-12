# 贡献指南

## 环境

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | 后端与示例宿主；`JAVA_HOME` 指向它即可，脚本会自动解析（也支持 jenv 布局） |
| Node | 20（≥ 20.19） | 前端与 harness 脚本 |
| pnpm | ≥ 10 | 前端是 pnpm workspace |
| Maven | 3.9+ 或仓内 `mvnw` | `node .harness/scripts/mvn.mjs` 会优先用 `mvnw` |
| shellcheck | 任意 | 可选；未装时本地门禁跳过该步，CI 上必跑 |

前端 e2e 首次运行需下载 chromium（约 150 MB，一次性）：

```bash
pnpm -C spark-ui run e2e:install
```

## 本地门禁

提 PR 前请让这两条通过：

```bash
pnpm -C .harness run ci       # 契约 / 依赖红线 / 种子 / 日志断言 / shellcheck / 两端构建与单测 / host-demo 离线打包
pnpm -C .harness run doctor   # Harness 自检
```

端到端验收（可选，CI 会跑）：

```bash
SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh     # 后端，约 10 分钟
SPARK_PORT=8091 bash .harness/scripts/e2e-frontend.sh    # 前端，约 1 分钟
SPARK_PORT=8091 bash .harness/scripts/deploy-verify.sh   # 部署验证，约 2 分钟
```

三套脚本都需要端口独占（后端 `SPARK_PORT`，前端 `SPARK_FRONT_PORT` 默认 5199，预览 4173），被占用时会以退出码 2 提前报错而不是连错实例。

**不需要模型密钥**：未设 `SPARK_LLM_*` 时，示例宿主在 `e2e` profile 下装配一个确定性的假规划器（`FakeLlmPlanner`），它产出的计划仍要过 `PlanValidator` 的全部校验，所以验的依然是校验边界、编排、网关、领域实现与前端渲染。模型的意图理解质量不在这套验收覆盖范围内。

## 改动边界

几条机械门禁会拦住的事，动手前先知道：

- **契约先行**：跨端数据结构的真源是 `.harness/contracts/*.schema.json`。先改 Schema 与示例、跑 `check-contracts`，再改两端实现。
- **平台模块不含领域词汇**：`spark-rooter-{spi,contracts,runtime,registry,gateway,web-mvc,spring-boot-starter}` 的源码里不得出现订单 / 商品 / 退款这类词，也不得出现 `userId` / `tenantId` / `Principal`。领域语义只能来自宿主的 `@SparkTool` / `@SparkParam` 注解。
- **前端只渲染白名单组件**：antd / antd-mobile 只能在 `spark-ui/packages/core/src/components/**` 与 `theme/**` 内 import；`apps/chat` 只用 `@spark-ui/core` 的包入口。渲染器里不得有 `eval` / `new Function` / `dangerouslySetInnerHTML` / 动态路径 `import()`。
- **金额与 ID 一律 `string`**，时间用 ISO-8601。
- **高风险工具必须经确认令牌**，令牌由后端签发与校验。

完整规则在 [`.harness/rules/`](.harness/rules/)，其中 `project-structure.md` 的「红线」一节列了触发即需修正的清单。

## 提交

Commit message 用 [Conventional Commits](https://www.conventionalcommits.org/)，描述用中文：

```
test(spark-ui): 前端单元测试基建并接入 ci
fix(runtime): 澄清屏按钮文案不再依赖已删除的动词推导
```

仓库配了 lefthook，提交时会自动跑格式与 message 校验。请不要用 `--no-verify` 绕过。

## 需求流程（可选了解）

本仓库的改动按八阶段流程走（需求分析 → 需求评审 → 编码 → 编码评审 → 推送 → CI → 部署验证 → 用户确认），每次变更的产物留在 `.harness/changes/<change-id>/` 下，含 spec、评审记录、编码报告与验收数据。

外部贡献者不必遵循这套流程——提 Issue 说明问题、或直接提 PR 让 CI 跑绿即可。但如果你想了解某个设计为什么是现在这样，`.harness/changes/` 里通常有答案，包括当时否决了哪些方案。

流程定义见 [`.harness/rules/dev-workflow.md`](.harness/rules/dev-workflow.md)。
