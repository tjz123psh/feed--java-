package io.github.p_feed.feed;

import io.github.p_feed.feed.dto.FeedAuthor;
import io.github.p_feed.feed.dto.FeedListResponse;
import io.github.p_feed.feed.dto.FeedVideoItem;
import io.github.p_feed.feed.dto.LikesCountResponse;
import io.github.p_feed.feed.dto.ListByFollowingResponse;
import io.github.p_feed.feed.dto.ListByPopularityRequest;
import io.github.p_feed.feed.dto.ListByPopularityResponse;
import io.github.p_feed.feed.dto.ListLatestResponse;
import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.like.LikeMapper;
import io.github.p_feed.video.Video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class FeedService {

    private final HotWindowRedisService hotWindowRedisService;
    private static final Logger log = LoggerFactory.getLogger(FeedService.class);
    private static final int REBUILD_SIZE = 1000;

    private final LikeMapper likeMapper;
    private final FeedMapper feedMapper;
    private final FollowingFeedCache followingFeedCache;
    private final VideoEntityCache videoEntityCache;
    private final TimelineRedisService timelineRedisService;
    private final SingleFlight singleFlight;

    public FeedService(
            LikeMapper likeMapper,
            FeedMapper feedMapper,
            FollowingFeedCache followingFeedCache,
            SingleFlight singleFlight,
            TimelineRedisService timelineRedisService,
            VideoEntityCache videoEntityCache,
            HotWindowRedisService hotWindowRedisService) {
        this.likeMapper = likeMapper;
        this.feedMapper = feedMapper;
        this.followingFeedCache = followingFeedCache;
        this.videoEntityCache = videoEntityCache;
        this.timelineRedisService = timelineRedisService;
        this.singleFlight = singleFlight;
        this.hotWindowRedisService = hotWindowRedisService;
    }

    // limit归一（五个流共用）
    public static int normalizeLimit(int limit) {
        return (limit <= 0 || limit > 50) ? 10 : limit;
    }

    /**
     * 把一组视频组装成 Feed 元素（输入顺序即输出顺序）。 is_liked 必须批量判断：一次 IN 查询，绝不在循环里逐条查——这是本步的性能红线。 viewerAccountId =
     * 0 表示匿名：跳过查询，全部 false。
     */
    public List<FeedVideoItem> buildFeedVideos(List<Video> videos, long viewerAccountId) {
        if (videos == null || videos.isEmpty()) {
            return List.of();
        }

        Map<Long, Boolean> likeMap = batchLiked(videos, viewerAccountId);

        List<FeedVideoItem> items = new ArrayList<>(videos.size());
        for (Video video : videos) {
            items.add(
                    new FeedVideoItem(
                            video.getId(),
                            new FeedAuthor(video.getAuthorId(), video.getUsername()),
                            video.getTitle(),
                            video.getDescription(),
                            video.getPlayUrl(),
                            video.getCoverUrl(),
                            video.getCreateTime().toEpochSecond(),
                            video.getLikesCount() == null ? 0L : video.getLikesCount(),
                            likeMap.getOrDefault(video.getId(), false)));
        }

        return items;
    }

    // 话题页：tag_name 必填 ->  limit 归一 -> 查 -> 组装（无分页游标）
    public FeedListResponse listByTag(String tagName, int limit, long viewerAccountId) {
        if (tagName == null || tagName.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.TAG_NAME_IS_REQUIRED);
        }

        List<Video> videos = feedMapper.listByTag(tagName, normalizeLimit(limit));
        return new FeedListResponse(buildFeedVideos(videos, viewerAccountId));
    }

    // 点赞榜：排序 like_count Desc ,id Desc
    // 游标四条校验文案逐字（全 400）；两个都为 0 = 第一页。
    // has_more = 本页装满（原版语义）；next_* 取本页最后一条。
    public LikesCountResponse listLikesCount(
            int limit, Long likesCountBefore, Long idBefore, long viewerAccountId) {
        int normalized = normalizeLimit(limit);

        // 游标校验
        Long cursorLikes = null;
        Long cursorId = null;
        if (likesCountBefore != null || idBefore != null) {
            if (likesCountBefore == null || idBefore == null) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST, Errors.LIKES_COUNT_AND_ID_MUST_BE_TOGETHER);
            }
            if (likesCountBefore < 0) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST, Errors.INVALID_CURSOR_LIKES_COUNT_BEFORE);
            }

            if (idBefore < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, Errors.INVALID_CURSOR_ID_BEFORE);
            }

            if (idBefore == 0L) {
                // id=0 ,但是likesCountBefore不为0则是错误情况
                if (likesCountBefore != 0L) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, Errors.INVALID_CURSOR_ID_BEFORE);
                }
                // 两个都为0 ：视为第一页，不加游标
            } else {
                // 此时是id和count都不为0的情况，正常
                cursorId = idBefore;
                cursorLikes = likesCountBefore;
            }
        }
        List<Video> videos = feedMapper.listLikesCountWithCursor(normalized, cursorLikes, cursorId);
        boolean hasMore = videos.size() == normalized;
        Long nextLikes = null;
        Long nextId = null;

        if (!videos.isEmpty()) {
            Video last = videos.get(videos.size() - 1);
            nextLikes = last.getLikesCount();
            nextId = last.getId();
        }

        return new LikesCountResponse(
                buildFeedVideos(videos, viewerAccountId), nextLikes, nextId, hasMore);
    }

    // 关注流(硬JWT): 登录才走整页缓存; 匿名直查
    public ListByFollowingResponse listByFollowing(
            int limit, Long latestTime, long viewerAccountId) {
        int normalized = normalizeLimit(limit);
        long before = (latestTime == null || latestTime <= 0) ? 0L : latestTime;

        // 走缓存
        if (viewerAccountId != 0L) {
            return followingFeedCache.getOrLoad(
                    viewerAccountId,
                    normalized,
                    before,
                    () -> loadFollowingFromDb(normalized, before, viewerAccountId));
        }

        return loadFollowingFromDb(normalized, before, viewerAccountId);
    }

    // 推荐流(软鉴权): 冷热分离, 请求游标与 next_time 都是毫秒
    public ListLatestResponse listLatest(int limit, Long latestTime, long viewerAccountId) {
        int normalized = normalizeLimit(limit);
        Long reqTime = (latestTime == null || latestTime <= 0) ? null : latestTime;
        return loadLatest(normalized, reqTime, viewerAccountId, true);
    }

    // 热榜(软鉴权): 60分钟滑动窗口 + 快照冻结
    public ListByPopularityResponse listByPopularity(
            ListByPopularityRequest req, long viewerAccountId) {
        int normalized = normalizeLimit(req.limit());
        if (req.latestPopularity() < 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, Errors.LATEST_POPULARITY_MUST_BE_NON_NEGATIVE);
        }

        // 三元游标必须要成对: 只给latest_popularity会被静默忽略; 如果不传这两个字段，或者只传了 latest_popularity，直接静默放行，当成第一页处理！
        boolean anyCursor = req.latestBefore() != null || req.latestIdBefore() != null;
        if (anyCursor
                && (req.latestBefore() == null
                        || req.latestIdBefore() == null
                        || req.latestIdBefore() == 0L)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, Errors.LATEST_BEFORE_AND_ID_MUST_BE_TOGETHER);
        }

        ListByPopularityResponse snapshotPage = null;
        try {
            snapshotPage = hotSnapshotPage(req, normalized, viewerAccountId);
        } catch (RuntimeException e) {
            // 快照读取失败(redis 挂掉/超时) -> DB兜底, 相应as_of=0
            log.warn("hot snapshot unavailable, fall back to db: {}", e.toString());
        }

        return snapshotPage != null
                ? snapshotPage
                : popularityFromDb(req, normalized, viewerAccountId);
    }

    /** DB 兜底/冷启动：as_of=0、next_offset=0（0 就是"我在走兜底"的标记） */
    private ListByPopularityResponse popularityFromDb(
            ListByPopularityRequest req, int normalized, long viewerAccountId) {
        boolean cursorComplete =
                req.latestBefore() != null
                        && req.latestIdBefore() != null
                        && req.latestIdBefore() > 0;
        List<Video> videos =
                feedMapper.listByPopularity(
                        normalized,
                        cursorComplete ? req.latestPopularity() : null,
                        cursorComplete ? req.latestBefore() : null,
                        cursorComplete ? req.latestIdBefore() : null);

        List<FeedVideoItem> items = buildFeedVideos(videos, viewerAccountId);
        Long nextPopularity = null;
        OffsetDateTime nextBefore = null;
        Long nextIdBefore = null;

        if (!videos.isEmpty()) {
            Video last = videos.get(videos.size() - 1);
            nextPopularity = last.getPopularity();
            nextBefore = last.getCreateTime();
            nextIdBefore = last.getId();
        }

        return new ListByPopularityResponse(
                items, 0L, 0, items.size() == normalized, nextPopularity, nextBefore, nextIdBefore);
    }

    /** 快照路径；返回 null = "该落 DB"（首页且窗口为空） */
    private ListByPopularityResponse hotSnapshotPage(
            ListByPopularityRequest req, int normalized, long viewerAccountId) {
        OffsetDateTime asOf = asOfMinute(req.asOf());
        hotWindowRedisService.ensureSnapshot(asOf);

        List<Long> ids = hotWindowRedisService.idsByOffset(asOf, req.offset(), normalized);
        if (ids.isEmpty()) {
            if (req.offset() > 0) {
                // 读取成功但空非首页: 直接空页不进DB, as_of保持真值
                return new ListByPopularityResponse(
                        List.of(), asOf.toEpochSecond(), req.offset(), false, null, null, null);
            }
            return null;
        }

        // 重排查数据库出来的视频
        List<Video> videos = reorderByIds(ids, feedMapper.listByIds(ids));
        List<FeedVideoItem> items = buildFeedVideos(videos, viewerAccountId);

        Long nextPopularity = null;
        OffsetDateTime nextBefore = null;
        Long nextIdBefore = null;

        if (!videos.isEmpty()) {
            Video last = videos.get(videos.size() - 1);
            nextPopularity = last.getPopularity();
            nextBefore = last.getCreateTime();
            nextIdBefore = last.getId();
        }

        return new ListByPopularityResponse(
                items,
                asOf.toEpochSecond(),
                req.offset() + items.size(),
                items.size() == normalized,
                nextPopularity,
                nextBefore,
                nextIdBefore);
    }

    /** 按给定 id 顺序重排（DB 查不到的静默跳过），与第 8 步 buildOrderedResult 同义 */
    private List<Video> reorderByIds(List<Long> ids, List<Video> rows) {
        Map<Long, Video> byId = new HashMap<>();
        for (Video row : rows) {
            byId.put(row.getId(), row);
        }

        List<Video> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Video row = byId.get(id);
            if (row != null) { // DB 里查不到的 id（视频已删）静默跳过，别把 null 塞进结果
                ordered.add(row);
            }
        }

        return ordered;
    }

    // ** as_of(秒) 向下对齐到 UTC 整分钟；<=0 用服务器当前分钟 */.
    private OffsetDateTime asOfMinute(long asOfSeconds) {
        OffsetDateTime base =
                asOfSeconds > 0
                        ? OffsetDateTime.ofInstant(
                                Instant.ofEpochSecond(asOfSeconds), ZoneOffset.UTC)
                        : OffsetDateTime.now(ZoneOffset.UTC);
        // 返回服务器时间说明是第一次来访,没有历史标签
        return base.truncatedTo(ChronoUnit.MINUTES);
    }

    /**
     * allowRebuild: 只允许重建一次窗口。原版靠递归重入且无深度上限（ZADD 失败还会被忽略）， 一旦 ZADD 持续失败就会无限递归 —— 这里第二次进来窗口仍为空就直接走
     * DB 兜底。
     */
    private ListLatestResponse loadLatest(
            int normalized, Long reqTime, long viewerAccountId, boolean allowRebuild) {
        Double oldest;
        try {
            oldest = timelineRedisService.oldestScore();
        } catch (RuntimeException e) {
            // 只有入口读失败才降级查库
            log.warn("timeline unavailable, fall back to db: {}", e.toString());
            return latestFromDb(normalized, reqTime, viewerAccountId);
        }

        if (oldest == null) {
            // 冷启动：窗口为空
            if (!allowRebuild) {
                return latestFromDb(normalized, reqTime, viewerAccountId);
            }

            List<Video> rows =
                    singleFlight.run(
                            "sf:fallback:global_timeline_rebuild", () -> rebuildTimeline());
            if (rows.isEmpty()) {
                return new ListLatestResponse(List.of(), 0L, false); // DB 也空 → 空页，防死循环
            }

            return loadLatest(normalized, reqTime, viewerAccountId, false); // 让被阻塞的请求再跑一遍
            // 最后一个参数 false 表示这次重跑不允许再次触发重建，防止又发现窗口为空而递归重建，避免死循环。
        }

        long reqMillis = (reqTime == null) ? System.currentTimeMillis() : reqTime;
        if (reqMillis <= oldest.longValue()) {
            // 游标落在热水区之外 → 纯冷路径，不回写 ZSET
            return singleFlight.run(
                    "sf:cold:listLatest:%d:%d".formatted(normalized, reqMillis),
                    () -> latestFromDb(normalized, reqTime, viewerAccountId));
        }

        // 热水区, null表示取最新时间的视频
        return hotPage(normalized, reqTime, viewerAccountId);
    }

    // 热路径: 窗口取id -> 三级缓存拿实体 -> 不足N条时用末条时间补DB
    private ListLatestResponse hotPage(int normalized, Long reqTime, long viewerAccountId) {
        // 这里严格-1表示严格 < reqTime
        double maxScore = (reqTime == null) ? Double.POSITIVE_INFINITY : (double) (reqTime - 1);

        List<Long> ids =
                timelineRedisService.idsDescByScore(Double.NEGATIVE_INFINITY, maxScore, normalized);
        List<Video> videos = ids.isEmpty() ? List.of() : videoEntityCache.getVideoByIds(ids);

        if (videos.size() < normalized) {
            int remain = normalized - videos.size();
            OffsetDateTime coldCursor =
                    videos.isEmpty()
                            ? beforeOf(reqTime)
                            : videos.get(videos.size() - 1).getCreateTime(); // 用末条实体的 DB 时间对齐 SQL;
            long cursorMillis = (coldCursor == null) ? 0L : coldCursor.toInstant().toEpochMilli();

            try {
                List<Video> cold =
                        singleFlight.run(
                                "sf:stitch:listLatest:%d:%d".formatted(remain, cursorMillis),
                                () -> feedMapper.listLatest(remain, coldCursor));

                List<Video> merged = new ArrayList<>(videos.size() + cold.size());
                merged.addAll(videos);
                merged.addAll(cold);
                videos = merged;
            } catch (RuntimeException e) {
                // 原版把 stitch 的错误吞掉返回短页，这里保留但记日志
                log.warn(
                        "stitch failed, return short page: remain={}, err={}",
                        remain,
                        e.toString());
            }
        }

        List<FeedVideoItem> items = buildFeedVideos(videos, viewerAccountId);
        return new ListLatestResponse(items, lastMillis(videos), videos.size() == normalized);
    }

    /** 重建：无视游标拉最新 1000 条 → 批量 ZADD；返回拉到的行（空 = DB 也是空的） */
    private List<Video> rebuildTimeline() {
        List<Video> rows = feedMapper.listLatest(REBUILD_SIZE, null);

        if (!rows.isEmpty()) {
            long added = timelineRedisService.rebuild(rows);
            if (added <= 0) {
                log.warn("timeline rebuild wrote nothing: rows={}", rows.size());
            }
        }

        return rows;
    }

    private ListLatestResponse latestFromDb(int normalized, Long reqTime, long viewerAccountId) {
        List<Video> videos = feedMapper.listLatest(normalized, beforeOf(reqTime));
        return new ListLatestResponse(
                buildFeedVideos(videos, viewerAccountId),
                lastMillis(videos),
                videos.size() == normalized);
    }

    // next_time = 末条的发布时间毫秒 ; 空页为0
    private static long lastMillis(List<Video> videos) {
        if (videos.isEmpty()) {
            return 0L;
        }

        return videos.get(videos.size() - 1).getCreateTime().toInstant().toEpochMilli();
    }

    private static OffsetDateTime beforeOf(Long reqTime) {
        return reqTime == null
                ? null
                : OffsetDateTime.ofInstant(Instant.ofEpochMilli(reqTime), ZoneId.systemDefault());
    }

    // 直查DB: 纯拉模型
    private ListByFollowingResponse loadFollowingFromDb(
            int normalized, long beforeSeconds, long viewerAccountId) {
        OffsetDateTime before =
                beforeSeconds > 0
                        ? OffsetDateTime.ofInstant(
                                Instant.ofEpochSecond(beforeSeconds), ZoneId.systemDefault())
                        : null;

        List<Video> videos = feedMapper.listByFollowing(normalized, viewerAccountId, before);
        long nextTime =
                videos.isEmpty()
                        ? 0L
                        : videos.get(videos.size() - 1).getCreateTime().toEpochSecond();
        boolean hasMore = videos.size() == normalized;
        return new ListByFollowingResponse(
                buildFeedVideos(videos, viewerAccountId), nextTime, hasMore);
    }

    // 一次性使用in查完，匿名的0直接返回空map，不碰数据库
    private Map<Long, Boolean> batchLiked(List<Video> videos, long viewerAccountId) {
        if (viewerAccountId == 0L) {
            return Map.of();
        }

        List<Long> ids = videos.stream().map(Video::getId).toList();
        List<Long> likeIds = likeMapper.listLikedVideoIds(viewerAccountId, ids);

        Map<Long, Boolean> likeMap = new HashMap<>();
        for (Long id : likeIds) {
            likeMap.put(id, true);
        }

        // 转化为Map
        return likeMap;
    }
}
