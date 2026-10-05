# 阶段 3 · 账号与 JWT 鉴权（✅ 已完成）

> 本册回答：**令牌怎么流转**、**每个请求怎么被验**、**11 个端点各做什么**。

## 1. 请求走向与依赖

```mermaid
%%{init: {"flowchart": {"curve": "linear"}}}%%
flowchart LR
    C(["请求"]) --> I["JwtAuthInterceptor<br/>四步校验 · 注入 accountID"]
    I --> CTRL["AccountController<br/>11 个 POST 端点"]
    CTRL --> AS["AccountService<br/>业务编排"]
    AS --> AM["AccountMapper<br/>accounts 表 · 4 个自定义方法"]
    AS --> JS["JwtService<br/>HS256 · 15 分钟 · 64hex refresh"]
    AS --> TS["TokenStore<br/>save · current · revoke · 反查"]
    AS --> PC["PasswordCipher<br/>bcrypt cost 10"]
```

拦截器自己也依赖 `JwtService`（验签）和 `TokenStore`（白名单），见下面第 3 节。

## 2. 令牌生命周期

```mermaid
sequenceDiagram
    participant C as 客户端
    participant S as AccountService
    participant T as TokenStore
    participant DB as MySQL
    participant R as Redis

    Note over C,R: 【登录】
    C->>S: POST /account/login
    S->>DB: 查账号 + bcrypt 比对密码
    S->>S: 签发 JWT（15 分钟）+ 生成 64hex refresh
    S->>T: save(id, token, refresh)
    T->>DB: 更新 token / refresh_token 两列（真相）
    T->>R: 写 v1:account:{id} · :refresh · v1:refresh:{token}
    S-->>C: {token, refresh_token, account_id, username}

    Note over C,R: 【刷新】
    C->>S: POST /account/refresh
    S->>T: findAccountIdByRefresh(token)
    T->>R: 查 v1:refresh:{token}
    T->>DB: 未命中则按 refresh_token 列查并回填索引
    S->>DB: 比对 accounts.refresh_token
    S->>S: 签发新 access token（refresh 不变）
    S-->>C: {token, refresh_token:""}（空串是契约规定的）

    Note over C,R: 【登出 / 改密】
    C->>S: POST /account/logout
    S->>T: revoke(id)
    T->>DB: token / refresh_token 置 NULL
    T->>R: 删三个键
    Note over C: 旧 token 立即失效（白名单里已没有它）
```

## 3. 拦截器四步校验

```mermaid
%%{init: {"flowchart": {"curve": "linear"}}}%%
flowchart LR
    A["请求进入"] --> B{"有 Authorization 头?"}
    B -->|"没有"| E1["401 missing authorization header"]
    B -->|"有"| C{"以 Bearer 开头?"}
    C -->|"否"| E2["401 invalid authorization header"]
    C -->|"是"| D{"JWT 验签通过?"}
    D -->|"失败/过期"| E3["401 invalid or expired token"]
    D -->|"通过"| F{"与 Redis 白名单一致?"}
    F -->|"不一致"| E4["401 token has been revoked"]
    F -->|"一致"| G["放行<br/>注入 accountID"]

    classDef err fill:#fdecea,stroke:#c62828
    class E1,E2,E3,E4 err
```

- `Bearer ` **带尾随空格**；验签在查缓存**之前**（无效令牌不浪费 Redis 往返）。
- 第四步是双保险：验签只证明"签名对、没过期"，白名单比对才保证"没被登出/改密踢掉"。
- 注入的 `accountID` 是后续所有业务取当前用户的**唯一来源**。

## 4. 11 个端点

| 端点 | 鉴权 | 成功响应 | 失败 |
|---|---|---|---|
| `/register` | 否 | `{"message":"account created"}` | 409 重名 |
| `/login` | 否 | `{token, refresh_token, account_id, username}` | 400 统一文案 |
| `/logout` | JWT | `{"message":"account logged out"}` | 401 |
| `/refresh` | 否 | `{token, refresh_token:""}` | 401 |
| `/changePassword` | 否 | `{"message":"successfully password changed"}` | 400 |
| `/rename` | JWT | `{token}`（新签发） | 409 重名 |
| `/updateProfile` | JWT | `{"message":"profile updated"}` | 400 全空 |
| `/findByID` `findByUsername` | 否 | Account（密码类字段被 @JsonIgnore 隐藏） | 404 |
| `/getProfile` | 否 | `{account, 四个计数}` | 400 缺 id |
| `/uploadAvatar` | JWT | `{"avatar_url":"…/static/avatars/{id}/{hex}.jpg"}` | 400 |

两处易错：

- `/changePassword` **不需要 JWT**，靠"用户名 + 旧密码"自证，成功后所有旧 token 作废。
- `/rename` 在**一个事务里**同时改用户名和 token 列，成功后旧 token 立即失效。

## 5. 存储：三键 + 两列

```mermaid
%%{init: {"flowchart": {"curve": "linear"}}}%%
flowchart LR
    L["登录 / 刷新"] --> DB[("MySQL accounts<br/>token · refresh_token<br/>username 唯一索引")]
    L --> RD[("Redis<br/>v1:account:{id} · 24h<br/>v1:account:{id}:refresh · 7d<br/>v1:refresh:{token} · 7d")]
    O["登出 / 改密"] -->|"两列清空 + 三键删除"| DB
    O --> RD
    V["拦截器校验"] -->|"读 v1:account:{id}"| RD
    RD -.->|"未命中回源并回填"| DB
```

分工：`v1:account:{id}` 给拦截器比对；`:refresh` 让 `revoke` 找到旧 refresh 去删索引；
`v1:refresh:{token}` 供 refresh 反查账号（未命中按库里的列查，命中后回填）。

## 6. 与后面的连接

| 本阶段产物 | 谁在用 |
|---|---|
| `JwtAuthInterceptor` + `accountID` 注入 | 阶段 4/5/6/8 所有需登录接口 |
| `AccountMapper.selectById` | 阶段 4 publish 时取用户名快照写入 `videos.username` |
| `accounts` 表 | 阶段 5 评论/点赞的作者、阶段 6 关注关系 |
| `getProfile` 四个计数 | 阶段 5/6 建好 `likes`/`socials` 后从不存在的表返回 0 变成真实值 |
