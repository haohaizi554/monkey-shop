#!/bin/bash

IMAGE_DIR="/data/images"
JAR_IMAGES="BOOT-INF/classes/static/images"

if [ -f app.jar ]; then
    echo "【启动脚本】正在初始化默认图片到挂载卷..."
    unzip -o app.jar "$JAR_IMAGES/*" -d /tmp/jar_extract/ > /dev/null 2>&1
    if [ -d "/tmp/jar_extract/$JAR_IMAGES" ]; then
        cp -rn /tmp/jar_extract/$JAR_IMAGES/* "$IMAGE_DIR/" 2>/dev/null || true
        echo "【启动脚本】默认图片初始化完成"
    else
        echo "【启动脚本】JAR 包内未找到默认图片，跳过"
    fi
    rm -rf /tmp/jar_extract/
else
    echo "【启动脚本】未找到 app.jar，跳过图片初始化"
fi

echo "【启动脚本】正在启动 MonkeyShop 应用..."
exec java -jar app.jar
