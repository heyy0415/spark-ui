# provider-demo

微服务形态的示例：一个普通 Spring Boot 业务服务，引入 `spark-provider-spring-boot-starter` 后把自己的 `@SparkTool` 方法暴露给远端 hub。

与 [`host-demo`](../host-demo/README.md) 的对照就是本示例的全部意义：

| | host-demo | provider-demo |
|---|---|---|
| 角色 | hub（内核 + 领域同进程） | provider（只有领域） |
| Manifest `protocol` | `in-process` | `http` |
| 含 Agent Runtime / Registry / Gateway | 是 | **否** |
| 含 LLM 客户端 | 是 | **否** |
| JDK | 21 | **17** |

**本工程刻意用 JDK 17 编译**：它是 provider 的下限，企业存量大量停在 17。若共享契约层（`spark-rooter-spi` / `spark-rooter-contracts`）退回 21 字节码，这里会直接编译失败——这就是那条约束的活体检查。

## 跑起来

```bash
# 1. 装平台与本示例的依赖
cd ../../ && ./mvnw -q install -DskipTests && cd examples/provider-demo

# 2. 打包（JDK 17）
mvn -q -o package

# 3. 起 hub（host-demo 当 hub，必须配 provider 密钥）
java -jar ../host-demo/target/host-demo.jar \
  --server.port=8093 --spring.profiles.active=e2e \
  --spark.providers.tokens.inventory-service=demo-token

# 4. 起 provider
java -jar target/provider-demo.jar \
  --server.port=8094 \
  --spark.provider.hub-url=http://127.0.0.1:8093 \
  --spark.provider.base-url=http://127.0.0.1:8094 \
  --spark.provider.token=demo-token
```

provider 启动后日志应出现：

```
spark-provider: scanned inventory.stock.get@1.0.0 from InventoryTools#stock
spark-provider: 1 tools scanned from 1 beans, service=inventory-service
spark-provider: published inventory.stock.get@1.0.0 to hub=http://127.0.0.1:8093
spark-provider: published 1/1 manifests
```

hub 侧确认注册成功、且是 http 形态：

```bash
curl -s localhost:8093/internal/tool-registry/tools/inventory.stock.get/versions | python3 -m json.tool
# protocol 应为 "http"，provider.serviceName 应为 "inventory-service"
```

一键验证全链路（15 项断言）：

```bash
bash ../../../.harness/scripts/e2e-provider.sh
```

## 为什么工具写在本工程里

`InventoryTools` 是本工程自有的领域工具，没有复用 `examples/domains/order-service`。

原因是 host-demo 已在进程内注册了 `order.*`，provider 再推同名 `toolId@version` 会被 Registry 按 **409** 拒绝（已发布版本不可变）。那反而证明了版本冲突保护有效，但测不到跨进程调用——所以这里给一个只存在于 provider 的 `inventory` 领域。

真实微服务本就如此：每个服务暴露自己的领域能力，hub 不持有任何领域实现。

## 接入你自己的服务

三步：

1. **加依赖**（只这一个 spark 坐标）

   ```xml
   <dependency>
     <groupId>com.sparkrooter</groupId>
     <artifactId>spark-provider-spring-boot-starter</artifactId>
     <version>0.1.0-SNAPSHOT</version>
   </dependency>
   ```

2. **标注方法**：与单体形态**完全一样**的 `@SparkTool` / `@SparkParam` / `@SparkRisk`。领域代码不知道自己跑在哪种形态里——这是设计目标，不是巧合。

3. **配四项**（见 `application.yml`）：`hub-url` / `service-name` / `base-url` / `token`。缺任一项拒绝启动。

## 注意事项

- **`token` 缺失即拒绝启动**。安全相关的缺省不能是宽松的。
- **`base-url` 用 `http://` 会 WARN**。工具返回值可能含敏感信息，生产应用 HTTPS 或 mTLS。
- **`RunContextPropagator` 在本形态下不生效**。它返回不透明 `Object`，设计上不可跨进程。要拿身份靠 hub 传来的 `sessionId` 或本服务自己的网关鉴权。provider-starter 检测到该 Bean 存在时会 WARN。
- **不要自行重试**。重试策略由 hub 的 Gateway 按 Manifest 统一决定；provider 自己重试会指数放大（`check-module-deps` 有门禁）。
- **推送失败不阻断启动**。领域服务的本职是业务能力，hub 暂时不可达不该让它起不来。失败记 ERROR 日志。

跨进程的完整安全边界见 [`agent-safety.md` §8](../../../.harness/rules/agent-safety.md)。
