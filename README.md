# 🐵 MonkeyShop | 网购猴子平台

> 基于 **Spring Boot 3** + **Vue 3** 的前后端一体化轻量级电商平台。
>
> A lightweight full-stack e-commerce platform built with Spring Boot 3 and Vue 3.

![Java](https://img.shields.io/badge/Java-17%2B-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-green) ![Vue](https://img.shields.io/badge/Vue.js-3.0-4FC08D) ![MySQL](https://img.shields.io/badge/MySQL-8.0-blue) ![Bootstrap](https://img.shields.io/badge/Bootstrap-5.3-purple)

## 📖 项目简介 (Introduction)

**MonkeyShop** 是一个模拟灵长类动物交易的电商平台（仅供学习演示）。项目采用前后端一体化架构，后端使用 Spring Boot 3 提供 RESTful API，前端使用原生 HTML 结合 Vue 3 (CDN) 进行渲染。

本项目摒弃了复杂的前端工程化构建（如 Webpack/Vite），回归最纯粹的开发体验，同时在后端实现了企业级的业务逻辑，如**订单快照**、**库存并发控制**、**图片垃圾回收**、**Spring Security 安全认证**、**数据可视化看板**等。

## 🛠 技术栈 (Tech Stack)

### 后端 (Backend)
*   **核心框架**: Spring Boot 3.2.0
*   **安全框架**: Spring Security (BCrypt 加密)
*   **数据库**: MySQL 8.0
*   **ORM**: Spring Data JPA
*   **工具**: Maven, Lombok (已移除，采用原生 Getter/Setter)
*   **定时任务**: Spring Scheduled (用于清理冗余图片)

### 前端 (Frontend)
*   **框架**: Vue.js 3 (Composition API, CDN引入)
*   **UI 库**: Bootstrap 5.3
*   **图表**: ECharts 5.4
*   **图标**: Bootstrap Icons

## ✨ 核心功能 (Features)

### 👤 用户端 (User)
*   **账户体系**: 注册/登录 (含图形验证码)、找回密码、个人资料修改、头像上传。
*   **商品浏览**: 首页轮播图、商品列表、**多维筛选** (关键词/价格区间/库存)、**实时搜索**。
*   **购物流程**: 商品详情弹窗、**库存检查**、选择收货地址 (支持临时新增)、提交订单。
*   **订单中心**: 查看历史订单、**发货状态追踪** (含发货时间)、**确认收货**、**申请退货**。
*   **地址管理**: 多地址增删改查、设置默认地址。

### 🛡️ 管理端 (Admin)
*   **数据看板**: ECharts 可视化展示 (GMV/订单量/访问量/退货率)、**双轴趋势图**、多时间维度筛选 (7天/30天/1年/自定义)。
*   **商品管理**: 商品上架/编辑/下架、**图片自动裁剪** (后端居中裁剪适配)、库存管理。
*   **订单管理**: 订单全览 (含**买家/商品快照**)、**一键发货**、**退货审批** (同意/确认收货)、删除订单 (自动回滚库存)。
*   **权限控制**: 独立的管理员表、登录拦截、敏感操作鉴权、**原地登录** (Session过期不跳转)。

## 🌟 项目亮点 (Highlights)

1.  **订单快照机制 (Order Snapshot)**
    *   下单时将商品信息（名称、价格、图片）和收货地址**物理复制**到订单表。即使后续商品涨价、修改或用户删除了地址，历史订单信息依然准确无误。

2.  **智能图片管理 (Smart Image Cleanup)**
    *   **引用计数**: 删除图片时，自动检查该图片是否被其他商品、用户头像或历史订单快照引用。
    *   **定时任务**: 每日凌晨自动扫描硬盘，清理数据库中不存在的“孤儿文件”，防止磁盘空间浪费。
    *   **自动裁剪**: 管理员上传非正方形图片时，后端自动进行**居中裁剪**，保证前端展示整齐。

3.  **高并发库存控制**
    *   使用数据库原子更新 (`UPDATE ... SET stock = stock - 1 WHERE stock > 0`) 防止超卖。

4.  **无感交互体验**
    *   全站拒绝原生 `alert/confirm`，封装了全局 **Toast 轻提示** 和 **Bootstrap Modal**。
    *   管理后台采用**原地登录**机制，Session 过期后无需跳转页面即可重新登录。

## ⚡ 快速开始 (Getting Started)

### 方式一：本地运行 (Local IDE)

### 1. 环境准备
*   JDK 21 或 17（不要用24因为不稳定）
*   MySQL 8.0
*   IntelliJ IDEA (推荐)

### 2. 数据库配置
在 MySQL 中创建数据库 `monkeyshop`，并执行以下 SQL 初始化表结构和数据：

```sql
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS `address`;
CREATE TABLE `address`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '地址ID',
  `user_id` bigint NOT NULL COMMENT '关联的用户ID',
  `receiver_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收货人姓名',
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收货人电话',
  `detail_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '详细地址',
  `is_default` int NULL DEFAULT NULL,
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '收货地址表' ROW_FORMAT = Dynamic;

DROP TABLE IF EXISTS `admin`;
CREATE TABLE `admin`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '管理员',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `username`(`username` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

DROP TABLE IF EXISTS `monkey`;
CREATE TABLE `monkey`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '商品名称',
  `breed` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '品种',
  `price` double NULL DEFAULT NULL COMMENT '价格',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '描述',
  `image_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '图片地址',
  `stock` int NULL DEFAULT 10 COMMENT '库存',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 8 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '猴子商品表' ROW_FORMAT = Dynamic;
INSERT INTO `monkey` VALUES (1, '悟空', '金丝猴', 9998, '性格活泼，会耍金箍棒，西游记联名款', '/images/product/c9f11d47-adaa-4774-a05a-f2ef6e12f453.jpg', 10);
INSERT INTO `monkey` VALUES (2, '杰克', '卷尾猴', 5000, '加勒比海盗同款，非常聪明，适合看家', '/images/product/33ba344f-c179-4d9e-b4e3-f94811f374d6.jpg', 10);
INSERT INTO `monkey` VALUES (3, '金刚', '大猩猩', 12000, '体型巨大，虽然不是猴子但很强壮，安全感爆棚', '/images/product/fb59ce47-9806-442c-861e-8d63a46615cd.jpg', 10);
INSERT INTO `monkey` VALUES (4, '莫莫', '狐猴', 3000, '马达加斯加特产，喜欢跳舞，眼神清澈愚蠢', '/images/product/5de3868f-4de4-452b-8bbc-bc9a0eb85f94.jpg', 10);
INSERT INTO `monkey` VALUES (5, '大草猪', '肥猪', 1, '这是一头大笨猪', '/images/product/57736ad2-eb28-4924-88b5-4e496e6bd68e.jpg', 10);
INSERT INTO `monkey` VALUES (6, '大笨蛋', '超级大笨蛋', 888, '这是一个超级大笨蛋', '/images/default_product.png', 100);
INSERT INTO `monkey` VALUES (7, '哈哈哈', '', 688, '', '/images/product/1203392d-1efc-4d5b-8802-d1a1cf8aed20.png', 10);

DROP TABLE IF EXISTS `orders`;
CREATE TABLE `orders`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '订单编号(对外展示)',
  `user_id` bigint NOT NULL COMMENT '买家ID',
  `product_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `product_image` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `price` double NOT NULL,
  `receiver_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `receiver_phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `address_snapshot` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '完整的收货地址',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '已支付' COMMENT '状态: 待支付/已支付/已发货',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '商品描述快照',
  `buyer_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '买家昵称快照',
  `buyer_avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '买家头像快照',
  `shipping_time` datetime NULL DEFAULT NULL COMMENT '发货时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `order_no`(`order_no` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '订单表' ROW_FORMAT = Dynamic;
INSERT INTO `orders` VALUES (10, '20251224012838152', 2, '杰克', '/images/product/33ba344f-c179-4d9e-b4e3-f94811f374d6.jpg', 5000, '你猜我是谁', '18888888888', '南天门', '已退款', '2025-12-24 01:28:38', '加勒比海盗同款，非常聪明，适合看家', '佳怡', '/images/avatar/4e837b67-0a29-4cc4-9c0f-ea29d6d27edf.png', '2025-12-24 01:30:36');

DROP TABLE IF EXISTS `user`;
CREATE TABLE `user`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `username`(`username` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

DROP TABLE IF EXISTS `visit_log`;
CREATE TABLE `visit_log`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `visit_time` datetime NOT NULL COMMENT '访问时间',
  `ip_address` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '访客IP (可选)',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_visit_time`(`visit_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 101 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;
INSERT INTO `visit_log` VALUES (1, '2025-12-22 19:22:07', '127.0.0.1');
INSERT INTO `visit_log` VALUES (2, '2025-12-22 19:22:08', '0:0:0:0:0:0:0:1');
INSERT INTO `visit_log` VALUES (3, '2025-12-22 19:22:08', '127.0.0.1');
INSERT INTO `visit_log` VALUES (4, '2025-12-22 19:22:09', '127.0.0.1');
INSERT INTO `visit_log` VALUES (5, '2025-12-22 19:22:09', '0:0:0:0:0:0:0:1');
INSERT INTO `visit_log` VALUES (6, '2025-12-22 19:22:10', '127.0.0.1');

SET FOREIGN_KEY_CHECKS = 1;
```

### 3. 修改配置
打开 `src/main/resources/application.properties`，配置你的数据库账号密码：

```properties
# 端口配置
server.port=8888

# 数据库连接配置
spring.datasource.url=jdbc:mysql://localhost:3306/monkeyshop?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8
spring.datasource.username=root
spring.datasource.password=你的数据库密码

# JPA 配置
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect

# 文件上传限制 (支持大图自动裁剪)
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=10MB
```

### 4. 运行项目
1.  找到启动类 `src/main/java/com/example/monkey/MonkeyShopApplication.java`。
2.  运行 `main` 方法启动 Spring Boot。
3.  后端会自动在项目根目录下创建 `src/main/resources/static/images/` 文件夹用于存储上传的图片（头像/商品图）。
4.  打开浏览器访问：
    *   **用户商城首页**: [http://localhost:8888](http://localhost:8081) (端口取决于你的配置)
    *   **管理员后台**: [http://localhost:8888/admin.html](http://localhost:8081/admin.html) (需先登录管理员账号)

### 方式二：🐳 Docker 部署 (Docker Deployment)

## 1. 创建 Docker 配置文件（项目中有）
1.  在项目根目录下创建文件 Dockerfile：
```Dockerfile
FROM openjdk:21-jdk-slim
WORKDIR /app
COPY target/*.jar /app/app.jar
# 暴露应用端口
EXPOSE 8888
ENTRYPOINT ["java", "-jar", "app.jar"]
```
2.  在项目根目录下创建文件 docker-compose.yml：
```YAML
services:
  # --- MySQL 容器 ---
  mysql:
    image: mysql:8.0
    container_name: monkey-mysql
    environment:
      MYSQL_ROOT_PASSWORD: rootpassword
      MYSQL_DATABASE: monkeyshop
      MYSQL_USER: monkeyuser       # 应用专用账户
      MYSQL_PASSWORD: monkeypass   # 应用专用密码
    ports:
      - "3307:3306"                # 映射宿主机 3307 端口 -> 容器 3306
    volumes:
      - mysql_data:/var/lib/mysql
    command: --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      timeout: 20s
      retries: 10

  # --- Java 应用容器 ---
  myshop:
    build: .
    container_name: monkey-app
    ports:
      - "8888:8888"
    depends_on:
      mysql:
        condition: service_healthy
    environment:
      # 覆盖 application.properties 中的配置
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/monkeyshop?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true
      SPRING_DATASOURCE_USERNAME: monkeyuser
      SPRING_DATASOURCE_PASSWORD: monkeypass
      SERVER_PORT: 8888
    volumes:
      # 挂载图片上传目录：宿主机 ./uploads -> 容器 /data/images
      - ./uploads:/data/images

volumes:
  mysql_data:
```
## 2. 打包与启动
在项目根目录执行以下命令：
```Bash
# Docker 一键编排启动
docker-compose up -d --build
```
## 3. ⚠️ 数据库连接注意事项
   Docker 启动的是一个全新的 MySQL 环境。
- **自动建表**：项目配置了 spring.jpa.hibernate.ddl-auto=update，容器启动后应用会自动在 Docker 数据库中创建所需的表结构。
- **手动管理数据库**：如果你需要使用 Navicat/DBeaver 连接 Docker 中的数据库（例如手动插入初始数据），请使用以下信息连接：
    - **主机**：localhost
    - **端口**：3307（注意：不是 3306，3306 已经被映射到宿主机的 3307）
    - **用户名**：monkeyuser
    - **密码**：monkeypass
    - **数据库名**：monkeyshop

启动成功后，访问 http://localhost:8888 即可。上传的图片将保存在项目根目录下的 uploads 文件夹中。

### 4. 停止与关闭 Docker 服务
   在项目根目录（docker-compose.yml 所在目录）执行以下命令：
```Bash
# 1. 停止并删除容器、网络（保留数据库数据和上传的图片）
docker-compose down

# 2. 如果想彻底清理（包括删除 MySQL 数据卷和 uploads 文件夹里的图片，谨慎使用！）
docker-compose down -v
# 注意：-v 参数会删除 volumes 中定义的 mysql_data 卷，数据库会重置为初始状态
```

- **docker-compose down**：仅停止并删除容器和网络，**数据库数据和 uploads 文件夹内容会保留**，下次 up 还能继续使用。
- **docker-compose down -v**：彻底删除，包括数据库卷，适合想完全重置环境时使用。
- 关闭后，您可以随时再次执行 **docker-compose up -d --build** 重新启动服务。

## 📂 目录结构 (Directory Structure)

```text
src/main/java/com/example/monkey
├── config          # 配置类
│   ├── DataInitializer.java   # 启动加载器 (自动创建默认管理员)
│   ├── SecurityConfig.java    # 安全配置 (BCrypt加密、CSRF禁用)
│   └── WebConfig.java         # 拦截器注册、本地图片资源映射
├── controller      # 控制器 (Web层，只负责路由分发，业务移交Service)
│   ├── AddressController.java # 收货地址管理
│   ├── AuthController.java    # 登录、注册、找回密码
│   ├── MonkeyController.java  # 商品管理接口
│   ├── OrderController.java   # 订单流程接口
│   ├── StatsController.java   # 数据可视化看板接口
│   ├── UploadController.java  # 图片上传入口
│   └── UserController.java    # 个人中心、头像管理、权限检查
├── entity          # 实体类 (数据库表映射)
│   ├── Address.java           # 收货地址
│   ├── Admin.java             # 管理员表
│   ├── Monkey.java            # 商品表 (含库存)
│   ├── Order.java             # 订单表 (含商品/买家快照、发货时间)
│   ├── User.java              # 普通用户表
│   └── VisitLog.java          # 访问日志表
├── interceptor     # 拦截器
│   └── VisitInterceptor.java  # 拦截页面请求，统计真实访问量
├── repository      # 数据仓库 (Spring Data JPA 接口)
│   ├── AddressRepository.java
│   ├── AdminRepository.java
│   ├── MonkeyRepository.java  # 含库存原子扣减 SQL
│   ├── OrderRepository.java
│   ├── UserRepository.java
│   └── VisitLogRepository.java
├── service         # 业务逻辑层 (核心业务、事务控制 @Transactional)
│   ├── CaptchaService.java    # 验证码生成与校验逻辑
│   ├── FileService.java       # 文件上传、智能裁剪、路径处理
│   ├── ImageCleanupService.java # 图片垃圾回收 (引用计数检查)
│   ├── OrderService.java      # 订单流转、库存回滚、快照组装
│   └── UserService.java       # 用户认证、加密、资料更新
├── task            # 定时任务
│   └── ImageTask.java         # 定时扫描硬盘，清理未引用的孤儿图片
└── util            # 工具类
    └── CaptchaUtil.java       # 验证码绘图工具
```

🤝 贡献与反馈 (Contribution)
本项目为全栈开发学习演示作品，涵盖了电商系统的核心闭环逻辑。
欢迎提交 Issue 或 Pull Request!