# 1. 基础镜像
FROM openjdk:21-jdk-slim

# 2. 【核心修改】安装字体库
# slim 镜像不含字体，会导致验证码（Graphics2D）报错
RUN apt-get update && apt-get install -y \
    fontconfig \
    libfreetype6 \
    fonts-dejavu \
    && rm -rf /var/lib/apt/lists/*

# 3. 设置工作目录
WORKDIR /app

# 4. 复制 jar 包
COPY target/*.jar app.jar

# 5. 暴露端口
EXPOSE 8888

# 6. 启动
ENTRYPOINT ["java", "-jar", "app.jar"]