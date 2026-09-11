# host-demo — 引入即可用的示例宿主

一个普通 Spring Boot 工程，演示任何 Java 服务接入 spark-rooter 的三步：

1. **加依赖**：`pom.xml` 引入 `com.sparkrooter:spark-rooter-spring-boot-starter`（本仓库先 `./mvnw -q install` 到本地仓）。
2. **声明工具**：任意 `@Service` 的 public 方法加 `@SparkTool`，record 参数加 `@SparkParam`（见 `DemoTools`、以及 `examples/domains/*/infra/*Tools.java`）。
3. **接自己的身份**：实现 `RunContextPropagator` 把登录态带到 spark 工作线程（`DemoContextPropagator`）；**必须**实现 `SessionIdResolver` 绑定登录态（缺 Bean 时 starter 拒绝启动）。本示例在 `application.yml` 显式设 `spark.runtime.demo-session-resolver: true` 放行演示实现（e2e profile 下改用 `DemoSessionIdResolver`），启动会 WARN；生产宿主不要复制这一行。

```bash
cd spark-rooter && ./mvnw -q install          # 平台 + 示例领域进本地仓
cd examples/host-demo && mvn -q package        # 首次在线拉 boot 插件；之后 mvn -q -o package
java -jar target/host-demo.jar --server.port=8080
# 日志：spark-rooter: 14 tools registered from 6 beans；selfcheck 9 项 OK；SessionIdResolver 为 demo 实现 WARN（显式开关放行）
```

## 权限在哪里

- **正面**：`DemoRoleAspect` 方法级切面切 `OrderTools.delete`，`X-Demo-User: guest` 说「删除订单 10005」→ 切面拒绝 → `TOOL_EXECUTION_FAILED`。
- **反面**：`DemoInterceptorOnlyGuard` 只在 MVC 拦截器拒 guest 访问 `/demo/**`。它对 spark 的工具调用**无效**——spark 不经 Controller。宿主权限必须是方法级（AOP / `@PreAuthorize` / 方法体校验）。
