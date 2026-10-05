package io.github.p_feed.video;

import org.springframework.stereotype.Component;

/** video-popularity 的投递接口：true=热度由消费者写，false=调用方同步兜底。 */
@Component
public interface PopularityEventPublisher{

    boolean publishPopularity(long videoId, long change);
}
