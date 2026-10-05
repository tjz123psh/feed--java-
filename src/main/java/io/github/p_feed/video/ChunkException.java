package io.github.p_feed.video;

import java.util.Map;
import org.springframework.http.HttpStatus;
import io.github.p_feed.infra.ApiException;

/**
 * 分块族"带附加字段"的错误：契约要求响应体在 error 之外再带
 * expected/actual（hash 不符）或 missing/completed/total（位图不满）。
 * 由 ChunkController 的局部 @ExceptionHandler 合并成最终 JSON。
 */
public class ChunkException extends ApiException    {
    private final Map<String,Object> details;

    public ChunkException(HttpStatus status, String message, Map<String,Object> details){
        super(status, message);
        this.details = details;
    }

    public Map<String,Object> getDetails(){
        return details;
    }
}


    //Map<String, Object> details：专门用来装这些额外数据。

    //构造方法：传入状态码、基础报错信息、额外数据。

    //getDetails()：让 Controller 把数据拿出来拼进最终 JSON。

