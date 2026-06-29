# Ubuntu 虚拟机部署 MonkeyShop 完整指南

> 从零开始，在 Ubuntu 虚拟机上克隆项目、安装 Docker、一键部署
> 采用多阶段构建，宿主机无需安装 JDK/Maven

---

## 第一阶段：从 GitHub 克隆项目

### 1.1 安装 Git（如果没有）

```bash
sudo apt update
sudo apt install -y git
```

### 1.2 配置 Git（首次使用需要）

```bash
git config --global user.name "你的用户名"
git config --global user.email "你的邮箱"
```

### 1.3 克隆项目

```bash
cd ~
git clone https://github.com/haohaizi554/monkey-shop.git
cd monkey-shop
```

### 1.4 验证项目文件

```bash
ls -la
```

你应该能看到以下关键文件：

```
Dockerfile
docker-compose.yml
entrypoint.sh
pom.xml
.env
src/
```

---

## 第二阶段：安装 Docker

### 2.1 卸载旧版本（如果有）

```bash
sudo apt remove -y docker docker-engine docker.io containerd runc
```

### 2.2 安装依赖

```bash
sudo apt update
sudo apt install -y ca-certificates curl gnupg
```

### 2.3 添加 Docker 官方 GPG 密钥

```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
```

### 2.4 添加 Docker 仓库

```bash
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
```

### 2.5 安装 Docker Engine

```bash
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

### 2.6 验证安装

```bash
docker --version
docker compose version
```

应该输出类似：

```
Docker version 27.x.x
Docker Compose version v2.x.x
```

### 2.7 免 sudo 使用 Docker（重要！）

```bash
sudo usermod -aG docker $USER
newgrp docker
```

> 如果不执行这一步，后续所有 docker 命令都需要加 sudo。
> 执行后需要**注销再登录**才能永久生效，`newgrp docker` 是临时生效。

### 2.8 测试 Docker

```bash
docker run hello-world
```

看到 `Hello from Docker!` 说明安装成功。

---

## 第三阶段：配置环境变量

### 3.1 检查 .env 文件

项目已自带 `.env` 文件，检查一下内容：

```bash
cat .env
```

应该包含：

```
MYSQL_ROOT_PASSWORD=<root-db-password>
MYSQL_USER=monkeyuser
MYSQL_PASSWORD=<app-db-password>
```

### 3.2 修改密码（建议）

```bash
nano .env
```

将密码改为你自己的强密码，例如：

```
MYSQL_ROOT_PASSWORD=MyStr0ng!R00tP@ss
MYSQL_USER=monkeyuser
MYSQL_PASSWORD=MyStr0ng!AppP@ss
```

> 按 `Ctrl+O` 保存，`Ctrl+X` 退出。

---

## 第四阶段：一键部署

> 项目采用 Docker 多阶段构建，编译在 Docker 内自动完成，**宿主机无需安装 JDK 和 Maven**。

### 4.1 确保 entrypoint.sh 有执行权限

```bash
chmod +x entrypoint.sh
```

### 4.2 构建并启动

```bash
docker compose up -d --build
```

这个命令会：
1. **阶段1 (builder)**：在 Docker 内拉取 Maven 镜像，自动编译打包 JAR
2. **阶段2 (runtime)**：构建运行时镜像，拷贝 JAR，安装字体库
3. 拉取 MySQL 8.0 镜像
4. 启动 MySQL 容器并等待健康检查通过
5. 启动 Java 应用容器（自动初始化默认图片）

> 首次构建需要下载基础镜像 + Maven 依赖，可能需要 5-10 分钟。
> 后续构建利用 Docker 缓存层，只重新编译变更的代码，速度很快。

### 4.3 查看启动日志

```bash
docker compose logs -f myshop
```

等待看到类似输出：

```
【启动脚本】正在初始化默认图片到挂载卷...
【启动脚本】默认图片初始化完成
【启动脚本】正在启动 MonkeyShop 应用...
... Started MonkeyShopApplication in x.xx seconds
```

按 `Ctrl+C` 退出日志查看。

### 4.4 检查容器状态

```bash
docker compose ps
```

应该看到两个容器都是 `running` 状态：

```
NAME            STATUS
monkey-app      Up ...
monkey-mysql    Up (healthy) ...
```

---

## 第五阶段：访问验证

### 5.1 本机访问

在 Ubuntu 虚拟机内：

```bash
curl -I http://localhost:8888
```

应该返回 `HTTP/1.1 200`。

### 5.2 浏览器访问

- 如果 Ubuntu 有图形界面，直接打开浏览器访问 `http://localhost:8888`
- 如果是宿主机 Windows 访问虚拟机，需要知道虚拟机 IP：

```bash
ip addr show | grep "inet " | grep -v 127.0.0.1
```

然后在 Windows 浏览器访问 `http://虚拟机IP:8888`

### 5.3 验证功能清单

| 验证项 | 操作 | 预期结果 |
|--------|------|----------|
| 首页背景图 | 访问 `http://IP:8888` | 看到猴子背景图，不是黑屏 |
| 验证码 | 点击注册，看验证码图片 | 显示4位字母数字验证码 |
| 注册 | 填写信息注册 | 注册成功 |
| 登录 | 用注册的账号登录 | 跳转到商城页 |
| 商品图 | 查看商城商品卡片 | 图片正常显示 |
| 轮播图 | 商城页顶部轮播 | 3张轮播图正常切换 |
| 管理后台 | 用外部化管理员密码登录 | 进入后台，数据看板正常 |
| 上传图片 | 后台添加商品上传图片 | 图片上传成功并显示 |

---

## 常用运维命令

### 查看日志

```bash
# 查看应用日志
docker compose logs -f myshop

# 查看 MySQL 日志
docker compose logs -f mysql

# 查看最近 100 行
docker compose logs --tail 100 myshop
```

### 重启服务

```bash
# 重启应用
docker compose restart myshop

# 重启全部
docker compose restart
```

### 更新代码后重新部署

```bash
cd ~/monkey-shop
git pull origin main
docker compose up -d --build
```

> 多阶段构建确保每次 `--build` 都会重新编译最新代码，不会出现"忘了打包就部署旧代码"的问题。

> ⚠️ 如果更新涉及 MySQL 时区或字符集变更，需要清除旧数据卷重新初始化：
> ```bash
> docker compose down -v
> docker compose up -d --build
> ```
> 这会删除所有数据库数据，请提前备份。

### 停止服务

```bash
docker compose down
```

### 完全清理（包括数据卷，慎用！）

```bash
docker compose down -v
```

> ⚠️ `-v` 会删除 MySQL 数据卷，所有数据将丢失！

### 进入容器调试

```bash
# 进入应用容器
docker exec -it monkey-app bash

# 进入 MySQL
docker exec -it monkey-mysql mysql -umonkeyuser -p"$MYSQL_PASSWORD" monkeyshop
```

### 查看上传的图片

```bash
ls -la ~/monkey-shop/uploads/avatar/
ls -la ~/monkey-shop/uploads/product/
```

---

## 常见问题排查

### Q1: `docker compose` 命令找不到

旧版 Docker 使用 `docker-compose`（带横杠），新版使用 `docker compose`（空格）。如果提示找不到：

```bash
# 检查版本
docker compose version

# 如果确实没有，安装插件
sudo apt install docker-compose-plugin
```

### Q2: 权限被拒绝 `permission denied`

```bash
# 将当前用户加入 docker 组
sudo usermod -aG docker $USER
newgrp docker

# 或者注销重新登录
```

### Q3: MySQL 启动失败

```bash
# 查看 MySQL 日志
docker compose logs mysql

# 常见原因：数据卷损坏，重置
docker compose down -v
docker compose up -d --build
```

### Q4: 应用启动后立即退出

```bash
# 查看退出日志
docker compose logs myshop

# 重新构建（多阶段构建会自动编译）
docker compose build --no-cache myshop
docker compose up -d
```

### Q5: 宿主机 Windows 无法访问虚拟机

1. 确认虚拟机网络模式是**桥接模式**或**NAT模式+端口转发**
2. 桥接模式：直接用虚拟机 IP 访问
3. NAT 模式：需要在 VirtualBox/VMware 中添加端口转发规则：
   - 主机端口：8888 → 虚拟机端口：8888
   - 主机端口：3307 → 虚拟机端口：3306

### Q6: 验证码显示空白

```bash
# 进入容器检查字体
docker exec -it monkey-app fc-list | head

# 应该能看到 DejaVu 和 Liberation 字体
# 如果没有，重新构建镜像
docker compose build --no-cache myshop
docker compose up -d
```

### Q7: 构建很慢 / Maven 依赖下载失败

首次构建需要下载大量 Maven 依赖，如果网络不好可以配置 Maven 国内镜像。在 Dockerfile 的 builder 阶段添加：

```dockerfile
# 在 COPY pom.xml 之前添加
COPY settings.xml /root/.m2/settings.xml
```

创建 `settings.xml` 配置阿里云 Maven 仓库。

---

## 部署流程总结

```
Ubuntu 虚拟机
    │
    ├── 1. git clone 项目
    │
    ├── 2. 安装 Docker（宿主机无需 JDK/Maven）
    │
    ├── 3. 配置 .env 密码
    │
    ├── 4. chmod +x entrypoint.sh
    │
    ├── 5. docker compose up -d --build
    │       │
    │       ├── Docker 阶段1: Maven 自动编译 JAR
    │       ├── Docker 阶段2: 构建运行时镜像
    │       │
    │       ├── MySQL 容器启动 → 健康检查通过
    │       │
    │       └── Java 容器启动
    │           ├── entrypoint.sh 初始化默认图片
    │           └── java -jar app.jar
    │
    └── 6. 浏览器访问 http://IP:8888 ✅
```
