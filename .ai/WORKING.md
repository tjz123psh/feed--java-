# WORKING.md —— Feed-Java 项目 AI 交接文档

> **给 AI 的说明**：换模型 / 换会话 / 压缩后，先读本文件，再读 `docs/README.md`，然后以 Git 状态、源码和测试为准核验。本文件由模型随时更新（用户在规则中已授权"模型觉得需要写入就写入"），每次关键步骤结束应顺手刷新。最后更新：2026-09-29。

## 0. 项目一句话

`/home/pang/Projects/Feed stream/feed-java`：用 **Java 21 + Spring Boot 4.0.8 + MyBatis-Plus 3.5.17 + mysql-connector-j 9.7.0** 从零重写仿抖音短视频 Feed 系统（Go 原版 `feedsystem_video_go-main`）。接口契约、表结构、缓存 key、错误文案全在 `docs/` 12 份中文文档里，**不需要读 Go 源码**，按文档实现即可。项目根下的 `.ai/WORKING.md` 是 AI 交接入口，`docs/README.md` §1.5 定义每步输出结构。

## 1. 当前进度（核验日期：2026-09-28，`mvnw test` 90/90 绿）

### 阶段 2 · 环境与工程骨架 —— ✅ 已完成

- `ApiApplication`(8081)、`WorkerApplication`(worker profile)、`web/HealthzController`+`WebConfig`(静态映射)
- `config/AppProps`(`Redis(keyPrefix)`,`Jwt(secret)` 双 record)、`infra/FeatureFlags`/`RedisKeys`/`RateLimits`
- `deploy/docker-compose.yml`：MySQL(3307,库 feedsystem)+Redis(6379)；Kafka 阶段 7 再加

### 阶段 3 · 账号与 JWT —— ✅ 已完成（2026-09-10 验收清单全绿）

**已完成**（全部过编译与验收）：

| 包 | 文件 | 说明 |
|----|------|------|
| `auth/` | `JwtService` | HS256 签发/解析，claims `account_id`(long)/`username`，15 分钟，refresh=SecureRandom 32B hex |
| `auth/` | `TokenStore` | `save/current/revoke`+`findAccountIdByRefresh`，先库后缓存、失败只 log、兜底回填 |
| `account/` | `Account` | `@JsonIgnore` password/token/refreshToken，`avatar_url` 别名，NON_EMPTY |
| `account/` | `AccountMapper` | `BaseMapper` + `updateTokens/clearTokens/updateUsernameAndToken/selectByRefreshToken` |
| `account/` | `PasswordCipher`、`AccountService`、`AccountController`、`dto/`(14 record) | 全部 10 个 `/account/*` 端点实现 |
| `web/` | `JwtAuthInterceptor`、`RateLimitInterceptor`(占位放行)、`WebConfig` 注册 | 鉴权四文案逐字；受保护路径已挂 |
| `infra/` | `Errors`/`ApiError`/`ApiException`/`GlobalExceptionHandler` | `{"error":文案}` 收口，含 handleUnreadable/handleGeneric → 500+原文 |

**已绿**：PasswordCipherTest(4)、JwtServiceTest(4)、TokenStoreTest(2)、RedisKeysTest(1)、两个 contextLoads（13 个）+ AccountContractTest(21) = **共 34 个**。

**待办**：
1. ✅ **第 10 步 uploadAvatar 已实现并验证**：multipart `@RequestPart(value="file", required=false)`；10MB+扩展名校验在代码内做；yml 已配 `spring.servlet.multipart.max-file-size: 12MB`（默认 1MB 会让 2~10MB 合法文件先被 Spring 拒掉）；`MaxUploadSizeExceededException` → 400 invalid file size 兜底；`transferTo(Path)` 写 `data/uploads/avatars/{uid}/{32hex+ext}`；绝对 URL = X-Forwarded-Proto(默认 http) + Host + `/static/avatars/...`。
2. ✅ **AccountContractTest 已写入**（`src/test/java/io/github/p_feed/account/`）：21 个用例覆盖 11 个端点成功+失败路径，文案逐字断言，`@SpringBootTest` 起真上下文（真 MySQL/Redis）。**Boot 4 三个坑见 §3**。
3. ✅ **阶段 3 验收清单已跑通**（2026-09-10，账号 acc_1789048479→_r）：register→login→logout→401 四连、refresh 空串、rename 换发、getProfile、find 双向、updateProfile、changePassword、头像落盘+HTTP 200、Redis 裸串检查全绿。**429 未验**：RateLimiter 仍是放行壳，真限流、429 验收与 login 防抖留到阶段 9。
4. ✅ **README §4 差异说明已补**（2026-09-10）：getProfile 400 `account_id is required`、四计数记 0、refresh 建列反查、头像 400 三段文案、12MB 上限设计。
5. 📄 **项目全景 + 阶段分册**（2026-09-21 更新，共 5 份，严格按源码核对）：
   - 位置：`docs/全景/`（仓库）与 `/home/pang/obsidian/学习笔记/项目/feed流项目/`（Obsidian），**两份必须保持同步**（改完用 `diff` 校验）。
   - 文件：`项目全景.md`（总览：路线依赖、四层协作、数据资产、不变式、已知不一致）、`阶段2-环境与骨架.md`、`阶段3-账号与JWT.md`、`阶段4-视频与分块上传.md`、`阶段5-评论与点赞.md`。
   - 维护约定：新阶段完工后加一份分册；全景 §1 改状态与规模、§2 改路线状态、§3 加节点、§5 更新表/键/测试数、§6 更新不一致项。图只保留"一张图讲一件事"的，不做堆砌；**单节点出边 ≤4**（用校验脚本扫，超出就把细节挪成表格）。
6. ✅ 阶段 4 全部完成（第 1~10 步），详见下方阶段 4 段。

### 阶段 4 · 视频与分块上传 —— ✅ 已完成（2026-09-16，第 1~10 步全 ✅，`mvnw test` 43/43 绿，本阶段验收清单跑通）

**已完成**：
1. ✅ 建表：`videos`/`tags`/`video_tags`/`outbox_msgs` 四张表（DDL 按《04》原文，复合索引 `idx_videos_popularity_time_id` 等已建）。
2. ✅ 契约表已抄（鉴权分两组：7 个要登录 / `listByAuthorID`、`getDetail` 免登录；publish 必填缺失是 **500** 不是 400；chunk 四端点 Redis 不可用统一 503 `chunk upload requires redis`；封面文案带 `is`）。
3. ✅ 第 3 步 FilesTool（`tool/`）：绝对路径根目录可空（缺省禁用上传），URL = X-Forwarded-Proto(默认 http/isSecure) + Host + `/static/{kind}/{uid}[/{yyyyMMdd}]/{32hex}{ext}`；`AppProps` 加了 `uploadRoot` 组件（测试里三处 `new AppProps(...)` 补 `, null`）。
4. ✅ 第 4 步 uploadVideo/uploadCover：mp4 ≤200MB / 图 ≤10MB，扩展名与文案逐字（含封面 `is`），`transferTo` 前 `Files.createDirectories`（否则 500 文案=路径），落盘 `videos/`/`covers/`（**复数**）。已知坑见 §3。
5. ✅ 第 5 步 publish：`@Transactional` 四步 = insert videos → outbox_msgs(`video_published`/`pending`) → tags `INSERT IGNORE` 去重 → video_tags 关联；tag 抽取正则 `#([\p{L}\p{N}_]+)` 抽中文/Unicode 数字话题去重（`TagExtractor` 独立类，3 单测）；必填缺失 500 + 三条文案；响应=整个 Video；`Video` 实体用 `@JsonProperty` 蛇形 + `@JsonInclude(NON_EMPTY)`。**实测**：publish 返回 `id=1`、`create_time` 为 RFC3339（Jackson 3 默认，勿配 write-dates-as-timestamps，见 §3）；tags 去重 1 行/中文话题正常；outbox `pending`=1 行；缺 play_url/title → 500 文案逐字；无 token → 401。

6. ✅ 第 6 步 getDetail + listByAuthorID（免登录）：`VideoCacheService` 详情读路径 = 缓存(`v1:video:detail:id={id}` **TTL 5 分钟**，不是 24h) → miss 抢锁(`lock:v1:...` TTL 2s) → double check → 查库 → 写缓存 → **Lua 按 token 释放锁**；未抢到锁则 **20ms × 5 次**轮询，等不到直查库且**不回填缓存**（有意差异，防迟到者覆盖）；Redis 异常一律降级直查库不写缓存；查无 → 404 `record not found`。`updatePopularityCache(videoId, delta)` 本阶段定义、**无调用者**（阶段 5 才用；id=0 或 delta=0 直接 return；DEL 详情+`v1:video:entity:{id}`；窗口键 `v1:hot:video:1m:{UTC yyyyMMddHHmm}` ZINCRBY + EXPIRE 2h；异常静默）。`listByAuthorID` 无缓存 `create_time DESC LIMIT 200`，空返回 `[]`。单测 `VideoCacheServiceTest` 3 个（**并发 20 请求只打库 1 次**、缓存命中 0 打库、404）。实测：TTL 264s、404、空数组。
7. ✅ 第 7 步 分块协议（纯设计，无代码）：状态全在 Redis 两键——会话键 `v1:chunk_upload:{uploadId}`（**JSON**，字段蛇形）+ 续传索引 `v1:chunk_upload_hash:{uid}:{fileHash}`（**裸 upload_id，非 JSON**），TTL 都 24h。状态机三条关键边：续传 `I→H`、幂等重传 `U→U`、缺片 `G→F`。选 Redis 而非表的理由：会话是 24h 瞬时状态（TTL 自动清理）、位图高频小写（免行锁/事务开销）、位图即事实来源、丢了重传即可。
8. ✅ 第 8 步 init：五字段必填（**包装类型 `Long`/`Integer` + `@NotNull @Min(1)`/`@NotBlank` + Controller `@Valid`**），`file_size ≤ 200MB`，hash 命中且会话在 → 同一 `upload_id` + 已传下标；未命中新建 `upload_id` = 纳秒 hex(16) + 16 字节随机 hex(32)（共 48 位）。Redis 不可用 → **503 `chunk upload requires redis`**（硬依赖，不兜底）。契约：校验不过 400（文案=底层绑定原文）；`file_size>200MB` → 400 `file size exceeds 200MB limit`。实测：两次 init 同一 `upload_id`、两键 TTL 86330s、停 Redis → 503、恢复 → 200、全量 43/43 绿。
9. ✅ 第 9 步 upload / status / complete（2026-09-16 验收 41 项全过）：`upload` 校验顺序 = 会话 404 → 属主 403 → **越界 400 → 位图幂等 200（早于 file 检查，重传可不带 file）** → file 400 → 读片 500 → **MD5 比对 400（`expected`=客户端值、`actual`=服务端值）** → 建 tmp → 写盘 500 → 置位图回写 500。`status` = `{upload_id, uploaded_chunks, total_chunks}`。`complete` 位图不满 → 400 `not all chunks uploaded` + `missing`(**计数、封顶 5**)/`completed`/`total`；满 → 按 0..N-1 追加字节到 `videos/{uid}/{yyyyMMdd}/{32hex}.mp4`；缺片 → 删半成品 + 500 `chunk N missing`（**tmp 与两键保留**）；成功 → 清 tmp + 删两键 + 返回 `{url,play_url}`。`ChunkException extends ApiException` 携带附加字段，由 ChunkController 局部处理器展开成 JSON。**修复 6 处**见 §3；核心剧本实测：kill -9 后同 `file_hash` 再 init → 同 id + `[0,2]`、幂等重传 200、`cmp` 逐字节一致、Redis 两键清理、4 端点停 Redis 全 503。
10. ✅ 第 10 步 差异清单收口（2026-09-16）：三类差异写入 README §4「阶段 4 差异」——未实现死代码（`DeleteVideo`/`UpdateLikesCount` 无路由）、免费能力（静态资源原生 Range，实测 206）、有意改动 5 条（轮询不回填、publish username 回表而非 JWT claims、运行中 Redis 不可用统一 503、`chunk_index` 必填、URL 用请求头拼）。
11. ✅ **阶段 4 本阶段验收清单跑通**（2026-09-16）：uploadVideo/uploadCover 成功（两键同值）、`accept-ranges: bytes` 且 Range 请求 **206 只回 100 字节**、publish 真实 URL 入库、`create_time` RFC3339、两个读接口免登录且空作者返回 `[]`、详情缓存 TTL ≤300、404、断点续传全剧本、outbox `pending` 行存在（3 条待阶段 7 消费）、`#stage4` 话题入库、封面 `Content-Type: image/png`。

### 阶段 5 · 评论与点赞 —— ✅ 已完成（2026-09-21，第 1~9 步全 ✅，`mvnw test` 59/59 绿）

1. ✅ 第 1 步 建表 + 挂限流：`comments`（`username` 冗余列免 join；三个索引）、`likes`（**`UNIQUE (video_id, account_id)`** 是并发防线）、`notifications`（`type=mention`，阶段 8 的 SSE 数据源）。`RateLimits` 加 `like_write`(30/分)、`comment_write`(10/分) 两条（`byIp=false`，按账号计），`WebConfig` 挂四个写接口。**修复 3 处路径拼写**：`isliked`→`isLiked`、`listMyLikedVideo`→`listMyLikedVideos`、`pubilish`→`publish`（这类错不报错，只让端点静默不鉴权）。
2. ✅ 第 2 步 实体 + Mapper：`Comment`（四个蛇形注解）、`Like`（不对外）、`Notification`；`CommentMapper.listByVideoId/selectOneById/deleteOneById`、`LikeMapper.insertIgnore`（**返回 boolean**）/`deleteLike`（**返回影响行数**）/`countByVideoAndAccount`/`listLikedVideos`（JOIN videos，按**点赞时间**倒序）。**修复 7 处**见 §3；新增 2 个测试类 7 用例。
3. ✅ 第 3 步 `comment/listAll`（**免登录**）：`video_id` 0/缺失 → 400；视频不存在 → **500** `video not found`（用 `selectById` 自判，**不能复用** `VideoCacheService.getDetail` 那个抛 404）；无缓存直查；`created_at ASC LIMIT 200`；空为 `[]`。实测四条全过 + 正向路径（插探针评论验升序与蛇形字段）。
4. ✅ 第 4 步 `comment/publish` + `comment/delete`：publish = `video_id` 400 → content trim 空 400 → 视频 500 → `@Transactional`(INSERT comments + `popularity+1`) → `updatePopularityCache(+1)` → `notifyMentions`；delete = id 400 → 不存在 500 → **非作者 401** → 删除（**不回滚 popularity**，契约如此不是遗漏）。**修复 3 处**见 §3。
5. ✅ 第 5 步 `like/like` + `like/unlike`：预检三连（`video_id ≤ 0` → 400；视频不存在 → 500；已赞/没赞 → 500）→ 事务（`insertIgnore` + `likes_count+1` + `popularity+1`；unlike 用 `deleteLike` 影响行数 + `GREATEST(x-1,0)` 夹底）→ 热度缓存 ±1。**修复 3 处**见 §3；实测 20 并发 → 1 success + 19 already，计数只 +1。
6. ✅ 第 6 步 @提及 → 通知：`MentionParser`（正则 `@(\w+)` **只匹配 ASCII**，`@张三` 不匹配；去重 + 跳过自己）；查不到用户**静默跳过**；通知插入失败**只记日志**（不影响发评论回滚）。`Notification` 实体修复两个字段名（`sender`→`senderId`、`idRead`→`isRead`）。实测：一条评论 5 个 @ → 恰好 1 条通知，字段全对。
7. ⚠️→✅ 第 7 步 异步铺路：`LikeEventPublisher`（`publishLike`/`publishUnlike`）+ `CommentEventPublisher`（`publishComment(commentId, action)`，action 取 `publish`/`delete`），Noop 实现均恒 `false`；写路径改成"**先投递 → 失败才同步降级**"，**投递与缓存是两条独立的边**（阶段 7 防双计）。**重大修复**：`@MapperScan("io.github.p_feed.**")` 把两个发布器接口也当 Mapper 注册 → 34 个测试 error；改为 `annotationClass = Mapper.class` + 8 个 Mapper 加 `@Mapper`。另修 `publikeUnlike`→`publishUnlike`、类名 `NoopLikeEventPulisher`→`NoopLikeEventPublisher`。
8. ✅ 第 8 步 `like/isLiked` + `like/listMyLikedVideos`：`isLiked` 返回 `{"is_liked":bool}`（**必须 `@JsonProperty("is_liked")`**，否则 Jackson 剥掉 `is` 前缀）；**不校验视频存在**（不存在也返回 200 + false）；`listMyLikedVideos` **无 `@RequestBody`**（原版不解析 body），JOIN 按点赞时间倒序 LIMIT 200。**修复 1 处**路径拼写 `/likeMyLikedVideos`→`/listMyLikedVideos`。
9. ✅ 第 9 步 收尾验证：`LikeConcurrencyTest`（双线程 like/unlike）+ `PopularityCacheTest`（DEL 两键 + 窗口 ZINCRBY + TTL≈2h + 两个守卫）；**变异验证**证明并发测试真能抓住 `insertIgnore` 返回值丢失的 bug；降级实测（停 Redis 点赞仍 200、DB 计数正确、日志有 warn）；验收清单第 4 条（250 条评论 → 返回 200 条严格升序）跑通。**遗留**：验收清单第 6 条（11 条评论 → 429）延后到阶段 9 —— `RateLimitInterceptor` 仍是放行壳。

10. ⏳ 下一目标：**阶段 6 · 关注与 Feed 时间线**（《06》：建 `socials` 表 + 五种视频流）。

### 阶段 6 · 关注与 Feed 时间线 —— ✅ 已完成（第 1~11 步 ✅，2026-09-28，`mvnw test` 90/90 绿，本阶段验收清单六条全绿）

1. ✅ 第 1 步 建 `socials` 表（**`UNIQUE (follower_id, vlogger_id)` 是并发防线**；`follower_id`=发起关注者、`vlogger_id`=被关注者；取消关注是 **DELETE** 不是打标记）+ 挂 `social_write` 20/分（`byIp=false` 按账号计）。**修复**：窗口误写 `Duration.ofMillis(1)`（1 毫秒）→ `ofMinutes(1)`。
2. ✅ 第 2 步 `social/follow` + `social/unfollow`：校验顺序 = 参数（`vlogger_id is required` 400）→ 双方账号存在（500 `account not found`）→ 不能关注自己 / 已关注 / 未关注（均 500）；**写序固定** = INSERT → 失效关注流缓存（本步留空函数）→ 发消息（Noop，失败只记日志）；并发防线靠唯一索引，`DuplicateKeyException` → `already followed`。实测 20 并发 → 1 行 + 19 already。
3. ✅ 第 3 步 三个查询：`getAllFollowers`/`getAllVloggers`（id 为 0/不传 → **回退登录态**；先查 `socials` 拿 id（`LIMIT 200` 无 ORDER BY）再批量查 `accounts`）+ `getCounts`（**只取登录态**，看别人主页要用前两个接口或 `account/getProfile`）。**修复**：`VOGGER_ID_IS_REQUIRED`→`VLOGGER_ID_IS_REQUIRED`、`getcounts`→`getCounts`。
4. ✅ 第 4 步 Feed 公共底座：`SoftJwtAuthInterceptor`（**没带头=匿名放行；只要带头就一律从严**，直接委托硬拦截器，防伪造 token 走后门）+ `FeedService.normalizeLimit`（`<=0 或 >50 → 10`）+ `buildFeedVideos`（`is_liked` **一次 IN 批量查**，viewer=0 跳过；`create_time` 取**秒**）+ `FeedVideoItem`（`description` 空则键不出现）。**修复**：`desription`→`description`、`lieksCount`→`likesCount`、`listLikeVideoIds`→`listLikedVideoIds`。
5. ✅ 第 5 步 `FeedMapper` 六条查询（①时间游标 ②二元游标 `(likes_count,id)` ③关注流子查询+时间游标 ④三元游标 `(popularity,create_time,id)` ⑤批量 id ⑥话题 join）+ 第一个 Feed 端点 `feed/listByTag`（400 `tag_name is required`、空话题 `[]`、响应只有 `video_list`）。**修复**：`vidoe_list`→`video_list`（第三次栽在 video 拼错）。测试 `FeedCursorTest` 8 用例：三种游标各翻页不重不漏（含"双键并列只靠 id 兜底"的极端数据）。
6. ✅ 第 6 步 点赞榜 `feed/listLikesCount`（**第一个复合游标接口**）：游标四条校验（`likes_count_before and id_before must be provided together` / `invalid cursor: likes_count_before must be >= 0` / `invalid cursor: id_before must be > 0`，全 400）+ **两个 0 = 第一页**（不报错）+ `has_more` 语义是"本页装满"而非"后面还有" + `next_*` 为空时键不出现（`@JsonInclude(NON_NULL)` 对齐 Go 的 omitempty）。测试 `LikesCountPagingTest` 3 用例；curl 六项全过（跨页无重叠、两个 0 等价首页、坏 token 401）。
7. ✅ 第 7 步 关注流 `feed/listByFollowing`（**硬 JWT**）：请求 `{limit, latest_time?}`（秒），响应 `{video_list, next_time, has_more}`（`next_time` 恒出现、空页 0；`has_more` = 本页装满）。SQL 是**纯拉模型**（`author_id IN (关注列表)` + `create_time < 游标`，发视频不写粉丝收件箱）。**整页响应缓存**：`FollowingFeedCache`（键 `v1:feed:listByFollowing:limit={n}:accountID={id}:before={秒}`、TTL **24h**、value = 整页 JSON；**只有登录用户才缓存**）；miss → 抢锁（**500ms**，详情锁是 2s 别混）→ double check → 查库写缓存；没抢到 → 5×20ms 轮询 → 等不到直查库不写缓存；Redis 挂掉全程降级仍可用。**SCAN 失效**（禁 KEYS）：`invalidate(accountId)` 用 `scan(ScanOptions)` 游标 + 批量 DEL，回填了第 2 步的空函数（social → feed 单向依赖）。测试：`FollowingFeedCacheTest`（`@MockitoBean FeedMapper` 验"第二次不查库"+ TTL≈24h + 失效作用域只删本账号）与 `FollowingFeedIntegrationTest`（真库：关注立刻拉到 → 缓存命中 → 关注 C 触发 SCAN 失效 → 新博主视频立刻出现）。**实测**：TTL 86400 → 3 秒后 86397（**只减不涨 = 命中缓存未重写**）、取关后 `--scan` 空 + TTL `-2`；**变异验证**（把失效模式串改坏）恰好让上述两个测试变红，证明不是空跑。
8. ✅ 第 8 步 `VideoEntityCache` 三级缓存 + 请求合并（2026-09-26，全量 **80/80** 绿）：新增依赖 caffeine（**Boot BOM 托管 3.2.4，pom 不写 `<version>`**；本地 m2 原先没有，首次构建联网下载）+ `feed/VideoEntityCache`（`@Component`，构造注入 `FeedMapper/StringRedisTemplate/RedisKeys`）+ `VideoEntityCacheTest` 4 用例。三级结构：**L1** Caffeine `maximumSize(2000).expireAfterWrite(5s)`，**键与 L2 完全相同**（`v1:video:entity:{id}`——所以 `VideoCacheService` 的 DEL 只清得到 L2，L1 陈旧由 5s 封顶，与原版一致）；**L2** 一次 MGET，**50ms 超时**用 `Thread.ofVirtual()` + 手写 `CompletableFuture.get(50ms)` 实现（Spring Data Redis 没有单次调用超时；**别用 per-call executor 的 try-with-resources**，`close()` 会等任务结束 = 取消掉超时），超时/异常一律全量下沉 L3、迟到结果直接丢弃；**L3** 每个 miss 的 id 一个虚拟线程任务，`inFlight`（`ConcurrentHashMap<Long, CompletableFuture<Video>>`）`putIfAbsent` 抢领头者、`finally` 用**两参数** `remove(id, mine)`，查库走 `FeedMapper.listByIds(List.of(id))`（**单元素不是批量**，与原版 `repo.GetByIDs(ctx, []uint{videoID})` 一致）→ 同步回写 L1 + 异步虚拟线程回写 L2（JSON、TTL 1h、失败仅日志）。输出**按输入 id 顺序重排、缺失静默**（连查库报错也是静默缺项），**不做空值缓存**（防穿透被固化）。测试：`@MockitoBean FeedMapper`（"打了几次库"能被 verify 数出来）+ 真 Redis + 每例随机 id + `invalidateAll()` 清 L1；① 100 并发（CountDownLatch 起跑）只打 1 次库 ② L2 命中回写 L1（删 Redis 键后仍不查库，再清 L1 才查库 1 次做反证）③ 输入 `[b,a,missing]`→输出 `[b,a]`、反着问→`[a,b]`、missing 的键不存在 ④ 异步回写 TTL ∈ [3500,3600]。**本步修掉 4 处**：漏 `@Component`、`for (Long id : missedL2)` 自引用空转导致 L3 永不执行、`keys.key("video:entity:$d", id)` 拼出字面量键（所有 id 撞同一把）、`isEqualTo(OffsetDateTime)` 断言必假（改比 `toInstant()`）。**未接线**：`VideoEntityCache` 当前还没有调用者，第 9 步推荐流才用它。
9. ✅ 第 9 步 推荐流 `listLatest` 冷热分离（2026-09-26，全量 **84/84** 绿）：新增 `feed/TimelineRedisService`（只收口 `v1:feed:global_timeline`：`oldestScore()` / `idsDescByScore(min,max,limit)` / `rebuild(videos)`，**不吞异常**——调用方必须区分"读失败"与"窗口为空"）、`feed/SingleFlight`（`ConcurrentHashMap<String, CompletableFuture<Object>>` + `putIfAbsent` + **两参数** remove，**必须单例 bean**；键 `sf:fallback:global_timeline_rebuild` / `sf:cold:listLatest:{limit}:{reqMillis}` / `sf:stitch:listLatest:{remain}:{cursorMillis}` 只是进程内 map 的键，永不写 Redis）、`dto/ListLatestRequest` + `ListLatestResponse`（**不复用 ListByFollowingResponse**：同形状但那个的 `next_time` 是秒）、`FeedController.listLatest`（`WebConfig:45` 早已白名单，本步没改鉴权配置）。流程：入口读 watermark 失败 → DB 兜底；窗口空 → singleflight 重建（DB 拉 1000 → 批量 ZADD；DB 也空 → 空页 `{[],0,false}`）；游标 ≤ watermark → 纯冷路径（singleflight 查库，**不回写 ZSET**）；否则热路径（`reverseRangeByScore(-inf, reqTime-1)` → `VideoEntityCache.getVideoByIds` → 不足 N 条时用末条实体的 `create_time` 拼 DB，**严格 `<`**）。单位：请求游标 / ZSET score / `next_time` 都是**毫秒**，条目 `create_time` 是**秒**。测试 `ListLatestFlowTest` 4 用例（`@MockitoBean FeedMapper` + 真 Redis + 固定基准毫秒 T0）：热路径 `listLatest`/`listByIds` 一次都没调、冷路径查库 1 次且 `ZCARD` 不变、空窗口重建只发生 1 次（score = 发布毫秒）、stitch 用末条热数据时间做游标（`ArgumentCaptor` 比 `toInstant()`）。**变异验证**：把 `if (videos.size() < normalized)` 改成恒真 → 用例变红，恢复后 4/4。**curl 验收**（`/tmp/feed-step9-accept.sh`，12 项全 PASS）：热路径内容与顺序 = `ZREVRANGE` 前 3 名、冷路径取到更老数据且 ZSET 5→5、`FLUSHDB` 后 200 且重建 `ZCARD` = 库里总数、**停 Redis 仍 200（DB 兜底）**、恢复后 200。
10. ✅ 第 10 步 热榜 `listByPopularity` 快照冻结 + 偏移（2026-09-28，全量 **90/90** 绿）：新增 `feed/HotWindowRedisService`（收口两族键：窗口 `hot:video:1m:{yyyyMMddHHmm}`、快照 `hot:video:merge:1m:{yyyyMMddHHmm}`；`ensureSnapshot(asOfMinute)` = 不存在才 `unionAndStore` 60 个窗口 + `expire` 2 分钟；`idsByOffset(asOfMinute, offset, limit)` = `reverseRange(snapshot, offset, offset+limit-1)` 解析 member）+ `dto/ListByPopularityRequest/Response`（`@JsonInclude(NON_NULL)` 让三个 `next_latest_*` 为空时不出现）+ `FeedController.listByPopularity`（WebConfig 早已白名单）+ `Errors` 两条文案。流程：`latest_popularity < 0` → 400；三元游标成对性 → 400（只给 `latest_popularity` 静默忽略）；try 快照路径 → 失败/首页空 → DB 兜底（`as_of=0`、`next_offset=0`）。三分支：读失败 → DB；读成功但空且 `offset>0` → **空页不进 DB**（`as_of` 保持真值、`next_offset=offset`）；空且 `offset==0` → DB。`next_offset = offset + len(items)`（不是 offset+limit），`has_more = len(items)==limit`。测试 `ListByPopularityFlowTest` 6 用例：快照冻结（同 as_of 追加 99 分不改第一页 + 快照 TTL∈[100,120]）、窗口边界（asOf-30 在、asOf-60 不在）、offset 越界空页不打库、首页空 → DB 兜底（as_of=0 + 三元游标取末行）、快照 id 在 DB 缺失时静默跳过（**这条是修 bug 的回归测试**）、两条 400 文案。**变异验证**：把 `ensureSnapshot` 的 `hasKey` 守卫改成恒 false → 用例 ① 变红，恢复后 6/6。**本步修掉的 bug**：`reorderByIds` 把 `if (row != null)` 写成 `if (rows != null)` → DB 里已删的视频会以 `null` 进结果列表 → 下游 `buildFeedVideos` NPE → **被外层 `catch (RuntimeException)` 吞成"悄悄降级成 DB 兜底页"**（表现为偶发 `as_of=0`）；拼写清理 5 处（见 §3 第 10 步坑位）。**curl 验收**（当时的脚本 `/tmp/feed-step10-accept.sh` 实跑 8 项全 PASS；该脚本在 2026-09-28 机器重启后随 `/tmp` 丢失，等价检查已并入 `.ai/accept/stage6-accept.sh` 的第 3 步「点赞写窗口/快照冻结/跨分钟更新」与第 5 步「limit 归一 + 游标 400」）：快照 TTL=120s、同一 as_of 追加 99 分后第一页不变、下一分钟的新快照里 99 分登顶、`as_of+3600` 滑出窗口 → `as_of=0` 走 DB、`offset=500` 空页（`as_of` 保持真值 + `next_offset=500` + 三个 `next_latest_*` 键不出现）、两条 400 文案逐字、`FLUSHDB` → 200 且 `as_of=0`、停 Redis → 200 且 `as_of=0`。**顺带修掉一个全局配置问题**：Jackson 3 默认开 `FAIL_ON_NULL_FOR_PRIMITIVES`，"缺 `limit`/`offset`"的请求体会直接 500（`Cannot map null into type int`），而 Go 对缺字段一律按零值走 —— `application.yml` 加 `spring.jackson.deserialization.fail-on-null-for-primitives: false`（五个 feed 端点的缺字段/显式 null 实测均 200）。
11. ✅ 第 11 步 全链路降级与前端联调（2026-09-28）：**《06》本阶段验收清单六条全绿**（脚本已入库：`.ai/accept/stage6-accept.sh`，`bash .ai/accept/stage6-accept.sh`，实跑 `FAIL=0`）——① 与参考实现双跑 = SKIP（8080 没跑 Go 版，文档标注可选，差异清单见 README §4）；② 新关注博主 → 其视频立刻出现在关注流第一页（真注册+登录+follow，证明 SCAN 失效生效）；③ 真实点赞写进 `v1:hot:video:1m:{分钟}` 窗口（ZSCORE 从无到 1）+ 同一 as_of 灌 99 分后第一页不变 + 下一分钟新快照里 99 分登顶；④ `FLUSHDB` 后五种流全 200（**FLUSHDB 连 token 白名单一起清 → 脚本里重新登录后才拿到 200**）；⑤ `limit` 0/51 都归一成 10 条（用直查 DB 的 `listLikesCount` 断言，避开时间线窗口干扰）+ 单游标 → 400 文案逐字；⑥ 停 Redis 后 `listLatest` 200（DB 兜底），"100 并发只 1 次查库"由 `VideoEntityCacheTest` 覆盖。**前端联调**（原版前端 + 临时把 vite proxy 指向 8081，验完已还原 + 停掉 dev server）：推荐 tab（`listLatest` 200、真数据渲染）、关注 tab（`listByFollowing` 200，登录后显示 @fb36703）、点赞榜 tab（`listLikesCount` 200）、热榜页（`listByPopularity` 200，**「加载更多」实测发出 `{"limit":10,"as_of":<上一响应的 as_of>,"offset":10}` 并拿到第二页**）、移动端 390×844 布局正常、console 只有 favicon 404。**遗留缺陷（原版前端，非本仓）**：首页三个 tab 的"下拉翻页"不可达 —— `HomeView` 用 `v-show="idx >= visibleRange.start && idx <= visibleRange.end"` 只让 `activeIndex±1` 三屏参与布局，而 `useVideoPlayer.ts:41` 用 `idx = round(scrollTop / el.clientHeight)` 反推下标 → **可达下标恒为 0~2**，永远到不了 `loadMoreIfNeeded` 的 `activeIndex >= items.length - 3` 门槛（实测连按 12 次 ↓ 后 `.active` 仍停在第 3 屏、`scrollTop` 钉在 1224；前端也没有 wheel/touch 处理可绕开）。后端翻页本身正确：curl 两页各 10 条、**交集 0**。**话题页（`listByTag`）前端无页面无入口**，只能 curl 验（脚本第 4 步已覆盖 200）。
12. ✅ 阶段 6 完成判定：本阶段验收清单六条全绿 = 阶段 6 完成；唯一残留是上面那条**原版前端**的分页缺陷，建议留到《09》第 9 步做前端联调改造时一并修（届时同时把 vite proxy 改成 `VITE_API_TARGET` 环境变量，别再硬编码）。**下一步：阶段 7 · outbox 与异步化**（第 2 步的 Noop 发布器换真链路、`v1:feed:global_timeline` 与热度窗口改由消费者维护）。

### 阶段 7 · Kafka 异步化 —— 🚧 进行中（2026-09-28 开工）

1. ✅ 第 1 步 容器进清单（2026-09-28）：`deploy/docker-compose.yml` 加入 `kafka` 服务（KRaft 单节点；`apache/kafka:latest` 实为 **4.3.1**；9092=业务 / 9093=controller；`ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092`）。验收：`config --services` 列出三服务、`ps` kafka Up、`kafka-topics.sh --list` 退出码 0 + **空列表**、broker 日志 `Kafka Server started`。镜像经 **daocloud 镜像站** 112 秒拉取（1ms 站 16 分钟未完，见 §3）。

2. ✅ 第 2 步 建 10 个 topic（2026-09-28）：`deploy/create-topics.sh` 入库并跑通——5 个业务 topic 各 **3 分区**、5 个 `.dlq` 各 1 分区（`--describe` 实测 `PartitionCount: 3 / ReplicationFactor: 1`）。创建时那句 `WARNING: ... period ('.') or underscore ('_') could collide` 是 Kafka 对含 `.`/`_` 名字的例行提醒（metric 名把两者归一成 `_`），非错误。

3. ✅ 第 3 步 控制台手工收发（2026-09-29）：producer 发 `2<TAB>{json}`（event_id=smoke1，key=2 → 落 `Partition:2`），独立消费验证通过。topic 里现有 **offset 0/1 两条同 event_id 消息**（当时多提交了一次）——正好作为"投递可重复"的素材，第 7 步消费者按 event_id 去重；**接 Java 消费者前先把这两条 smoke 消息清掉**。console 工具在 4.3.1 上的三条提示均为正常：`--property is deprecated`（新名 `--reader-property`/`--formatter-property`，旧写法仍可用）、KIP-848 提示、`--timeout-ms` 到点退出的 `ERROR TimeoutException`（标准退出方式）。

4. ✅ 第 4 步 加依赖与配置（2026-09-29）：pom 加 `kafka-clients`（BOM 管版本；实测 `kafka.version=4.1.2`）；yml 加 `feed.kafka.bootstrap`（`${KAFKA_BOOTSTRAP:localhost:9092}`）与 `feed.outbox.fixed-delay-ms/batch-size`；`AppProps` 增 `Kafka`/`Outbox` 两个嵌套 record（保留 `uploadRoot`）。用户确认验收五条通过（依赖树/编译/启动无绑定失败）。

5. ✅ 第 5 步 生产者（2026-09-29）：`mq/KafkaConfig`（参数表 + AdminClient + ApplicationRunner 启动探测）、`mq/EventPublisher`（5 个发布方法 + 契约 record + 2 秒截止降级）、`FeatureFlags.disableMq()` 到位。验收：`EventPublisherTest` 绿、控制台实测 Java 投递（key=2、`Partition:2`、event_id 32 位 hex）、全量 **91/91** 绿。AI 顺手修 4 处小瑕疵（borker typo、两处注释残字、`InterruptedException` 未还原中断标志）。差异清单已记：`acks=1` + 幂等显式关闭（`acks=all` + 幂等列为改进项）。

6. ✅ 第 6 步 消费者骨架（2026-09-29）：`mq/AbstractConsumer`（四条语义 + 提交竞态处理）+ `mq/dlq/DlqProducer`；`AbstractConsumerTest` 4/4 绿、全量 **95/95** 绿。AI 修 3 处：kafka-clients 4.x 已删 `committed(TopicPartition)` 单分区重载（测试改 `committed(Set)`，见 §3）；`DlqProducer` 失败日志误放进 `instanceof InterruptedException` 分支（DLQ 失败会静默吞，已移出）；`AbstractConsumer` 小清理（started 日志级别、`;;`、注释漏字）。

7. ✅ 第 7 步 四个落库消费者 + 发送侧切换（2026-10-02）：消费者在 **`mq/consumers/`**（用户自建包名；`LikeConsumer`/`CommentConsumer`/`SocialConsumer`/`PopularityConsumer` + `ConsumerBootstrap`，`@Profile("worker")`，每 topic 一线程）；发送侧三个域适配器 + `PopularityEventPublisher` + `@Primary` 全部接线；Like/Comment Service 按 Go 原版改成"两条通道各自投递、各自兜底"（双计修复）。验收：`.ai/accept/stage7-like-flow.sh` **全绿**（PASS×8）；积压回放实测（Worker 起后自动消费掉 19:01 投递的 like+popularity，video 1：8/53→9/54）；全量 **99/99** 绿。测试收口：`LikeConcurrencyTest`/`MentionNotificationTest` 改 `@MockitoBean` 发布器→false（固定同步路径，不再依赖 Worker 赛跑）、新增 `LikeDoubleCountTest`（4 例：两条通道独立 + 投递成功时 HTTP 侧不碰库/缓存）。

8. ✅ 第 8 步 Outbox 与时间线（2026-10-03）：`mq/OutboxPoller`（API 进程，`@Scheduled`=`feed.outbox.fixed-delay-ms`；pending→按 create_time 升序→LIMIT batch→投 `video-timeline`→**成功后删行**）、`consumers/TimelineConsumer`（`cg-timeline`，经 `ApiConsumerBootstrap` 在 **API 侧**装配；`TimelineRedisService.addGlobal` = ZADD + 裁剪到最新 1000）、`ApiApplication` 加 `@EnableScheduling`。门控：`@Profile("!worker")` + `feed.outbox.auto`（`src/test/resources/application.properties` 默认 false——避免几十个缓存测试上下文起线程/定时器抢 cg-timeline）。验收：探针行 2 秒内 pending=0、`ZSCORE`=当天毫秒、DLQ=0、`cg-timeline` LAG=0；顺带实测了 10 条历史积压行"补投"（重启 API 后一秒内成批发出）。**过程中修两处笔误**：`remove` 误用（应 `removeRange`；把 0/-1001 当 member 走 String 序列化 → CCE → 消息全进 DLQ 的"半成功"）与 `TimelineEvent` 的 `@JsonProperty(" video_id")` 多空格；测试补"探针不得进 DLQ"断言专抓半成功。全量 **101/101** 绿。

9. ✅ 第 9 步 六项验收清单（2026-10-03）：`.ai/accept/stage7-checklist.sh` 一次跑完 **六项全过**（退出码 0）——① 端到端点赞 ② 积压回放（kill Worker→5 赞全部挂起→重启恰好补落、5 个视频计数各 +1）③ 毒消息（进 DLQ、后续不卡、随后清空 dlq）④ broker 宕机降级（停 Kafka 后点赞/评论仍 200 且【立即】落库；拉起 Kafka 后无需重启服务自动恢复异步）⑤ outbox 裁剪（连发 1005 条 → ZCARD 恒 1000 + 推荐流兜底重建冒烟）⑥ 消费组证明（cg-video-write 12 分区行、cg-timeline 3 行，CONSUMER-ID 齐全、总 LAG=0）。脚本副作用与"后台 Worker 回收方式"写在脚本头（跑完后 Worker 是后台进程：日志 `/tmp/c9-worker.log`、pid `/tmp/c9-worker.pid`）。

**阶段 7（Kafka 异步化）到此全部收工：第 1~9 步 ✅，全量 101/101 绿，两条验收脚本入库（stage7-like-flow.sh / stage7-checklist.sh）。**

> 下一步：《08-Redis缓存与SSE通知》（cg-notification 的装配与扇出在阶段 8 验证）。

## 2. 关键约定与决策（新会话必须遵守）

1. **状态码路线：已选 B（2026-09-09 写入 README §4）**。登录失败 400 统一文案 `invalid username or password`、注册/改名重名 409 `username already exists`；refresh 401、find 404 不动。**红线：登录失败不得用 401**。
2. **错误契约**：`{"error": 文案}` 无包装；文案逐字不改（统一在 `Errors.java`）；请求体解析失败与兜底异常 → 500+原始错误原文。
3. **auth 心智模型**：JWT 验签 + 白名单比对双保险。Redis 三键：`v1:account:{id}`(24h 裸串)、`v1:account:{id}:refresh`(7d)、`v1:refresh:{token}`(7d 反查)。DB 两列是真相、先库后缓存、缓存失败只记日志。拦截器四文案：missing authorization header→invalid authorization header→invalid or expired token→token has been revoked（验签在查缓存前，铁律①）。
4. **铁律③**：Redis 值一律 `StringRedisTemplate` 裸字符串（阶段 8 与 Go 原版互读）。**spring-jdbc 7.0.9 已在传递依赖中**（mybatis-plus starter → spring-boot-starter-jdbc），`JdbcTemplate` 直接用，不需加依赖。
5. **禁止引入 `spring-boot-starter-security`**（会接管 401/403 破坏契约）。
6. **教学节奏与模板**：每步按 `docs/README.md` §1.5 输出（目的→知识点→代码→验收→测试说明，可选前置检查/变更记录）。**测试说明（2026-09-24 起）**：测试文件由 AI 写、用户只审阅，但要用 3~5 行讲清"测什么、怎么测"（覆盖哪几条契约、数据怎么造、断言什么），不贴代码细节。
7. **写入权限**：默认不写代码不改文件；用户明确说"你来写/写入"才动文件。
8. **验收命令必须自包含**（2026-09-25）：给用户的 curl/命令块里不留要手填的占位符（`<A的id>` 这类会被原样粘贴 → 查到不存在的键 → 报 `-2` 之类假故障）。用变量拼键名，或写成脚本直接跑。**验收脚本入库到 `.ai/accept/`**（2026-09-28 起）：只放 `/tmp` 会被重启清空——机器重启就丢了两个脚本，故搬进仓库。
9. **nvim 快捷键**：`<leader>mt` 全量测试、`<leader>JT` 当前测试类、`<leader>Jt` 光标方法、`<leader>mb` jdtls 重新导入（改 pom/依赖后用）、`<leader>sr` spring-boot:run 启动。给命令时同时给快捷键。
10. **不要弹 ask_user_question 提问框**；用文字提问。
11. **文件移动后必须读盘确认**：nvim 里打开的旧 buffer 可能在 `:w` 时把已删/已移的文件写回来（发生过：dto/AccountController.java 复活导致 bean 冲突）。改完文件后以磁盘/grep 为准，别信并行 read 的顺序。
12. **冷启动与巡检（重启机器后照这个顺序做；2026-09-28 实测）**：
    1. **容器**：`docker compose -f deploy/docker-compose.yml ps` —— 两个容器带重启策略，重启机器后一般自己就 healthy；没起就 `up -d` 再等 healthy。
    2. **后端**：`./mvnw spring-boot:run` ↔ `<leader>sr`；用 IPv4 确认：`curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/healthz` → `200`。
    3. **冒烟**（四个公开流各一发，全 200）：
       ```bash
       for p in listLatest listLikesCount listByPopularity; do curl -s --noproxy '*' -o /dev/null -w "$p=%{http_code} " -X POST "http://127.0.0.1:8081/feed/$p" -H 'content-type: application/json' -d '{"limit":2}'; done; curl -s --noproxy '*' -o /dev/null -w "listByTag=%{http_code}\n" -X POST http://127.0.0.1:8081/feed/listByTag -H 'content-type: application/json' -d '{"tag_name":"java"}'
       ```
    4. **前端**：本项目已自带副本 `frontend/`（2026-09-28 从 `feedsystem_video_go-main/frontend` 克隆而来，**proxy 已指向 `http://127.0.0.1:8081`**；克隆时排除了 `node_modules`，用 `npm install --registry=https://registry.npmmirror.com` 重建，约 15 秒）：
       ```bash
       cd frontend && npm run dev -- --host 127.0.0.1     # → http://127.0.0.1:5173/
       ```
       **原版仓那份不要再改**（它的 proxy 指向 8080，留给 Go 版双跑用）。副本自带 `.gitignore` 已忽略 `node_modules`/`dist`，别把 112M 提交进仓库。改副本的源码不受"原版前端不改动"约束——它就是给我们改的（《09》第 9 步要改的 `VITE_API_TARGET` 也在这里做）。
    5. **端口被占**：`ss -ltnp | grep ':8081' | grep -oP 'pid=\K[0-9]+'` 拿 pid 再 `kill`；**别用 `ps | grep 关键字` + `kill`**（会匹配到自己的包装进程，§3 有记录）。
    **一律写 `127.0.0.1` 而不是 `localhost`，curl 加 `--noproxy '*'`**（本机 `ALL_PROXY` + `localhost→::1` 会造出"服务没起来"的假故障，§3 有记录）。

## 3. 技术坑位备忘（踩过/易踩）

- **JDBC URL（重要，已修）**：`application.yml` 的 datasource URL 删掉了 `characterEncoding=utf8mb4` 和 `forceConnectionTimeZoneToSession`——mysql-connector-j 9.7.0 下组合抛 `No enum constant BooleanPropertyDefinition.AllowableValues`。当前：`jdbc:mysql://localhost:3307/feedsystem?connectionTimeZone=LOCAL&allowPublicKeyRetrieval=true&useSSL=false`。文档《02》的 URL 是按旧驱动写的，**以实测为准**。
- java-jwt 4.x 异常在 `com.auth0.jwt.exceptions` 子包；多参数 SQL 必须 MyBatis 的 `@Param`。**`@MapperScan` 已改成 `basePackages="io.github.p_feed", annotationClass=Mapper.class`**——原来写的 `"io.github.p_feed.**"` 会把扫描范围内**所有接口**当 Mapper 注册（阶段 5 第 7 步新建的两个 `EventPublisher` 接口被误扫 → 同类型两个 bean → 34 个 `@SpringBootTest` 全 error）。所以 8 个 Mapper 各自要标 `@Mapper`；**新建普通接口（发布器、策略接口等）不会再被误注册**。
- `Bearer ` 前缀**必须带尾随空格**（`"Bearer "`），否则 substring 截出的 token 头带空格验签必挂——曾因此全接口 401 `invalid or expired token`。
- `Account.@JsonIgnore` 会同时禁反序列化：请求体必须走 dto record，不能 `@RequestBody Account`。
- LambdaQueryWrapper 的条件参数是**方法引用** `Account::getUsername`（无括号），带括号是语法错。
- `AppProps` 构造 `(Redis, Jwt)` 双参；jdtls 报 `Content is not allowed in prolog` 通常是把 buffer filetype 误设 xml（`:e!`+`:set filetype=java`+`:LspRestart`）。图片不能直视，走视觉 MCP（当前 Gemini 地区不可用，截图报错让用户贴文字或直接跑编译）。
- getProfile 四计数用 `JdbcTemplate`+`safeCount`（catch RuntimeException 记 0）；三表未建正好验证容错，建表后可改正式设施。
- **Boot 4 的测试写法和 Boot 3 不一样（2026-09-10 实测）**：① `AutoConfigureMockMvc` 的包名是 `org.springframework.boot.webmvc.test.autoconfigure`（构件 `spring-boot-webmvc-test`），老的 `org.springframework.boot.test.autoconfigure.web.servlet` **不存在**；② 项目有两个 `@SpringBootConfiguration`（ApiApplication/WorkerApplication），`@SpringBootTest` 必须显式 `classes = ApiApplication.class`，否则报 "Found multiple @SpringBootConfiguration..."；③ 测试上下文里**没有 `ObjectMapper` bean**（Boot 4 把 jackson 自动配置拆出核心模块，pom 未引 `spring-boot-starter-jackson`），测试里自己 `new ObjectMapper()` 即可；④ MockMvc 默认 Host 头带端口（`localhost:8081`，取自 server.port），断言 avatar_url 这类绝对 URL 别写死 host，用 `startsWith`+`containsString`+`endsWith` 结构断言。
- **Boot 4 用的是 Jackson 3（包名 `tools.jackson`，2026-09-13 实测）**：`SerializationFeature` 里**没有** `WRITE_DATES_AS_TIMESTAMPS` 这个常量——那是 Jackson 2 的；`spring.jackson.serialization.write-dates-as-timestamps: false` 这种配置在 Boot 4 会直接**绑定失败**（`No enum constant tools.jackson.databind.SerializationFeature...`），启动即挂。Jackson 3 **默认就把日期序列化成 ISO-8601 字符串**（`OffsetDateTime` → `2026-09-13T10:10:15+08:00`），契约格式天然满足，**不要加这段配置**。《04》文档里"配置 write-dates-as-timestamps: false"是按 Boot 3 写的，以实测为准。
- **`MultipartFile.transferTo` 不会自动建父目录**（第 4 步踩）：不先 `Files.createDirectories(target.getParent())` 就 transferTo，会抛 FileNotFoundException 变成 500（文案=路径本身，很迷惑）。阶段 3 头像没踩是因为 AccountService 里显式建过目录；VideoService.store 必须自己建。
- 目录布局契约：落盘目录是 **`videos`/`covers`（复数）**，拼成单数 `video` 文件会写错地方；封面扩展名集合含 `webp` 且没有空串（`Set.of("jpg","jpeg","png","webp")`）。
- 契约文案差一个词也要照抄：头像/视频坏扩展名是 `only .mp4 is allowed`（无 is）/ `only .jpg/.jpeg/.png/.webp allowed`（无 is），封面是 `only .jpg/.jpeg/.png/.webp is allowed`（**有 is**），常量和文案要分开，别复用错的。
- **JSON 蛇形键必须 `@JsonProperty` 桥接**（第 6/8 步两次踩）：`ListByAuthorIDRequest(long authorId)` 漏注解 → 客户端发 `author_id` 绑不上 → 500 `Cannot map null into type long`；`ChunkInitRequest` 的 `fileName` 同理（键 `filename` 绑不上，一律 null）。想快速确认键名绑没绑上，可用项目自身 Jackson 3 写个小探针 `new tools.jackson.databind.ObjectMapper().readValue(json, Dto.class)`。
- **`VideoCacheService` 两个致命笔误（第 6 步）**：① `DETAIL_TTL = Duration.ofMillis(5)`（应为 `ofMinutes(5)`）→ 缓存 5 毫秒就过期 → 并发单测变成"20 次全打库"；② Lua 释放锁写成 `KEY[1] = ARGV[1]`（应为 `KEYS[1] == ARGV[1]`）→ 每次 unlock 报 `Error in execution`，锁只能靠 2s TTL 过期。两者都被 `catch → log.warn` 吞掉，只看测试结果很容易误判成逻辑问题，**看日志**。
- **验证"缓存有没有写进去"要先 `DEL` 键再打请求**：残留旧键会让请求走命中路径、TTL 只显示衰减余量（曾见 4s、264s），看起来像"没写缓存"。
- **类级路径拼错会伪装成"端点不存在"（第 8 步）**：`@RequestMapping("/vidoe")` → 请求 `/video/chunk/init` 落到静态资源兜底，响应 `No static resource video/chunk/init`（500）。同理 `WebConfig` 鉴权清单里 `/video/chunk/static`（应为 `status`）会让该端点**静默免鉴权**——清单里的路径要和 `@PostMapping` 逐字对照。
- **会话键必须走 `sessionKey()`（第 8 步）**：`ChunkSessionStore.create` 曾直接把裸 `uploadId` 当键写入，而 `load()` 读的是带前缀的 `v1:chunk_upload:{id}` → 永远读不到 → 续传永远失败（每次 init 都新 id）。用 `redis-cli --scan --pattern '000*'` 能揪出这类漏前缀的裸键；`sessionKey()` 返回类型误写成 `Object` 正是编译器没能拦住它的原因。
- **chunk 族与 account 族的"绑定失败"状态码不同**：chunk 族要 **400**（文案=底层原文），用 ChunkController 内的**局部** `@ExceptionHandler(HttpMessageNotReadableException.class)` 实现；account 族维持全局的 500。控制器局部处理器优先于 `@RestControllerAdvice`，互不影响。
- **必填校验的"短路掩盖"（第 8 步）**：`if (a == null || ... || b <= 0)` 中若同时缺多个字段，先命中的分支会正常抛 400，看起来通过了；只缺数值字段时才走到包装类型拆箱 → NPE → 500。**测必填字段要把其他必填字段都填满**，否则测不到。
- Hibernate Validator 默认文案跟随系统 locale（本机中文 `不能为null`/`最小不能小于1`），Spring 包装后还带方法签名、很长；契约只要求"底层绑定错误原文"，属合规。要稳定可读可自行从 `MethodArgumentNotValidException` 的 field error 里拼一条。
- **`Files.newOutputStream(APPEND)` 单独用不创建文件**（第 9 步致命坑，已实测）：`complete` 合并时要么 `CREATE + APPEND` 一起给，要么先 `Files.createFile`——只给 `APPEND` 会抛 `NoSuchFileException`，"合并"就永远 500。判断依据可写个小 Java 探针直接验，比读文档可靠。
- **`RedisConnectionFailureException` 接不住"Redis 挂了"的全部情况**（第 9 步隐蔽坑）：Lettuce 的 `RedisCommandTimeoutException` 被 Spring 翻译成 **`org.springframework.dao.QueryTimeoutException`**（不是 `RedisConnectionFailureException`），所以运行中停 Redis 时 catch 不命中 → 返回 500/404 而非契约要求的 503。**改捕父类 `DataAccessException`**（两者都是它的子类）才稳。日志里能看到原始信息是 `Redis command timed out`。
- `ApiException` 是 RuntimeException，**不会被 `catch (IOException)` 接住**：在 try 块里抛业务异常时，要单独写一个 `catch (ApiException e)` 分支（清理资源后 `throw e` 保留精确文案），否则资源不清理或文案被下一个 catch 覆盖。
- `pkill -9 -f ApiApplication` **会杀掉执行它的那个 shell 自身**（命令行文本里含该字符串，也被匹配）。用 `kill -9 $(ss -ltnp | grep ':8081' | grep -oP 'pid=\K[0-9]+')` 按端口杀更稳。
- 契约里 `upload` 族"响应 JSON 键顺序"（`Map.of` 无序）不影响任何解析；契约只约束键名与值，不约束顺序。

### 阶段 5 新增坑位（2026-09-21）

- **Maven 的退出码会被管道吃掉**：`./mvnw -q compile 2>&1 | tail -20; echo $?` 打的是 `tail` 的退出码（恒 0），编译失败也显示成功。要拿真实结果就**重定向到文件后单独查**：`./mvnw compile > /tmp/x.log 2>&1; echo $?; grep -E "ERROR|BUILD" /tmp/x.log`。
- **`WebConfig` 清单里的路径拼写错=静默失效**（阶段 5 一次修了 3 处）：`/like/isliked`、`/like/listMyLikedVideo`、`/comment/pubilish` 都匹配不到任何 handler，端点**不报错、只是不被鉴权/限流**。写完端点后回头逐字对照清单，或用"不带 token 应 401"反查。
- **`io.lettuce.core.dynamic.annotation.Param` 不是 MyBatis 的 `@Param`**（阶段 5 第 2 步）：IDE 自动导入很容易选错，结果是 `#{video_id}` 绑定不上。正确包是 `org.apache.ibatis.annotations.Param`。
- **`#{now(3)}` 是错的写法**：`#{}` 是占位符不是函数调用，会去找名为 `now(3)` 的参数。要函数就写死在 SQL 里（`values(..., now(3))`）。
- **`GREATEST` 至少两个参数**：写成 `GREATEST(likes_count - 1)` 是 SQL 语法错误，执行时才炸（启动不报）——夹底必须写 `GREATEST(x, 0)`。
- **`CommentMapper.selectById`/`deleteById` 与 `BaseMapper` 同名方法冲突**（`long` vs `Serializable`）：MyBatis 会报方法重载歧义，改名成 `selectOneById`/`deleteOneById` 即可。
- **布尔响应字段：Jackson 会剥掉 `is` 前缀**：`record IsLikedResponse(boolean isLiked)` 不加注解会输出 `{"liked":...}`，契约要 `is_liked`，**必须 `@JsonProperty("is_liked")`**。
- **"先投递、后落库"的顺序不能反**（阶段 5 第 7 步，为阶段 7 铺路）：写路径必须是"**先问投递成不成，失败了才自己写库**"。如果在投递前先插库，阶段 7 打开投递后会出现**双写**（消费者又插一次）。comment 的 `publishComment(0L, "publish")` 里那个 0 是刻意占位。
- **投递与缓存是两条独立的边**：`dbEnqueued`（写行+计数）与 `popEnqueued`（热度缓存）要分开判断。合成一个开关，阶段 7 会出现热度**双计**（消费者 + service 各加一次）。
- **并发 bug 的症状是"不报错、慢慢漂"**：`insertIgnore` 返回值不判断时，20 并发 → 10 个 success、计数 +10 但 `likes` 表只多 1 行——唯一索引守住了行，计数却漂了。断言必须同时覆盖"行数"与"计数"。
- **测试的清理代码要放 `finally`**：我在 `LikeConcurrencyTest` 里把清理写在断言之后，断言失败时清理被跳过 → 残留行 + 漂移计数污染库（`likes` 3 行但 `likes_count=4` 就是这么来的）。用 `finally` + `while`（应对"多减了一次计数"）。
- **变异验证是判断"测试是否真在测"的最快手段**：把被测逻辑改坏（如删掉 `insertIgnore` 的返回值判断），测试**必须变红**；不变红说明测试空跑。并发测试尤其要这么做——串行执行时它也会绿。
- **造真并发必须用 `CountDownLatch`**：`start.await()` + 主线程 `start.countDown()` 让所有线程同一瞬间起跑；直接循环调用会串行执行，测不出并发问题。
### 阶段 6 新增坑位（2026-09-24）

- **测试改 JWT 尾字符会假失败（flaky，实测 6%）**：`JwtServiceTest.tamperedSignature_throws` 原来改 token 的**最后一个字符**——base64url 尾字符只有 **4 位有效**（后 2 位是填充），换成同前缀的字符解码后字节完全相同 → 签名没变、验签照样通过 → 报 "Expecting code to raise a throwable"。32 字节签名固定 43 字符，尾字符只有 16 种取值，其中 1 种会撞上（1/16 ≈ 6%）。**改签名段的第一个字符**（6 位全有效）才必然改坏签名。同理，任何"改 base64 末位来造非法输入"的测试都不可靠。
- **`@JsonProperty` 的键名拼错只会在输出里现形**（第三次栽在 `video`）：`FeedListResponse` 写成 `@JsonProperty("vidoe_list")` → 响应变成 `{"vidoe_list":[]}`，curl 的 `jq '.video_list'` 取到 null 才暴露。**收尾前 grep 一遍**：`grep -rn "vidoe\|desription\|lieks\|VOGGER\|seprate" src/`。
- **`<script>` 里的 `<` 必须写成 `&lt;`**：FeedMapper 的游标条件（`create_time &lt; #{latestBefore}`）不转义会导致 XML 解析失败；多个 `<if>` 用 `<where>` 包裹可自动吃掉开头多余的 `and`。
- **`AccountMapper.selectByIds` 会触发 MyBatis-Plus 的告警**：MP 自带同名注入方法，自定义 `@Select` 后打印 `Skipping ... ignoring this injection`——**是告警不是错误**，自定义实现生效。
- **软鉴权的不变式**：`/feed/*` 里除 `listByFollowing`（硬 JWT）外全是软鉴权。**没带头=匿名 viewer=0；带头就必须验签通过**——"带坏头当匿名"是安全漏洞，`SoftJwtAuthInterceptor` 直接委托硬拦截器正是为了结构上防住它（实测伪造 token → 401）。
- **验证"整页缓存有没有命中"不用查 SQL 日志**：看键的 TTL 是否**只减不涨**——命中路径不会重写缓存，TTL 继续衰减；若被重写会跳回 86400。实测第一次 86400 → 3 秒后 86397 即证明命中（第 7 步）。
- **占位符命令别整段粘贴**：`redis-cli TTL "v1:...:accountID=<A的id>:before=0"` 里的 `<A的id>` 不替换会得到 `-2`（键不存在），看起来像"缓存没写进去"。要么先 `--scan --pattern` 把键名抓出来，要么用变量拼。

### 阶段 6 第 8 步坑位（2026-09-26）

- **`String.format` 漏 `%` 会静默拼出字面量键**：`keys.key("video:entity:$d", id)` 里的 `$d` 不是占位符，`String.format` 把整串原样返回 → 所有 id 撞同一把键 `v1:video:entity:$d`（探针实测两个不同 id 拼出的键相等），L1/L2 全串味且**不报任何错**。格式串只认 `%d`/`%s`；写完缓存类用 `redis-cli --scan --pattern 'v1:video:entity:*'` 看一眼键的形状。
- **自引用循环遍历 = 静默空转**：`List<Long> missedL2 = new ArrayList<>(); for (Long id : missedL2) {...}` 遍历的是刚建的空表——L3 永不执行，所有 L2 miss 的 id 都变成"缺失"返回空；因为表是空的，连 `ConcurrentModificationException` 都不会抛。这种"少打/打错一个字母"的 bug 只有断言能抓（`hasSize(1)` 必红）。
- **新类漏 `@Component`：编译能过、测试启动才炸**：`@Autowired private VideoEntityCache` 会报 `NoSuchBeanDefinitionException`（全仓没有别处注册它）。IDE 也不提示，属于"运行时才发现"的一类。
- **Jackson 3 读回 `OffsetDateTime` 会归一化成 UTC**：写出去 `+08:00`、读回来是 `Z`（实测 `2026-09-25T10:30:00+08:00` → `2026-09-25T02:30Z`），而 `OffsetDateTime.equals` 连偏移一起比 → 断言必假。**时间断言一律比 `toInstant()`**（或 `toEpochSecond()`）；第 11 步 Java↔Go 双跑对比也别拿这个字符串逐字比。

### 阶段 6 第 9 步坑位（2026-09-26）

- **`ZSetOperations.reverseRangeByScore` 的参数顺序与原版相反**：Spring Data 是 `(key, min, max, offset, count)`，Go 是 `ZRevRangeByScore(key, max, min, ...)`。照 Go 的顺序搬**不报错，只是静默返回空集**。`±inf` 用 `Double.NEGATIVE_INFINITY / POSITIVE_INFINITY`（实测能翻成 `-inf`/`+inf`）；批量写只有 `add(key, Set<TypedTuple<String>>)`，**没有 `addAll`**。
- **`has_more = 本页是否满条`，断言要和 limit 对齐**：stub 回 2 条、`limit=2` 就是满页（`has_more=true`）；要覆盖"不满页"得把 limit 提到 3。"末条"取的是列表最后一个元素（更老的那条），别拿更近的那条算 `next_time`。这次就是参考实现里这条断言写错了，被补上的测试当场抓到。
- **`while read` 循环体里跑 `docker compose exec -T` 会吃掉 stdin**：实测迭代次数 3 → 1（ZADD 只写进第 1 条，`ZCARD=1`），加 `</dev/null` 恢复 3 次。要么加重定向，要么别用循环——把参数攒进数组，一次 `ZADD key s1 m1 s2 m2 ...` 传完。
- **类名 / 方法名拼错编译不报**：`TimeLineRedisService`（应为 Timeline）、`listLateest`、`loadFollwingFromDb`、`vidoeIds`、`missdL1`、`soore`、`faided`、`fail back` 都是这次扫出来的——定义与调用两边一起拼错就永远编译通过，只有 grep 能抓。收尾例行一行：`grep -rn "vidoe\|missd\|lastest\|Lateest\|TimeLine\|Follwing\|faided\|soore\|fail back" src/`。
- **改类名要连类型名一起替换**：把字段/变量 `timeLineRedisService` 批量替换成 `timelineRedisService` 时，类型名 `TimeLineRedisService` 不会跟着变（我这次就漏了两处，编译才红）。改完用同一行 grep 复查。

### 阶段 6 第 10 步坑位（2026-09-28）

- **Mockito 重复打桩会真调用上一次的 answer（这次让测试红了一把）**：`when(mock.listByIds(any())).thenAnswer(...)` 本身是**一次真实调用**——参数是匹配器占位（null），Mockito 会用**已经注册的 answer** 去处理它。所以第二次 `when(...)` 会拿 `null` 跑第一次的 lambda → `matching(null, ...)` 直接 NPE。对策：同一方法只打一次桩（answer 内部按被请求的 id 过滤，前后两个阶段就都能覆盖），或改用 `doAnswer(...).when(mock).m(...)`。
- **`if (row != null)` 写成 `if (rows != null)` 的代价会被 catch 掩盖**：热榜 `reorderByIds` 里 DB 查不到的 id（视频已删）会被当成 `null` 塞进结果 → 下游 `buildFeedVideos` NPE → 外层 `catch (RuntimeException)` 把它吞成"降级走 DB"。**线上表现是偶发 `as_of=0`，不报错**，只有"快照里有、DB 里没有"的用例能抓到（补的 6 号用例就是）。
- **`unionAndStore` 的第一个参数也是源**（真 Redis 实测）：`unionAndStore(a, [b], dest)` → 15 分；`unionAndStore(a, [a,b], dest)` → **25 分**（a 被算两次）。正确写法是 `unionAndStore(windows.get(0), windows.subList(1, size), dest)`。另外**窗口全空时 dest 会被删掉**，所以"快照不存在"的判断在窗口全空时每次都成立、每次请求重做一次并集（代价可忽略）。
- **拼写家族又添 5 个**（均已修）：`LATEST_BRFORE_AND_ID_MUST_BE_TOGETHER`（常量名错，字符串本身是对的）、`populartiyFromDb`、`windosKeys`、`asOfMintue`、`mintue`（VideoCacheService 第 5 步留下的局部变量）。这类名字拼错编译不报，只能靠 grep 抓。
- **本机 `curl localhost` 会走 SOCKS 代理撞墙（实测，验收脚本会误报"服务没起来"）**：shell 里有 `ALL_PROXY=socks5://127.0.0.1:7890`，curl 把 `localhost` 交给代理，代理自己解析成 **IPv6 `::1`**，而 Tomcat 只在 IPv4 监听 → 连接被拒、`%{http_code}` 返回 `000`；同一时刻 `curl 127.0.0.1:8081/healthz` 是 200。**验收脚本一律用 `127.0.0.1` 并 `export no_proxy/NO_PROXY`（或加 `curl --noproxy '*'`）**，否则会得出一堆假的"服务没起来"。顺带：`ss -ltnp | grep ':8081'` 能直接看到占用端口的 pid，比盲猜快。
- **Jackson 3 默认 `FAIL_ON_NULL_FOR_PRIMITIVES`：请求体缺 `int`/`long` 字段 → 500**（实测 `{"limit":3,"as_of":x}` 缺 `offset` 时返回 `Cannot map null into type int`）。Go 对缺字段是按零值的，所以已在 `application.yml` 关掉该开关；以后新增请求 DTO 用基本类型即可，不必改成包装类型。反过来说，**`{"limit":null}` 现在也会被当成 0**（走 limit 归一），这是有意对齐原版。
- **别用 `ps | grep 关键字` + `kill` 停自己的服务**：包装进程的命令行里就含那个关键字，会匹配到**你自己**（这次我 `pkill -f spring-boot:run` 被工具拦下、换 `ps|grep` 后仍把自己的 bash 杀了，任务直接终止）。要停就按端口找 pid：`ss -ltnp | grep ':8081' | grep -oP 'pid=\K[0-9]+'`。

### 阶段 6 第 11 步坑位（2026-09-28）

- **本机的 `localhost` / `::1` 是双向坑，验收脚本一律写 `127.0.0.1`**：Tomcat 只监听 IPv4（`*:8081`）、vite 默认只监听 IPv6（`[::1]:5173`，Node 把 `localhost` 解析成 `::1`）；再叠加 shell 里的 `ALL_PROXY=socks5://127.0.0.1:7890`（curl 会把 `localhost` 交给代理，代理再解析成 ::1）→ 表现为 `000` / `ERR_CONNECTION_REFUSED`，看起来像"服务没起来"。对策：curl 用 `127.0.0.1` 并 `--noproxy '*'`（或 `export no_proxy`），vite 用 `npm run dev -- --host 127.0.0.1`。
- **SQL 直插 `videos` 的两个坑**：(a) 走 `docker compose exec mysql -e` 必须加 `--default-character-set=utf8mb4`，否则中文标题存成乱码（实测 `翻页探针 1` → `ç¿»é¡µæ¢å¼ 1`）；(b) `author_id` 必须对应 `accounts` 里真实存在的账号，否则 `social/follow` 会 500 `account not found`（脚本里挑"视频最多的作者"时要 `join accounts`）。
- **`listLatest` 看不到"窗口建立之后"才插入的新视频**：时间线 ZSET 只在**为空**时从 DB 重建，新插入的行要等重建或阶段 7 的消费者写进来 —— 所以"插 12 条后立刻断言 `listLatest` 返回 10 条"会拿到旧页。要计数断言就用直查 DB 的 `listLikesCount`，或先 `DEL v1:feed:global_timeline` 触发重建。
- **原版前端首页三个 tab 的"下拉翻页"不可达（前端缺陷，留到《09》修）**：`v-show` 只让 `activeIndex±1` 三屏参与布局，而下标是 `round(scrollTop / el.clientHeight)` 反推的 → 可达下标恒为 0~2，永远够不到 `loadMoreIfNeeded` 的 `index >= len-3`（实测 12 次 ↓ 后 `.active` 仍停在第 3 屏）。热榜页用按钮 + `next_offset` 翻页，不受影响（实测能翻到第二页）。**动过原版前端后必须还原**：它的 vite proxy 是硬编码 `127.0.0.1:8080`，改完记得 `cp` 回备份并 `diff` 核对（它不是 git 仓库，没有 `git checkout` 可兜底）。

### 阶段 7 坑位（2026-09-28）

- **拉 Docker Hub 镜像只有镜像站这条路，且镜像站之间速度差一个数量级**：直连 `registry-1.docker.io` i/o timeout（无 daemon.json、无 systemd proxy）；`docker.1ms.run` 能拉但慢——Kafka 688MB 跑 16 分钟没完（被用户停掉）；**`docker.m.daocloud.io` 112 秒拉完**（mysql/redis/rabbitmq 也出自它）。拉完 `docker tag <镜像站路径> apache/kafka:latest`，compose 引用标准名字。**《07》原稿 `docker.1ms.run/library/apache/kafka:latest` 是笔误**（Kafka 不在 `library/` 命名空间、实测该路径不存在），已改为 `docker.1ms.run/apache/kafka:latest`。
- **`kafka-topics.sh --list` 的"成功"= 空输出 + 退出码 0**：任何 Kafka 命令"没输出"时先 `echo $?`，别把"空列表"当"没跑起来"。
- **fish 里 `$'\t'` 直接报语法错误（2026-09-28 实测）**：fish 不支持 bash 的 ANSI-C 引用，`key.separator=$'\t'` 会报"在变量 $ 之后没有变量名"；fish 要写**不加引号**的 `\t`（展开成真 Tab；双引号里 `"\t"` 则保持字面量、不展开）。《07》第 3 步命令已按 fish 修正；bash 用户仍用 `$'\t'`。
- **`./mvnw -q dependency:tree` 输出 0 行（2026-09-29 实测）**：maven-dependency-plugin 的树走 INFO 日志、被 `-q` 吞掉；验证依赖要写 `./mvnw dependency:tree -Dincludes=org.apache.kafka`——《07》第 4 步原命令已照此修正。
- **kafka-clients 4.x 删了一批旧 API（2026-09-29 实测）**：`KafkaConsumer.committed(TopicPartition)` 单分区重载已不存在，只剩 `committed(Set<TopicPartition>)`（返回 Map）。查签名用 `javap -classpath ~/.m2/repository/org/apache/kafka/kafka-clients/4.1.2/kafka-clients-4.1.2.jar <类>`。
- **阶段 7 起"点赞 → 热度缓存"只有两条路径会写（双计修复）**：投递成功由 PopularityConsumer 写、投递失败由 Service 降级写。**阶段 6 验收脚本第 3 项（点赞→热度窗口）从此依赖 Worker 在跑**，不跑 Worker 时只有降级路径写。
- **`EventPublisherTest` 每次运行真投一条 like 到 `like-events`**：Worker 在跑时会被消费（库里会多出 user 1 赞 video 2 的行）；开 Worker 前先 `--delete --topic like-events` + `bash deploy/create-topics.sh` 清干净。
- **Worker 启动命令（2026-10-02 用户自行落地的 pom 机制）**：`./mvnw spring-boot:run -Dmain.class=io.github.p_feed.WorkerApplication`；API 仍 `./mvnw spring-boot:run`（`<leader>sr`）。注意 **`-Dspring-boot.run.main-class` 在本项目无效**（pom 显式 `<mainClass>${main.class}</mainClass>` 优先级更高，传了被忽略、起出来还是 API）——《02》里那几处旧写法已失效，以本命令为准。
- **消费者"半成功"陷阱（第 8 步真踩过）**：`TimelineConsumer.handle` 第一行 ZADD 成功、第二行 `remove` 误用抛 CCE → 症状是"Redis 写对了 + 消息全进 DLQ"。教训：测试只断言"成功侧"会漏掉它（ZADD 已经发生）——`TimelineOutboxTest` 已加"探针消息不得进 DLQ"断言（等重试窗口 1+2+4s 过去再查 DLQ）。**写 ZSET 时分清 `remove`（删指定 member）和 `removeRange`（按排名区间删）**。
