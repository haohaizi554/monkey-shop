# 1. 基础镜像：使用 JDK 21
FROM openjdk:21-jdk-slim

# 2. 设置容器内的工作目录
WORKDIR /app

# 3. 把你的 jar 包复制进去
COPY app.jar /app/app.jar

# 5. 暴露端口
EXPOSE 8888

# 6. 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]