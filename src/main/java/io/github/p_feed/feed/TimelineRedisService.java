package io.github.p_feed.feed;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import io.github.p_feed.infra.RedisKeys;
import io.github.p_feed.video.Video;

/**
 * 推荐流时间线（第 9 步）：只收口 v1:feed:global_timeline 这一个 ZSET。
 * score = 发布时间毫秒，member = 视频 id 十进制串；窗口只保留最新 1000 条（修剪由阶段 7 的消费者做）。
 * 不吞异常：调用方要能区分"读失败"与"窗口为空"。
 */
@Component
public class TimelineRedisService {

    private final StringRedisTemplate redis;
    private final RedisKeys keys;

    public TimelineRedisService(StringRedisTemplate redis, RedisKeys keys) {
        this.redis = redis;
        this.keys = keys;
    }

    // 窗口最老的一条的 score（毫秒）= 冷热分界线；窗口为空返回 null，读失败抛异常
    public Double oldestScore(){
        Set<ZSetOperations.TypedTuple<String>> tail = redis.opsForZSet().rangeWithScores(timelineKey(), 0, 0);
        
        if(tail == null || tail.isEmpty()){
            return null;
        }
        return tail.iterator().next().getScore();
    }


    
    // 按score倒序取member并转化为id ; 参数顺序是(min, max)
    public List<Long> idsDescByScore(double minScore, double maxScore, int limit){
        Set<String> members = redis.opsForZSet().reverseRangeByScore(timelineKey(), minScore, maxScore, 0, limit);
        
        if(members == null){
            return List.of();
        }

        List<Long> ids = new ArrayList<>(members.size());
        for(String member : members){
            try {
                ids.add(Long.parseLong(member));
            } catch (Exception e) {
                // 坏 member 静默跳过
            }
        }

        return ids;
    }

    // 重建窗口: 批量 zadd, score = 发布时间毫秒 ; 返回真正写入的条数
    public long rebuild(List<Video> videos){
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        for(Video video : videos){
            tuples.add(ZSetOperations.TypedTuple.of(String.valueOf(video.getId()), (double)video.getCreateTime().toInstant().toEpochMilli()));
        }

        Long added = redis.opsForZSet().add(timelineKey(), tuples);
        return added == null ? 0L : added;
    }

    
    private @NonNull String timelineKey() {
        return keys.key("feed:global_timeline");
    }

    // 第8步: cg-timeline 消费者专用--单条追加(score=发布时间毫秒)并裁剪到最新 1000
    public void addGlobal(long videoId, long createTimeMillis){
        redis.opsForZSet().add(timelineKey(), String.valueOf(videoId), createTimeMillis);
        redis.opsForZSet().removeRange(timelineKey(), 0, -1001);
    }
}
