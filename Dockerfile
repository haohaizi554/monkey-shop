# 1. 基础镜像 (使用华为云加速源)
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/openjdk:21-jdk-slim

# 2. 替换 Debian 12 (Bookworm) 默认的官方源为阿里云源 (解决 apt-get 连不上的问题)
RUN sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list.d/debian.sources 2>/dev/null || true && \
    sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list 2>/dev/null || true

# 3. 安装字体库 (验证码 Graphics2D 依赖) + Liberation字体(Arial开源替代) + 时区数据
RUN apt-get update && apt-get install -y \
    fontconfig \
    libfreetype6 \
    fonts-dejavu \
    fonts-liberation \
    tzdata \
    && rm -rf /var/lib/apt/lists/*

# 4. 设置容器时区为上海
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 5. 配置项目工作目录
WORKDIR /app

# 6. 拷贝 jar 包
COPY target/*.jar app.jar

# 7. 拷贝启动脚本
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# 8. 暴露端口
EXPOSE 8888

# 9. 使用启动脚本 (初始化默认图片 + 启动应用)
ENTRYPOINT ["/entrypoint.sh"]
