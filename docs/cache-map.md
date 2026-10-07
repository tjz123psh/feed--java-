# 缓存地图（阶段 8 第 7 步收口）

> 全项目 Redis 键一览（前缀 `v1:`，由 `RedisKeys` 统一拼装；唯一例外见最后一行）。
> 心智模型一句话：**DB 是真相，缓存全是可重建物**——任何键被删/被清只影响一次回源，不影响正确性。
> 逐项实测记录见文末。

| key | 写方 | 失效方 | TTL | 验证动作 |
|-----|------|--------|-----|----------|
| `v1:account:{id}` | 登录 / 刷新 / 改名 | 登出 / 改密 / 再登录 | 24 h | 《03》已做 |
| `v1:account:{id}:refresh` | 登录 / 刷新 | 登出 / 改密 | 7 d | 《03》已做 |
| `v1:refresh:{token}` | 登录 / 刷新（反查索引） | 登出（按旧值清理） | 7 d | 《03》已做 |
| `v1:video:detail:id={id}` | getDetail miss 回填 | 热度变更（点赞/评论/热度消费者） | 5 min（锁 `lock:v1:video:detail:id={id}` **2 s**） | 点赞后立刻查：key 应已消失 |
| `v1:video:entity:{id}` | Feed L3 异步回写（虚拟线程） | 同上（一次 DEL 两键） | 1 h | 同上 |
| `v1:feed:global_timeline` | timeline 消费者 / 冷重建（DB 最新一批） | 只裁剪不失效（`removeRange 0 -1001`） | 永久，裁剪留最新 **1000** | 《07》已做 |
| `v1:feed:listByFollowing:*` | 整页 miss 回填 | 关注变更 SCAN | 24 h（锁 **500 ms**） | 《06》已做 |
| `v1:hot:video:1m:{UTC yyyyMMddHHmm}` | `VideoCacheService.updatePopularityCache` ZINCRBY（member=videoId 十进制串） | 靠 TTL 滚出 | 2 h | 点赞后查 ZSCORE |
| `v1:hot:video:merge:1m:{UTC yyyyMMddHHmm}` | `HotWindowRedisService.ensureSnapshot`（`/feed/listByPopularity` 触发；ZUNIONSTORE 最近 60 个窗口） | TTL | 2 min | 调一次热榜看 key |
| `v1:chunk_upload*` | chunk/init | chunk/complete | 24 h | 《04》已做 |
| `feedsystem:ratelimit:*` | 每请求 INCR | 窗口到期 | 窗口长度 | 《09》；**全项目唯一没有 `v1:` 前缀的键** |

两条时间口径约定（写方与读方必须一致，代码注释里都写了）：

- 热度窗口按 **UTC 整分钟** 截断（`yyyyMMddHHmm`），写方 `VideoCacheService` 与读方 `HotWindowRedisService` 共用同一格式；
- 详情/实体两键的失效只在 `updatePopularityCache` 一处发生——任何"视频热度/内容变了"的写路径都必须走它。

## 实测记录

- **2026-10-07 第 7 步（`.ai/accept/stage8-cache-map.sh`，14 PASS / 0 FAIL）**：
  - `FLUSHDB` 后：通知读端、私信发送/列表全部可用（老 token 走 DB 兜底 + 回填）✓
  - 点赞一条视频：`v1:video:detail:id={id}` 与 `v1:video:entity:{id}` **同一瞬间消失**（约 100 ms 内双 0）✓
  - 热度窗口 `v1:hot:video:1m:202610071345` 记到 `ZSCORE=1` ✓；merge 快照 `TTL=120 s` ✓
  - `FLUSHDB` 后 `v1:feed:global_timeline` 冷重建（`ZCARD=8`，DB 回源）✓
