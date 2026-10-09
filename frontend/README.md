# feed-java 前端（ShortVideo）

Vue 3 + TypeScript + Vite 前端，对接 feed-java 后端。开发时经 Vite 代理把 `/api/...`
转发到后端（默认 `http://127.0.0.1:8081`）。

> 2026-10-08 已整体重构为「影院舞台」风格：设计规范见仓库根 `DESIGN.md`，样式 token 在 `src/style.css`。

## 页面

| 路由 | 页面 | 说明 |
|------|------|------|
| `/` | 推荐页 | 沉浸式短视频播放，支持推荐、关注、点赞榜三个 Tab，本地搜索标题/作者，滚动加载。 |
| `/hot` | 热榜 | 对接 `/feed/listByPopularity`，支持刷新与加载更多。 |
| `/video` | 发布页 | 登录后可发布视频；视频按 5MB 分片上传，支持 MD5 校验、并发上传、失败重试、断点续传。 |
| `/video/:id` | 视频详情 | 视频播放、点赞、评论抽屉、作者信息。 |
| `/account` | 账号页 | 登录、个人信息、粉丝/关注入口。 |
| `/account/register` | 注册页 | 创建账号。 |
| `/account/change-password` | 改密页 | 使用旧密码修改密码。 |
| `/settings` | 设置页 | 改名、退出登录、跳转改密。 |
| `/u/:id` | 用户主页 | 用户作品、粉丝/关注列表、关注/取关、私信入口。 |
| `/messages` | 私信联系人 | 从粉丝和关注用户中选择聊天对象。 |
| `/messages/:peerId` | 私信会话 | 查看最近 50 条私信并发送消息。 |

## 前端能力

- Pinia 保存 access token 与 refresh token；API Client 在 401 时自动调用 `/account/refresh` 并重试原请求。
- 发布页使用 `spark-md5` 计算文件 MD5 与分片 MD5。
- 首页为沉浸式短视频流：全部 slide 参与布局，`<video>` 仅挂载当前片与前后各一条，长列表播放成本低。
- 点赞、关注、评论、分享等交互通过 toast 提示结果。

## 开发启动

前置：后端 API 已启动（仓库根目录 `./mvnw spring-boot:run`，监听 :8081）。

```bash
cd frontend
npm install --registry=https://registry.npmmirror.com   # 首次
npm run dev -- --host 127.0.0.1                         # → http://127.0.0.1:5173/
```

两个可覆盖的环境变量：

| 变量 | 作用 | 默认 |
|------|------|------|
| `VITE_API_TARGET` | Vite 代理目标（后端地址） | `http://127.0.0.1:8081` |
| `VITE_API_BASE` | 请求前缀；设为完整后端地址可绕过代理直连 | `/api` |

## 构建

```bash
npm run build    # vue-tsc -b && vite build，产物在 dist/
```
