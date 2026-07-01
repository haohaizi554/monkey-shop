# MonkeyShop 前端

这是 MonkeyShop 的 Vue 3 + TypeScript 单页应用，负责商城浏览、登录注册、订单、个人中心和管理后台。前端通过 `/api/v1` 调用 Spring Boot 后端，使用 HttpOnly Cookie 承载登录态，并在请求层自动补充 `X-Trace-Id`、CSRF Header 和 401 刷新令牌重试。

## 功能范围

- 商城页：商品列表、关键词搜索、价格筛选、库存筛选、下单弹窗和地址选择。
- 登录页：登录、注册、验证码/人机验证、找回密码入口。
- 订单页：个人订单列表、收货、删除、退货申请和退货物流动作。
- 个人中心：用户资料、头像、密码、地址簿和强制改密流程承接。
- 管理后台：GMV、订单、访问量、退货率统计，商品维护、图片上传、订单发货与退货审批。
- 路由守卫：未登录用户跳转登录页，非管理员访问 `/admin` 会回到商城页。

## 技术栈

| 领域 | 技术 |
| --- | --- |
| 框架 | Vue 3, `<script setup>`, TypeScript |
| 构建 | Vite 8, vue-tsc, terser, vite-plugin-compression2 |
| 状态与路由 | Pinia, Vue Router |
| UI | Element Plus, `@element-plus/icons-vue` |
| 网络 | Axios, Cookie 凭据, CSRF Header, Trace ID |
| 国际化 | vue-i18n |
| 质量 | ESLint, Prettier, Playwright, axe, Lighthouse |

## 目录结构

```text
frontend/
  src/
    api/          # 后端 API 封装与 Axios 拦截器
    components/   # 布局、商品图片、人机验证组件
    locales/      # i18n 文案
    router/       # 页面路由和登录/管理员守卫
    stores/       # Pinia 登录态和主题状态
    utils/        # 金额、日期、CSRF 等工具
    views/        # 商城、登录、订单、个人中心、管理后台
  scripts/        # API contract、a11y、Lighthouse 检查脚本
  tests/          # Playwright 可访问性测试
```

## 开发命令

```powershell
npm ci
npm run dev
```

常用质量检查：

```powershell
npm run build
npm run lint
npm run format
npm run test:api-contract
npm run test:a11y
npm run test:lighthouse
```

## 本轮验证

- `npm run build` 已通过，包含 `vue-tsc -b` 类型检查和 Vite 生产构建。
- 构建时出现 `@vueuse/core` 中 `/* #__PURE__ */` 注释位置相关的 Rolldown 警告，以及插件耗时提示；当前不会导致构建失败。

## 后端约定

- API 基础路径为 `/api/v1`。
- 请求默认 `withCredentials: true`，依赖后端设置 HttpOnly Cookie。
- 非 GET 请求自动带 CSRF Header。
- 401 响应会自动调用 `/auth/refresh` 后重试原请求。
- 前端路由使用 history 模式，后端或 Nginx 需要把 SPA 页面回退到 `index.html`。