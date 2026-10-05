package io.github.p_feed.infra;

import java.util.List;
import java.time.Duration;

public final class RateLimits{
    public record Rule(String name, int limit, Duration window, boolean byIp){}

    public static final List<Rule> ALL = List.of(
        new Rule("account_register", 5, Duration.ofHours(1), true),
        new Rule("account_login", 10, Duration.ofMinutes(1), true),
        new Rule("like_write", 30, Duration.ofMinutes(1),false),
        new Rule("comment_write", 10, Duration.ofMinutes(1), false),
        new Rule("social_write", 20, Duration.ofMinutes(1),false)
    );
    
}
