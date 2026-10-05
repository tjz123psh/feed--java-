package io.github.p_feed.infra;

import org.springframework.http.HttpStatus;

/*
 * 业务错误：携带状态码与契约文案，由GlobalExceptionHander统一转成{"error"：文案}
 */

public class ApiException extends RuntimeException{

    private final int status;

    public ApiException(int status , String message){
        super(message);
        this.status = status;
    }

    public ApiException(HttpStatus status, String message){
        this(status.value(),message);
    }

    public int getStatus(){
        return status;
    }
}
