# feed-java —— 短视频 Feed 系统的 Java 重写

把参考实现 `feedsystem_video_go-main`（Go 版）按 `docs/` 里的 12 份中文教程逐阶段重写为
**Java 21 + Spring Boot 4.0.8 + MyBatis-Plus + MySQL 8 + Redis 7 + Kafka** 版本。
接口契约、缓存 key、错误文案与原版逐字对齐；有意差异全部记录在本文 §5。

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
- **SSE 只推本进程连接**：`SseHub` 是进程内 `Map`，跨进程（Go 混合部署 / 多实例）收不到实时帧，
  靠 `/notification/list` 轮询兜底（`docs/README.md` §4 阶段 8 有实测记录）。

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

# 6) 前端（仓库自带 frontend/ 副本，vite proxy 已指向 8081）
cd frontend && npm install --registry=https://registry.npmmirror.com   # 首次
npm run dev -- --host 127.0.0.1                                        # → http://127.0.0.1:5173/
```

常见坑：本机 `curl localhost` 可能被 SOCKS 代理劫持成 `::1`——统一写 `127.0.0.1`（或 `--noproxy '*'`）；
Worker 的 `-Dmain.class` 传参方式来自 pom 的 `<mainClass>${main.class}</mainClass>`，写法与网上常见的
`-Dspring-boot.run.main-class` 不同。

## 3. 测试与验收

- **单元/集成套件**：`./mvnw test` —— 33 个测试类、135 条用例；4 条 Java↔Go 互通用例在 Go 版
  （:8080）不在线时自动 skip。
- **验收脚本库** `.ai/accept/`（bash + curl + jq，全部自包含、退出码 = 失败条数）：

| 脚本 | 覆盖 |
|---|---|
| `stage6-accept.sh` | 关注流/五种流分页/热榜快照/FLUSHDB 自愈 |
| `stage7-like-flow.sh` | 点赞 200 → ≤5s 落库、计数 +1、LAG=0、DLQ 空 |
| `stage7-checklist.sh` | 六项清单：积压回放、毒消息 DLQ、broker 宕机降级、outbox 裁剪等 |
| `stage8-notif-read.sh` / `stage8-sse-auth.sh` / `stage8-sse-stream.sh` | 通知读端 / SSE 鉴权 / 挂流与 keepalive |
| `stage8-notification-flow.sh` | 点赞 → SSE 收到 `data:` 帧 + 落库 + 自赞不通知 |
| `stage8-cache-map.sh` | FLUSHDB 生存性、两键同消、热度窗口、merge 快照 |
| `stage8-interop-mixed.sh` | Go 8080 与 Java 8081 混合部署（SSE 进程边界） |
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
与 ab 默认一致）——两版**用同一工具、同一参数、同时在线、共享同一 MySQL/Redis**。
每端点先预热 300 请求，再正式 `-n 1000 -c 50` 跑两轮，下表取第 2 轮。

| 接口 | 版本 | P50 | P95 | 平均延迟 | 吞吐 |
|---|---|---|---|---|---|
| `video/getDetail` | Java 8081 | 12 ms | 31 ms | 12.7 ms | 3690 rps |
| `video/getDetail` | Go 8080 | 13 ms | 19 ms | 12.8 ms | 3650 rps |
| `feed/listLatest` | Java 8081 | 14 ms | 33 ms | 14.7 ms | 3195 rps |
| `feed/listLatest` | Go 8080 | 12 ms | 24 ms | 12.8 ms | 3636 rps |

解释（数字之外更重要的是为什么）：

- **吞吐与中位数两版同量级**（~3.2–3.7k rps、P50 12–14 ms）：读路径都是"缓存命中 + 一次 HTTP"，
  瓶颈在每请求新建 TCP 连接与序列化，不在 JVM GC 或框架本身。
- **预热轮明显更差**（Java getDetail P95 49 ms、listLatest P95 87 ms）：JIT 编译与缓存预热占主导，
  这正是正式采样前必须先预热的原因；两版都遵循同一流程。
- **Go 的 P95 略低**（getDetail 19 vs 31 ms；listLatest 24 vs 33 ms）：尾部差异主要来自
  Servlet 容器的线程模型与 Tomcat 连接接受路径；中位数和吞吐说明两者读性能等价。
- 未开 keep-alive 时每请求一次连接建立，这是数字里最大的一块固定成本；真实前端复用连接会更好看。

## 5. 差异清单（与 Go 原版的有意差异，收口）

### 5.1《09》第 7 步点名项（逐条确认）

| # | 名字 | 现状 | 理由 / 影响面 |
|---|---|---|---|
| 1 | 状态码路线 B（账号族） | ✅ 已落地 | 登录失败 400（红线：不得 401）、注册/改名重名 409、refresh 401、find 404；防用户名枚举。证据：`Errors.java` + `AccountService` |
| 2 | refresh 兜底列查询 | ✅ 已落地 | 原版 FindAll 全表扫是坏做法（《03》），改 `WHERE refresh_token=?` + 命中回填索引；`TokenStore.java:116` |
| 3 | SSE 鉴权加白名单（决策 b） | ✅ 已落地 | 验签后补《03》白名单比对 → 登出/换发立即 401 `token has been revoked`；参考实现只验签（15 分钟窗口内旧 token 仍可用）。`SseAuthInterceptor.java:65` |
| 4 | event_id 幂等 | ⚠️ 部分 | like/follow 靠唯一索引天然幂等 ✅；**comment 消费与通知落库无幂等键**——按《07》《08》指示如实记为已知弱点，改进项 = `consumed_events` 去重表（5 个事件已预留 `event_id`，消费方尚未使用） |
| 5 | mention 不推 SSE | ✅ 已落地 | mention 由评论路径直接落库；SSE 只覆盖 like/publish/follow（《05》《08》语义），靠 `/notification/list` 兜底 |
| 6 | 《04》轮询耗尽不回填缓存 | ✅ 已落地 | 抢锁失败等不到 → 直查库且不回填，防迟到者覆盖持锁者刚写的版本；`VideoCacheService.java:90` |
| 7 | 热度失败打 warn（原版静默） | ✅ 已落地 | 原版四步 `_ = ...` 连日志都没有；Java 侧 5 处 catch 全部 `log.warn`，仅可观测性、不改行为 |
| 8 | DEL 受 Lettuce 2s 命令超时约束 | ⚠️ 约束 | 原版 DEL 不设超时；Java 全部 Redis 命令共享 `timeout: 2s`（无法照搬）。影响：Redis 挂时读接口实测最长 **14.1s**（7 条命令×2s）后降级。可选改进：Lettuce `REJECT_COMMANDS` |
| 9 | 《05》listAll 无缓存 | ✅ 维持 | 契约规定无缓存直查（`created_at ASC LIMIT 200`），未改动 |
| 10 | 部署约束两条 | ✅ 已写入 §1 | API 单实例（OutboxPoller 无认领机制）；SSE 只推本进程连接 |

### 5.2 各阶段已收录差异（源：`docs/README.md` §4）

- **阶段 4**：不实现死代码路由（`DeleteVideo`/`UpdateLikesCount` 未注册路由，实现它们等于新增未授权 API 面）；
  静态资源白拿 HTTP Range（实测 206）；有意改动 5 条——轮询不回填、publish username 回表取当前值、
  chunk 族 Redis 运行中不可用统一 503（原版 500/404）、`chunk_index` 必填、上传 URL 用请求头拼。
- **阶段 6**：`VideoEntityCache` L3 按原版"每 id 单查"而非批量；L1 用 Caffeine（与原版 L1 同键、5s 封顶）；
  `create_time` UTC 归一化（跨实现比对注意）；时间线重建"只重入一次"；`unionAndStore` 第一参数也是源；
  请求体缺字段按零值（关闭 `FAIL_ON_NULL_FOR_PRIMITIVES`）；热榜降级页翻页语义照原版；register 不发 token。
- **阶段 7**：生产者 `acks=1` + 显式关闭幂等（对齐原版宽松语义；`acks=all`+幂等列为改进项）。
- **阶段 8**：私信不校验收件人存在（照抄原版，加校验反而破坏对齐）；通知/SSE 401 与参考实现不同（决策 b）；
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
- 与 Go 版的逐项差分验收（D1–D10）属选修，未做时以第 6 步自测矩阵为验收依据。
- 原版前端首页"下拉翻页"存在可达性缺陷（`v-show` 只让相邻三屏布局、下标反推 ⇒ 永远够不到翻页阈值），
  `frontend/` 副本留待修复——后端翻页本身正确（curl 两页交集为 0）。

## 7. 目录速览

```
src/main/java/io/github/p_feed/    account · auth · video · chunk · comment · like ·
                                   social · feed · notification · message · mq · infra · web · tool
src/test/java/...                  33 个测试类（契约/并发/缓存/消费者/限流）
deploy/                            docker-compose.yml（三容器）· create-topics.sh
docs/                              12 份重写教程 · cache-map.md（Redis 键地图）
.ai/accept/                        14 本验收脚本（含一键矩阵）
.ai/WORKING.md                     AI 交接文档：每阶段进度、坑位、决策记录
frontend/                          前端副本（vite proxy → 8081）
```
