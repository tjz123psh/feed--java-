package io.github.p_feed.social;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("socials")  
public class Social{

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long followerId;
    private Long vloggerId;
    
    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }
    public Long getFollowerId() {
        return followerId;
    }
    public void setFollowerId(Long followerId) {
        this.followerId = followerId;
    }
    public Long getVloggerId() {
        return vloggerId;
    }
    public void setVloggerId(Long vloggerId) {
        this.vloggerId = vloggerId;
    }
}
