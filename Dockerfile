# ===== 阶段1: Maven 编译 =====
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# 先拷贝 pom.xml，利用 Docker 缓存层加速依赖下载（依赖不变时无需重新下载）
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 再拷贝源码并编译
COPY src ./src
RUN mvn clean package -DskipTests -B

# ===== 阶段2: 运行时镜像 =====
FROM openjdk:21-jdk-slim

# 替换 Debian 官方源为阿里云源
RUN sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list.d/debian.sources 2>/dev/null || true && \
    sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list 2>/dev/null || true

# 安装字体库 (验证码 Graphics2D 依赖) + Liberation字体(Arial开源替代) + 时区数据
RUN apt-get update && apt-get install -y \
    fontconfig \
    libfreetype6 \
    fonts-dejavu \
    fonts-liberation \
    tzdata \
    && rm -rf /var/lib/apt/lists/*

# 设置容器时区为上海
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 配置项目工作目录
WORKDIR /app

# 从编译阶段拷贝 jar 包（不再依赖宿主机的 target/）
COPY --from=builder /build/target/*.jar app.jar

# 拷贝启动脚本
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# 暴露端口
EXPOSE 8888

# 使用启动脚本 (初始化默认图片 + 启动应用)
ENTRYPOINT ["/entrypoint.sh"]
