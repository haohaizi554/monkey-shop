# MonkeyShop 分阶段推送清单

日期：2026-07-23

本清单记录从基础节点 `6a7a7ce4` 到最终代码节点 `480f4334` 的 81 个增量提交。前 69 个提交形成初始 UI、业务、安全与质量交付，后续 12 个提交按批次 7 至批次 11 独立补齐验收确定性、MicroK8s PII、CI、安全依赖、原生可观测/支撑服务和运行态缺陷。其后另有 1 个验收证据提交和 2 个 GitOps 发布提交，因此 `6079dc62` 发布节点从基础节点起共包含 84 个提交；本验收关闭记录紧随该发布节点。

2026-07-23（Asia/Shanghai）完成发布验证时，远端架构升级分支为验收证据节点 `aa6b2d92`，`main` 的 GitOps 发布节点为 `6079dc62`。批次 1 至批次 11、最终证据提交和主分支发布的必需 GitHub Actions 均已取得真实绿色结果。

## 基础节点

基础节点 `6a7a7ce4` 已包含新版设计 Token、吉祥物动作资源、状态与反馈组件、消费者应用壳、认证、发现与商品、购物车与支付、履约售后、会员与账户页面。

- [x] 新设计系统与三类应用壳已形成交付基线。
- [x] 消费端主要交易路径已形成交付基线。
- [x] 登录、注册、重置密码与限流体验已形成交付基线。
- [x] 消费端路由、对话框和 E2E fixture 已完成基线回归。

## 批次 1：后台工作台与平台完整性

提交范围：`f17b72ba^..f06e12bb`，共 18 个提交，目标节点 `f06e12bb`。

- [x] 重建后台运营壳、顶栏、侧栏和业务导航。
- [x] 重建商品、订单、库存、营销、风控、仪表盘和租户工作台。
- [x] 增加订单、支付、退款、物流和会员运营界面。
- [x] 为库存、营销、风控、仪表盘和租户操作补齐并发与陈旧响应保护。
- [x] 收紧租户上下文、真实导出和跨租户 PII 边界。
- [x] 完成对应组件、交互、授权与隔离测试。
- [x] 已确认目标节点 `f06e12bb` 是远端 `d6a0b99d` 的祖先。

## 批次 2：视觉门与本地运行态

提交范围：`93d9c701^..60ed8057`，共 5 个提交，目标节点 `60ed8057`。

- [x] 锁定全路由桌面、移动端、亮色和暗色视觉基线。
- [x] 修复支付回调后的订单状态推进与本地管理员初始化状态。
- [x] 提供 MySQL、Redis 兼容服务、Spring Boot 和 Vite 的原生 Windows 启停与验收脚本。
- [x] Lighthouse 对页面运行错误实行失败门禁。
- [x] 修复后台 SPA 深链接转发。
- [x] 已确认目标节点 `60ed8057` 是远端 `d6a0b99d` 的祖先。

## 批次 3：交易、安全与分布式一致性

提交范围：`b3bd7325^..86577488`，共 13 个提交，目标节点 `86577488`。

- [x] 校验可信代理 CIDR，并把 Redis 限流改为原子执行。
- [x] 对齐导出指标、仪表盘与可观测性契约。
- [x] 校验 JWT issuer、audience 和 `nbf`。
- [x] 分离订单可见性和生命周期转换。
- [x] 原子化库存认领、补偿与结算生命周期。
- [x] 租约化 Snowflake 节点，避免多实例 ID 冲突。
- [x] 禁用不安全的直接上传入口。
- [x] 事务化支付回调重放保护，并保留直接购买 SKU/数量语义。
- [x] 已确认目标节点 `86577488` 是远端 `d6a0b99d` 的祖先。

## 批次 4：接口契约、分页与最后一轮 UI 收口

提交范围：`62dbf68f^..1e9f26ef`，共 23 个提交，目标节点 `1e9f26ef`。

- [x] 建立规范订单行契约与支付跳转消费逻辑。
- [x] 保证 Snowflake 标识在 JSON 和全部交易链路中无精度损失。
- [x] 将商品、订单、仪表盘、地址和结算选择改为数据库分页/定向查询。
- [x] 隔离认证重试状态，安全记录异常 JWT 解析，并固定生产制品引用。
- [x] 通过授权流式端点交付租户导出文件。
- [x] 强化品牌识别、购物车、账户概览和 404 恢复页。
- [x] 删除浏览器端过期的全量列表客户端和内部边界客户端。
- [x] 补齐对应单元、契约、可访问性和路由元数据测试。
- [x] 已确认目标节点 `1e9f26ef` 是远端 `d6a0b99d` 的祖先。

## 批次 5：质量、安全扫描与 PII 最终验收

提交范围：`bf0a2f2b^..97076b50`，共 9 个提交，目标节点 `97076b50`。

- [x] 强化请求、流式响应和 API 边界。
- [x] 恢复并提高 mutation、contract、JaCoCo、SpotBugs 与依赖门禁。
- [x] 将 WS1 当前树扫描限定为 Git 可提交候选，排除忽略的本机运行数据。
- [x] 仅对 Chromium `ERR_NETWORK_CHANGED` 增加有限重试并覆盖测试。
- [x] 完成本地 PII 备份、回填、密文审计和默认拒绝明文读取。
- [x] 扫描全部共享 CSS 和 Vue 样式块，禁止在语义 Token 注册表外写入原始颜色。
- [x] 更新需求逐项验收证据。
- [x] 已确认目标节点 `97076b50` 是远端 `d6a0b99d` 的祖先。

## 批次 6：UI 与账户安全补充

提交范围：`d6a0b99d^..d6a0b99d`，共 1 个提交，目标节点 `d6a0b99d`，提交主题为“优化UI”。

- [x] 增加地址编辑与旧密码校验流程。
- [x] 后端认证响应补充密码过期状态。
- [x] 增加猴类图片下载脚本及相关 UI、账户回归测试。
- [x] `git fetch origin` 后确认本地与远端均为 `d6a0b99d`。

## 批次 7：交付后审查补丁

- [x] 前端统一处理 `passwordChangeRequired` 与 `passwordExpired`，密码过期用户不能绕过安全修改流程。
- [x] 将猴类图片下载改为仓库相对路径，校验真实 JPEG，生成许可归属与 SHA-256 清单，并增加离线回归测试。
- [x] 让本地安全探针规避 Redis 中持久化的历史合成封禁 IP，同时保留 403、429 与 ProblemDetail 断言。
- [x] 为当前架构升级分支补齐 5 个 GitHub Actions 工作流触发规则，并把下载器离线测试纳入 CI。
- [x] 增加静态仓库契约、WS8 脚本契约和总验收入口覆盖。
- [x] 修复账户浏览器用例的非唯一等待条件，并为首屏 eager 吉祥物补充高资源优先级。
- [x] 完成审查补丁的最终全量本地门禁。
- [x] 提交审查补丁为 `9866c1db`。
- [x] 审查补丁及后续 CI 修复已进入远端；包含该节点的 `93e3d0c0` 在 CI/CD、WS1 Security Gate 与 CodeQL 三项工作流均为绿色。

## 批次 8：总验收门禁确定性修复

- [x] WS5 在保留既有代理排除项的同时强制加入 `127.0.0.1`、`localhost` 与 `::1`，避免 Clash 代理阻断 Playwright 本地服务探测。
- [x] WS7 在预期的全零镜像摘要负例后清除原生失败退出码，使聚合 PowerShell 调用方获得真实成功状态。
- [x] 总验收在无 SSH 目标时调用原生 Windows 本地 PII 审计，不再隐式依赖 Docker；远端 SSH 分支仍执行容器/集群侧审计。
- [x] 增加 3 个对应仓库契约回归；全量 Maven 报告为 1,716 个测试、0 失败、0 错误、41 跳过。
- [x] 在 Clash 开启且未预设 `NO_PROXY` 的条件下执行无人工跳过的本地总入口，943.9 秒后以退出码 0 完成；Lighthouse 为 96/100/100/100，LCP 1,279 ms，本地 PII 为 349/349 密文且 0 个盲索引不一致。
- [x] 批次 7 与批次 8 已进入远端；CI 修复节点 `93e3d0c0` 的三项必需工作流均为绿色。

## 批次 9：MicroK8s PII 运行态加固

- [x] 删除直接 Helm 与 Argo CD 两条开发验收路径中的 `APP_PII_ENCRYPTION_ENABLED=false` 绕过，显式关闭明文读取与一次性回填。
- [x] 首次部署生成独立的 256 位 AES/HMAC 密钥，并将密钥、版本和创建时间写入命名空间 runtime Secret；重复部署读取并复用已有材料，避免旧密文因静默换钥失效。
- [x] Helm/Argo 就绪后从真实 Pod 读取 PII 加密、明文读取、回填和密钥版本环境变量，任一状态不满足 fail-closed 即失败。
- [x] 将两条 MicroK8s 脚本纳入 WS8 独立门禁，并以两个先红后绿的 Java 回归锁定配置、Secret 和 Pod 校验契约。
- [x] 通过假的 `ssh/scp` 执行动态生成路径；两份 Bash 通过 `bash -n`，两份 values 通过 Helm 渲染。
- [x] 当前完整总入口在 1,257.8 秒后以退出码 0 完成：1,718 个后端测试零失败，JaCoCo 84.88%，PITest 85.36%，扫描器零阻断，Lighthouse 96/100/100/100，PII 349/349。
- [ ] 两个历史 VM 地址的 TCP/22 均超时；恢复可达集群后执行真实 MicroK8s/Argo 运行态验收。
- [x] 批次 9 已推送为 `66857fc2`；CI/CD `30005065330`、WS1 `30005065308`、CodeQL `30005065296` 均为绿色。

## 批次 10A：原生 Windows 可观测栈

- [x] 通过 Clash `127.0.0.1:7890` 下载并校验固定版本的 Collector、Prometheus、Loki、Tempo 与 Grafana 官方制品。
- [x] 所有服务绑定环回地址，运行数据、PID 身份和随机生成的 Grafana 管理密码均保存在仓库外的 `%LOCALAPPDATA%\MonkeyShop`。
- [x] 修复 Collector 内部指标与 Spring `8888` 端口冲突，以及 Loki 误选虚拟网卡后内部自连超时的问题。
- [x] 关闭 Grafana 离线本地模式下的更新检查、插件预安装与新闻外呼。
- [x] 增加 `start-local.ps1 -WithObservability`，在 Spring 启动前接通本地 OTLP；默认开发模式仍保持 exporter 为 `none`。
- [x] 以真实 Spring `GET /api/v1/monkeys` 请求证明 Tempo Server Span、TraceQL 精确检索、同 Trace ID Loki 日志、Prometheus span metrics 与 Grafana 三数据源联动。
- [x] Tempo metrics-generator 已向 Prometheus 写入 30 条 span metric 序列与 2 条 service graph 序列。
- [x] 修复本地启动超时后 Maven/Java 子进程树未回收的问题；1 秒故意超时负例确认 8888 与 MonkeyShop 后端进程均无残留。
- [x] 脚本 AST、Dashboard JSON、Collector/Loki/Tempo/Prometheus 原生配置解析、WS6 门禁与阶段回归均已通过。
- [x] 当前重放后的批次节点为 `2281baf8`；后续进程清理节点为 `3e60d9f1`。
- [x] 批次 10A 已推送；CI/CD `30006714248`、WS1 `30006714177`、CodeQL `30006714212` 均为绿色。

## 批次 10B：原生 Windows 安全与对象存储栈

- [x] 通过 Clash 下载并按固定 SHA-256 校验 Vault 2.0.3、SeaweedFS 4.29 与 ClamAV 1.5.3 Windows 制品。
- [x] 增加 bootstrap/start/status/stop/verify 生命周期脚本；运行数据、操作员材料和应用凭据均位于仓库外并限制 ACL。
- [x] Vault 使用持久化 file storage 和 Transit；应用令牌仅允许解密，encrypt 实测返回 403。
- [x] 首次启动将已有 AES/HMAC PII 密钥包裹进 Vault，Spring 启动前删除两个原始密钥环境变量，旧密文继续可读。
- [x] SeaweedFS S3 已实测建桶、写入、stat、读取、预签名读取与删除；ClamAV 已实测干净样本与 EICAR 拒绝。
- [x] Spring 以 `vault-transit + minio + ClamAV` 冷启动并返回健康；关闭端口故障探针证明依赖不可用时非零退出且不残留监听。
- [x] 修复 Clash 进程级代理污染、慢速 `Get-NetTCPConnection` 探测、后端/Grafana 回环绑定和重复 Maven 语义验收；热启动复测为 18.0 秒。
- [x] WS6、WS8 与启动验证器共 23 个聚焦测试零失败；真实支持服务语义验收、运行冒烟、安全和 429 探针通过。
- [x] 修复首次启动缺桶、状态脚本代理绕行和重复启动模式覆盖；随机缺失桶真实建删成功，无开关重复启动 8.6 秒且 PID 与两项模式状态保持不变。
- [x] 重启原生可观测栈，使 Grafana 应用新的 `127.0.0.1` 绑定和代理排除配置。
- [x] 管理员重启 MySQL 后，3306 与 33060 均仅监听 `127.0.0.1`；统一监听门禁同时覆盖运行时、支撑与可观测服务，并实测拒绝临时 wildcard 监听。
- [x] 拆分执行最终后端、质量报告、WS1 扫描、WS5、WS6/7/8、Kyverno、运行态、429、PII 与本地支撑语义门禁，全部取得退出码 0 并更新证据。
- [x] 可观测栈重启后更新最终 PID、监听与真实 trace 证据。
- [x] 创建并推送批次 10B 节点 `8641d187`。
- [x] CI/CD `30008413100`、WS1 `30008413158`、CodeQL `30008413186` 均为绿色。

## 批次 11：埋点加密摘要运行态热修

- [x] 真实 Chromium 验收定位到 `/api/v1/tracking/events` 因递归拼接画像摘要而触发 `encrypted_profile_summary` 字段溢出。
- [x] 将画像摘要收敛为固定大小的最新事件快照；行为与兴趣历史仍由既有标签及事件记录保留。
- [x] 增加 100 次重复事件回归；Tracking、JPA、WS11 与 SchemaMigration 共 15 个测试零失败。
- [x] `mvn -o -DskipTests validate` 通过：Spotless 972 个文件干净，Checkstyle 0 违规。
- [x] `scripts/verify-local-runtime.ps1 -RunRateLimitProbe` 通过：349/349 PII 密文、124 个 OpenAPI 操作、真实 Chromium 登录与店铺链路均成功。
- [x] 创建并推送节点 `480f4334`。
- [x] CI/CD `30010693037`、WS1 `30010692977`、CodeQL `30010693024` 均为绿色；Snyk `30010693063` 与 Sonar `30010693107` 也按凭据感知策略正常结束。

## 远端核验

- [x] `git fetch origin` 与 `git ls-remote` 确认远端架构升级分支为 `aa6b2d92`，其中最终代码节点为 `480f4334`。
- [x] 确认最终代码节点从基础节点起共有 81 个增量提交，且批次 1 至批次 11 严格连续。
- [x] 合并前确认远端 `main` 节点 `008651d5` 是架构升级分支的祖先且无独有提交；随后以 `--ff-only` 快进到 `aa6b2d92` 并推送。
- [x] 批次 1 至批次 11 的 CI/CD、WS1 Security Gate 与 CodeQL 已取得真实绿色结果；Snyk/Sonar 工作流在缺少外部令牌时按设计显式降级而非伪报外部质量门通过。
- [x] 主分支 CI/CD `30015188255`、WS1 `30015185956`、CodeQL `30015188219`、Snyk `30015186527`、Sonar `30015189059` 均成功。
- [x] 主分支镜像作业通过 Trivy、code scanning、GHCR 推送与 cosign 签名；提交 `373c0c9a` 固定 digest `sha256:8d0dc3905bde643a73cc4b74b749ab821d5c42ff392095c5a211f48b0f51eafe`，提交 `6079dc62` 将 Argo `targetRevision` 固定到 `373c0c9a408d55ab25de605179f75cf568297470`。

## 不随代码推送伪装完成的事项

公开 TLS/HSTS、真实 Sonar Quality Gate、生产 Vault/KMS 托管与职责分离、Turnstile、Sentry、真实 Argo 集群、签名镜像的集群准入、灰度回滚以及 30 天 SLO 仍需要外部环境或凭据。分支 CI 已真实完成 GHCR 推送与 cosign 签名，Vault/SeaweedFS/ClamAV 与 OTel/Loki/Tempo/Grafana 已在 Windows 本机形成可重复验收栈，但这些证据不等同于生产环境验收。
