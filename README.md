# 🐵 MonkeyShop | 网购猴子平台

> 基于 **Spring Boot 3** + **Vue 3** 的前后端一体化轻量级电商平台。
>
> A lightweight full-stack e-commerce platform built with Spring Boot 3 and Vue 3.

![Java](https://img.shields.io/badge/Java-17%2B-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-green) ![Vue](https://img.shields.io/badge/Vue.js-3.0-4FC08D) ![MySQL](https://img.shields.io/badge/MySQL-8.0-blue) ![Bootstrap](https://img.shields.io/badge/Bootstrap-5.3-purple)

## 📖 项目简介 (Introduction)

**Monkey Shop** 是一个模拟灵长类动物交易的电商平台（仅供学习演示）。项目采用前后端一体化架构，后端使用 Spring Boot 3 提供 RESTful API，前端使用原生 HTML 结合 Vue 3 (CDN) 进行渲染。

本项目摒弃了复杂的前端工程化构建（如 Webpack/Vite），回归最纯粹的开发体验，同时在后端实现了企业级的业务逻辑，如**订单快照**、**库存并发控制**、**图片垃圾回收**、**数据可视化看板**等。

## 🛠 技术栈 (Tech Stack)

### 后端 (Backend)
*   **核心框架**: Spring Boot 3.2.0
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
*   **购物流程**: 商品详情弹窗、**库存检查**、选择收货地址、提交订单。
*   **订单中心**: 查看历史订单、**发货状态追踪** (含发货时间)、**确认收货**、**申请退货**。
*   **地址管理**: 多地址增删改查、设置默认地址。

### 🛡️ 管理端 (Admin)
*   **数据看板**: ECharts 可视化展示 (GMV/订单量/访问量/退货率)、**双轴趋势图**、多时间维度筛选 (7天/30天/1年/自定义)。
*   **商品管理**: 商品上架/编辑/下架、**图片自动裁剪** (后端居中裁剪适配)、库存管理。
*   **订单管理**: 订单全览 (含**买家/商品快照**)、**一键发货**、**退货审批** (同意/确认收货)、删除订单 (自动回滚库存)。
*   **权限控制**: 独立的管理员表、登录拦截、敏感操作鉴权。

## 🌟 项目亮点 (Highlights)

1.  **订单快照机制 (Order Snapshot)**
    *   下单时将商品信息（名称、价格、图片）和收货地址**物理复制**到订单表。即使后续商品涨价、修改或用户删除了地址，历史订单信息依然准确无误。

2.  **智能图片管理 (Smart Image Cleanup)**
    *   **引用计数**: 删除图片时，自动检查该图片是否被其他商品、用户头像或历史订单快照引用。
    *   **定时任务**: 每日凌晨自动扫描硬盘，清理数据库中不存在的“孤儿文件”，防止磁盘空间浪费。
    *   **自动裁剪**: 管理员上传非正方形图片时，后端自动进行**居中裁剪**，保证前端展示整齐。

3.  **无感交互体验**
    *   全站拒绝原生 `alert/confirm`，封装了全局 **Toast 轻提示** 和 **Bootstrap Modal**。
    *   管理后台采用**原地登录**机制，Session 过期后无需跳转页面即可重新登录。

## ⚡ 快速开始 (Getting Started)

### 1. 环境准备
*   JDK 21 或 17
*   MySQL 8.0
*   IntelliJ IDEA (推荐)

### 2. 数据库配置
在 MySQL 中创建数据库 `monkeyshop`，并执行以下 SQL 初始化表结构：

```sql
CREATE DATABASE monkeyshop;
USE monkeyshop;

-- 管理员表
CREATE TABLE admin (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  password VARCHAR(100) NOT NULL,
  nickname VARCHAR(50) DEFAULT '管理员',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO admin (username, password, nickname) VALUES ('admin', '123456', '超级管理员');

-- 用户表
CREATE TABLE user (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  password VARCHAR(100) NOT NULL,
  phone VARCHAR(20),
  avatar VARCHAR(255),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 商品表
CREATE TABLE monkey (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(255),
  breed VARCHAR(255),
  price DOUBLE,
  stock INT DEFAULT 10,
  description VARCHAR(500),
  image_url VARCHAR(500)
);

-- 订单表
CREATE TABLE orders (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_no VARCHAR(64) NOT NULL UNIQUE,
  user_id BIGINT NOT NULL,
  buyer_name VARCHAR(50),
  buyer_avatar VARCHAR(255),
  product_name VARCHAR(255),
  product_image VARCHAR(255),
  price DOUBLE,
  description VARCHAR(500),
  receiver_name VARCHAR(50),
  receiver_phone VARCHAR(20),
  address_snapshot VARCHAR(500),
  status VARCHAR(20),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 地址表
CREATE TABLE address (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  receiver_name VARCHAR(50),
  phone VARCHAR(20),
  detail_address VARCHAR(255),
  is_default TINYINT(1) DEFAULT 0
);

-- 访问日志表
CREATE TABLE visit_log (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  visit_time DATETIME,
  ip_address VARCHAR(50)
);
```

### 3. 修改配置
打开 `src/main/resources/application.properties`，配置你的数据库账号密码：

```properties
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

## 📂 目录结构 (Directory Structure)

```text
src/main/java/com/example/monkey
├── config          # Web配置 (拦截器注册、静态资源映射)
├── controller      # 控制器 (处理 HTTP 请求)
│   ├── AuthController.java    # 登录/注册/验证码
│   ├── MonkeyController.java  # 商品管理
│   ├── OrderController.java   # 订单/支付/发货/退货
│   ├── StatsController.java   # 数据可视化接口
│   ├── UploadController.java  # 图片上传与自动裁剪
│   └── UserController.java    # 用户个人中心/头像管理
├── entity          # 实体类 (User, Admin, Monkey, Order, Address, VisitLog)
├── interceptor     # 拦截器 (VisitInterceptor 用于统计真实访问量)
├── repository      # 数据仓库 (Spring Data JPA 接口)
├── service         # 业务逻辑 (CaptchaService, ImageCleanupService)
├── task            # 定时任务 (ImageTask 用于清理冗余图片)
└── util            # 工具类 (CaptchaUtil)
```

🤝 贡献与反馈 (Contribution)
本项目为全栈开发学习演示作品，涵盖了电商系统的核心闭环逻辑。
欢迎提交 Issue 或 Pull Request!