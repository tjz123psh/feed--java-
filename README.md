# feed-java —— 短视频 Feed 系统（Java）

用 **Java 21 + Spring Boot 4.0.8 + MyBatis-Plus + MySQL 8 + Redis 7 + Kafka**
实现的短视频 Feed 系统：账号与 JWT、视频与分块上传、评论点赞、关注与五种 Feed 流、
Kafka 异步化、Redis 缓存与 SSE 实时通知、限流与优雅停机。
接口契约、缓存 key、错误文案以 `docs/` 中的规格为准；关键设计决策与已知限制见本文 §5、§6。

> 这份 README 是《09》第 7 步的交付物：新人从 `docker compose up` 一路走到前端可用。

## 1. 架构

```
客户端(Vue3 前端 :5173 / curl)
   │ HTTP :8081                          ┌──────────────────────────────────────────┐
   ▼                                     │ API 进程  ApiApplication                  │
   ├───────▶  全部 HTTP 端点  ──────────▶│  · JWT / 限流拦截器                        │
   ◀───────   SSE 推流(通知)  ◀──────────│  · SseHub（进程内 SSE 连接与推送）          │
                                         │  · OutboxPoller → 投 video-timeline       │
                                         │  · cg-timeline 消费者                      │
                                         │  · cg-notification 消费者 ×3               │
                                         └───────────────┬──────────────────────────┘
                                                         │ Kafka :9092
                                         （5 业务 topic ×3 分区 + 5 个 .dlq）
                                                         │
                                         ┌───────────────┴──────────────────────────┐
                                         │ Worker 进程  WorkerApplication            │
                                         │  · cg-video-write 消费者 ×4：              │
                                         │    Like / Comment / Social / Popularity   │
                                         │    （落库 · 计数 · 缓存失效与热度窗口）     │
                                         └───────────────┬──────────────────────────┘
                                                         │
                              MySQL :3307（数据真相）      Redis :6379（缓存/锁/限流）
                              三者由 deploy/docker-compose.yml 提供
```

**部署约束（两条，务必遵守）**

- **API 进程保持单实例**：`OutboxPoller` 没有认领机制，多开会把同一批 outbox 行重复投递
  （`mq/OutboxPoller.java` 头注释）。
- **SSE 只推本进程连接**：`SseHub` 是进程内 `Map`，跨进程（多实例）收不到实时帧，
  靠 `/notification/list` 轮询兜底。

## 2. 启动

前置：Docker + JDK 21 + Node 20+。

```bash
# 1) 依赖容器：MySQL(3307) / Redis(6379) / Kafka(9092)
cd deploy && docker compose up -d && cd ..

# 2) 建 10 个 topic（5 业务 ×3 分区 + 5 个 DLQ；等 Kafka 就绪后跑）
bash deploy/create-topics.sh

# 3) API 进程（:8081）—— 另开一个终端
./mvnw spring-boot:run

# 4) Worker 进程 —— 再开一个终端；看到 "worker consumers started: 4" 即就绪
./mvnw spring-boot:run -Dmain.class=io.github.p_feed.WorkerApplication

# 5) 冒烟：健康检查 + 四个公开流
curl -s http://127.0.0.1:8081/healthz                      # {"status":"ok"}
for p in listLatest listLikesCount listByPopularity; do
  curl -s -o /dev/null -w "$p=%{http_code} " -X POST "http://127.0.0.1:8081/feed/$p" \
    -H 'content-type: application/json' -d '{"limit":2}'; done
curl -s -o /dev/null -w "listByTag=%{http_code}\n" -X POST http://127.0.0.1:8081/feed/listByTag \
  -H 'content-type: application/json' -d '{"tag_name":"java"}'

# 6) 前端（仓库自带 frontend/ 副本，2026-10-08 已整体重构：影院舞台风格）
cd frontend && npm install --registry=https://registry.npmmirror.com   # 首次
npm run dev -- --host 127.0.0.1                                        # → http://127.0.0.1:5173/
# 代理目标默认 127.0.0.1:8081，可用 VITE_API_TARGET=http://host:port 覆盖
```

常见坑：本机 `curl localhost` 可能被 SOCKS 代理劫持成 `::1`——统一写 `127.0.0.1`（或 `--noproxy '*'`）；
Worker 的 `-Dmain.class` 传参方式来自 pom 的 `<mainClass>${main.class}</mainClass>`，写法与网上常见的
`-Dspring-boot.run.main-class` 不同。

## 3. 测试与验收

- **单元/集成套件**：`./mvnw test` —— 34 个测试类、131 条用例。
- **验收脚本库** `.ai/accept/`（bash + curl + jq，全部自包含、退出码 = 失败条数）：

| 脚本 | 覆盖 |
|---|---|
| `stage6-accept.sh` | 关注流/五种流分页/热榜快照/FLUSHDB 自愈 |
| `stage7-like-flow.sh` | 点赞 200 → ≤5s 落库、计数 +1、LAG=0、DLQ 空 |
| `stage7-checklist.sh` | 六项清单：积压回放、毒消息 DLQ、broker 宕机降级、outbox 裁剪等 |
| `stage8-notif-read.sh` / `stage8-sse-auth.sh` / `stage8-sse-stream.sh` | 通知读端 / SSE 鉴权 / 挂流与 keepalive |
| `stage8-notification-flow.sh` | 点赞 → SSE 收到 `data:` 帧 + 落库 + 自赞不通知 |
| `stage8-cache-map.sh` | FLUSHDB 生存性、两键同消、热度窗口、merge 快照 |
| `stage9-ratelimit.sh` | 10×400→429 序列、响应体逐字、无 Retry-After、TTL 不重置 |
| `stage9-observability.sh` | actuator health/metrics、优雅停机 |
| `stage9-degradation.sh` | Redis 挂 fail-open/降级、三件全停 /healthz 200 |
| `stage9-matrix.sh` | **一键矩阵**：前置检查（三容器 + API + Worker）+ `mvnw test` + 七本快验收 + 降级/全停 |

矩阵用法（前置：三容器、API、Worker 都在跑）：

```bash
bash .ai/accept/stage9-matrix.sh    # 全绿 = 0 项失败
```

## 4. 压测（2026-10-08）

方法：本机无 `ab`，用 `loadtest`（node，ab 风格百分位表；`--cores 1` 单进程、无 keep-alive，
与 ab 默认一致）。每端点先预热 300 请求，再正式 `-n 1000 -c 50` 跑两轮，下表取第 2 轮。

| 接口 | P50 | P95 | 平均延迟 | 吞吐 |
|---|---|---|---|---|
| `video/getDetail` | 12 ms | 31 ms | 12.7 ms | 3690 rps |
| `feed/listLatest` | 14 ms | 33 ms | 14.7 ms | 3195 rps |

解释（数字之外更重要的是为什么）：

- 读路径是"缓存命中 + 一次 HTTP"（~3.2–3.7k rps、P50 12–14 ms），瓶颈在每请求新建 TCP 连接
  与序列化，不在 JVM GC 或框架本身。
- **预热轮明显更差**（getDetail P95 49 ms、listLatest P95 87 ms）：JIT 编译与缓存预热占主导，
  这正是正式采样前必须先预热的原因。
- 未开 keep-alive 时每请求一次连接建立，这是数字里最大的一块固定成本；真实前端复用连接会更好看。

## 5. 关键设计决策与取舍（收口）

### 5.1 交付点名项（逐条确认）

| # | 事项 | 现状 | 说明 |
|---|---|---|---|
| 1 | 状态码（账号族） | ✅ | 登录失败 400（红线：不得 401）、注册/改名重名 409、refresh 401、find 404；防用户名枚举。证据：`Errors.java` + `AccountService` |
| 2 | refresh 兜底列查询 | ✅ | 按 `accounts.refresh_token` 列 `WHERE refresh_token=?` 反查 + 命中回填缓存（写库先行、缓存后写）；`TokenStore.java:116` |
| 3 | SSE 鉴权加白名单 | ✅ | 验签后补《03》白名单比对 → 登出/换发立即 401 `token has been revoked`（仅验签会让旧 token 在自然过期前继续可用）。`SseAuthInterceptor.java:65` |
| 4 | event_id 幂等 | ⚠️ 部分 | like/follow 靠唯一索引天然幂等 ✅；**comment 消费与通知落库无幂等键**——按《07》《08》指示如实记为已知弱点，改进项 = `consumed_events` 去重表（5 个事件已预留 `event_id`，消费方尚未使用） |
| 5 | mention 不推 SSE | ✅ | mention 由评论路径直接落库；SSE 只覆盖 like/publish/follow（《05》《08》语义），靠 `/notification/list` 兜底 |
| 6 | 《04》轮询耗尽不回填缓存 | ✅ | 抢锁失败等不到 → 直查库且不回填，防迟到者覆盖持锁者刚写的版本；`VideoCacheService.java:90` |
| 7 | 热度失败打 warn | ✅ | 热度/缓存更新失败 5 处 catch 全部 `log.warn`，仅可观测性、不改行为 |
| 8 | Redis 命令 2s 超时约束 | ⚠️ 约束 | 全部 Redis 命令共享 `timeout: 2s`。影响：Redis 挂时读接口实测最长 **14.1s**（7 条命令×2s）后降级。可选改进：Lettuce `REJECT_COMMANDS` |
| 9 | 《05》listAll 无缓存 | ✅ | 契约规定无缓存直查（`created_at ASC LIMIT 200`），维持 |
| 10 | 部署约束两条 | ✅ | API 单实例（OutboxPoller 无认领机制）；SSE 只推本进程连接 |

### 5.2 各阶段取舍（源：`docs/README.md` §4）

- **阶段 4**：不实现未注册路由（`DeleteVideo`/`UpdateLikesCount` 无路由，实现它们等于新增未授权 API 面）；
  静态资源白拿 HTTP Range（实测 206）；有意取舍 5 条——轮询不回填、publish username 回表取当前值、
  chunk 族 Redis 运行中不可用统一 503、`chunk_index` 必填、上传 URL 用请求头拼。
- **阶段 6**：`VideoEntityCache` L3 为"每个 id 单查"（singleflight 内单元素 IN）；L1 用 Caffeine（与 L2 同键、5s 封顶）；
  `create_time` 被 Jackson 3 归一化成 UTC（时间比对用 `toInstant()`）；时间线重建"只重入一次"；`unionAndStore` 第一参数也是源；
  请求体缺字段按零值（关闭 `FAIL_ON_NULL_FOR_PRIMITIVES`）；热榜降级页 `as_of=0`/`next_offset=0` 的翻页语义；register 不发 token。
- **阶段 7**：生产者 `acks=1` + 显式关闭幂等（宽松语义；`acks=all`+幂等列为改进项）。
- **阶段 8**：私信不校验收件人存在（维持宽松，加校验会改变既有行为）；通知/SSE 401 走白名单（登出即失效）；
  SSE 只推本进程连接（架构事实）。

### 5.3 改进项 ↔ 对应测试

| 改进/增强 | 对应测试 |
|---|---|
| SSE 白名单即失效 | `SseAuthTest`、`stage8-sse-auth.sh` |
| refresh 列查询 | `TokenStoreTest` |
| 轮询耗尽不回填 | `VideoCacheServiceTest` |
| 热度失败 warn 可观测 | `PopularityCacheTest`、`stage9-degradation.sh`（降级日志实测） |
| Caffeine L1 | `VideoEntityCacheTest` |
| 优雅停机 + actuator health/metrics | `stage9-observability.sh` |
| 限流五规则（照搬《09》参数） | `RateLimitTest`、`stage9-ratelimit.sh` |
| Outbox 轮询 + 时间线消费者 | `TimelineOutboxTest`、`stage7-checklist.sh` |
| 一键自测矩阵 | `stage9-matrix.sh` |

## 6. 已知限制 / 未做的改进项（如实记录）

- comment 消费与通知落库无 `event_id` 去重（改进项：`consumed_events` 表；见 §5.1-4）。
- 生产者 `acks=1`、幂等关闭（见 §5.2 阶段 7）。
- Redis 故障时读接口最长 ~14s 后降级（§5.1-8；可选 `REJECT_COMMANDS` 快速失败）。
- 前端已于 2026-10-08 整体重构（「影院舞台」，见 `DESIGN.md`），并修复了旧版"下拉翻页不可达"的缺陷（全部 slide 参与布局、video 仅挂载 active±1；实测 10→13 条分页触发正常）。

## 7. 目录速览

```
src/main/java/io/github/p_feed/    account · auth · video · chunk · comment · like ·
                                   social · feed · notification · message · mq · infra · web · tool
src/test/java/...                  33 个测试类（契约/并发/缓存/消费者/限流）
deploy/                            docker-compose.yml（三容器）· create-topics.sh
docs/                              02~09 共 8 份阶段教程 · cache-map.md（Redis 键地图） · 全景/（项目全景与阶段记录）
.ai/accept/                        13 本验收脚本（含一键矩阵）
.ai/WORKING.md                     AI 交接文档：每阶段进度、坑位、决策记录
frontend/                          前端（Vue 3，影院舞台风格；vite proxy → 8081）
```
