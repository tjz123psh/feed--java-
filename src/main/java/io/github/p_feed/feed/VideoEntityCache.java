package io.github.p_feed.feed;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.github.p_feed.infra.RedisKeys;
import io.github.p_feed.video.Video;
import tools.jackson.databind.ObjectMapper;

// 视频实体三级缓存（第 8 步）：L1 Caffeine 5s → L2 Redis MGET（键 v1:video:entity:{id}，TTL 1h）
// → L3 查库（同 id 并发用 inFlight 合并）。
// 返回按输入 id 顺序重排，查不到的 id 静默缺失；L2/L3 故障一律降级成"未命中"，方法不抛异常。

@Component
public class VideoEntityCache {

    private static final Logger log = LoggerFactory.getLogger(VideoEntityCache.class);
    // 内部换粗:
    private static final long L1_MAX_SIZE = 2000;
    private static final Duration L1_TTL = Duration.ofSeconds(5);
    // 二级缓存Redis:
    private static final Duration L2_TTL = Duration.ofHours(1);
    private static final long L2_TIMEOUT_MS = 50;

    private final StringRedisTemplate redis;
    private final RedisKeys keys;
    private final FeedMapper feedMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VideoEntityCache(FeedMapper feedMapper, StringRedisTemplate redis, RedisKeys keys) {
        this.feedMapper = feedMapper;
        this.redis = redis;
        this.keys = keys;
    }

    // L1 的键与 L2 的 Redis 键是同一个字符串，失效时只需记一个键名
    private final Cache<String, Video> l1 =
            Caffeine.newBuilder().maximumSize(L1_MAX_SIZE).expireAfterWrite(L1_TTL).build();

    // 原版 sf:entity:{id} 的进程内版本：同 id 并发时只有领头者查库，其余等它的结果
    public final ConcurrentHashMap<Long, CompletableFuture<Video>> inFlight =
            new ConcurrentHashMap<>();


    // 输入为空返回空列表;结果顺序 = 输入即可顺序 , 缺失的id不出现
    public List<Video> getVideoByIds(List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Video> found = new ConcurrentHashMap<>();

        List<Long> missedL1 = new ArrayList<>();
        for (Long id : videoIds) {
            Video cache = l1.getIfPresent(entityKey(id));
            if (cache != null) {
                found.put(id, cache);
            } else {
                missedL1.add(id);
            }
        }

        if (missedL1.isEmpty()) {
            return reorder(videoIds, found);
        }

        readL2(missedL1, found);
        
        List<Long> missedL2 = new ArrayList<>();
        for(Long id : missedL1){
            if(!found.containsKey(id)){
                missedL2.add(id);
            }
        }

        if(missedL2.isEmpty()){
            return reorder(videoIds, found);
        }

        loadL3(missedL2,found);
        return reorder(videoIds, found);
    }
    
    // caffeine 活在JVM里, Redis的DEL清理不掉它; 测试的时候需要调用这个
    public void invalidateAll(){
        l1.invalidateAll();;
    }

    // L3：每个 miss 的 id 一个虚拟线程任务，同 id 并发被 inFlight 合并成一次查库 
    // 同id并发: 在同一个时间窗口内，有多个请求（线程）同时在查同一个视频 ID。
    private void loadL3(List<Long> missedL2, Map<Long, Video> found) {
        List<Thread> workers = new ArrayList<>(missedL2.size());
        for(Long id : missedL2){
            workers.add(Thread.ofVirtual().name("l3-entity").start(() -> {

                Video video = loadOne(id);
                if(video != null){
                    found.put(id, video);
                }
            }));
        }

        // 主线程等待所有的虚拟线程异步执行查库操作
        for(Thread worker : workers){
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
    
    // 单个 id：抢到 inFlight 槽位的当领头者查库，没抢到的等它的结果
    // 言下之意是只有一个线程有执行查库权, 其他的都得等到他查完共享信息(对同一个id来说)
    private Video loadOne(Long id) {
        CompletableFuture<Video> mine = new CompletableFuture<>(); //装当前线程的查库结果
        CompletableFuture<Video> existing = inFlight.putIfAbsent(id, mine); //将线程id和mine注册到inFlight当中,是原子操作

        // 这里是跟随着分支,等待领头的查库结果,还有异常情况
        if(existing != null){
            try {
                return existing.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }catch(Exception e){
                return null;   // 领头者失败 → 本 id 静默缺失，与原版一致
            }
        }

        try {
            Video video = queryDb(id);
            mine.complete(video);
            // 查不到就 complete(null)，等待者同样得到"缺失"
            return video;
        } catch (Exception e) {
            log.warn("L3 query failed, id silently missing: id={}, err={}",id,e.toString());
            mine.complete(null);
            return null;
        }finally{
            inFlight.remove(id,mine);// 两参数：只删自己注册的条目, 只有领头的能删除,等待的不能擅自删除
        }

    }


    private Video queryDb(Long id) {
        List<Video> rows = feedMapper.listByIds(List.of(id));
        if(rows == null || rows.isEmpty()){
            return null;// 不做空值缓存，避免把"不存在"固化 1 小时
        }

        Video video = rows.get(0);
        l1.put(entityKey(video.getId()), video);// 同步回写 L1
        writeL2Async(video);// 异步回写 L2
        return video;
    }


    private void writeL2Async(Video video) {
        String key = entityKey(video.getId());
        String json;
        try {
            json = objectMapper.writeValueAsString(video);
        } catch (Exception e) {
            log.warn("serialize video failed, skip L2 write: id={}, err={}", video.getId(), e.toString());
            return;
        }

        Thread.ofVirtual().name("l2-writeback").start(() -> {
            try {
                redis.opsForValue().set(key, json, L2_TTL);
            } catch (Exception e) {
                log.warn("L2 write-back failed: key={}, err={}",key,e.toString());
            }
        });
    }


    /** L2：一次 MGET 取全部 miss；50ms 没回来就整体当 miss 下沉 L3（迟到结果直接丢弃，不回写任何一级） */
    private void readL2(List<Long> missedL1, Map<Long, Video> found) {
        List<String> cacheKeys = missedL1.stream().map(this::entityKey).toList();
        List<String> values;
        try {
            values = mgetWithin(cacheKeys);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            log.warn(
                    "L2 mget failed, fall back to L3: count={}, err={}",
                    cacheKeys.size(),
                    e.toString());
            return;
        }

        if(values == null){
            return;
        }

        for(int i = 0 ; i < missedL1.size(); i++){
            String json = values.get(i);
            if(json == null){
                continue ;// MGET 里不存在的键是 null 元素，不是错误;
            }
            Video video = parseVideo(json);
            if(video == null){// 这里的json坏了
                continue; // 坏值当未命中，让 L3 用正确 JSON 覆盖/
            }

            long id = missedL1.get(i);
            found.put(id, video);
            l1.put(entityKey(id), video); // 回写缓存L1
        }
    }

    private Video parseVideo(String json) {
        try {
            return objectMapper.readValue(json, Video.class);
        } catch (Exception e) {
            return null;
        }
    }

    // spring Data Redis 没有单次调用超时: 命令交给虚拟线程发,主线程最多等50ms
    private List<String> mgetWithin(List<String> cacheKeys)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<List<String>> pending = new CompletableFuture<>();

        Thread vt =
                Thread.ofVirtual()
                        .name("l2-mget")
                        .start(
                                () -> {
                                    try {
                                        pending.complete(redis.opsForValue().multiGet(cacheKeys));
                                    } catch (Exception e) {
                                        pending.completeExceptionally(e);
                                    }
                                });

        // 超时将虚拟线程的打断并抛弃,减少IO资源浪费, 并将缓存信息返回
        try {
            return pending.get(L2_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            vt.interrupt();
            return Collections.nCopies(cacheKeys.size(), null);
        }
    }

    private List<Video> reorder(List<Long> videoIds, Map<Long, Video> found) {
        List<Video> result = new ArrayList<>(videoIds.size());
        for (Long id : videoIds) {
            Video video = found.get(id);
            if (video != null) {
                result.add(video);
            }
        }
        return result;
    }

    private String entityKey(Long id) {
        return keys.key("video:entity:%d", id);
    }
}
