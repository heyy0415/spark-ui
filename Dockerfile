# 单镜像 Demo：前端静态资源 + 示例宿主（host-demo），8080 端口，无外部依赖。
#   docker build -t spark-demo . && docker run -p 8080:8080 spark-demo
# 不用仓内 mvnw（它指向内网镜像源），直接用官方 maven 镜像走 Maven Central。

# ---- 1. 前端
FROM node:20-alpine AS ui
RUN corepack enable
WORKDIR /src/spark-ui
COPY spark-ui/package.json spark-ui/pnpm-lock.yaml spark-ui/pnpm-workspace.yaml spark-ui/.npmrc ./
COPY spark-ui/packages/core/package.json packages/core/
COPY spark-ui/apps/chat/package.json apps/chat/
RUN pnpm install --frozen-lockfile
COPY spark-ui/ ./
COPY .harness/contracts/ /src/.harness/contracts/
# 同源部署：API 与页面同一个域，VITE_API_BASE_URL 留空
RUN pnpm run build

# ---- 2. 后端
FROM maven:3.9-eclipse-temurin-21 AS be
WORKDIR /src
COPY spark-rooter/pom.xml spark-rooter/pom.xml
COPY .harness/contracts/ .harness/contracts/
COPY spark-rooter/ spark-rooter/
RUN mvn -q -B -f spark-rooter/pom.xml install -DskipTests
# 前端产物进示例宿主的 classpath:/static，由 Spring Boot 静态资源 + SPA 回退托管
COPY --from=ui /src/spark-ui/apps/chat/dist spark-rooter/examples/host-demo/src/main/resources/static
RUN mvn -q -B -f spark-rooter/examples/host-demo/pom.xml package -DskipTests

# ---- 3. 运行
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=be /src/spark-rooter/examples/host-demo/target/host-demo.jar app.jar
ENV JAVA_TOOL_OPTIONS="-Xmx300m -XX:+UseSerialGC"
ENV SERVER_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
