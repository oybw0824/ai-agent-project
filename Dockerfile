# ============================================================
# 统一 Dockerfile — 支持所有子模块构建
# ============================================================
# 构建示例：
#   docker build --build-arg MODULE=mcp-service --build-arg PORT=8081 -t mcp-service .
#   docker build --build-arg MODULE=agent-server --build-arg PORT=8082 -t agent-server .
# ============================================================

ARG MODULE
ARG PORT

# Maven 构建阶段
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

ARG MODULE

# 先复制父 POM 和子模块 POM（利用 Docker 层缓存）
COPY pom.xml /build/pom.xml
COPY ${MODULE}/pom.xml /build/${MODULE}/pom.xml

# 下载依赖（层缓存优化）
RUN mvn dependency:go-offline -pl ${MODULE} -am -B

# 复制源代码
COPY ${MODULE}/src /build/${MODULE}/src

# 构建
RUN mvn package -pl ${MODULE} -am -DskipTests -B

# 运行阶段
FROM eclipse-temurin:17-jre
WORKDIR /app

ARG MODULE
ARG PORT

COPY --from=build /build/${MODULE}/target/*.jar app.jar
EXPOSE ${PORT}
ENTRYPOINT ["java", "-jar", "app.jar"]
