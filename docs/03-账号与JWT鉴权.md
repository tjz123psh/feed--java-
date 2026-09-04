# 阶段 3 · 账号与 JWT 鉴权

> **本阶段做什么**：建本工程第一张表，做注册/登录/刷新/登出/改名/改密/资料/头像接口，并搭好全站复用的鉴权拦截器与限流雏形。
> **做完能演示**：注册 → 登录拿 token → 带 token 调受保护接口 → 登出后旧 token 立即 401。
> **开工前确认**：`/healthz` 返回 `{"status":"ok"}`、MySQL(3307) 与 Redis(6379) 容器在跑（《02》第 5 步、第 2 步）；
> 库里还没有业务表，第一张在本阶段第 1 步建；能直接复用的《02》产出只有 `AppProps`、`FeatureFlags`、`RedisKeys`。本机 curl 一律带 `--noproxy '*'`（《02》「卡住了看这里」）。

## 第 1 步 · 建 accounts 表

原版 GORM 启动时 AutoMigrate 自动建表，Java 版用 MyBatis-Plus **不建表**，所以每个阶段第一件事是自己建表。进容器：`cd deploy && docker compose exec -it mysql mysql -uroot -p123456 feedsystem`，把下面整段粘进去执行（MySQL 8 语法）：

```sql
CREATE TABLE `accounts` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `username` varchar(191) DEFAULT NULL,
  `password` longtext,
  `token` longtext,
  `refresh_token` longtext,
  `avatar_url` varchar(512) DEFAULT NULL,
  `bio` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uni_accounts_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

验证：`SHOW TABLES;` 期望列出 `accounts`；`SHOW CREATE TABLE accounts\G` 期望看到 `uni_accounts_username`。
两句解释：`username` 用 varchar(191) 是 utf8mb4 下唯一索引受 767 字节限制；`password`/`token`/`refresh_token` 是 longtext（GORM 的 string 默认映射），Java 侧实体写 `String` 即可。
列名转驼峰不用配：MyBatis-Plus 的 `MybatisConfiguration` 在构造里就把 `mapUnderscoreToCamelCase` 设为 true；它也不是 Spring 的键——真要用必须写顶层 `mybatis-plus:` 前缀，嵌在 `spring:` 下会被静默忽略，所以本工程哪一步都不写这个键。

再补两件本阶段就要用的配置。签名密钥写进 `application.yml` 的 `feed:` 段（《02》第 7 步预告过这件事），并在 `AppProps` 里加一个嵌套 record `Jwt(String secret)`：

```yaml
feed:
  jwt:
    secret: ${JWT_SECRET:feedsystem-dev-secret-key}   # 键即 feed.jwt.secret；并进《02》第 7 步已有的 feed: 段，别开第二个顶层 feed:
```

新建 `infra/RateLimits.java`（原来写在《02》，现已移走，因为规则到本阶段才用到）。**本阶段只放两条**，《05》追加 `like_write` / `comment_write`、《06》追加 `social_write`：

```java
public final class RateLimits {
    public record Rule(String name, int limit, Duration window, boolean byIp) {}
    public static final List<Rule> ALL = List.of(
        new Rule("account_register",  5, Duration.ofHours(1), true),    // 5 次/小时，按 IP
        new Rule("account_login",    10, Duration.ofMinutes(1), true)); // 10 次/分钟，按 IP
}
```

限流的实现原理在《09》，本阶段只是把规则表建好，第 6 步挂到两个接口上。
**通过标志**：`SHOW TABLES;` 里有 `accounts`；`./mvnw -q compile` 通过；`RateLimits.ALL` 里只有两条规则。

## 第 2 步 · 抄下接口契约

本节是实现依据。**路径、字段名、错误文案都是契约，不许改名。**
所有接口一律 `POST` + JSON（头像上传除外），路径前缀 `/account`。
错误响应统一 `{"error": "文案"}`，**没有任何 `code`/`ok`/`data` 包装层**。

| 路径 | 鉴权 | 请求 | 成功响应（逐字） |
|------|------|------|------------------|
| `/account/register` | 否 | `{username, password}` | `{"message":"account created"}` |
| `/account/login` | 否 | `{username, password}` | `{token, refresh_token, account_id, username}` |
| `/account/refresh` | 否 | `{refresh_token}` | `{token, refresh_token:"", account_id, username}`（refresh_token 是**空串**，保持） |
| `/account/changePassword` | **否**（用 username+旧密码证明身份） | `{username, old_password, new_password}` | `{"message":"successfully password changed"}` |
| `/account/findByID` | 否 | `{id}` | Account 对象 `{id, username, bio?, avatar_url?}`（bio/avatar_url 空时省略） |
| `/account/findByUsername` | 否 | `{username}` | 同上 |
| `/account/getProfile` | 否 | `{account_id}` | `{account:{...}, video_count, total_likes, follower_count, vlogger_count}` |
| `/account/logout` | JWT | 无 body | `{"message":"account logged out"}` |
| `/account/rename` | JWT | `{new_username}` | `{"token":"<新 access token>"}`（字段名就叫 token，客户端要替换本地值） |
| `/account/updateProfile` | JWT | `{avatar_url?, bio?}` | `{"message":"profile updated"}` |
| `/account/uploadAvatar` | JWT | multipart 字段 `file` | `{"avatar_url":"http://{Host}/static/avatars/{uid}/{随机hex+原扩展名}"}` |

失败文案逐字：旧密码错 → **400** `unsuccessfully password changed`；updateProfile 两字段全空 → `nothing to update`；
rename 重名 → **409** `username already exists`（这句人话文案只有 rename 有）。头像三段校验逐字：400 `missing file`（没有 `file` 字段）；400 `invalid file size`（大小 ≤0 或超过 10 MB——**空文件与超大共用同一句**）；400 `only .jpg/.jpeg/.png/.webp allowed`（扩展名转小写后比）。登录态取不到 accountID → 401 + `accountID not found`；建目录 / 存盘 / 写库失败 → 500 + 底层错误原文（原版没有统一的"处理失败"文案）。
其余业务失败（**登录时**的用户不存在、密码错、**注册**重名）状态码是 **500**、error 文案是底层错误原文——这是历史约定。
> **注意细分，别笼统并进 500 组**：`findByID` / `findByUsername` 的"查无此人"走 `gorm.ErrRecordNotFound` →
> **404** `record not found`（error 原文就是这个哨兵错误）；`getProfile` 的查找失败才是 500 原文。
> **例外（照抄，别并进 500 组）**：`/account/refresh` 失败固定 **401** `{"error":"invalid refresh token"}`——
> 原版 Refresh 的 service 层失败全部折叠成这一条（token 为空、缓存/库里值不等、全表扫无匹配、签发或落库失败）。
> 唯一例外：请求体本身解析不了（`ShouldBindJSON` 报错）走通用映射 → 500 + 原始错误。前端对 401 有自动刷新逻辑，这条尤其不能改。

**决策点：错误状态码（现在就选定，后面全程一致）**
- **A 严格对齐**：上面说的 500 全保留（login 用户不存在/密码错、注册重名），`findByID`/`findByUsername` 查无 404。适合想跑差异测试的人。
- **B 语义修正**：登录失败 400、**注册**重名 409（refresh 的 401、findByID/findByUsername 的 404 保持不动，本来就这样）。**红线：登录失败不得用 401**——前端对 401
  有专门分支（自动刷 token 重试，`client.ts:66`），401 会凭空触发刷新流程。
别混用；无论选哪个，都在 `docs/README.md` 的差异说明处记一行（最终收口见《09》第 7 步的差异清单）。
**通过标志**：能说出 11 个路径各自要不要鉴权；A/B 已选定并写进 README。

## 第 3 步 · 想清楚 Token 模型（本阶段最重要的心智）

**这不是无状态 JWT。** 每次请求要过两道关：JWT 验签 + 与服务端存的"当前有效 token"比对。
后果：登出/改密/改名/重新登录都能让旧 token **立即**失效；同一账号任何时刻只有一个登录态（互踢）。

| 项 | 规格 |
|----|------|
| access token | JWT，HS256，有效期 **15 分钟**，claims：`account_id`、`username`、`exp/iat/nbf`；密钥取 `feed.jwt.secret` |
| refresh token | **64 位 hex 随机串（32 字节随机数），不是 JWT**，只用于换发新 access token |
| 密码 | bcrypt cost 10，密文前缀 `$2a$10$` |
| 存储 | `accounts` 表的 `token`、`refresh_token` 两列 + 下面三个 Redis 键 |

| Redis 键（经 RedisKeys 拼 `v1:` 前缀） | 值 | TTL |
|------|----|-----|
| `v1:account:{id}` | 当前 access token **原文字符串**（裸串，不是 JSON） | 24 h |
| `v1:account:{id}:refresh` | 当前 refresh token | 7 d |
| `v1:refresh:{token}` | accountID 十进制串（反查索引） | 7 d |

三条铁律（后面每步都按它写）：① **验签在查缓存之前**（顺序反了=可拿任意签名 token 冒充探测）；
② **写序先库后缓存**，缓存失败不回滚库操作、只记日志；③ Redis 值一律 **StringRedisTemplate 裸字符串**——用默认 JDK 序列化器的 RedisTemplate 会写进乱码字节，阶段 8 与参考实现互读缓存时必炸。
**通过标志**：能答出"为什么登出的 token 立刻失效""为什么同一账号只能有一个登录态"。

## 第 4 步 · 加两个依赖，写出 auth 与 account 的地基

依赖只加两个：`com.auth0:java-jwt:4.4.0`、`org.springframework.security:spring-security-crypto`。
> 警告：**禁止引入 `spring-boot-starter-security`**——它的自动配置会接管 401/403 的响应格式，直接破坏第 2 步的错误契约。

文件摆放：包 `auth` 放 `JwtService`（HS256 签发/解析，`newRefreshToken()`=SecureRandom 32B hex，有效期 15 分钟，claims 放 `account_id`、`username`）
与 `TokenStore`（三键+两列的读写收口：save/current/revoke，登录登出改名都走它；`current` 要含"Redis 读超时或未命中 → 查库兜底 → 回填 Redis"这条路径，缓存写失败只记日志、不回滚库操作）；
包 `account` 放 `PasswordCipher`（bcrypt cost 10）、实体 `Account`、`AccountController`、`AccountService`、`AccountMapper`（`INSERT` 要能捕获 `DuplicateKeyException`），
以及 `account/dto/`——每个接口一个 record，`@JsonProperty` 逐字段对齐第 2 步的表；包 `web` 放第 6 步的 `JwtAuthInterceptor`、`RateLimitInterceptor` 与 `WebConfig`；
包 `infra` 补 `Errors.java` 与一个 `@RestControllerAdvice`，把第 2 步选定的状态码路线收口成一处。验证：`./mvnw test`。
**通过标志**：单测里签发→解析 claims 相等、篡改签名→抛异常、`exp-iat=900s`、密文以 `$2a$10$` 开头；
把 Redis 键删掉后 `TokenStore` 仍能从库里取到 token 并回填。

## 第 5 步 · 写注册、登录、登出三个接口

- **register**：重名 → 500 + 底层错误原文（原版注册没有预检查，直接撞 MySQL 1062 把原始错误回吐；`username already exists` 是 rename 的 409 文案）；成功 → `{"message":"account created"}`。
- **login**：按 username 查库 → bcrypt 比对 → 签发两 token → **先 UPDATE 库两列，再 SET 三键**（缓存失败只记日志，不影响登录）。
- **logout**：删三键 + 清两列，返回 `{"message":"account logged out"}`。

先设两个变量，后面各步与验收清单都复用：`H=localhost:8081; J='Content-Type: application/json'`。验证：跑本阶段验收清单的前两条 curl（register → login 取回 token）；第三条 logout 要等第 6 步拦截器就位后才验得准。
**通过标志**：register 返回 `{"message":"account created"}`；login 返回 `token`、`refresh_token`、`account_id`、`username` 四个字段。

## 第 6 步 · 写鉴权与限流两个拦截器并注册到白名单路径

`JwtAuthInterceptor` 四步，四种 401 文案逐字：
① 无 `Authorization` 头 → `missing authorization header`；② 非 `Bearer ` 前缀 → `invalid authorization header`；
③ 验签/过期失败 → `invalid or expired token`；④ 与 `v1:account:{id}` 的值**字符串比对**，不等 →
`token has been revoked`；Redis 读超时 50 ms 或未命中 → 查 `accounts.token` 列比对，通过后回填 Redis。
全部通过 → `request.setAttribute("accountID", id)`。

`RateLimitInterceptor` 用第 1 步那两条规则（Lua 脚本 INCR+PEXPIRE）：`/account/login` 10 次/分钟/IP；
`/account/register` 5 次/小时/IP。键 `feedsystem:ratelimit:account_login:{ip}`（**无 `v1:` 前缀**，限流键族独立）。
算法：INCR + 首次 EXPIRE（固定窗口，窗口交界允许 2 倍突发）；Redis 挂 → 放行（fail-open）；超限 429 `{"error":"too many requests"}`。

注册（路径清单即契约，加在《02》第 6 步那个 `WebConfig` 里）：

```java
reg.addInterceptor(jwtAuth).addPathPatterns(
    "/account/logout", "/account/rename",
    "/account/updateProfile", "/account/uploadAvatar");
    // ↑ 阶段 4/5/6/8 继续追加各自分组；register/login/refresh/changePassword/
    //   findByID/findByUsername/getProfile 必须留在外面
reg.addInterceptor(rateLimit).addPathPatterns("/account/login", "/account/register");
```

验证：不带 token 调 `$H/account/logout` → 401 `missing authorization header`；带第 5 步的 token 调 → `{"message":"account logged out"}`；
再拿同一个 token 调一次 → 401 `token has been revoked`。然后连打 12 次登录：
`bash -c 'for i in $(seq 1 12); do curl -s --noproxy "*" -o /dev/null -w "%{http_code} " -XPOST localhost:8081/account/login -H "Content-Type: application/json" -d "{\"username\":\"x\",\"password\":\"y\"}"; done'`
（你的登录 shell 是 fish，`for i in $(...)` 这种写法要包一层 `bash -c` 才认）
**通过标志**：`/account/login` 不被鉴权拦截；第 11 次起出现 `429`；
`cd deploy && docker compose exec -T redis redis-cli --no-auth-warning -a 123456 GET feedsystem:ratelimit:account_login:127.0.0.1` 有计数。

## 第 7 步 · 写 refresh 与 changePassword（两个免鉴权接口）

- **refresh**：`v1:refresh:{token}` 反查账号（miss 时按 `refresh_token` 列查库——原版全表扫是坏做法，你直接建列查询，记差异）
  → 与库中值相等 → 只签**新 access token**，写库写缓存；响应的 `refresh_token` 是**空串**（第 2 步契约，保持）。
- **changePassword**：旧密码错 → 400 `unsuccessfully password changed`；成功 → `{"message":"successfully password changed"}`，
  并且**自动执行 logout**（删三键清两列，全端下线）。

验证：先 `RT=$(curl -s --noproxy '*' -XPOST localhost:8081/account/login -H "Content-Type: application/json" -d '{"username":"u3","password":"***"}' | jq -r .refresh_token)`，再 `curl -s --noproxy '*' -XPOST $H/account/refresh -H "$J" -d "{\"refresh_token\":\"$RT\"}" | jq` → 期望 `refresh_token` 输出 `""`。
改密：`curl -s --noproxy '*' -XPOST $H/account/changePassword -H "$J" -d '{"username":"u3","old_password":"123456","new_password":"654321"}'`。
**通过标志**：refresh 拿到新 `token`；改密成功后旧 access token 立刻 401。

## 第 8 步 · 写 rename 与 updateProfile

- **rename**：新 username + 新 access token，**同一事务**更新 username 和 token 两列，再回写 `v1:account:{id}`；
  重名捕获 `DuplicateKeyException` → `username already exists`；账号查不到 → **404** `account not found`；`new_username` 为空 → 走通用错误映射；响应体 `{"token":"<新 access token>"}`，字段名就叫 token。
- **updateProfile**：`{avatar_url?, bio?}` 都可选，全空 → `nothing to update`；成功 → `{"message":"profile updated"}`。

验证：`curl -s --noproxy '*' -XPOST $H/account/rename -H "Authorization: Bearer $T" -H "$J" -d '{"new_username":"u3c"}' | jq -r 'keys'`（`$T` 用第 5 步新登录的 token）。
**通过标志**：输出 `["token"]`；旧 token 随后调用返回 401 `token has been revoked`。

## 第 9 步 · 写 findByID / findByUsername / getProfile

三个都免鉴权：前两个返回 `{id, username, bio?, avatar_url?}`（空字段省略）；`getProfile` 的 `account_id` 传 0 或缺失 → **400** `account_id is required`（原版这条是路由内联校验，`router.go:149-151`）；
**getProfile 的四个计数**实时 COUNT/SUM（videos、socials、likes），任一失败 → 该字段记 0 不报错（原版行为，保持）；本阶段 `videos`、`socials`、`likes` 三张表都还没有（`videos` 到《04》第 1 步才建），正好验证"失败记 0"这条容错路径。
验证：`curl -s --noproxy '*' -XPOST $H/account/getProfile -H "$J" -d '{"account_id":1}' | jq 'keys'`。
**通过标志**：输出含 `account` 与四个 `*_count`，进程不抛异常栈。

## 第 10 步 · 写 uploadAvatar，然后补契约测试

multipart 字段 `file`；上限 10 MB、扩展名 jpg/jpeg/png/webp 四种（大小写不敏感）、
存 `uploads/avatars/{uid}/{随机16字节hex+扩展名}`（根目录是《02》第 6 步建的 `data/uploads`）、
返回绝对 URL（Host 头 + X-Forwarded-Proto）；校验不过 400，写盘异常 500，文案见第 2 步。
最后写 `AccountContractTest`：第 2 步每个接口的成功与失败路径都断言，**文案逐字**。
**通过标志**：响应里的 URL 浏览器能打开，`ls data/uploads/avatars/1/` 有 16 字节 hex 命名的文件；`./mvnw -q test` 全绿，故意改错一个文案时测试会变红。

## 本阶段验收清单

> 下面整段是 **bash 语法**。你的默认 shell 是 fish，先敲 `bash` 进入子 shell 再整段粘贴（完事后 `exit` 退回），
> 或存成 `check-03.sh` 后 `bash check-03.sh` 运行。命令里的 `***` 换成你自己的密码。

```bash
H=localhost:8081; J='Content-Type: application/json'
curl -s --noproxy '*' -XPOST $H/account/register -H "$J" -d '{"username":"u3","password":"***"}'   # {"message":"account created"}
LOGIN=$(curl -s --noproxy '*' -XPOST localhost:8081/account/login -H "Content-Type: application/json" -d '{"username":"u3","password":"***"}')
T=$(echo "$LOGIN" | jq -r .token); AID=$(echo "$LOGIN" | jq -r .account_id)   # 后续 id 都用 $AID，别硬编码
curl -s --noproxy '*' -XPOST $H/account/logout -H "Authorization: Bearer $T"                        # logged out
curl -s --noproxy '*' -XPOST $H/account/logout -H "Authorization: Bearer $T"                        # 401 token has been revoked
LOGIN2=$(curl -s --noproxy '*' -XPOST localhost:8081/account/login -H "Content-Type: application/json" -d '{"username":"u3","password":"***"}')
RT=$(echo "$LOGIN2" | jq -r .refresh_token)
curl -s --noproxy '*' -XPOST $H/account/refresh -H "$J" -d "{\"refresh_token\":\"$RT\"}" | jq '.refresh_token'  # 期望 ""
T2=$(curl -s --noproxy '*' -XPOST localhost:8081/account/login -H "Content-Type: application/json" -d '{"username":"u3","password":"***"}' | jq -r .token)
T3=$(curl -s --noproxy '*' -XPOST $H/account/rename -H "Authorization: Bearer $T2" -H "$J" -d '{"new_username":"u3b"}' | jq -r .token)   # rename 换发新 token，T2 同时作废；T3 就是新 token
curl -s --noproxy '*' -XPOST $H/account/getProfile -H "$J" -d "{\"account_id\":$AID}" | jq 'keys'  # account+四个 *_count
curl -s --noproxy '*' -XPOST $H/account/findByID       -H "$J" -d "{\"id\":$AID}" | jq -r .username           # 免登录可查，期望 u3b
curl -s --noproxy '*' -XPOST $H/account/findByUsername -H "$J" -d '{"username":"u3b"}' | jq -r .id       # 与 $AID 相同
curl -s --noproxy '*' -XPOST $H/account/updateProfile  -H "Authorization: Bearer $T3" -H "$J" -d '{}'   # 期望 error: nothing to update
curl -s --noproxy '*' -XPOST $H/account/updateProfile  -H "Authorization: Bearer $T3" -H "$J" -d '{"bio":"hi"}'   # {"message":"profile updated"}
# changePassword 不要拿当前 token 测（它会顺手把你下线）：用账号+旧密码即可（上面 rename 把 u3 改成 u3b 了）
curl -s --noproxy '*' -XPOST $H/account/changePassword -H "$J" \
  -d '{"username":"u3b","old_password":"wrongpass","new_password":"secret1234"}'   # 期望 400 unsuccessfully password changed（免鉴权）
# 头像：任意一张本地小图，字段名必须是 file（没有小图可以用 ffmpeg -f lavfi -i color=blue:s=320x240:d=1 avatar.jpg 生成一张）
curl -s --noproxy '*' -XPOST $H/account/uploadAvatar -H "Authorization: Bearer $T3" -F file=@一张图片的路径 | jq -r .avatar_url
# 期望 http://localhost:8081/static/avatars/{$AID}/<16字节hex>.<扩展名>，浏览器能打开
cd deploy   # 最后两行要用 docker compose 进容器，先在 deploy/ 下
docker compose exec -T redis redis-cli --no-auth-warning -a 123456 GET "v1:account:$AID" | head -c 20   # 裸 JWT 前缀，无 JSON 引号
bash -c 'for i in $(seq 1 12); do curl -s --noproxy "*" -o /dev/null -w "%{http_code} " -XPOST localhost:8081/account/login -H "Content-Type: application/json" -d "{\"username\":\"x\",\"password\":\"y\"}"; done'    # 登录总数到第 11 次起 429（前面已登录多次，测试里会更早到）
```

## 卡住了看这里

**Java 验签 Go 签的 token 失败。**
原因：两边 `JWT_SECRET` 不一致。
处理：统一环境变量；这是阶段 9 差异测试的基础设施。

**Redis 里 token 值带引号或乱码。**
原因：用了 JDK 序列化器的 RedisTemplate。
处理：换 StringRedisTemplate（第 3 步铁律 ③）。

**登录成功后立刻 401。**
原因：拦截器把 `/account/login` 也拦了，或白名单注册写成了全局。
处理：核对第 6 步路径清单。

**rename 后前端"掉登录"。**
原因：响应字段是 `token` 不是 `new_token`，前端没替换本地值。
处理：按契约实现；这是刻意的"改名即换发 token"设计。

**限流测试怎么都触发不了 429。**
原因：每次 curl 走了不同出口 IP（代理），或 Redis 挂了 fail-open。
处理：`--noproxy '*'` 直连；确认 Redis 在跑。
