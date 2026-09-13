# Spark

给 Java 服务装上一条自然语言入口。

Spark 把用户说的话交给模型理解，再从你声明的工具中选出合适的调用，补齐参数，并把结果渲染成表格、卡片或时间线。你只需要在 Spring Service 方法上加 `@SparkTool`，不用自己编写 prompt、意图分类和参数解析。

> 项目仍在快速迭代中，当前主要面向本地运行、二次开发和架构验证。

## 你可以用它做什么

- 用注解声明工具，自动生成 Manifest 和 JSON Schema。
- 在多轮对话中保留实体和列表，理解“第二个”“上一单”这类指代。
- 对删除、退款等有副作用的操作先展示确认卡片，再由后端签发一次性令牌并重新校验。
- 由后端下发 UI Schema，前端只渲染白名单组件（Form、Card、Table、Result、Timeline）。
- 让工具和 hub 同进程运行，也可以通过 HTTP 放在独立 provider 服务中。

## 快速开始

环境要求：JDK 21、Node 20、pnpm 10。模型使用任意 OpenAI 兼容接口；没有模型时可以用 e2e profile 中的确定性假规划器跑验收。

```bash
node .harness/scripts/mvn.mjs -q -B install -DskipTests
cd spark-rooter/examples/host-demo && mvn -q package -DskipTests
java -jar target/host-demo.jar
# 另开终端
pnpm -C spark-ui install
pnpm -C spark-ui run dev
```

打开 <http://localhost:5173>，试试“看看我的订单”。模型配置：

```bash
export SPARK_LLM_BASE_URL=https://your-endpoint/v1
export SPARK_LLM_API_KEY=...
export SPARK_LLM_MODEL=...
```

密钥只应来自环境变量或密钥管理系统。没有模型时服务会明确返回未配置提示，不会偷偷走规则兜底。

## 接入自己的 Spring Boot 服务

添加 `spark-rooter-spring-boot-starter`（当前版本为 `0.1.0-SNAPSHOT`），再在 Service 方法上声明 `@SparkTool`。只有标注 `@SparkParam` 的参数会进入输入 schema；带副作用的方法还应声明 `@SparkRisk`，并提供确认界面和确认重校验逻辑。完整示例见 [`host-demo`](spark-rooter/examples/host-demo/README.md)。

宿主必须提供 `SessionIdResolver` 和 `RunContextPropagator`，权限请放在方法级切面中处理。前端可直接使用 `spark-chat`，或嵌入 [`@spark-ui/core`](spark-ui/packages/core/README.md) 的 `client`、`react` 和完整渲染入口。

## 部署形态

单体模式下工具和 hub 在同一个 Spring Boot 进程；微服务模式下业务服务引入 provider starter，hub 通过 HTTP 调用它。默认状态在内存中，适合单副本；多副本部署时引入 `spark-rooter-redis` 并设置 `spark.storage.type=redis`。Redis 不可用时服务会拒绝请求，而不是退回各自的内存状态。参见 [`provider-demo`](spark-rooter/examples/provider-demo/README.md)。

## 安全边界

模型只能从候选工具中选择，参数必须通过 schema 校验；实体 ID 必须来自用户原话或会话上下文。高风险操作使用绑定 run、action、参数摘要和 session 的一次性确认令牌，确认后由领域逻辑再次检查。所有调用经过 Gateway 的统一校验、策略、幂等、执行、输出校验和审计流程。详细边界见 [`agent-safety.md`](.harness/rules/agent-safety.md)。

## 仓库结构

- [`spark-rooter/`](spark-rooter/)：Java 后端、starter、Redis 支持和示例服务
- [`spark-ui/`](spark-ui/)：React 组件库与聊天应用
- [`.harness/contracts/`](.harness/contracts/)：前后端共享的 JSON Schema
- [`.harness/`](.harness/)：规则、契约检查、e2e 脚本和变更记录

## 开发与验证

```bash
pnpm -C .harness run ci
pnpm -C .harness run doctor
```

需要验证运行时链路时，再按改动范围运行 `.harness/scripts/` 下的 e2e、provider、multi-instance 或部署验证脚本。每项需求的设计、实现和验收记录都在 [`.harness/changes/`](.harness/changes/) 中。

本仓库不自动发布 Maven 或 npm 包。需要发布时，请使用源码和各模块 README 的构建说明，把包发到你自己的仓库。

## 许可

[MIT](LICENSE)
